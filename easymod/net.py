"""Accès réseau minimal (sans dépendance externe) : API JSON et téléchargements."""

import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

from . import APP_NAME, __version__

USER_AGENT = f"{APP_NAME}/{__version__} (gestionnaire de mods; github.com/jpbarretobot/jpbbot)"
TIMEOUT = 30


class NetworkError(Exception):
    pass


def _request(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
    try:
        return urllib.request.urlopen(req, timeout=TIMEOUT)
    except Exception as exc:
        raise NetworkError(f"Impossible de joindre {urllib.parse.urlparse(url).netloc} : {exc}") from exc


def get_json(url: str, params: dict | None = None):
    if params:
        url += "?" + urllib.parse.urlencode(params)
    with _request(url) as resp:
        return json.loads(resp.read().decode("utf-8"))


def download(url: str, dest_dir: Path, filename: str = "", progress=None) -> Path:
    dest_dir = Path(dest_dir)
    dest_dir.mkdir(parents=True, exist_ok=True)
    with _request(url) as resp:
        if not filename:
            disposition = resp.headers.get("Content-Disposition", "")
            match = re.search(r'filename\*?=(?:UTF-8\'\')?"?([^";]+)', disposition)
            filename = urllib.parse.unquote(match.group(1)) if match else Path(urllib.parse.urlparse(resp.url).path).name
        filename = re.sub(r'[<>:"/\\|?*]', "_", filename) or "telechargement.zip"
        total = int(resp.headers.get("Content-Length") or 0)
        target = dest_dir / filename
        done = 0
        with open(target, "wb") as out:
            while chunk := resp.read(256 * 1024):
                out.write(chunk)
                done += len(chunk)
                if progress:
                    progress(done, total)
    return target


def github_latest_asset(repo: str, pattern: str) -> str:
    """URL du premier fichier de la dernière version GitHub dont le nom correspond au motif."""
    release = get_json(f"https://api.github.com/repos/{repo}/releases/latest")
    regex = re.compile(pattern, re.I)
    for asset in release.get("assets", []):
        if regex.search(asset["name"]):
            return asset["browser_download_url"]
    raise NetworkError(f"Aucun fichier « {pattern} » dans la dernière version de {repo}")
