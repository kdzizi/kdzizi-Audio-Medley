/**
 * 零依赖 mDNS / DNS-SD responder（Node 内置 dgram）
 *
 * 让局域网里的 snapclient（安卓 snapdroid、PC snapclient）能「自动发现」本服务端，
 * 不用每台设备手填 host。服务类型与官方 snapserver 一致：
 *   _snapcast._tcp          -> stream 端口（1704）
 *   _snapcast-jsonrpc._tcp  -> control 端口（1705）
 *   _snapcast-http._tcp     -> 网页控制台（1780）
 * 实例名统一用 "Snapcast"（snapdroid 的 NsdHelper 只接受以 "Snapcast" 开头的服务名）。
 *
 * 用法:
 *   const mdns = require('./mdns');
 *   mdns.publish({ instance: 'Snapcast', type: '_snapcast._tcp', port: 1704, ip: '192.168.0.105' });
 */
const dgram = require('dgram');

const MDNS_ADDR = '224.0.0.251';
const MDNS_PORT = 5353;

const TYPE_A = 1, TYPE_PTR = 12, TYPE_TXT = 16, TYPE_AAAA = 28, TYPE_SRV = 33, TYPE_ANY = 255;
const CLASS_IN = 1;
const FLAG_CACHE_FLUSH = 0x8000;
const TTL = 120;

// ---------- DNS 编解码 ----------
function writeName(name) {
  const out = [];
  for (const p of String(name).split('.').filter(x => x.length)) {
    const b = Buffer.from(p, 'utf8');
    if (b.length > 63) continue;
    out.push(Buffer.from([b.length]), b);
  }
  out.push(Buffer.from([0]));
  return Buffer.concat(out);
}

function readName(buf, off) {
  const labels = [];
  let pos = off, end = off, jumped = false, guard = 0;
  while (guard++ < 64) {
    const len = buf[pos];
    if (len === undefined) break;
    if (len === 0) { pos++; if (!jumped) end = pos; break; }
    if ((len & 0xc0) === 0xc0) {                 // 压缩指针
      const ptr = ((len & 0x3f) << 8) | buf[pos + 1];
      if (!jumped) end = pos + 2;
      pos = ptr; jumped = true;
      continue;
    }
    labels.push(buf.toString('utf8', pos + 1, pos + 1 + len));
    pos += 1 + len;
    if (!jumped) end = pos;
  }
  return { name: labels.join('.').toLowerCase(), end };
}

function rr(name, type, rdata, flush) {
  const cls = flush ? (CLASS_IN | FLAG_CACHE_FLUSH) : CLASS_IN;
  const head = Buffer.alloc(10);
  head.writeUInt16BE(type, 0);
  head.writeUInt16BE(cls, 2);
  head.writeUInt32BE(TTL, 4);
  head.writeUInt16BE(rdata.length, 8);
  return Buffer.concat([writeName(name), head, rdata]);
}

function txtRdata(txt) {
  const parts = [];
  for (const [k, v] of Object.entries(txt || {})) {
    const s = Buffer.from(`${k}=${v}`, 'utf8');
    if (s.length > 255) continue;
    parts.push(Buffer.from([s.length]), s);
  }
  if (!parts.length) parts.push(Buffer.from([0]));   // 空 TXT 也要有一个 0 字节
  return Buffer.concat(parts);
}

function srvRdata(port, target) {
  const b = Buffer.alloc(6);
  b.writeUInt16BE(0, 0);   // priority
  b.writeUInt16BE(0, 2);   // weight
  b.writeUInt16BE(port, 4);
  return Buffer.concat([b, writeName(target)]);
}

function ipToBuf(ip) {
  return Buffer.from(ip.split('.').map(Number));
}

// ---------- responder ----------
const services = [];      // { instance, type, port, txt }
let sock = null;
let announceTimer = null;
let stopped = false;

