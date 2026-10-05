/**
 * 极简 Snapcast 服务端（Windows 上跑，官方只发布 Windows 版 client，没有 server）
 * 实现 Snapcast 二进制协议 v2（SnapStreamProtocolVersion = 2）
 * 与官方 snapclient 0.29 ~ 0.35 / snapdroid 0.29.0.2 兼容
 *
 *   - 1704 : stream（音频流 + 时间同步）
 *   - 1705 : control（JSON-RPC，简版，够客户端查询用）
 *
 * 用法: node server.js [wav路径] [端口]
 */
const os = require('os');
const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');

// 选真实局域网网卡（跳过 WSL / VPN / Hyper-V 虚拟网卡，它们常抢在前面）
const LOCAL_IP = (() => {
  const all = [];
  for (const list of Object.values(os.networkInterfaces()))
    for (const a of list) if (a.family === 'IPv4' && !a.internal) all.push(a.address);
  return all.find(x => x.startsWith('192.168.')) || all.find(x => x.startsWith('10.')) || all[0] || '127.0.0.1';
})();

const WAV = process.argv[2] || path.join(__dirname, 'sync-test.wav');
const PORT = parseInt(process.argv[3] || '1704', 10);
const CONTROL_PORT = PORT + 1;

const CHUNK_MS = 20;      // 每个音频块 20ms
const BUFFER_MS = 1000;   // 客户端缓冲，和官方默认一致

// ---------- 读取 WAV ----------
const raw = fs.readFileSync(WAV);
if (raw.toString('ascii', 0, 4) !== 'RIFF') throw new Error('不是 WAV 文件: ' + WAV);
// 定位 data chunk（不假设头一定是 44 字节）
let off = 12, pcm = null, fmt = null;
while (off + 8 <= raw.length) {
  const id = raw.toString('ascii', off, off + 4);
  const sz = raw.readUInt32LE(off + 4);
  const body = off + 8;
  if (id === 'fmt ') fmt = { ch: raw.readUInt16LE(body + 2), rate: raw.readUInt32LE(body + 4), bits: raw.readUInt16LE(body + 14) };
  if (id === 'data') { pcm = raw.slice(body, body + sz); break; }
  off = body + sz + (sz % 2);
}
console.log('[wav]', fmt, 'pcm bytes:', pcm.length);

const BYTES_PER_MS = fmt.rate * fmt.ch * (fmt.bits / 8) / 1000;
const CHUNK_BYTES = Math.round(BYTES_PER_MS * CHUNK_MS);
const DURATION_SEC = pcm.length / BYTES_PER_MS / 1000; // 音源总时长

function mediaInfo() {
  // 歌名从 <同名>.json sidecar 读（{"title":...,"artist":...}），没有就用文件名
  let name = path.basename(WAV).replace(/\.wav$/i, '');
  let artist = '';
  try {
    const meta = JSON.parse(fs.readFileSync(path.join(path.dirname(WAV), name + '.json'), 'utf8'));
    name = meta.title || name;
    artist = meta.artist || '';
  } catch (e) { /* 无 sidecar，用文件名 */ }
  return {
    name, artist,
    durationSec: Math.round(DURATION_SEC),
    positionSec: Math.floor(readOff / BYTES_PER_MS / 1000),
    paused: state.paused,
  };
}

// ---------- 时间工具 ----------
// 重要：snapcast 协议里所有时间戳都是"单调时钟"（各设备开机时间），
// 不能用 Unix 时间，否则客户端算播放时刻时 int32 会溢出
const PROC_START_NS = process.hrtime.bigint();
function nowTv() {
  const ns = Number(process.hrtime.bigint() - PROC_START_NS); // 进程启动以来的 ns
  return { sec: Math.floor(ns / 1e9), usec: Math.floor((ns % 1e9) / 1e3) };
}
// 单调时钟的微秒总数（用于调度）
function nowUs() { return Number(process.hrtime.bigint() - PROC_START_NS) / 1e3; }
function usToTv(us) {
  const u = Math.round(us);
  return { sec: Math.floor(u / 1e6), usec: u % 1e6 };
}
function tvMs(t) { return t.sec * 1000 + t.usec / 1000; }

