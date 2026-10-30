"""Recherche des jeux installés (Steam, Epic Games, GOG, Minecraft, ajouts manuels)."""

import json
import os
import re
from pathlib import Path

from . import profiles
from .models import Game
from .paths import IS_MAC, IS_WINDOWS, roaming_app_data

SKIP_NAMES = re.compile(r"proton|steam linux runtime|steamworks|redistributable|soundtrack|dedicated server|sdk",
                        re.I)


def _registry_value(root_name: str, key: str, value: str) -> str | None:
    if not IS_WINDOWS:
        return None
    try:
        import winreg

        root = getattr(winreg, root_name)
        with winreg.OpenKey(root, key) as handle:
            return str(winreg.QueryValueEx(handle, value)[0])
    except OSError:
        return None


def steam_roots() -> list[Path]:
    roots = []
    reg = _registry_value("HKEY_CURRENT_USER", r"Software\Valve\Steam", "SteamPath")
    if reg:
        roots.append(Path(reg))
    if IS_WINDOWS:
        roots += [Path(r"C:\Program Files (x86)\Steam"), Path(r"C:\Program Files\Steam")]
    elif IS_MAC:
        roots.append(Path.home() / "Library" / "Application Support" / "Steam")
    else:
        roots += [Path.home() / ".steam" / "steam", Path.home() / ".local" / "share" / "Steam"]
    unique = []
    for root in roots:
        if root.is_dir() and root.resolve() not in [u.resolve() for u in unique]:
            unique.append(root)
    return unique


def _vdf_values(text: str, key: str) -> list[str]:
    return [m.replace("\\\\", "\\") for m in re.findall(rf'"{key}"\s+"([^"]*)"', text, re.I)]


def steam_games() -> list[Game]:
    games, libraries = [], []
    for root in steam_roots():
        libraries.append(root)
        vdf = root / "steamapps" / "libraryfolders.vdf"
        if vdf.exists():
            libraries += [Path(p) for p in _vdf_values(vdf.read_text(errors="replace"), "path")]
    seen = set()
    for lib in libraries:
        apps = lib / "steamapps"
        if not apps.is_dir() or apps.resolve() in seen:
            continue
        seen.add(apps.resolve())
        for manifest in apps.glob("appmanifest_*.acf"):
            text = manifest.read_text(errors="replace")
            appid = (_vdf_values(text, "appid") or [""])[0]
            name = (_vdf_values(text, "name") or [""])[0]
            installdir = (_vdf_values(text, "installdir") or [""])[0]
            path = apps / "common" / installdir
            if installdir and path.is_dir() and not SKIP_NAMES.search(name):
                games.append(Game(name, path, profiles.profile_for_game(path, appid), "Steam", appid))
    return games


def epic_games() -> list[Game]:
    folder = Path(os.environ.get("PROGRAMDATA", r"C:\ProgramData")) / "Epic" / "EpicGamesLauncher" / "Data" / "Manifests"
    games = []
    if folder.is_dir():
        for item in folder.glob("*.item"):
            try:
                data = json.loads(item.read_text(encoding="utf-8", errors="replace"))
            except ValueError:
                continue
            path = Path(data.get("InstallLocation", ""))
            name = data.get("DisplayName", path.name)
            if path.is_dir() and not SKIP_NAMES.search(name):
                games.append(Game(name, path, profiles.profile_for_game(path), "Epic Games"))
    return games


def gog_games() -> list[Game]:
    if not IS_WINDOWS:
        return []
    games = []
    try:
        import winreg

        base = r"SOFTWARE\WOW6432Node\GOG.com\Games"
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, base) as root:
            for i in range(winreg.QueryInfoKey(root)[0]):
                sub = winreg.EnumKey(root, i)
                path = _registry_value("HKEY_LOCAL_MACHINE", f"{base}\\{sub}", "path")
                name = _registry_value("HKEY_LOCAL_MACHINE", f"{base}\\{sub}", "gameName") or sub
                if path and Path(path).is_dir():
                    games.append(Game(name, Path(path), profiles.profile_for_game(Path(path)), "GOG"))
    except OSError:
        pass
    return games


def minecraft_games() -> list[Game]:
    if IS_MAC:
        path = Path.home() / "Library" / "Application Support" / "minecraft"
    elif IS_WINDOWS:
        path = roaming_app_data() / ".minecraft"
    else:
        path = Path.home() / ".minecraft"
    return [Game("Minecraft (Java)", path, "minecraft", "Minecraft Launcher")] if path.is_dir() else []


def manual_games(config) -> list[Game]:
    games = []
    for entry in config["manual_games"]:
        path = Path(entry["path"])
        if path.is_dir():
            key = entry.get("profile") or profiles.profile_for_game(path)
            games.append(Game(entry.get("name") or path.name, path, key, "Ajouté manuellement"))
    return games


def find_games(config) -> list[Game]:
    found: dict[str, Game] = {}
    for finder in (steam_games, epic_games, gog_games, minecraft_games):
        try:
            for game in finder():
                found.setdefault(game.id, game)
        except OSError:
            continue
    for game in manual_games(config):
        found[game.id] = game  # un ajout manuel prime (l'utilisateur a pu choisir le profil)
    return sorted(found.values(), key=lambda g: g.name.lower())
