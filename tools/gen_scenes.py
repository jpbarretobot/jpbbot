"""Génère les illustrations de fond et les textures animées du widget.

Usage : python3 tools/gen_scenes.py [dossier_sortie] [scene_xxx ... | fx]
Sans liste, seules les textures animées (fx) sont générées : les fonds sont désormais de vraies
photos (voir tools/fetch_photos.py). Nommer une scène pour régénérer son illustration.
Les images sont déterministes (graines fixes) : relancer le script redonne les mêmes fichiers.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1200, 600
HORIZON = 0.66  # position de la ligne d'eau (fraction de la hauteur)
OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/drawable-nodpi"


# ---------------------------------------------------------------- outils

def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32) / 255.0


def lerp(a, b, t):
    return a + (b - a) * t


def gradient(stops, n):
    """stops: liste de (position 0..1, couleur hex) -> tableau (n, 3)."""
    pos = np.linspace(0, 1, n)
    out = np.zeros((n, 3), np.float32)
    ps = [p for p, _ in stops]
    cs = [hexc(c) for _, c in stops]
    for ch in range(3):
        out[:, ch] = np.interp(pos, ps, [c[ch] for c in cs])
    return out


def value_noise(w, h, cell, rng, tile_x=False, tile_y=False):
    gw, gh = int(np.ceil(w / cell)) + 1, int(np.ceil(h / cell)) + 1
    if tile_x:
        gw = w // cell
    if tile_y:
        gh = h // cell
    g = rng.random((gh + 1, gw + 1)).astype(np.float32)
    if tile_x:
        g[:, gw] = g[:, 0]
    if tile_y:
        g[gh, :] = g[0, :]
    ys = np.arange(h) / cell
    xs = np.arange(w) / cell
    y0 = np.floor(ys).astype(int)
    x0 = np.floor(xs).astype(int)
    fy = ys - y0
    fx = xs - x0
    fy = fy * fy * (3 - 2 * fy)
    fx = fx * fx * (3 - 2 * fx)
    if tile_x:
        x0 = x0 % gw
    if tile_y:
        y0 = y0 % gh
    a = g[np.ix_(y0, x0)]
    b = g[np.ix_(y0, x0 + 1)]
    c = g[np.ix_(y0 + 1, x0)]
    d = g[np.ix_(y0 + 1, x0 + 1)]
    fx = fx[None, :]
    fy = fy[:, None]
    return lerp(lerp(a, b, fx), lerp(c, d, fx), fy)


def fbm(w, h, base, octaves, rng, tile_x=False, tile_y=False, gain=0.5):
    total = np.zeros((h, w), np.float32)
    amp, norm, cell = 1.0, 0.0, base
    for _ in range(octaves):
        cell_i = max(2, int(cell))
        if tile_x:
            while w % cell_i:
                cell_i -= 1
        if tile_y:
            while h % cell_i:
                cell_i -= 1
        total += amp * value_noise(w, h, cell_i, rng, tile_x, tile_y)
        norm += amp
        amp *= gain
        cell /= 2
    return total / norm


def ridge(w, base, amp, rng, octaves=5):
    n = fbm(w, 1, base, octaves, rng)[0]
    return (n - n.mean()) * amp


def blur(arr, r):
    if r <= 0:
        return arr
    if arr.ndim == 2:
        im = Image.fromarray(np.clip(arr * 255, 0, 255).astype(np.uint8), "L")
        return np.asarray(im.filter(ImageFilter.GaussianBlur(r)), np.float32) / 255.0
    im = Image.fromarray(np.clip(arr * 255, 0, 255).astype(np.uint8))
    return np.asarray(im.filter(ImageFilter.GaussianBlur(r)), np.float32) / 255.0


def over(img, color, alpha):
    """Compose une couleur (3,) ou une image (h,w,3) avec un masque alpha (h,w)."""
    a = alpha[..., None]
    return img * (1 - a) + np.asarray(color, np.float32) * a


def radial(cx, cy, rx, ry=None):
    ry = ry or rx
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    return np.sqrt(((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2)


# ---------------------------------------------------------------- éléments

def pine_mask(w, h, items, ss=2, seed=0):
    """items: (x, y_base, hauteur, largeur, inclinaison). Silhouettes de sapins anticrénelées."""
    rng = np.random.default_rng(seed)
    im = Image.new("L", (w * ss, h * ss), 0)
    dr = ImageDraw.Draw(im)
    for x, yb, ht, wd, lean in items:
        x, yb, ht, wd, lean = x * ss, yb * ss, ht * ss, wd * ss, lean * ss
        trunk_top = yb - ht
        dr.polygon([(x - wd * 0.035, yb + 4 * ss), (x + lean - wd * 0.008, trunk_top), (x + lean + wd * 0.008, trunk_top), (x + wd * 0.035, yb + 4 * ss)], fill=255)
        n = max(10, int(ht / (5 * ss)))
        bottom = yb - ht * rng.uniform(0.08, 0.2)
        for i in range(n):
            t = i / (n - 1)
            y = bottom - (bottom - trunk_top) * t
            cx = x + lean * t
            env = (1 - t) ** 1.05 * (0.92 + 0.08 * np.sin(t * 9))
            for side in (-1, 1):
                if rng.random() < 0.12 and t < 0.85:
                    continue
                L = wd * 0.5 * env * rng.uniform(0.55, 1.15)
                droop = L * rng.uniform(0.18, 0.4)
                thick = max(2 * ss, (bottom - trunk_top) / n * rng.uniform(1.3, 2.2))
                tip = (cx + side * L, y + droop)
                dr.polygon([(cx, y - thick * 0.6), (cx + side * L * 0.55, y + droop * 0.2), tip, (cx + side * L * 0.4, y + droop * 0.75 + thick * 0.3), (cx, y + thick * 0.5)], fill=255)
                # petites aiguilles qui dépassent
                for _ in range(2):
                    u = rng.uniform(0.3, 0.95)
                    px, py = cx + side * L * u, y + droop * u
                    dr.line([(px, py), (px + side * rng.uniform(2, 6) * ss, py + rng.uniform(2, 7) * ss)], fill=255, width=ss)
    im = im.resize((w, h), Image.LANCZOS)
    return np.asarray(im, np.float32) / 255.0


def ridge_mask(ys):
    y = np.arange(H, dtype=np.float32)[:, None]
    return np.clip(y - ys[None, :] + 0.5, 0, 1)


def forest_line(rng, base_y, density, hmin, hmax, xs=None, ridge_amp=12):
    items = []
    line = base_y + ridge(W, 260, ridge_amp, rng)
    x = -20.0
    while x < W + 20:
        if xs is None or xs(x):
            ht = rng.uniform(hmin, hmax) * (0.6 + 0.8 * rng.random() ** 2)
            items.append((x, line[int(np.clip(x, 0, W - 1))] + rng.uniform(-3, 3), ht, ht * rng.uniform(0.32, 0.45), rng.uniform(-1.5, 1.5)))
        x += rng.uniform(0.25, 1.1) * density
    m = pine_mask(W, H, items, seed=int(rng.integers(1 << 30)))
    m = np.maximum(m, ridge_mask(line + 2))
    return m


def stars(rng, img, sky_h, count=500):
    y = (rng.random(count) ** 1.6) * sky_h
    x = rng.random(count) * W
    b = rng.random(count) ** 3
    layer = np.zeros((H, W), np.float32)
    for xi, yi, bi in zip(x.astype(int), y.astype(int), b):
        layer[yi, xi] = max(layer[yi, xi], 0.35 + 0.65 * bi)
    glow = blur(layer, 1.2) * 2.2
    layer = np.clip(layer + glow, 0, 1)
    return over(img, hexc("#fff6e6"), layer * 0.9)


def sky_clouds(rng, img, top, bottom, lit, shade, cover=0.5, scale=260, stretch=3.0, alpha=1.0, light_from=(0.5, 0.0)):
    h = int(bottom - top)
    n = fbm(int(W / stretch) + 2, h, scale / stretch, 6, rng)
    n = np.asarray(Image.fromarray((n * 255).astype(np.uint8)).resize((W, h), Image.BICUBIC), np.float32) / 255.0
    yy = np.linspace(0, 1, h)[:, None]
    dens = np.clip((n - (1 - cover) * 0.55 - 0.25) * 3.2, 0, 1)
    dens *= np.clip(yy * 3, 0, 1) * np.clip((1 - yy) * 2.5, 0, 1)
    # éclairage : différence de densité décalée vers la source de lumière
    shift = np.roll(np.roll(dens, -6, axis=0), int(-8 * (light_from[0] - 0.5) * 2), axis=1)
    light = np.clip(0.6 + (dens - shift) * 1.3 - dens * 0.25, 0, 1)[..., None]
    col = lit * light + shade * (1 - light)
    region = img[top:bottom]
    a = (dens * alpha)[..., None]
    img[top:bottom] = region * (1 - a) + col * a
    return img


def reflect(img, hz, rng, darken, tint, ripple=3.0, glitter=None):
    sky = img[:hz][::-1].copy()
    wh = H - hz
    refl = sky[:wh] if sky.shape[0] >= wh else np.concatenate([sky, np.repeat(sky[-1:], wh - sky.shape[0], 0)])
    # ondulations horizontales
    out = np.empty_like(refl)
    noise = fbm(W, wh, 40, 4, rng)
    for r in range(wh):
        k = (r / wh)
        off = (np.sin(r * 0.9 + noise[r] * 6) * ripple * (0.3 + k * 2.5)).astype(int)
        idx = np.clip(np.arange(W) + off, 0, W - 1)
        out[r] = refl[r, idx]
    out = blur(out, 2.2)
    streaks = fbm(W // 6, wh, 6, 3, rng)
    streaks = np.asarray(Image.fromarray((streaks * 255).astype(np.uint8)).resize((W, wh), Image.BILINEAR), np.float32) / 255.0
    out = out * (1 - darken) + tint * darken
    out = out * (0.9 + 0.2 * streaks[..., None])
    if glitter is not None:
        gx, gcol, gw, gstr = glitter
        x = np.arange(W)[None, :]
        r = np.arange(wh)[:, None]
        width = gw * (0.6 + r / wh * 1.8)
        col = np.exp(-((x - gx) / width) ** 2)
        spark = fbm(W // 10, wh, 2, 2, rng)
        spark = np.asarray(Image.fromarray((spark * 255).astype(np.uint8)).resize((W, wh), Image.BILINEAR), np.float32) / 255.0
        spark = np.clip((spark - 0.5) * 5, 0, 1)
        a = np.clip(col * (0.25 + spark * 1.2) * gstr * (1 - r / wh * 0.5), 0, 1)
        out = over(out, gcol, a)
    img[hz:] = out
    # liseré lumineux de la ligne d'eau
    img[hz:hz + 2] = img[hz:hz + 2] * 0.7 + img[hz - 3:hz - 1] * 0.3
    return img


def finish(img, rng, vignette=0.45, grain=0.025, warm=None, bloom=0.0):
    if bloom:
        b = blur(np.clip(img - 0.6, 0, 1), 18)
        img = 1 - (1 - img) * (1 - b * bloom)
    v = radial(W / 2, H / 2, W * 0.75, H * 0.9)
    img = img * (1 - vignette * np.clip(v - 0.45, 0, 1)[..., None] ** 1.5)
    if warm is not None:
        img = img * (1 - 0.08) + warm * 0.08 * img
    g = (rng.random((H, W)) - 0.5) * grain
    img = img + g[..., None]
    return np.clip(img, 0, 1)


def save(img, name, quality=88):
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    im.save(os.path.join(OUT, name + ".webp"), "WEBP", quality=quality, method=6)


# ---------------------------------------------------------------- paysage

def landscape(seed, p):
    """p: dictionnaire de palette / options. Renvoie une image (H, W, 3)."""
    rng = np.random.default_rng(seed)
    hz = int(H * HORIZON)
    img = np.repeat(gradient(p["sky"], H)[:, None, :], W, axis=1)

    if p.get("stars"):
        img = stars(rng, img, hz, p.get("stars"))

    # astre
    if "sun" in p:
        sx, sy, srad, scol, glow, gcol = p["sun"]
        d = radial(sx * W, sy * H, 1.0)
        img = over(img, hexc(gcol), np.clip(np.exp(-(d / (glow * W)) ** 1.2), 0, 1) * p.get("glow_strength", 0.85))
        img = over(img, hexc(gcol), np.clip(np.exp(-(d / (glow * W * 0.35)) ** 2), 0, 1) * 0.6)
        img = over(img, hexc(scol), np.clip((srad - d) / 2.0, 0, 1))
    if "moon" in p:
        mx, my, mr = p["moon"]
        d = radial(mx * W, my * H, 1.0)
        img = over(img, hexc("#9fb6d8"), np.exp(-(d / (W * 0.09)) ** 1.3) * 0.35)
        disc = np.clip((mr - d) / 1.5, 0, 1)
        d2 = radial(mx * W + mr * 0.45, my * H - mr * 0.2, 1.0)
        crescent = disc * np.clip((d2 - mr * 0.95) / 2.0, 0, 1)
        img = over(img, hexc("#f4f0e2"), crescent)

    if "clouds" in p:
        for c in p["clouds"]:
            img = sky_clouds(rng, img, int(c[0] * H), int(c[1] * H), hexc(c[2]), hexc(c[3]), cover=c[4], scale=c[5], alpha=c[6], light_from=(p.get("sun", (0.5,))[0], 0))

    haze = hexc(p["haze"])
    # montagnes lointaines
    for i, (base, amp, col, hz_amt, sc) in enumerate(p["mountains"]):
        ys = hz - base * H + ridge(W, sc, amp * H * 2.2, rng, octaves=7)
        m = ridge_mask(ys)
        col = lerp(hexc(col), haze, hz_amt)
        grad = np.linspace(0, 1, H)[:, None, None]
        shade = col * (0.85 + 0.25 * grad)
        img = over(img, shade, m)
        # brume au pied
        mist = np.exp(-((np.arange(H)[:, None] - hz + base * H * 0.15) / (H * 0.06)) ** 2) * p.get("mist", 0.3)
        img = over(img, haze, mist * np.ones((1, W)) * m)

    # lignes de forêt (lointaine -> proche)
    for (base, dens, hmin, hmax, col, hz_amt) in p["forests"]:
        m = forest_line(rng, hz - base * H, dens, hmin, hmax)
        img = over(img, lerp(hexc(col), haze, hz_amt), m)
        mist = np.clip(1 - (hz - np.arange(H)[:, None]) / (H * 0.05), 0, 1) * (np.arange(H)[:, None] < hz)
        img = over(img, haze, mist * 0.35 * p.get("mist", 0.3) * np.ones((1, W)))

    # grands sapins des bords (premier plan)
    items = []
    for side in p.get("sides", ["l", "r"]):
        n = 7
        for k in range(n):
            if side == "l":
                x = rng.uniform(-40, W * 0.30)
            else:
                x = rng.uniform(W * 0.78, W + 40)
            edge = abs((x / W) - (0.0 if side == "l" else 1.0))
            ht = H * rng.uniform(0.45, 0.95) * (1.05 - edge * 1.1)
            items.append((x, hz - 4 + rng.uniform(0, 6), ht, ht * rng.uniform(0.3, 0.42), rng.uniform(-2, 2)))
    if items:
        bank = np.zeros(W, np.float32) + H * 2
        xs_ = np.arange(W)
        if "l" in p.get("sides", ["l", "r"]):
            bank = np.minimum(bank, hz - 6 + np.clip((xs_ - W * 0.36) / 3.0, 0, None) + ridge(W, 120, 6, rng))
        if "r" in p.get("sides", ["l", "r"]):
            bank = np.minimum(bank, hz - 6 + np.clip((W * 0.74 - xs_) / 3.0, 0, None) + ridge(W, 120, 6, rng))
        side_m = np.maximum(pine_mask(W, H, items, seed=seed), ridge_mask(bank) * (np.arange(H)[:, None] < hz + 1))
        side_col = hexc(p["near"])
        img = over(img, lerp(side_col, haze, p.get("near_haze", 0.15)), side_m)

    if p.get("poles"):
        pm = Image.new("L", (W * 2, H * 2), 0)
        dr = ImageDraw.Draw(pm)
        for x, top in p["poles"]:
            dr.rectangle([x * 2 - 2, top * H * 2, x * 2 + 2, hz * 2 + 6], fill=255)
            dr.rectangle([x * 2 - 12, top * H * 2 + 24, x * 2 + 12, top * H * 2 + 27], fill=255)
            dr.line([(x * 2 - 12, top * H * 2 + 27), (x * 2 - 160, top * H * 2 + 60)], fill=200, width=1)
        pm = np.asarray(pm.resize((W, H), Image.LANCZOS), np.float32) / 255.0
        img = over(img, lerp(hexc(p["near"]), haze, 0.35), pm)

    if p.get("cabin"):
        cx, cw = p["cabin"]
        cm = Image.new("L", (W, H), 0)
        dr = ImageDraw.Draw(cm)
        by = hz - 2
        dr.rectangle([cx - cw / 2, by - cw * 0.45, cx + cw / 2, by], fill=255)
        dr.polygon([(cx - cw * 0.62, by - cw * 0.42), (cx, by - cw * 0.85), (cx + cw * 0.62, by - cw * 0.42)], fill=255)
        cm = np.asarray(cm, np.float32) / 255.0
        img = over(img, lerp(hexc(p["near"]), haze, 0.25), cm)
        win = np.zeros((H, W), np.float32)
        wy0, wy1 = int(by - cw * 0.32), int(by - cw * 0.12)
        for wx in (cx - cw * 0.25, cx + cw * 0.12):
            win[wy0:wy1, int(wx):int(wx + cw * 0.14)] = 1
        img = over(img, hexc("#ffc46b"), win)
        img = over(img, hexc("#ff9d3c"), blur(win, 14) * 1.6)

    # eau
    glitter = None
    if "glitter" in p:
        gx, gcol, gw, gs = p["glitter"]
        glitter = (gx * W, hexc(gcol), gw, gs)
    img = reflect(img, hz, rng, p.get("water_dark", 0.35), hexc(p.get("water_tint", p["near"])), p.get("ripple", 3.0), glitter)

    # rive au premier plan
    shore = H - 0.06 * H + ridge(W, 180, 0.04 * H, rng)
    sm = ridge_mask(shore)
    img = over(img, hexc(p.get("shore", p["near"])), sm)

    if p.get("snow_ground"):
        sg = np.clip(sm * 1.0, 0, 1) * 0.85
        img = over(img, hexc(p["snow_ground"]), sg)

    if p.get("fog"):
        fogn = fbm(W, H, 220, 5, rng)
        y = np.arange(H)[:, None] / H
        band = np.exp(-((y - HORIZON + 0.08) / 0.22) ** 2)
        img = over(img, haze, np.clip(band * (0.55 + 0.6 * fogn) * p["fog"], 0, 0.92))

    return finish(img, rng, p.get("vignette", 0.45), warm=None, bloom=p.get("bloom", 0.0))


# ---------------------------------------------------------------- palettes

FOREST_WARM = [(0.02, 6, 14, 26, "#5b3a22", 0.55), (-0.005, 9, 22, 40, "#3a2416", 0.3)]

SCENES = {
    "scene_sunset": (11, dict(
        sky=[(0, "#4a2a1f"), (0.25, "#a5512a"), (0.5, "#e8913f"), (0.62, "#ffcf7a"), (0.66, "#ffd894"), (1, "#ffd894")],
        sun=(0.6, 0.5, 14, "#fff4d0", 0.32, "#ffb347"), glow_strength=0.9,
        clouds=[(0.05, 0.42, "#ffc070", "#6a3524", 0.48, 300, 0.75)],
        haze="#e39a4f", mist=0.45,
        mountains=[(0.13, 0.05, "#a0603a", 0.55, 420), (0.07, 0.04, "#6e3a22", 0.4, 300)],
        forests=FOREST_WARM, near="#1c120c", near_haze=0.08,
        poles=[(905, 0.36), (820, 0.43)],
        glitter=(0.62, "#ffd27a", 30, 1.0), water_dark=0.25, water_tint="#3a1d10", bloom=0.5)),
    "scene_sunrise": (12, dict(
        sky=[(0, "#2c2f55"), (0.25, "#6c5a86"), (0.45, "#d98a8e"), (0.6, "#ffc79a"), (0.66, "#ffe3b8"), (1, "#ffe3b8")],
        sun=(0.32, 0.52, 13, "#fff7e0", 0.3, "#ffbf8a"), glow_strength=0.8,
        clouds=[(0.08, 0.40, "#ffc2a6", "#5d4a73", 0.42, 280, 0.7)],
        haze="#d9a3a6", mist=0.6,
        mountains=[(0.15, 0.06, "#8a6f97", 0.55, 420), (0.08, 0.04, "#5c4a6e", 0.4, 300)],
        forests=[(0.02, 6, 14, 26, "#433651", 0.5), (-0.005, 9, 22, 40, "#2a2236", 0.3)],
        near="#16121d", glitter=(0.3, "#ffd6b0", 28, 0.9), water_dark=0.25, water_tint="#2a2236", bloom=0.4)),
    "scene_clear_day": (13, dict(
        sky=[(0, "#2f6fb5"), (0.4, "#69a6dc"), (0.66, "#d4e6f2"), (1, "#d4e6f2")],
        sun=(0.78, 0.12, 14, "#fffdf2", 0.18, "#fff3cf"), glow_strength=0.6,
        clouds=[(0.08, 0.35, "#ffffff", "#9fb7cf", 0.3, 240, 0.8)],
        haze="#b9d3e6", mist=0.25,
        mountains=[(0.17, 0.06, "#7d98b5", 0.5, 420), (0.09, 0.04, "#4f6f72", 0.35, 300)],
        forests=[(0.02, 6, 14, 26, "#2f5a45", 0.35), (-0.005, 9, 22, 40, "#1d3d2c", 0.15)],
        near="#0f2419", glitter=(0.78, "#ffffff", 40, 0.5), water_dark=0.3, water_tint="#1d4a63", vignette=0.35)),
    "scene_clear_night": (14, dict(
        sky=[(0, "#050914"), (0.4, "#0e1b36"), (0.66, "#23365a"), (1, "#23365a")],
        stars=700, moon=(0.72, 0.2, 16),
        haze="#2a3c5e", mist=0.35,
        mountains=[(0.15, 0.06, "#1d2a45", 0.45, 420), (0.08, 0.04, "#131c30", 0.35, 300)],
        forests=[(0.02, 6, 14, 26, "#0f1626", 0.3), (-0.005, 9, 22, 40, "#0a0f1b", 0.15)],
        near="#05080f", glitter=(0.72, "#c9d8f0", 18, 0.6), water_dark=0.35, water_tint="#070c18", vignette=0.5)),
    "scene_cloudy_day": (15, dict(
        sky=[(0, "#6f7d8c"), (0.4, "#9aa6b2"), (0.66, "#c9cfd4"), (1, "#c9cfd4")],
        clouds=[(0.0, 0.5, "#e6e8ea", "#6d7782", 0.75, 300, 0.95)],
        haze="#b4bcc4", mist=0.4,
        mountains=[(0.16, 0.06, "#7d8893", 0.5, 420), (0.08, 0.04, "#566169", 0.35, 300)],
        forests=[(0.02, 6, 14, 26, "#3d4c48", 0.4), (-0.005, 9, 22, 40, "#26322f", 0.2)],
        near="#151d1b", water_dark=0.35, water_tint="#3a4650", vignette=0.4)),
    "scene_cloudy_night": (16, dict(
        sky=[(0, "#0b0f17"), (0.4, "#1a2130"), (0.66, "#2d3646"), (1, "#2d3646")],
        moon=(0.3, 0.18, 14),
        clouds=[(0.0, 0.5, "#4c5668", "#141a24", 0.7, 300, 0.92)],
        haze="#2f3848", mist=0.35,
        mountains=[(0.15, 0.06, "#222a38", 0.45, 420), (0.08, 0.04, "#171d28", 0.35, 300)],
        forests=[(0.02, 6, 14, 26, "#121821", 0.3), (-0.005, 9, 22, 40, "#0b1016", 0.15)],
        near="#06090d", water_dark=0.4, water_tint="#0b1016", vignette=0.5)),
    "scene_rain_day": (17, dict(
        sky=[(0, "#4b5560"), (0.4, "#6f7a84"), (0.66, "#9aa3a8"), (1, "#9aa3a8")],
        clouds=[(0.0, 0.48, "#a3abb2", "#3d454e", 0.85, 260, 0.95)],
        haze="#87929a", mist=0.65,
        mountains=[(0.15, 0.05, "#5d6870", 0.6, 420), (0.08, 0.04, "#45515a", 0.45, 300)],
        forests=[(0.02, 6, 14, 26, "#2f3d3b", 0.45), (-0.005, 9, 22, 40, "#1f2a28", 0.25)],
        near="#111816", near_haze=0.25, cabin=(640, 46), ripple=5.0,
        water_dark=0.4, water_tint="#2b353b", fog=0.35, vignette=0.45)),
    "scene_rain_night": (18, dict(
        sky=[(0, "#090c12"), (0.4, "#151b25"), (0.66, "#262e3a"), (1, "#262e3a")],
        clouds=[(0.0, 0.48, "#323b4a", "#0e1219", 0.85, 260, 0.95)],
        haze="#283040", mist=0.55,
        mountains=[(0.15, 0.05, "#1c2330", 0.5, 420), (0.08, 0.04, "#141a24", 0.4, 300)],
        forests=[(0.02, 6, 14, 26, "#10151d", 0.35), (-0.005, 9, 22, 40, "#0a0e14", 0.2)],
        near="#05070a", near_haze=0.15, cabin=(640, 46), ripple=5.0,
        glitter=(0.545, "#ffb35a", 10, 0.7), water_dark=0.45, water_tint="#080b10", fog=0.25, vignette=0.55)),
    "scene_storm": (19, dict(
        sky=[(0, "#14111d"), (0.4, "#2b2638"), (0.66, "#4a4558"), (1, "#4a4558")],
        clouds=[(0.0, 0.52, "#6b6680", "#16131f", 0.9, 220, 0.98)],
        haze="#3e3a4c", mist=0.5,
        mountains=[(0.15, 0.06, "#2a2737", 0.5, 420), (0.08, 0.04, "#1d1b27", 0.4, 300)],
        forests=[(0.02, 6, 14, 26, "#151320", 0.35), (-0.005, 9, 22, 40, "#0d0c14", 0.2)],
        near="#06050a", ripple=7.0, water_dark=0.45, water_tint="#0d0c14", vignette=0.55)),
    "scene_snow_day": (20, dict(
        sky=[(0, "#9fb3c8"), (0.4, "#c3d0dc"), (0.66, "#e6ecf1"), (1, "#e6ecf1")],
        clouds=[(0.0, 0.45, "#f4f6f8", "#a9b6c3", 0.7, 300, 0.8)],
        haze="#dfe6ec", mist=0.6,
        mountains=[(0.17, 0.06, "#c5d1dc", 0.45, 420), (0.09, 0.04, "#9aaaba", 0.35, 300)],
        forests=[(0.02, 6, 14, 26, "#5d6f73", 0.45), (-0.005, 9, 22, 40, "#3c4c4f", 0.25)],
        near="#24302f", near_haze=0.2, water_dark=0.25, water_tint="#c9d6e0", ripple=0.6,
        shore="#eef3f6", snow_ground="#f4f7f9", vignette=0.3)),
    "scene_snow_night": (21, dict(
        sky=[(0, "#0e1528"), (0.4, "#1f2c4a"), (0.66, "#3b4c6e"), (1, "#3b4c6e")],
        clouds=[(0.0, 0.45, "#56688c", "#141c30", 0.6, 300, 0.85)],
        haze="#3d4e70", mist=0.5,
        mountains=[(0.17, 0.06, "#4a5c80", 0.4, 420), (0.09, 0.04, "#2e3c5a", 0.35, 300)],
        forests=[(0.02, 6, 14, 26, "#1a2338", 0.35), (-0.005, 9, 22, 40, "#111829", 0.2)],
        near="#080c16", cabin=(640, 46), glitter=(0.545, "#ffb35a", 10, 0.5),
        water_dark=0.3, water_tint="#2b3a58", ripple=0.6, shore="#8fa0c0", snow_ground="#a9b8d6", vignette=0.5)),
    "scene_fog_day": (22, dict(
        sky=[(0, "#9ea4a6"), (0.4, "#bfc3c3"), (0.66, "#d6d8d6"), (1, "#d6d8d6")],
        haze="#cfd2d0", mist=0.9,
        mountains=[(0.15, 0.05, "#a9aeae", 0.75, 420)],
        forests=[(0.03, 6, 16, 30, "#6d7574", 0.7), (0.0, 9, 22, 40, "#47504e", 0.45)],
        near="#1e2524", near_haze=0.35, water_dark=0.3, water_tint="#a0a6a5", ripple=1.5, fog=0.8, vignette=0.3)),
    "scene_fog_night": (23, dict(
        sky=[(0, "#141820"), (0.4, "#252b35"), (0.66, "#3a414b"), (1, "#3a414b")],
        haze="#3b424c", mist=0.9,
        mountains=[(0.15, 0.05, "#30363f", 0.7, 420)],
        forests=[(0.03, 6, 16, 30, "#1e242b", 0.6), (0.0, 9, 22, 40, "#12171c", 0.4)],
        near="#07090c", near_haze=0.3, cabin=(640, 46), glitter=(0.545, "#ffb35a", 10, 0.4),
        water_dark=0.35, water_tint="#1a1f26", ripple=1.5, fog=0.7, vignette=0.5)),
}


# ---------------------------------------------------------------- textures animées
# Toutes les textures se raccordent (tuilables) dans le sens du défilement pour que
# l'animation en boucle du widget soit sans couture.

def rgba(color, alpha):
    a = np.clip(alpha, 0, 1)
    out = np.zeros(a.shape + (4,), np.uint8)
    out[..., :3] = (np.asarray(color) * 255).astype(np.uint8)
    out[..., 3] = (a * 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def save_rgba(im, name, quality=85):
    im.save(os.path.join(OUT, name + ".webp"), "WEBP", quality=quality, method=6)


def rain_texture(seed, w, h, count, length, width, alpha, slant=0.18):
    rng = np.random.default_rng(seed)
    ss = 2
    im = Image.new("L", (w * ss, h * ss), 0)
    dr = ImageDraw.Draw(im)
    for _ in range(count):
        x = rng.uniform(0, w) * ss
        y = rng.uniform(0, h) * ss
        ln = rng.uniform(0.6, 1.3) * length * ss
        a = int(255 * alpha * rng.uniform(0.35, 1.0))
        for dy in (0, -h * ss, h * ss):  # raccord vertical
            for dx in (0, -w * ss, w * ss):
                dr.line([(x + dx, y + dy), (x + dx - ln * slant, y + dy + ln)], fill=a, width=int(width * ss))
    im = im.resize((w, h), Image.LANCZOS).filter(ImageFilter.GaussianBlur(0.4))
    return rgba(hexc("#dfe8f2"), np.asarray(im, np.float32) / 255.0)


def snow_texture(seed, w, h, count, rmin, rmax, blur_r):
    rng = np.random.default_rng(seed)
    ss = 2
    im = Image.new("L", (w * ss, h * ss), 0)
    dr = ImageDraw.Draw(im)
    for _ in range(count):
        x, y = rng.uniform(0, w) * ss, rng.uniform(0, h) * ss
        r = rng.uniform(rmin, rmax) * ss
        a = int(255 * rng.uniform(0.55, 1.0))
        for dy in (0, -h * ss, h * ss):
            for dx in (0, -w * ss, w * ss):
                dr.ellipse([x + dx - r, y + dy - r, x + dx + r, y + dy + r], fill=a)
    im = im.resize((w, h), Image.LANCZOS).filter(ImageFilter.GaussianBlur(blur_r))
    return rgba(hexc("#ffffff"), np.asarray(im, np.float32) / 255.0)


def cloud_texture(seed, w, h, cover, softness, color, top=0.0, bottom=0.75, alpha=0.9):
    rng = np.random.default_rng(seed)
    n = fbm(w, h, 240, 6, rng, tile_x=True)
    y = np.linspace(0, 1, h)[:, None]
    mask = np.clip((y - top) / 0.08, 0, 1) * np.clip((bottom - y) / 0.25, 0, 1)
    dens = np.clip((n - (1 - cover)) / softness, 0, 1) * mask
    # léger modelé : plus clair en haut des nuages
    up = np.roll(dens, 5, axis=0)
    light = np.clip(0.8 + (dens - up) * 1.5, 0.55, 1.0)
    col = np.asarray(color)[None, None, :] * light[..., None]
    out = np.zeros((h, w, 4), np.uint8)
    out[..., :3] = (np.clip(col, 0, 1) * 255).astype(np.uint8)
    out[..., 3] = (dens * alpha * 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def fog_texture(seed, w, h):
    rng = np.random.default_rng(seed)
    n = fbm(w, h, 200, 5, rng, tile_x=True)
    y = np.linspace(0, 1, h)[:, None]
    band = np.exp(-((y - 0.62) / 0.28) ** 2)
    a = np.clip((n - 0.3) * 1.6, 0, 1) * band * 0.75
    return rgba(hexc("#e9ecee"), a)


def single_clouds(seed, w, h):
    """Un ou deux nuages isolés et vaporeux en haut de l'image (raccord horizontal)."""
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    env = np.zeros((h, w), np.float32)
    for cx, cy, rx, ry, k in ((0.30, 0.17, 0.22, 0.11, 1.0), (0.76, 0.12, 0.14, 0.07, 0.85)):
        for dx in (0, -w, w):
            d = ((x - cx * w - dx) / (rx * w)) ** 2 + ((y - cy * h) / (ry * h)) ** 2
            env = np.maximum(env, k * np.exp(-d ** 1.3 * 1.4))
    n = fbm(w, h, 120, 7, rng, tile_x=True, gain=0.55)
    dens = np.clip((env - 0.45 + (n - 0.5) * 1.6) * 2.4, 0, 1)
    dens = blur(dens, 1.2)
    up = np.roll(dens, 4, axis=0)
    light = np.clip(0.82 + (dens - up) * 2.0 - dens * 0.12, 0.6, 1.0)[..., None]
    col = hexc("#ffffff") * light + hexc("#aeb8c6") * (1 - light)
    out = np.zeros((h, w, 4), np.uint8)
    out[..., :3] = (np.clip(col, 0, 1) * 255).astype(np.uint8)
    out[..., 3] = (np.clip(dens ** 0.85 * 0.92, 0, 1) * 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def shooting_star(w, h, hx, hy, length, angle_deg):
    """Étoile filante : tête brillante en (hx, hy), traînée vers le haut à droite."""
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    a = np.radians(angle_deg)
    ux, uy = np.cos(a), -np.sin(a)  # direction de la traînée
    px, py = x - hx * w, y - hy * h
    t = px * ux + py * uy            # position le long de la traînée
    d = np.abs(-px * uy + py * ux)   # distance à l'axe
    along = np.clip(1 - t / (length * w), 0, 1) * (t > -2)
    width = 0.6 + 1.6 * along
    tail = np.exp(-(d / width) ** 2) * along ** 1.6
    head = np.exp(-((px ** 2 + py ** 2) / 10.0))
    glow = np.exp(-((px ** 2 + py ** 2) / 120.0)) * 0.35
    alpha = np.clip(tail + head + glow, 0, 1)
    return rgba(hexc("#f4f7ff"), alpha)


TONES = {
    "_warm": (1.0, 0.80, 0.62),
    "_night": (0.36, 0.40, 0.50),
    "_grey": (0.74, 0.77, 0.81),
    "_dark": (0.42, 0.43, 0.48),
}


def tone(im, mult):
    a = np.asarray(im, np.float32)
    a[..., :3] *= np.asarray(mult, np.float32)
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")


def main():
    os.makedirs(OUT, exist_ok=True)
    only = set(sys.argv[2:]) or {"fx"}
    for name, (seed, p) in SCENES.items():
        if name not in only:
            continue
        save(landscape(seed, p), name)
        print("✓", name)
    if "fx" not in only:
        return
    # pluie : deux couches (loin / près) tuilables verticalement
    # textures à l'échelle d'un widget 2 × 1 (~500 × 220 px) pour que gouttes et flocons restent visibles
    save_rgba(rain_texture(1, 600, 300, 420, 16, 1.5, 0.6, 0.14), "fx_rain_far")
    save_rgba(rain_texture(2, 600, 300, 110, 30, 2.4, 0.9, 0.16), "fx_rain_near")
    save_rgba(snow_texture(3, 600, 300, 260, 1.0, 1.9, 0.4), "fx_snow_far")
    save_rgba(snow_texture(4, 600, 300, 60, 2.4, 4.0, 0.9), "fx_snow_near")
    save_rgba(shooting_star(1000, 500, 0.62, 0.20, 0.16, 28), "fx_meteor_a")
    save_rgba(shooting_star(1000, 500, 0.40, 0.12, 0.12, 32), "fx_meteor_b")
    # nuages et brume : une version par teinte (doré, nuit, gris, sombre) pour garder le relief
    for name, im in (
        ("fx_clouds_far", cloud_texture(5, 1200, 600, 0.55, 0.35, hexc("#ffffff"), 0.0, 0.62, 0.75)),
        ("fx_clouds_near", cloud_texture(6, 1200, 600, 0.5, 0.3, hexc("#ffffff"), 0.02, 0.5, 0.9)),
        ("fx_cloud_single", single_clouds(8, 1000, 500)),
        ("fx_fog", fog_texture(7, 1200, 600)),
    ):
        save_rgba(im, name)
        for suffix, mult in TONES.items():
            save_rgba(tone(im, mult), name + suffix)
    # éclair : lueur blanche diffuse venant du ciel
    y = np.linspace(0, 1, 300)[:, None] * np.ones((1, 600))
    x = np.linspace(0, 1, 600)[None, :]
    a = np.exp(-((x - 0.35) / 0.4) ** 2) * np.clip(1 - y * 1.3, 0, 1) * 0.75
    save_rgba(rgba(hexc("#e6e4ff"), a), "fx_lightning")
    print("✓ textures")


if __name__ == "__main__":
    main()
