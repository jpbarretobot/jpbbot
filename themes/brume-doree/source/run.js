const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
const out = path.resolve(process.argv[2]); const mode = process.argv[3] || 'all';
const save = (f, d) => { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, Buffer.from(d.split(',')[1], 'base64')); };
(async () => {
  const b = await chromium.launch(); const p = await b.newPage();
  await p.goto('file://' + path.resolve(__dirname, 'gen.html'));
  save(path.join(out, 'fonds/brume-doree-accueil.png'), await p.evaluate(() => wall('home')));
  save(path.join(out, 'fonds/brume-doree-verrouillage.png'), await p.evaluate(() => wall('lock')));
  const plan = { verre: null, ambre: ['camera', 'galerie', 'notes'], rouille: ['feu', 'messages', 'musique'], foret: ['pin', 'boussole', 'sante'] };
  const names = await p.evaluate(() => names());
  for (const n of names) {
    const st = Object.keys(plan).find(k => plan[k] && plan[k].includes(n)) || 'verre';
    save(path.join(out, `icones/${n}.png`), await p.evaluate(([n, s]) => ico(n, s, 512), [n, st]));
  }
  if (mode === 'all') { const T = 12, F = 30;
    for (let i = 0; i < T * F; i++) save(path.join(out, '../frames', `f${String(i).padStart(4, '0')}.jpg`), await p.evaluate(([t, T]) => animFrame(t, T), [i / F, T])); }
  await b.close();
})();
