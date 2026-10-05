const fs = require('fs'), path = require('path');
const SRC = 'C:/Users/kdzizi/Desktop/sound/snapdroid-app/Snapcast/src/main';
let err = 0;

const ids = new Set(), strings = new Set(), layouts = new Set(), menus = new Set();
function walk(d, cb) { for (const f of fs.readdirSync(d)) { const p = path.join(d, f); fs.statSync(p).isDirectory() ? walk(p, cb) : cb(p); } }
walk(SRC + '/res', p => {
  const rel = path.relative(SRC + '/res', p).replace(/\\/g, '/');
  const parts = rel.split('/');
  const dir = parts[0], name = parts[1];
  const t = fs.readFileSync(p, 'utf8');
  for (const m of t.matchAll(/android:id="@\+id\/([\w.]+)"/g)) ids.add(m[1]);
  if (dir === 'values') for (const m of t.matchAll(/<string name="([\w.]+)"/g)) strings.add(m[1]);
  if (dir === 'layout') layouts.add(name.replace(/\.xml$/, ''));
  if (dir === 'menu') menus.add(name.replace(/\.xml$/, ''));
});

const javaFiles = [];
walk(SRC + '/java', p => { if (p.endsWith('.java')) javaFiles.push(p); });
for (const jf of javaFiles) {
  const base = path.basename(jf);
  const t = fs.readFileSync(jf, 'utf8');
  for (const m of t.matchAll(/(?<![\w.])R\.id\.(\w+)/g)) if (!ids.has(m[1])) { console.log('MISSING id ' + m[1] + ' <- ' + base); err++; }
  for (const m of t.matchAll(/(?<![\w.])R\.layout\.(\w+)/g)) if (!layouts.has(m[1])) { console.log('MISSING layout ' + m[1] + ' <- ' + base); err++; }
  for (const m of t.matchAll(/(?<![\w.])R\.string\.(\w+)/g)) if (!strings.has(m[1])) { console.log('MISSING string ' + m[1] + ' <- ' + base); err++; }
  for (const m of t.matchAll(/(?<![\w.])R\.menu\.(\w+)/g)) if (!menus.has(m[1])) { console.log('MISSING menu ' + m[1] + ' <- ' + base); err++; }
}

const man = fs.readFileSync(SRC + '/AndroidManifest.xml', 'utf8');
for (const m of man.matchAll(/android:name="\.(\w+)"|android:name="de\.badaix\.snapcast\.(\w+)"/g)) {
  const cls = m[1] || m[2];
  if (['START_SERVICE', 'STOP_SERVICE', 'BOOT_COMPLETED'].includes(cls)) continue; // action 名，非组件
  if (!javaFiles.some(f => f.endsWith(cls + '.java'))) { console.log('MISSING class ' + cls); err++; }
}
walk(SRC + '/res', p => {
  if (!p.endsWith('.xml') || p.includes('values')) return;
  const t = fs.readFileSync(p, 'utf8');
  for (const m of t.matchAll(/@string\/(\w+)/g)) if (!strings.has(m[1])) { console.log('MISSING @string/' + m[1] + ' in ' + path.basename(p)); err++; }
});
console.log(err ? err + ' 个问题' : 'OK 静态闭合检查全部通过');
console.log('ids:' + ids.size + ' strings:' + strings.size + ' java:' + javaFiles.length);