function fullName(s) { return `${s.instance}.${s.type}.local`; }

function buildReply(questions, s, host) {
  const answers = [];
  const additional = [];
  const inst = fullName(s);
  // DNS 名字比较一律用小写（查询名在解析时已 lower），写回记录时用原始大小写
  const instL = inst.toLowerCase();
  const typeL = `${s.type}.local`.toLowerCase();
  const hostL = host.toLowerCase();
  let matched = false;

  for (const q of questions) {
    if (q.name === typeL && (q.qtype === TYPE_PTR || q.qtype === TYPE_ANY)) {
      matched = true;
      answers.push(rr(`${s.type}.local`, TYPE_PTR, writeName(inst), false));
      additional.push(rr(inst, TYPE_SRV, srvRdata(s.port, host), true));
      additional.push(rr(inst, TYPE_TXT, txtRdata(s.txt), true));
      additional.push(rr(host, TYPE_A, ipToBuf(s.ip), true));
    } else if (q.name === instL && (q.qtype === TYPE_SRV || q.qtype === TYPE_TXT || q.qtype === TYPE_ANY)) {
      matched = true;
      if (q.qtype !== TYPE_TXT) answers.push(rr(inst, TYPE_SRV, srvRdata(s.port, host), true));
      if (q.qtype !== TYPE_SRV) answers.push(rr(inst, TYPE_TXT, txtRdata(s.txt), true));
      additional.push(rr(host, TYPE_A, ipToBuf(s.ip), true));
    } else if (q.name === hostL && (q.qtype === TYPE_A || q.qtype === TYPE_ANY)) {
      matched = true;
      answers.push(rr(host, TYPE_A, ipToBuf(s.ip), true));
    } else if (q.name === '_services._dns-sd._udp.local' && (q.qtype === TYPE_PTR || q.qtype === TYPE_ANY)) {
      matched = true;
      answers.push(rr('_services._dns-sd._udp.local', TYPE_PTR, writeName(`${s.type}.local`), false));
    }
  }
  return matched ? { answers, additional } : null;
}

function send(msg, rinfo, forceUnicast) {
  if (!sock) return;
  try {
    if (forceUnicast || rinfo.port !== MDNS_PORT) sock.send(msg, rinfo.port, rinfo.address);
    else sock.send(msg, MDNS_PORT, MDNS_ADDR);
  } catch (e) { /* 忽略网络抖动 */ }
}

// 主动公告（开机 + 定期）：把 PTR/SRV/TXT/A 一起组播出去，已在监听的设备立刻能看到
function announce() {
  if (stopped) return;
  for (const s of services) {
    const inst = fullName(s);
    const ancount = 4;
    const head = Buffer.alloc(12);
    head.writeUInt16BE(0, 0);
    head.writeUInt16BE(0x8400, 2);        // QR=1, AA=1
    head.writeUInt16BE(0, 4);             // questions
    head.writeUInt16BE(ancount, 6);       // answers
    const body = Buffer.concat([
      rr(`${s.type}.local`, TYPE_PTR, writeName(inst), false),
      rr(inst, TYPE_SRV, srvRdata(s.port, s.host), true),
      rr(inst, TYPE_TXT, txtRdata(s.txt), true),
      rr(s.host, TYPE_A, ipToBuf(s.ip), true),
    ]);
    send(Buffer.concat([head, body]), { address: MDNS_ADDR, port: MDNS_PORT });
  }
}

