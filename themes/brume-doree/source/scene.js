// Brume Dorée: procedural misty-lake-at-dusk scene + icon renderer (canvas 2D).
function rng(seed) { let s = seed % 2147483647 || 1; return () => (s = (s * 16807) % 2147483647) / 2147483647; }
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const rgb = (c, a = 1) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;
const HAZE = [226, 140, 52], NEAR = [22, 13, 8];

function pine(x, cx, by, h, r) {
  const w = h * (0.28 + r() * 0.1), tiers = 7 + (r() * 4 | 0);
  cx.beginPath(); cx.moveTo(x, by - h);
  for (let i = 1; i <= tiers; i++) { const t = i / tiers, y = by - h + h * 0.92 * t, hw = w * t * (0.75 + r() * 0.35);
    cx.lineTo(x + hw, y); cx.lineTo(x + hw * 0.35, y - h * 0.02); }
  cx.lineTo(x + w * 0.05, by); cx.lineTo(x - w * 0.05, by);
  for (let i = tiers; i >= 1; i--) { const t = i / tiers, y = by - h + h * 0.92 * t, hw = w * t * (0.75 + r() * 0.35);
    cx.lineTo(x - hw * 0.35, y - h * 0.02); cx.lineTo(x - hw, y); }
  cx.closePath(); cx.fill();
}
function treeLine(cx, W, base, hMin, hMax, col, r, density) {
  cx.fillStyle = col;
  cx.fillRect(0, base - hMin * 0.25, W, hMin * 0.25 + 4);
  for (let x = -40; x < W + 40; x += density * (0.5 + r())) pine(x, cx, base + r() * hMin * 0.1, hMin + r() * (hMax - hMin), r);
}
function fogBand(cx, W, y, h, a) {
  const g = cx.createLinearGradient(0, y - h, 0, y + h);
  g.addColorStop(0, rgb(HAZE, 0)); g.addColorStop(0.5, rgb([240, 170, 90], a)); g.addColorStop(1, rgb(HAZE, 0));
  cx.fillStyle = g; cx.fillRect(0, y - h, W, h * 2);
}
// Draws everything above the waterline into ctx; returns horizon y.
function sky(cx, W, H, o) {
  const hy = H * o.horizon, r = rng(o.seed);
  const g = cx.createLinearGradient(0, 0, 0, hy);
  g.addColorStop(0, '#3a1e0e'); g.addColorStop(0.3, '#8a4214'); g.addColorStop(0.65, '#de7d24'); g.addColorStop(1, '#ffc066');
  cx.fillStyle = g; cx.fillRect(0, 0, W, hy + 2);
  const sx = W * o.sunX, sy = hy - H * 0.115;
  let s = cx.createRadialGradient(sx, sy, 0, sx, sy, W * 0.9);
  s.addColorStop(0, 'rgba(255,240,190,1)'); s.addColorStop(0.03, 'rgba(255,214,130,.95)'); s.addColorStop(0.12, 'rgba(255,170,70,.45)'); s.addColorStop(1, 'rgba(255,140,40,0)');
  cx.fillStyle = s; cx.fillRect(0, 0, W, hy + 2);
  // soft cloud streaks
  for (let i = 0; i < 14; i++) { const y = hy * (0.15 + r() * 0.7), w = W * (0.3 + r() * 0.6), x = r() * W - w / 2;
    const cg = cx.createLinearGradient(0, y - 12, 0, y + 12); cg.addColorStop(0, 'rgba(60,25,10,0)'); cg.addColorStop(0.5, `rgba(70,28,10,${0.15 + r() * 0.2})`); cg.addColorStop(1, 'rgba(60,25,10,0)');
    cx.fillStyle = cg; cx.beginPath(); cx.ellipse(x + w / 2, y, w / 2, 8 + r() * 14, 0, 0, 7); cx.fill(); }
  // tree layers far → near, separated by fog
  const layers = [[0.12, 0.03, 0.05, 24], [0.3, 0.04, 0.07, 30], [0.55, 0.05, 0.1, 40], [0.85, 0.07, 0.15, 56]];
  layers.forEach(([d, a, b, den], i) => {
    const col = mix(HAZE, NEAR, d), base = hy + 2;
    // hide gap for the sun: trees thinner near sun on far layers
    treeLine(cx, W, base, H * a, H * b, rgb(col), r, den * W / 1080);
    fogBand(cx, W, base - H * 0.02, H * (0.025 + i * 0.006), 0.35 - i * 0.05);
  });
  return hy;
}
function scene(cx, W, H, o) {
  const off = document.createElement('canvas'); off.width = W; off.height = H; const oc = off.getContext('2d');
  const hy = sky(oc, W, H, o);
  cx.drawImage(off, 0, 0, W, hy, 0, 0, W, hy);
  // reflection with ripples
  const rH = H - hy, r = rng(o.seed + 3);
  cx.save(); cx.filter = 'blur(6px)';
  for (let y = 0; y < rH; y += 3) { const srcY = hy - y * (hy / rH) * 0.9 - 1; const dx = Math.sin(y * 0.09 + r()) * (2 + y * 0.02);
    cx.drawImage(off, 0, Math.max(0, srcY), W, 3, dx - 30, hy + y, W + 60, 3); }
  cx.restore();
  let g = cx.createLinearGradient(0, hy, 0, H);
  g.addColorStop(0, 'rgba(60,25,8,.05)'); g.addColorStop(0.5, 'rgba(40,18,6,.3)'); g.addColorStop(1, 'rgba(14,7,3,.7)');
  cx.fillStyle = g; cx.fillRect(0, hy, W, rH);
  // sun glitter path
  const sx = W * o.sunX;
  for (let i = 0; i < 900; i++) { const t = r(), y = hy + t * t * rH, spread = 18 + t * W * 0.22, x = sx + (r() - 0.5) * spread * 2;
    cx.fillStyle = `rgba(255,${190 + r() * 50 | 0},${110 + r() * 60 | 0},${(1 - t) * 0.55 * r()})`; cx.fillRect(x, y, 6 + r() * 30 * (1 - t * 0.5), 2 + t * 3); }
  fogBand(cx, W, hy + 6, H * 0.03, 0.4);
  // foreground structures
  props(cx, W, H, hy, o);
  // grain + vignette
  const id = cx.getImageData(0, 0, W, H), d = id.data;
  for (let i = 0; i < d.length; i += 4) { const n = (r() - 0.5) * 14; d[i] += n; d[i + 1] += n; d[i + 2] += n; }
  cx.putImageData(id, 0, 0);
  const v = cx.createRadialGradient(W / 2, H * 0.45, H * 0.2, W / 2, H * 0.5, H * 0.8);
  v.addColorStop(0, 'rgba(10,5,2,0)'); v.addColorStop(1, 'rgba(10,5,2,.55)');
  cx.fillStyle = v; cx.fillRect(0, 0, W, H);
}
function lamp(cx, x, base, h, s) {
  cx.fillStyle = '#120a06'; cx.fillRect(x - 5 * s, base - h, 10 * s, h);
  cx.fillRect(x - 40 * s, base - h, 80 * s, 6 * s);
  const ly = base - h + 30 * s;
  const g = cx.createRadialGradient(x, ly, 0, x, ly, 160 * s); g.addColorStop(0, 'rgba(255,220,150,.85)'); g.addColorStop(0.15, 'rgba(255,170,70,.35)'); g.addColorStop(1, 'rgba(255,150,50,0)');
  cx.fillStyle = g; cx.beginPath(); cx.arc(x, ly, 160 * s, 0, 7); cx.fill();
  cx.fillStyle = '#ffe6b0'; cx.beginPath(); cx.arc(x, ly, 9 * s, 0, 7); cx.fill();
  cx.strokeStyle = '#120a06'; cx.lineWidth = 3 * s; cx.beginPath(); cx.moveTo(x, base - h + 6 * s); cx.lineTo(x, ly - 8 * s); cx.stroke();
}
function props(cx, W, H, hy, o) {
  const s = W / 1080, dark = '#140b06';
  cx.fillStyle = dark; cx.strokeStyle = dark;
  // sailboat mast + rigging
  if (o.boat) { const bx = W * o.boat, by = hy + H * 0.012;
    cx.beginPath(); cx.moveTo(bx - 120 * s, by); cx.lineTo(bx + 140 * s, by); cx.lineTo(bx + 110 * s, by + 28 * s); cx.lineTo(bx - 95 * s, by + 28 * s); cx.fill();
    cx.fillRect(bx - 4 * s, by - H * 0.2, 8 * s, H * 0.2);
    cx.lineWidth = 2 * s; cx.beginPath(); cx.moveTo(bx, by - H * 0.2); cx.lineTo(bx - 120 * s, by); cx.moveTo(bx, by - H * 0.2); cx.lineTo(bx + 140 * s, by); cx.moveTo(bx - 60 * s, by - H * 0.13); cx.lineTo(bx + 60 * s, by - H * 0.13); cx.stroke(); }
  // pier with railing going to the right edge
  const py = hy + H * o.pierY, px = W * o.pierX;
  cx.beginPath(); cx.moveTo(px, py); cx.lineTo(W + 10, py + H * 0.05); cx.lineTo(W + 10, py + H * 0.07); cx.lineTo(px, py + 10 * s); cx.fill();
  cx.lineWidth = 7 * s;
  for (let x = px; x < W + 60; x += 70 * s) { const t = (x - px) / (W - px + 1), y = py + t * H * 0.05, ph = (30 + t * 110) * s;
    cx.fillRect(x - 3 * s * (1 + t), y - ph, 6 * s * (1 + t), ph); }
  cx.beginPath(); cx.moveTo(px, py - 30 * s); cx.lineTo(W + 10, py + H * 0.05 - 140 * s); cx.stroke();
  cx.lineWidth = 4 * s; cx.beginPath(); cx.moveTo(px, py - 16 * s); cx.lineTo(W + 10, py + H * 0.05 - 75 * s); cx.stroke();
  // wet ground in front with sheen
  const gy = hy + H * o.ground;
  const g = cx.createLinearGradient(0, gy, 0, H); g.addColorStop(0, 'rgba(25,12,6,0)'); g.addColorStop(0.3, 'rgba(25,12,6,.3)'); g.addColorStop(1, 'rgba(14,7,3,.75)');
  cx.fillStyle = g; cx.fillRect(0, gy, W, H - gy);
  o.lamps.forEach(([lx, lh]) => lamp(cx, W * lx, hy + H * o.pierY + 40 * s, H * lh, s * (0.6 + lh)));
  if (o.figure) figure(cx, W * o.figure[0], H * o.figure[1], o.figure[2] * s);
}
function figure(cx, x, y, s) { // hooded walker seen from behind, rust coat, cropped by the bottom edge
  const H = cx.canvas.height;
  const coat = new Path2D();
  coat.moveTo(x - 10 * s, y - 520 * s);
  coat.bezierCurveTo(x - 75 * s, y - 522 * s, x - 92 * s, y - 455 * s, x - 78 * s, y - 405 * s);
  coat.bezierCurveTo(x - 140 * s, y - 392 * s, x - 168 * s, y - 350 * s, x - 172 * s, y - 280 * s);
  coat.bezierCurveTo(x - 180 * s, y - 120 * s, x - 205 * s, y + 40 * s, x - 215 * s, H);
  coat.lineTo(x + 205 * s, H);
  coat.bezierCurveTo(x + 190 * s, y + 40 * s, x + 168 * s, y - 120 * s, x + 158 * s, y - 280 * s);
  coat.bezierCurveTo(x + 152 * s, y - 350 * s, x + 128 * s, y - 392 * s, x + 66 * s, y - 405 * s);
  coat.bezierCurveTo(x + 84 * s, y - 455 * s, x + 58 * s, y - 522 * s, x - 10 * s, y - 520 * s);
  let g = cx.createLinearGradient(x - 200 * s, 0, x + 200 * s, 0);
  g.addColorStop(0, '#2a0b04'); g.addColorStop(0.45, '#5e1a0b'); g.addColorStop(0.8, '#8e2c12'); g.addColorStop(1, '#c4561e');
  cx.fillStyle = g; cx.fill(coat);
  cx.save(); cx.clip(coat);
  g = cx.createLinearGradient(0, y - 520 * s, 0, H); g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(1, 'rgba(10,3,1,.55)'); cx.fillStyle = g; cx.fillRect(0, 0, cx.canvas.width, H);
  cx.strokeStyle = 'rgba(20,5,2,.28)'; cx.lineWidth = 5 * s; // coat folds
  [[-90, -250, -120, H], [20, -330, 10, H], [100, -260, 130, H], [-20, -60, -40, H]].forEach(([a, b, c, d]) => {
    cx.beginPath(); cx.moveTo(x + a * s, y + b * s); cx.quadraticCurveTo(x + (a + c) / 2 * s + 15 * s, (y + b * s + d) / 2, x + c * s, d); cx.stroke(); });
    cx.restore();
  // rim light from the sunset on the right edge
  cx.save(); cx.clip(coat); cx.strokeStyle = 'rgba(255,170,80,.7)'; cx.lineWidth = 10 * s; cx.filter = `blur(${4 * s}px)`;
  cx.beginPath(); cx.moveTo(x + 50 * s, y - 515 * s); cx.bezierCurveTo(x + 84 * s, y - 455 * s, x + 75 * s, y - 410 * s, x + 66 * s, y - 405 * s);
  cx.bezierCurveTo(x + 128 * s, y - 392 * s, x + 152 * s, y - 350 * s, x + 158 * s, y - 280 * s); cx.bezierCurveTo(x + 168 * s, y - 120 * s, x + 190 * s, y + 40 * s, x + 205 * s, H); cx.stroke(); cx.restore();
  // rolled mat on the back with strap
  cx.save(); cx.translate(x - 150 * s, y - 330 * s); cx.rotate(-0.12);
  g = cx.createLinearGradient(0, 0, 70 * s, 0); g.addColorStop(0, '#0d0705'); g.addColorStop(1, '#2a1a10'); cx.fillStyle = g;
  cx.beginPath(); cx.roundRect(0, 0, 62 * s, 520 * s, 30 * s); cx.fill(); cx.restore();
  cx.strokeStyle = '#120804'; cx.lineWidth = 14 * s; cx.beginPath(); cx.moveTo(x + 40 * s, y - 395 * s); cx.lineTo(x - 120 * s, y - 120 * s); cx.stroke();
}
// ---------- Icons ----------
const GLYPHS = {
  telephone: 'M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z',
  messages: 'M21 11.5a8.4 8.4 0 0 1-9 8.3 8.5 8.5 0 0 1-3.8-.9L3 20.5l1.6-4.8A8.4 8.4 0 0 1 3.5 11.5 8.5 8.5 0 0 1 12 3a8.4 8.4 0 0 1 9 8.5z',
  internet: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM2 12h20M12 2a15 15 0 0 1 0 20M12 2a15 15 0 0 0 0 20',
  camera: 'M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2zM12 9a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
  galerie: 'M3 3h18v18H3zM8.5 7a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zM21 15l-5-5L5 21',
  agenda: 'M3 5h18v16H3zM16 3v4M8 3v4M3 10h18',
  horloge: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM12 6v6l4 2',
  meteo: 'M17.5 19H9a7 7 0 1 1 6.7-9h1.8a4.5 4.5 0 1 1 0 9z',
  notes: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8',
  rappels: 'M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01',
  musique: 'M9 18V5l12-2v13M6 15a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM18 13a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
  parametres: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z',
  fichiers: 'M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z',
  plans: 'M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0zM12 7a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
  boussole: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM16.2 7.8l-2.1 6.3-6.3 2.1 2.1-6.3z',
  email: 'M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM22 6l-10 7L2 6',
  contacts: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
  calculatrice: 'M5 2h14a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zM7 6h10v4H7zM8 14h.01M12 14h.01M16 14h.01M8 18h.01M12 18h.01M16 18h.01',
  sante: 'M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21l7.8-7.5 1-1.1a5.5 5.5 0 0 0 0-7.8z',
  livres: 'M2 4h6a4 4 0 0 1 4 4v13a3 3 0 0 0-3-3H2zM22 4h-6a4 4 0 0 0-4 4v13a3 3 0 0 1 3-3h7z',
  store: 'M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4zM3 6h18M16 10a4 4 0 0 1-8 0',
  video: 'M23 7l-7 5 7 5zM1 5h15v14H1z',
  podcasts: 'M12 11a1 1 0 1 0 0 2 1 1 0 0 0 0-2zM12 15v7M8.5 16.5a5 5 0 1 1 7 0M5.6 19.4a9 9 0 1 1 12.8 0',
  feu: 'M12 22c4 0 7-3 7-7 0-4-3-6-4-10-1 2-2 3-3 3 0-2-1-4-3-6 0 4-4 6-4 13 0 4 3 7 7 7zM12 22c-2 0-3-1.5-3-3.5S12 14 12 14s3 2.5 3 4.5-1 3.5-3 3.5z',
  pin: 'M12 2l4 6h-2l4 5h-2.5l4.5 6H4l4.5-6H6l4-5H8zM12 19v3',
};
const ICON_STYLES = {
  verre: { bg: ['#3d2c1f', '#1d1510'], glyph: ['#f6b752', '#c96f1d'], rim: 'rgba(255,190,110,.55)' },
  ambre: { bg: ['#e39a35', '#a9541a'], glyph: ['#2a1508', '#3a1d0b'], rim: 'rgba(255,225,170,.8)' },
  rouille: { bg: ['#8a3a16', '#4a1a08'], glyph: ['#ffc070', '#e88a2c'], rim: 'rgba(255,170,100,.6)' },
  foret: { bg: ['#2a3a2c', '#141d16'], glyph: ['#e7a24a', '#b9661c'], rim: 'rgba(230,170,100,.45)' },
};
function icon(name, style, S = 512) {
  const c = document.createElement('canvas'); c.width = c.height = S; const x = c.getContext('2d');
  const st = ICON_STYLES[style], k = S / 512, rad = 118 * k, p = 8 * k;
  x.shadowColor = 'rgba(0,0,0,.45)'; x.shadowBlur = 0;
  const body = () => { x.beginPath(); x.roundRect(p, p, S - 2 * p, S - 2 * p, rad); };
  body(); let g = x.createLinearGradient(0, 0, 0, S); g.addColorStop(0, st.bg[0]); g.addColorStop(1, st.bg[1]); x.fillStyle = g; x.fill();
  x.save(); body(); x.clip();
  g = x.createRadialGradient(S * 0.5, S * 1.05, 0, S * 0.5, S * 1.05, S * 0.7); g.addColorStop(0, 'rgba(255,150,50,.35)'); g.addColorStop(1, 'rgba(255,150,50,0)'); x.fillStyle = g; x.fillRect(0, 0, S, S);
  g = x.createLinearGradient(0, 0, 0, S * 0.5); g.addColorStop(0, 'rgba(255,240,220,.18)'); g.addColorStop(1, 'rgba(255,240,220,0)'); x.fillStyle = g; x.fillRect(0, 0, S, S * 0.5);
  x.restore();
  body(); g = x.createLinearGradient(0, 0, 0, S); g.addColorStop(0, st.rim); g.addColorStop(0.5, 'rgba(255,170,80,.08)'); g.addColorStop(1, 'rgba(255,150,60,.35)'); x.strokeStyle = g; x.lineWidth = 5 * k; x.stroke();
  // glyph
  const sc = 11 * k; x.save(); x.translate(S / 2 - 12 * sc, S / 2 - 12 * sc); x.scale(sc, sc);
  g = x.createLinearGradient(0, 0, 0, 24); g.addColorStop(0, st.glyph[0]); g.addColorStop(1, st.glyph[1]);
  x.strokeStyle = g; x.lineWidth = 1.9; x.lineCap = 'round'; x.lineJoin = 'round';
  x.shadowColor = style === 'ambre' ? 'rgba(255,230,180,.4)' : 'rgba(255,150,40,.55)'; x.shadowBlur = 18 * k;
  x.stroke(new Path2D(GLYPHS[name])); x.restore();
  return c;
}
