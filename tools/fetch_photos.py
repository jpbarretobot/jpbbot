"""Recherche et téléchargement de vraies photos de paysage sous licence libre.

  python3 tools/fetch_photos.py candidates   -> photos_review/<scene>.jpg (planches numérotées) + candidates.json
  python3 tools/fetch_photos.py final        -> applique tools/photo_selection.json dans les ressources Android

Source : Wikimedia Commons (images remarquables et images de qualité, licences libres).
"""
import io
import json
import os
import sys
import time
import urllib.parse
import urllib.request

from PIL import Image, ImageDraw, ImageFont, ImageOps

UA = "MeteoPaysageWidget/1.0 (https://github.com/jpbarretobot/jpbbot)"
REVIEW = "photos_review"
RES = "app/src/main/res/drawable-nodpi"

QUERIES = {
    "scene_sunset": ["sunset lake forest", "sunset lake pine trees", "golden hour lake", "sunset misty lake"],
    "scene_sunrise": ["sunrise lake mist", "sunrise lake forest", "dawn lake fog"],
    "scene_clear_day": ["lake forest blue sky", "mountain lake summer", "lake reflection pine"],
    "scene_clear_night": ["starry sky lake", "milky way lake", "moon night lake"],
    "scene_cloudy_day": ["overcast lake", "cloudy sky lake forest", "clouds lake mountains"],
    "scene_cloudy_night": ["moonlight clouds night", "night clouds lake", "cloudy night landscape"],
    "scene_rain_day": ["rain lake", "rainy forest", "rain landscape"],
    "scene_rain_night": ["rain night", "rainy night lake", "night rain forest"],
    "scene_storm": ["thunderstorm landscape", "storm clouds lake", "lightning storm"],
    "scene_snow_day": ["snowy forest", "winter lake snow", "snow pine forest"],
    "scene_snow_night": ["winter night snow", "snow night forest", "snowy night"],
    "scene_fog_day": ["foggy forest", "misty lake forest", "fog lake"],
    "scene_fog_night": ["fog night forest", "misty night", "foggy night lake"],
}


def get(url, binary=False, tries=2):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=20) as r:
                data = r.read()
            return data if binary else json.loads(data)
        except Exception as e:  # noqa: BLE001
            print("  ! ", url[:120], e)
            time.sleep(2 * (i + 1))
    return None


def commons(query, category):
    q = f'{query} incategory:"{category}"'
    url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode({
        "action": "query", "format": "json", "generator": "search", "gsrnamespace": 6,
        "gsrsearch": q, "gsrlimit": 12, "prop": "imageinfo",
        "iiprop": "url|size|extmetadata", "iiurlwidth": 1600,
    })
    data = get(url) or {}
    out = []
    for page in (data.get("query", {}).get("pages", {}) or {}).values():
        ii = (page.get("imageinfo") or [{}])[0]
        w, h = ii.get("width", 0), ii.get("height", 0)
        if not w or w / max(h, 1) < 1.25:
            continue
        meta = ii.get("extmetadata", {})
        thumb = ii.get("thumburl") or ii.get("url")
        out.append({
            "url": thumb,
            "preview": thumb.replace("/1600px-", "/640px-"),
            "title": page.get("title", ""),
            "author": _strip(meta.get("Artist", {}).get("value", "")),
            "license": meta.get("LicenseShortName", {}).get("value", ""),
            "source": ii.get("descriptionurl", ""),
        })
    return out


def _strip(html):
    import re
    return re.sub(r"<[^>]+>", "", html).strip()


def load(url):
    data = get(url, binary=True)
    if not data:
        return None
    try:
        im = Image.open(io.BytesIO(data))
        im = ImageOps.exif_transpose(im).convert("RGB")
        return im
    except Exception as e:  # noqa: BLE001
        print("  ! image", e)
        return None


def candidates():
    os.makedirs(REVIEW, exist_ok=True)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 28)
    except OSError:
        font = ImageFont.load_default()
    all_c = {}
    for scene, queries in QUERIES.items():
        found, seen = [], set()
        for q in queries:
            for c in commons(q, "Featured pictures on Wikimedia Commons") + commons(q, "Quality images"):
                if c["url"] and c["url"] not in seen:
                    seen.add(c["url"])
                    found.append(c)
        found = found[:32]
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(8) as ex:
            imgs = list(ex.map(lambda c: load(c.get("preview") or c["url"]), found))
        tiles = []
        kept = []
        for c, im in zip(found, imgs):
            if im is None or im.width < 300:
                continue
            kept.append(c)
            t = ImageOps.fit(im, (400, 200))
            d = ImageDraw.Draw(t)
            d.rectangle([0, 0, 52, 36], fill=(0, 0, 0))
            d.text((6, 2), str(len(kept) - 1), fill=(255, 255, 0), font=font)
            tiles.append(t)
            if len(kept) >= 28:
                break
        all_c[scene] = kept
        cols = 4
        rows = max(1, (len(tiles) + cols - 1) // cols)
        sheet = Image.new("RGB", (400 * cols, 200 * rows))
        for i, t in enumerate(tiles):
            sheet.paste(t, ((i % cols) * 400, (i // cols) * 200))
        sheet.save(os.path.join(REVIEW, scene + ".jpg"), quality=80)
        print(scene, len(kept), flush=True)
    with open(os.path.join(REVIEW, "candidates.json"), "w") as f:
        json.dump(all_c, f, ensure_ascii=False, indent=1)


def final():
    with open("tools/photo_selection.json") as f:
        sel = json.load(f)
    credits = []
    for scene, c in sel.items():
        im = load(c["url"])
        if im is None:
            sys.exit(f"échec du téléchargement : {scene}")
        # recadrage 2:1 ; "focus" = position verticale du cadrage (0 haut .. 1 bas)
        im = ImageOps.fit(im, (1400, 700), Image.LANCZOS, centering=(c.get("focus_x", 0.5), c.get("focus", 0.5)))
        im.save(os.path.join(RES, scene + ".webp"), "WEBP", quality=86, method=6)
        credits.append(f'{c["title"].replace("File:", "")} — {c["author"]} — {c["license"]} — {c["source"]}')
        print("✓", scene)
    os.makedirs("app/src/main/res/raw", exist_ok=True)
    with open("app/src/main/res/raw/photo_credits.txt", "w") as f:
        f.write("\n".join(credits) + "\n")


if __name__ == "__main__":
    {"candidates": candidates, "final": final}[sys.argv[1]]()