// ---------- 协议常量 ----------
const T_CODEC_HEADER = 1, T_WIRE_CHUNK = 2, T_SERVER_SETTINGS = 3, T_TIME = 4, T_HELLO = 5, T_CLIENT_INFO = 7, T_ERROR = 8;

function buildBase(type, id, refersTo, payload, sent) {
  const b = Buffer.alloc(26);
  b.writeUInt16LE(type, 0);
  b.writeUInt16LE(id, 2);
  b.writeUInt16LE(refersTo, 4);
  b.writeInt32LE(sent.sec, 6);
  b.writeInt32LE(sent.usec, 10);
  b.writeInt32LE(0, 14);   // received.sec
  b.writeInt32LE(0, 18);   // received.usec
  b.writeUInt32LE(payload.length, 22);
  return Buffer.concat([b, payload]);
}

function payJson(obj) {
  const s = Buffer.from(JSON.stringify(obj), 'utf8');
  const p = Buffer.alloc(4 + s.length);
  p.writeUInt32LE(s.length, 0);
  s.copy(p, 4);
  return p;
}
function payCodecHeader(codec, header) {
  const c = Buffer.from(codec, 'ascii');
  const p = Buffer.alloc(4 + c.length + 4 + header.length);
  p.writeUInt32LE(c.length, 0);
  c.copy(p, 4);
  p.writeUInt32LE(header.length, 4 + c.length);
  header.copy(p, 8 + c.length);
  return p;
}
function payChunk(ts, audio) {
  const p = Buffer.alloc(12 + audio.length);
  p.writeInt32LE(ts.sec, 0);
  p.writeInt32LE(ts.usec, 4);
  p.writeUInt32LE(audio.length, 8);
  audio.copy(p, 12);
  return p;
}
function payTime(lat) {
  const p = Buffer.alloc(8);
  p.writeInt32LE(lat.sec, 0);
  p.writeInt32LE(lat.usec, 4);
  return p;
}

// WAV 头（data size 置 0，snapcast 就是这么发的）
function wavHeaderPayload() {
  const h = Buffer.alloc(44);
  h.write('RIFF', 0, 'ascii'); h.writeUInt32LE(36, 4); h.write('WAVE', 8, 'ascii');
  h.write('fmt ', 12, 'ascii'); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20);
  h.writeUInt16LE(fmt.ch, 22); h.writeUInt32LE(fmt.rate, 24);
  h.writeUInt32LE(fmt.rate * fmt.ch * fmt.bits / 8, 28);
  h.writeUInt16LE(fmt.ch * fmt.bits / 8, 32); h.writeUInt16LE(fmt.bits, 34);
  h.write('data', 36, 'ascii'); h.writeUInt32LE(0, 40);
  return h;
}
const CODEC_HEADER_MSG = () => buildBase(T_CODEC_HEADER, 0, 0, payCodecHeader('pcm', wavHeaderPayload()), nowTv());
const SERVER_SETTINGS_MSG = (vol = 100, muted = false) =>
  buildBase(T_SERVER_SETTINGS, 0, 0, payJson({ bufferMs: BUFFER_MS, latency: 0, muted, volume: vol }), nowTv());

// ---------- 音源：循环读取 PCM ----------
let readOff = 0;
function nextChunkAudio() {
  const buf = Buffer.alloc(CHUNK_BYTES);
  let filled = 0;
  while (filled < CHUNK_BYTES) {
    const n = Math.min(CHUNK_BYTES - filled, pcm.length - readOff);
    pcm.copy(buf, filled, readOff, readOff + n);
    filled += n; readOff += n;
    if (readOff >= pcm.length) readOff = 0; // 循环播放
  }
  return buf;
}

// ---------- DSP：3段EQ（RBJ biquad）+ 每设备频段角色（简易2.1分频） ----------
const DSP = {
  eq: { low: 0, mid: 0, high: 0 },            // dB，-12..+12
  chainL: null, chainR: null,                  // 全局EQ滤波器状态（跨块连续）
};