function onMessage(msg, rinfo) {
  // 不过滤"自己发的查询"：应答是 QR=1 会被下面的判断跳过，不会自激；
  // 而不过滤才能让跑在同一台机器上的客户端（PC snapclient / 本机调试）也发现得了
  if (msg.length < 12) return;
  const flags = msg.readUInt16BE(2);
  if (flags & 0x8000) return;                                  // 是应答，不是查询
  const qdcount = msg.readUInt16BE(4);
  if (!qdcount) return;

  const id = msg.readUInt16BE(0);
  const questions = [];
  let pos = 12, wantUnicast = false;
  for (let i = 0; i < qdcount; i++) {
    const { name, end } = readName(msg, pos);
    pos = end;
    const qtype = msg.readUInt16BE(pos);
    const qclass = msg.readUInt16BE(pos + 2);
    pos += 4;
    if (qclass & FLAG_CACHE_FLUSH) wantUnicast = true;         // QU 位：要求单播回复
    questions.push({ name, qtype, qclass: qclass & 0x7fff });
  }
  console.log(`[mdns ] query from ${rinfo.address}:${rinfo.port} -> ${questions.map(q => q.name).join(',')}`);

  let replied = false;
  for (const s of services) {
    const rep = buildReply(questions, s, s.host);
    if (!rep) continue;
    const head = Buffer.alloc(12);
    head.writeUInt16BE(id, 0);
    head.writeUInt16BE(0x8400, 2);
    head.writeUInt16BE(0, 4);
    head.writeUInt16BE(rep.answers.length, 6);
    head.writeUInt16BE(0, 8);
    head.writeUInt16BE(rep.additional.length, 10);
    send(Buffer.concat([head, ...rep.answers, ...rep.additional]), rinfo, wantUnicast);
    replied = true;
  }
  if (!replied) console.log('[mdns ] 无匹配服务，未应答');
}

/**
 * 发布一个服务。可多次调用（不同类型共用同一个 socket）。
 * @param {object} o
 *   instance  实例名，snapdroid 要求以 "Snapcast" 开头
 *   type      服务类型，如 "_snapcast._tcp"
 *   port      服务端口
 *   ip        本机局域网 IPv4（必须，组播接口也要用它，否则会发到 WSL/VPN 虚拟网卡）
 *   host      主机名（默认 snapnode.local）
 *   txt       TXT 记录
 */
function publish(o) {
  const svc = {
    instance: o.instance || 'Snapcast',
    type: String(o.type || '_snapcast._tcp').toLowerCase().replace(/\.local$/, ''),
    port: o.port | 0,
    ip: o.ip,
    host: (o.host || 'snapnode') + '.local',
    txt: o.txt || {},
  };
  services.push(svc);

  if (sock) { announce(); return svc; }

  sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  sock.on('message', onMessage);
  sock.on('error', e => {
    // 5353 被别的 mDNS 服务（iTunes / Bonjour）占用时仍可能共用，这里只提示
    console.log('[mdns ] socket error:', e.message);
  });
  // 注意：接收组播必须绑 0.0.0.0（绑到单播 IP 收不到目的地址为 224.0.0.251 的包）。
  // Windows 上 5353 常被多进程共享、查询包可能投递给别的 socket，所以另有定期公告兜底。
  sock.bind(MDNS_PORT, () => {
    try {
      sock.setMulticastTTL(255);
      sock.setMulticastInterface(svc.ip);   // 关键：不发到虚拟网卡
      sock.addMembership(MDNS_ADDR, svc.ip);
    } catch (e) { /* 某些平台不支持，忽略 */ }
    console.log(`[mdns ] 已发布 ${fullName(svc)} -> ${svc.ip}:${svc.port}`);
    announce();
    setTimeout(announce, 1000);
    setTimeout(announce, 3000);
    // Windows 上 5353 常被多个进程（Bonjour/浏览器等）共享占用，查询包不一定投递到本 socket，
    // 所以除了应答查询，还要靠定期主动公告兜底：最坏 20s 内新设备一定能看见
    announceTimer = setInterval(announce, 20000).unref?.();
  });

  return svc;
}

function unpublish() {
  stopped = true;
  if (announceTimer) clearInterval(announceTimer);
  if (sock) { try { sock.close(); } catch (e) {} sock = null; }
}

module.exports = { publish, unpublish, _services: services };
