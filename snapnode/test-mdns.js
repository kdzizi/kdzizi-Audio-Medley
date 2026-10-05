/**
 * mDNS 发现自检：向 224.0.0.251:5353 发 _snapcast._tcp 的 PTR / SRV / TXT / A 查询，
 * 打印服务端回的记录。用来确认"安卓 App 能不能自动搜到服务端"。
 *
 * 用法: node test-mdns.js [服务类型]     默认 _snapcast._tcp
 */
const dgram = require('dgram');

const MDNS_ADDR = '224.0.0.251';
const MDNS_PORT = 5353;
const TYPE = { 1: 'A', 12: 'PTR', 16: 'TXT', 28: 'AAAA', 33: 'SRV' };

function writeName(name) {
  const out = [];
  for (const p of String(name).split('.').filter(x => x.length)) {
    const b = Buffer.from(p, 'utf8');
    out.push(Buffer.from([b.length]), b);
  }
  out.push(Buffer.from([0]));
  return Buffer.concat(out);
}
function readName(buf, off) {
  const labels = [];
  let pos = off, end = off, jumped = false;
  while (true) {
    const len = buf[pos];
    if (len === 0) { pos++; if (!jumped) end = pos; break; }
    if ((len & 0xc0) === 0xc0) {
      const ptr = ((len & 0x3f) << 8) | buf[pos + 1];
      if (!jumped) end = pos + 2;
      pos = ptr; jumped = true;
      continue;
    }
    labels.push(buf.toString('utf8', pos + 1, pos + 1 + len));
    pos += 1 + len;
    if (!jumped) end = pos;
  }
  return { name: labels.join('.'), end };
}

function query(qname, qtype) {
  const head = Buffer.alloc(12);
  head.writeUInt16BE(0, 0);
  head.writeUInt16BE(0, 2);
  head.writeUInt16BE(1, 4);          // 1 个 question
  const q = Buffer.alloc(4);
  q.writeUInt16BE(qtype, 0);
  q.writeUInt16BE(1, 2);             // class IN
  return Buffer.concat([head, writeName(qname), q]);
}

function dump(msg, from) {
  const ancount = msg.readUInt16BE(6);
  const arcount = msg.readUInt16BE(10);
  let pos = 12;
  const qd = msg.readUInt16BE(4);
  for (let i = 0; i < qd; i++) { const r = readName(msg, pos); pos = r.end + 4; }
  console.log(`\n--- reply from ${from} (answers=${ancount} additional=${arcount}) ---`);
  const total = ancount + arcount;
  for (let i = 0; i < total; i++) {
    const { name, end } = readName(msg, pos);
    let p = end;
    const type = msg.readUInt16BE(p);
    const cls = msg.readUInt16BE(p + 2);
    const ttl = msg.readUInt32BE(p + 4);
    const len = msg.readUInt16BE(p + 8);
    p += 10;
    const data = msg.slice(p, p + len);
    p += len;
    pos = p;
    let val = '';
    if (type === 12) val = readName(msg, p - len).name;
    else if (type === 33) { const port = data.readUInt16BE(4); val = `port=${port} target=${readName(msg, p - len + 6).name}`; }
    else if (type === 1) val = [...data].join('.');
    else if (type === 16) { const t = []; let o = 0; while (o < len) { const l = data[o]; t.push(data.toString('utf8', o + 1, o + 1 + l)); o += 1 + l; } val = t.join(' '); }
    console.log(`  ${TYPE[type] || type} ${name} ttl=${ttl} flush=${!!(cls & 0x8000)} ${val}`);
  }
}

const svc = (process.argv[2] || '_snapcast._tcp').replace(/\.local$/, '') + '.local';
const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
let got = 0;
sock.on('message', m => { got++; dump(m, 'server'); });
sock.bind(0, () => {
  try { sock.setMulticastTTL(255); } catch (e) {}
  const send = (name, t) => sock.send(query(name, t), MDNS_PORT, MDNS_ADDR);
  send(svc, 12);                       // PTR：发现阶段
  setTimeout(() => send('Snapcast.' + svc, 33), 300);   // SRV：解析阶段
  setTimeout(() => send('Snapcast.' + svc, 16), 600);   // TXT
  setTimeout(() => send('snapnode.local', 1), 900);     // A
  setTimeout(() => {
    console.log(got ? '\n[OK] 服务端有应答 —— 局域网内可被自动发现' : '\n[FAIL] 没有任何应答');
    sock.close();
    process.exit(got ? 0 : 1);
  }, 2500);
});