function shelfCoef(type, f0, gainDb, rate, Q = 0.707) {
  const A = Math.pow(10, gainDb / 40);
  const w0 = 2 * Math.PI * f0 / rate;
  const cw = Math.cos(w0), alpha = Math.sin(w0) / (2 * Q);
  let b0, b1, b2, a0, a1, a2;
  if (type === 'lowshelf') {
    const sq = 2 * Math.sqrt(A) * alpha;
    b0 = A * ((A + 1) - (A - 1) * cw + sq); b1 = 2 * A * ((A - 1) - (A + 1) * cw); b2 = A * ((A + 1) - (A - 1) * cw - sq);
    a0 = (A + 1) + (A - 1) * cw + sq; a1 = -2 * ((A - 1) + (A + 1) * cw); a2 = (A + 1) + (A - 1) * cw - sq;
  } else if (type === 'highshelf') {
    const sq = 2 * Math.sqrt(A) * alpha;
    b0 = A * ((A + 1) + (A - 1) * cw + sq); b1 = -2 * A * ((A - 1) + (A + 1) * cw); b2 = A * ((A + 1) + (A - 1) * cw - sq);
    a0 = (A + 1) - (A - 1) * cw + sq; a1 = 2 * ((A - 1) - (A + 1) * cw); a2 = (A + 1) - (A - 1) * cw - sq;
  } else if (type === 'peaking') {
    b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A;
    a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A;
  } else { // lowpass / highpass（12dB/oct Butterworth）
    b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2;
    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
    if (type === 'highpass') { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2; }
  }
  return { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 };
}
function makeBiquad(coef) { return { coef, x1: 0, x2: 0, y1: 0, y2: 0 }; }
function biq(f, x) {
  const c = f.coef;
  const y = c.b0 * x + c.b1 * f.x1 + c.b2 * f.x2 - c.a1 * f.y1 - c.a2 * f.y2;
  f.x2 = f.x1; f.x1 = x; f.y2 = f.y1; f.y1 = y;
  return y;
}
// 全局EQ链：低架200Hz + 峰值1kHz + 高架4kHz
function rebuildEQ() {
  const r = fmt.rate;
  DSP.chainL = [makeBiquad(shelfCoef('lowshelf', 200, DSP.eq.low, r)), makeBiquad(shelfCoef('peaking', 1000, DSP.eq.mid, r, 1.0)), makeBiquad(shelfCoef('highshelf', 4000, DSP.eq.high, r))];
  DSP.chainR = [makeBiquad(shelfCoef('lowshelf', 200, DSP.eq.low, r)), makeBiquad(shelfCoef('peaking', 1000, DSP.eq.mid, r, 1.0)), makeBiquad(shelfCoef('highshelf', 4000, DSP.eq.high, r))];
}
rebuildEQ();

function applyGlobalEQ(pcmBuf) {
  const flat = DSP.eq.low === 0 && DSP.eq.mid === 0 && DSP.eq.high === 0;
  if (flat) return pcmBuf;
  const n = pcmBuf.length >> 1;
  const out = Buffer.alloc(pcmBuf.length);
  for (let i = 0; i < n; i++) {
    const isL = (i % 2) === 0;
    let x = pcmBuf.readInt16LE(i * 2);
    const chain = isL ? DSP.chainL : DSP.chainR;
    for (const f of chain) x = biq(f, x);
    out.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(x))), i * 2);
  }
  return out;
}
// 每设备频段：全频 / low(≤300Hz 低音炮) / high(>300Hz 卫星箱)
function applyBand(ci, pcmBuf) {
  if (!ci.band || ci.band === 'full') return pcmBuf;
  if (!ci.bandF) {
    const coef = ci.band === 'low' ? shelfCoef('lowpass', 300, 0, fmt.rate, 0.707) : shelfCoef('highpass', 300, 0, fmt.rate, 0.707);
    ci.bandF = [makeBiquad(coef), makeBiquad(coef)];
  }
  const n = pcmBuf.length >> 1;
  const out = Buffer.alloc(pcmBuf.length);
  for (let i = 0; i < n; i++) {
    let x = pcmBuf.readInt16LE(i * 2);
    x = biq(ci.bandF[i % 2], x);
    out.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(x))), i * 2);
  }
  return out;
}

// ---------- 客户端管理 ----------
const clients = new Map(); // sock -> {id, host, version, diff}
let clientSeq = 0;

let msgId = 0;
let seq = 0;
let startUs = null; // 单调时钟起点（微秒）

function nextMsgId() { msgId = (msgId + 1) % 65536; return msgId; }

