const { chromium } = require('playwright');
const fs = require('fs'); const path = require('path');
const out = process.argv[2]; const frames = path.join(out, '..', 'frames');
fs.mkdirSync(out, { recursive: true }); fs.mkdirSync(frames, { recursive: true });
const save = (f, d) => fs.writeFileSync(f, Buffer.from(d.split(',')[1], 'base64'));
(async () => {
  const b = await chromium.launch(); const p = await b.newPage();
  await p.goto('file://' + path.resolve(__dirname, 'gen.html'));
  save(path.join(out, 'fond-accueil-ambre.png'), await p.evaluate(() => frame(4.2)));
  save(path.join(out, 'fond-verrouillage-ambre.png'), await p.evaluate(() => frame(9.7, { dim: .12 })));
  save(path.join(out, 'fond-accueil-sans-feuilles.png'), await p.evaluate(() => frame(0, { noLeaves: true })));
  for (let i = 0; i < 3; i++) save(path.join(out, `dossier-verre-${i+1}.png`), await p.evaluate(v => tile(v), i));
  const N = 15 * 30;
  for (let i = 0; i < N; i++) save(path.join(frames, `f${String(i).padStart(4,'0')}.jpg`), await p.evaluate(t => frameJpg(t), i / 30));
  await b.close();
})();
