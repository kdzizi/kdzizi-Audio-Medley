// 生成用于同步测试的 WAV：48kHz / 16bit / 立体声
// 内容设计：每秒一次短促 click（左右声道交替），叠加持续和弦背景
// 目的：一旦多设备不同步，click 会变成明显的"双击/回声"
const fs = require('fs');
const path = require('path');

const RATE = 48000, CH = 2, BITS = 16;
const DUR = 30; // 秒
const total = RATE * DUR;
const data = Buffer.alloc(total * CH * 2);

// 背景和弦 pad（A 大调感）
const pad = [220.0, 277.18, 329.63, 440.0];

for (let i = 0; i < total; i++) {
  const t = i / RATE;
  let L = 0, R = 0;

  // 背景 pad，缓慢起伏
  const lfo = 0.55 + 0.45 * Math.sin(2 * Math.PI * 0.12 * t);
  let p = 0;
  for (let k = 0; k < pad.length; k++) {
    p += Math.sin(2 * Math.PI * pad[k] * t) * (0.055 / (k + 1));
  }
  p *= lfo;

  // 每秒一次的 click：偶秒在左，奇秒在右
  const secIdx = Math.floor(t);
  const local = t - secIdx;
  if (local < 0.04) {
    const env = Math.exp(-local * 90);
    const click = Math.sin(2 * Math.PI * 1100 * local) * env * 0.5;
    // 再加一点高频成分让 click 更"脆"
    const clickHi = Math.sin(2 * Math.PI * 3300 * local) * env * 0.15;
    if (secIdx % 2 === 0) L += click + clickHi;
    else R += click + clickHi;
  }

  // 每 4 秒一次的强拍（双声道），便于对齐判断
  if (secIdx % 4 === 0 && local < 0.06) {
    const env = Math.exp(-local * 55);
    const boom = Math.sin(2 * Math.PI * 160 * local) * env * 0.45;
    L += boom; R += boom;
  }

  L += p * (1 + 0.15 * Math.sin(2 * Math.PI * 0.3 * t));
  R += p * (1 - 0.15 * Math.sin(2 * Math.PI * 0.3 * t));

  // 软限幅
  L = Math.tanh(L * 1.2) * 0.8;
  R = Math.tanh(R * 1.2) * 0.8;

  data.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(L * 32767))), i * 4);
  data.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(R * 32767))), i * 4 + 2);
}

// 44 字节 RIFF WAVE 头（与 snapcast pcm_encoder 完全一致的构造）
const h = Buffer.alloc(44);
h.write('RIFF', 0, 'ascii');
h.writeUInt32LE(36 + data.length, 4);
h.write('WAVE', 8, 'ascii');
h.write('fmt ', 12, 'ascii');
h.writeUInt32LE(16, 16);
h.writeUInt16LE(1, 20);                                  // PCM
h.writeUInt16LE(CH, 22);
h.writeUInt32LE(RATE, 24);
h.writeUInt32LE(RATE * CH * BITS / 8, 28);               // byte rate
h.writeUInt16LE(CH * BITS / 8, 32);                      // block align
h.writeUInt16LE(BITS, 34);
h.write('data', 36, 'ascii');
h.writeUInt32LE(data.length, 40);

const out = path.join(__dirname, 'sync-test.wav');
fs.writeFileSync(out, Buffer.concat([h, data]));
console.log('written:', out, (44 + data.length) / 1048576, 'MB', DUR + 's', RATE + 'Hz', CH + 'ch');