// 向单个客户端下发音量/静音（ServerSettings）
function sendSettings(sock, info, refersTo = 0) {
  broadcast(buildBase(T_SERVER_SETTINGS, nextMsgId(), refersTo,
    payJson({ bufferMs: BUFFER_MS, latency: 0, muted: !!info.muted, volume: info.volume | 0 }), nowTv()), sock);
}

function broadcast(buf, sock) {
  try { sock.write(buf); } catch (e) { /* 忽略断开 */ }
}

function tick() {
  const targetUs = startUs + seq * CHUNK_MS * 1000;
  const wait = (targetUs - nowUs()) / 1000;
  setTimeout(() => {
    try {
      const base = applyGlobalEQ(nextChunkAudio());
      // 每台设备单独打包：offsetMs 推迟播放（对照实验），band 做分频角色
      for (const [sock, ci] of clients) {
        const ts = usToTv(targetUs + (ci.offsetMs || 0) * 1000);
        broadcast(buildBase(T_WIRE_CHUNK, nextMsgId(), 0, payChunk(ts, applyBand(ci, base)), nowTv()), sock);
      }
      seq++;
    } catch (e) {
      console.error('[tick error]', e.message);
    }
    tick();
  }, Math.max(0, wait));
}

// ---------- stream 端口 ----------
const stream = net.createServer(sock => {
  const info = { id: ++clientSeq, addr: sock.remoteAddress, version: '?', os: '?', name: '?', diff: 0, diffs: [], jitter: 0, drift: 0, volume: 100, muted: false, offsetMs: 0, band: 'full', bandF: null, ready: false };
  clients.set(sock, info);
  console.log(`[+] client #${info.id} ${sock.remoteAddress}:${sock.remotePort}  (当前 ${clients.size} 台)`);

  let buf = Buffer.alloc(0);
  sock.on('data', d => {
    buf = Buffer.concat([buf, d]);
    while (buf.length >= 26) {
      const size = buf.readUInt32LE(22);
      if (size > 8 * 1024 * 1024) { buf = Buffer.alloc(0); break; } // 异常保护
      if (buf.length < 26 + size) break;
      const msg = buf.slice(0, 26 + size);
      buf = buf.slice(26 + size);
      handle(sock, info, msg);
    }
  });
  sock.on('error', () => {});
  sock.on('close', () => {
    const wasReady = info.ready;
    clients.delete(sock);
    if (wasReady) bump();
    console.log(`[-] client #${info.id} ${info.addr} 断开 (剩余 ${clients.size} 台)`);
  });
});

function handle(sock, info, msg) {
  const type = msg.readUInt16LE(0);
  const id = msg.readUInt16LE(2);
  const sent = { sec: msg.readInt32LE(6), usec: msg.readInt32LE(10) };
  const payload = msg.slice(26);

  if (type === T_HELLO) {
    let h = {};
    try { h = JSON.parse(payload.slice(4).toString('utf8')); } catch (e) {}
    info.version = h.Version || '?';
    info.os = h.OS || '?';
    info.name = h.HostName || h.ClientName || '?';
    info.proto = h.SnapStreamProtocolVersion || 1;
    console.log(`    hello from #${info.id}: ${info.name} v${info.version} ${info.os} proto=${info.proto}`);
    if (!info.ready) { info.ready = true; bump(); } // 握手后才算在线设备（扫描探针不在此列）
    // 关键：ServerSettings 的 refersTo 必须 = Hello 请求的 id，客户端按此匹配请求（2s 超时）
    sendSettings(sock, info, id);
    broadcast(CODEC_HEADER_MSG(), sock);
  } else if (type === T_TIME) {
    // 回一个 Time：latency = 服务端接收时刻 - 客户端发送时刻（两边都是各自的单调时钟，符合协议）
    const recv = nowTv();
    const lat = { sec: recv.sec - sent.sec, usec: recv.usec - sent.usec };
    while (lat.usec < 0) { lat.usec += 1e6; lat.sec -= 1; }
    while (lat.usec >= 1e6) { lat.usec -= 1e6; lat.sec += 1; }
    // 协议里 diff = 客户端单调时钟 - 服务端单调时钟，绝对值无意义（各设备开机时间不同）；
    // 有意义的是它的「抖动」——相邻两次同步结果的波动，越小说明时钟越稳、同步越准
    const d = tvMs(lat);
    info.diff = d;
    if (info.diffs.length) info.jitter = Math.abs(d - info.diffs[info.diffs.length - 1]);
    info.diffs.push(d);
    if (info.diffs.length > 60) info.diffs.shift();
    info.drift = info.diffs[info.diffs.length - 1] - info.diffs[0];
    broadcast(buildBase(T_TIME, nextMsgId(), id, payTime(lat), nowTv()), sock);
  } else if (type === T_CLIENT_INFO) {
    try { console.log(`    client info #${info.id}:`, payload.slice(4).toString('utf8')); } catch (e) {}
  } else {
    console.log(`    unknown msg type ${type} from #${info.id}`);
  }
}

// ---------- control 端口（1705）简版 JSON-RPC ----------
const control = net.createServer(sock => {
  console.log(`[+] control conn ${sock.remoteAddress}`);
  let buf = '';
  sock.on('data', d => {
    buf += d.toString('utf8');
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!line) continue;
      let req; try { req = JSON.parse(line); } catch (e) { continue; }
      const reply = r => { try { sock.write(JSON.stringify(r) + '\n'); } catch (e) {} };
      const m = req.method || '';
      if (m === 'Server.GetStatus') {
        reply({ id: req.id, jsonrpc: '2.0', result: { server: { host: { arch: 'x86_64', ip: [], mac: '', name: 'snapnode', os: 'Windows' }, snapserver: { controlProtocolVersion: 1, name: 'snapnode', protocolVersion: 1, version: '0.29.0-node' } }, streams: [{ id: 'test', status: 'playing', uri: { raw: WAV } }] } });
      } else if (m === 'Server.GetRPCVersion') {
        reply({ id: req.id, jsonrpc: '2.0', result: { major: 2, minor: 0, patch: 0 } });
      } else if (m === 'Client.GetStatus') {
        const list = [...clients.values()].map(c => ({ id: c.id + '', connected: true, host: { name: c.name, os: c.os }, config: { volume: { percent: 100, muted: false } }, snapclient: { name: 'Snapclient', protocolVersion: 2, version: c.version } }));
        reply({ id: req.id, jsonrpc: '2.0', result: list });
      } else {
        reply({ id: req.id, jsonrpc: '2.0', result: 'ok' });
      }
    }
  });
  sock.on('error', () => {});
});

// stream/control 可以停了再起（退位→接管之间要能来回切）
let ticking = false;
function startStream() {
  if (!control.listening) {
    control.listen(CONTROL_PORT, '0.0.0.0', () => console.log(`[control] listening 0.0.0.0:${CONTROL_PORT}`));
  }
  if (stream.listening) return;
  stream.listen(PORT, '0.0.0.0', () => {
    console.log(`[stream] listening 0.0.0.0:${PORT}`);
    startUs = nowUs() + 300000; // 给客户端一点缓冲余量
    if (!ticking) { ticking = true; tick(); }
  });
}
function stopStream() {
  try { if (stream.listening) stream.close(() => console.log('[stream] 已停止发流（退位）')); } catch (e) {}
  try { if (control.listening) control.close(); } catch (e) {}
}

/** 退位：断开自己所有客户端（它们会自动发现并连到新服务端），控制台转为代理 */
function resignTo(target) {
  upstream = target;
  for (const [sock] of clients) { try { sock.destroy(); } catch (e) {} }
  clients.clear();
  stopStream();
  console.log(`[role ] 已退位，音频源 = ${target}；本机 1780 控制台转为代理`);
  bump();
}

/** 启动时先看看局域网里是不是已经有服务端了 —— 有就不抢，直接当遥控器（自动退让） */
function probeExisting() {
  return new Promise(resolve => {
    const base = LOCAL_IP.split('.').slice(0, 3).join('.') + '.';
    const me = LOCAL_IP;
    let pending = 254, found = null, timer = null;
    const finish = () => { if (timer) return; timer = setTimeout(() => resolve(found), 0); };
    if (!process.env.SNAPNODE_NO_FOLLOW) {
      for (let i = 1; i <= 254; i++) {
        const ip = base + i;
        if (ip === me) { if (--pending === 0) finish(); continue; }
        const s = net.connect(PORT, ip);
        s.setTimeout(400);
        s.on('connect', () => { if (!found) found = ip; s.destroy(); });
        s.on('error', () => s.destroy());
        s.on('timeout', () => s.destroy());
        s.on('close', () => { if (--pending === 0) finish(); });
      }
    } else finish();
  });
}

probeExisting().then(found => {
  if (found) {
    upstream = `${found}:${HTTP_PORT}`;
    console.log(`[role ] 局域网已有服务端 ${found}:${PORT}，本机自动退让（只当中控，:1780 代理到它）`);
  } else {
    startStream();
  }
});

// 每 10 秒打印一次状态
setInterval(() => {
  if (!clients.size) return;
  const s = [...clients.values()].map(c => `#${c.id}(${c.name} rtt~${c.diff.toFixed(1)}ms)`).join(' ');
  console.log(`[stat] ${clients.size} clients: ${s}  chunk#${seq}`);
}, 10000).unref?.();

// ---------- HTTP 控制端（1780）：任意设备的浏览器都能当遥控器 ----------
const HTTP_PORT = parseInt(process.env.SNAPNODE_HTTP_PORT || '1780', 10);
const state = { paused: false };

// ---------- 服务端角色：同一局域网只能有一个「源」 ----------
// upstream 非空 = 本进程已退位，1780 控制台变成新服务端的透明代理（两边 API 同构）
const HOSTNAME = os.hostname();
let upstream = null;          // 'ip:port'，指向当前真正放音频流的那台
let rev = 0;                  // 状态版本号，用于多端同步
const sseClients = new Set(); // SSE 订阅者

function statusObj() {
  return {
    server: {
      host: LOCAL_IP, port: PORT, httpPort: HTTP_PORT, name: HOSTNAME,
      source: path.basename(WAV),
      role: upstream ? 'follower' : 'server',
      upstream: upstream || null,
    },
    media: mediaInfo(),
    eq: { ...DSP.eq },
    clients: clientList(),
  };
}

/** 状态变了就推给所有订阅端（SSE），多中控靠它保持一致 */
function bump() {
  rev++;
  const payload = JSON.stringify({ ...statusObj(), rev });
  for (const res of sseClients) {
    try { res.write(`data: ${payload}\n\n`); } catch (e) { sseClients.delete(res); }
  }
}

/** 退位后把控制请求原样转给上游（SSE 也能流式转发，因为只是管道） */
function proxyApi(req, res, url) {
  const u = new URL(`http://${upstream}${url.pathname}${url.search}`);
  const p = http.request({
    hostname: u.hostname, port: u.port || 80, path: u.pathname + u.search,
    method: req.method, headers: { ...req.headers, host: u.host },
  }, pr => {
    res.writeHead(pr.statusCode || 502, {
      'Content-Type': pr.headers['content-type'] || 'application/json',
      'Access-Control-Allow-Origin': '*',
    });
    pr.pipe(res);
  });
  p.on('error', e => {
    try { res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' }); } catch (x) {}
    res.end(JSON.stringify({ error: 'upstream unreachable: ' + e.message, upstream }));
  });
  req.pipe(p);
}

function clientList() {
  // ready=false 的是"只连了 TCP 还没握手"的连接（如局域网扫描的探测），不计入在线设备
  return [...clients.values()].filter(c => c.ready).map(c => ({
    id: c.id, name: c.name || ('client-' + c.id), addr: c.addr, os: c.os,
    version: c.version, volume: c.volume, muted: c.muted,
    jitter: +c.jitter.toFixed(2), drift: +c.drift.toFixed(2), samples: c.diffs.length, offsetMs: c.offsetMs | 0, band: c.band,
  }));
}
function findById(id) {
  for (const [sock, c] of clients) if (String(c.id) === String(id)) return { sock, c };
  return null;
}

const httpSrv = http.createServer((req, res) => {  const url = new URL(req.url, 'http://x');
  const send = (code, body, type = 'application/json') => {
    res.writeHead(code, { 'Content-Type': type + '; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
    res.end(body);
  };

  if (url.pathname === '/' || url.pathname === '/index.html') {
    try { return send(200, fs.readFileSync(path.join(__dirname, 'control.html')), 'text/html'); }
    catch (e) { return send(404, 'control.html not found'); }
  }
  // 已退位时，控制类请求透明转给真正放流的那台（两边 API 同构）
  const LOCAL_ONLY = new Set(['/api/handover', '/api/resume', '/api/follow', '/api/events', '/api/who']);
  if (upstream && url.pathname.startsWith('/api/') && !LOCAL_ONLY.has(url.pathname))
    return proxyApi(req, res, url);

  // 本机角色（退位后中控靠它显示「当前服务端是谁」）
  if (url.pathname === '/api/who') {
    return send(200, JSON.stringify({
      name: HOSTNAME, host: LOCAL_IP, port: PORT, httpPort: HTTP_PORT,
      role: upstream ? 'follower' : 'server', upstream, rev,
    }));
  }

  // 状态推送（多端中控同步用）：状态一变就推一帧，订阅端不用靠轮询猜
  if (url.pathname === '/api/events') {
    if (upstream) return proxyApi(req, res, url);   // 退位后订阅上游
    res.writeHead(200, {
      'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache',
      'Connection': 'keep-alive', 'Access-Control-Allow-Origin': '*',
    });
    res.write(': ok\n\n');
    try { res.write(`data: ${JSON.stringify({ ...statusObj(), rev })}\n\n`); } catch (e) {}
    sseClients.add(res);
    const hb = setInterval(() => { try { res.write(': ping\n\n'); } catch (e) {} }, 15000);
    req.on('close', () => { clearInterval(hb); sseClients.delete(res); });
    return;
  }

  // 接管：新服务端（手机 / 另一台 PC）来调这个接口，拿走曲目与进度，旧的随即退位
  if (url.pathname === '/api/handover') {
    const to = url.searchParams.get('to');
    const httpPort = parseInt(url.searchParams.get('http') || String(HTTP_PORT), 10);
    if (!to) return send(400, JSON.stringify({ error: 'missing ?to=<ip>' }));
    send(200, JSON.stringify({
      ok: true, handover: { to, httpPort },
      media: mediaInfo(),               // 新服务端照它设曲目 + seek 到同一位置
      clients: clientList().length,
    }));
    setImmediate(() => resignTo(`${to}:${httpPort}`));   // 先应答，再断流
    return;
  }

  // 收回播放权：本机重新成为服务端
  if (url.pathname === '/api/resume') {
    upstream = null;
    startStream();
    bump();
    return send(200, JSON.stringify({ ok: true, role: 'server' }));
  }

  // 手动跟随某台服务端（不接管，只把本机中控指向它）
  if (url.pathname === '/api/follow') {
    const to = url.searchParams.get('to');
    const httpPort = parseInt(url.searchParams.get('http') || String(HTTP_PORT), 10);
    if (!to) return send(400, JSON.stringify({ error: 'missing ?to=<ip>' }));
    resignTo(`${to}:${httpPort}`);
    return send(200, JSON.stringify({ ok: true, upstream }));
  }

  if (url.pathname === '/api/status') {
    return send(200, JSON.stringify({ ...statusObj(), rev }));
  }
  // 快进/快退：/api/seek?sec=10 或 sec=-15，直接移动 PCM 读取位置
  if (url.pathname === '/api/seek') {
    const sec = parseFloat(url.searchParams.get('sec') || '0');
    let off = readOff + Math.round(sec * BYTES_PER_MS * 1000);
    off = ((off % pcm.length) + pcm.length) % pcm.length;
    readOff = off;
    bump();
    return send(200, JSON.stringify({ ok: true, media: mediaInfo() }));
  }
  // /api/vol?id=1&v=70   省略 id = 全局
  if (url.pathname === '/api/vol') {
    const v = Math.max(0, Math.min(100, parseInt(url.searchParams.get('v') ?? '100', 10)));
    const id = url.searchParams.get('id');
    if (id) { const t = findById(id); if (t) { t.c.volume = v; sendSettings(t.sock, t.c); } }
    else for (const [sock, c] of clients) { c.volume = v; sendSettings(sock, c); }
    bump();
    return send(200, JSON.stringify({ ok: true, clients: clientList() }));
  }
  if (url.pathname === '/api/mute') {
    const id = url.searchParams.get('id');
    const m = url.searchParams.get('m');
    const apply = (sock, c) => { c.muted = m === null ? !c.muted : (m === '1' || m === 'true'); sendSettings(sock, c); };
    if (id) { const t = findById(id); if (t) apply(t.sock, t.c); }
    else for (const [sock, c] of clients) apply(sock, c);
    bump();
    return send(200, JSON.stringify({ ok: true, clients: clientList() }));
  }
  // 人为延迟（对照实验）：给某台设备的播放时间戳加偏移，让它比别人晚发声
  if (url.pathname === '/api/offset') {
    const id = url.searchParams.get('id');
    const ms = Math.max(-200, Math.min(500, parseInt(url.searchParams.get('ms') ?? '0', 10) || 0));
    const t = findById(id);
    if (t) t.c.offsetMs = ms;
    bump();
    return send(200, JSON.stringify({ ok: !!t, clients: clientList() }));
  }
  // EQ：/api/eq?preset=bass 或 ?low=6&mid=2&high=4（dB，-12..12）
  if (url.pathname === '/api/eq') {
    const presets = { flat: [0, 0, 0], bass: [6, 0, -1], vocal: [-2, 4, 1], treble: [-1, 0, 6], warm: [4, 1, -2] };
    const p = url.searchParams.get('preset');
    if (p && presets[p]) [DSP.eq.low, DSP.eq.mid, DSP.eq.high] = presets[p];
    for (const k of ['low', 'mid', 'high']) {
      const v = parseFloat(url.searchParams.get(k));
      if (!isNaN(v)) DSP.eq[k] = Math.max(-12, Math.min(12, v));
    }
    rebuildEQ();
    bump();
    return send(200, JSON.stringify({ ok: true, eq: DSP.eq }));
  }
  // 每设备频段角色：full / low(低音炮) / high(卫星箱)
  if (url.pathname === '/api/band') {
    const t = findById(url.searchParams.get('id'));
    const band = url.searchParams.get('band');
    if (t && ['full', 'low', 'high'].includes(band)) { t.c.band = band; t.c.bandF = null; }
    bump();
    return send(200, JSON.stringify({ ok: !!t, clients: clientList() }));
  }
  // 新设备接入：直接下载安卓客户端 APK
  if (url.pathname === '/apk') {
    try {
      const apk = fs.readFileSync(path.join(__dirname, '..', 'Snapcast.apk'));
      res.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Disposition': 'attachment; filename="Snapcast.apk"', 'Content-Length': apk.length });
      return res.end(apk);
    } catch (e) { return send(404, JSON.stringify({ error: 'apk not found' })); }
  }
  if (url.pathname === '/api/pause') {
    state.paused = url.searchParams.get('p') === '1';
    bump();
    return send(200, JSON.stringify({ ok: true, paused: state.paused }));
  }
  send(404, JSON.stringify({ error: 'not found' }));
});

// 暂停：停止喂音频块（客户端缓冲放完即静音）
const _origNext = nextChunkAudio;
nextChunkAudio = function () {
  if (state.paused) return Buffer.alloc(CHUNK_BYTES); // 静音块
  return _origNext();
};

httpSrv.listen(HTTP_PORT, '0.0.0.0', () => {
  console.log(`[http ] 控制页 http://${LOCAL_IP}:${HTTP_PORT}/`);
});

// ---------- mDNS 广播（局域网自动发现，客户端不用手填 host） ----------
// 官方 snapserver 用 avahi/bonjour 发这几条；这里用 mdns.js 自己发，零依赖
try {
  const mdns = require('./mdns');
  const txt = { version: '0.29.0-node', control: String(CONTROL_PORT), http: String(HTTP_PORT) };
  mdns.publish({ instance: 'Snapcast', type: '_snapcast._tcp', port: PORT, ip: LOCAL_IP, txt });
  mdns.publish({ instance: 'Snapcast', type: '_snapcast-jsonrpc._tcp', port: CONTROL_PORT, ip: LOCAL_IP, txt });
  mdns.publish({ instance: 'Snapcast', type: '_snapcast-http._tcp', port: HTTP_PORT, ip: LOCAL_IP, txt });
} catch (e) {
  console.log('[mdns ] 发布失败:', e.message);
}
