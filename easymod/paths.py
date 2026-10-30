"""Emplacements système (dossier de l'application, Téléchargements, AppData...)."""

import os
import sys
from pathlib import Path

from . import APP_NAME

IS_WINDOWS = sys.platform.startswith("win")
IS_MAC = sys.platform == "darwin"


def app_data_dir() -> Path:
    """Dossier où EasyMod range sa configuration, ses sauvegardes et son cache."""
    override = os.environ.get("EASYMOD_HOME")
    if override:
        base = Path(override)
    elif IS_WINDOWS:
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming")) / APP_NAME
    elif IS_MAC:
        base = Path.home() / "Library" / "Application Support" / APP_NAME
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / APP_NAME.lower()
    base.mkdir(parents=True, exist_ok=True)
    return base


def downloads_dir() -> Path:
    """Dossier Téléchargements de l'utilisateur (gère un dossier déplacé sous Windows)."""
    if IS_WINDOWS:
        try:
            import winreg

            key = winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Explorer\Shell Folders",
            )
            value, _ = winreg.QueryValueEx(key, "{374DE290-123F-4565-9164-39C4925E467B}")
            return Path(value)
        except OSError:
            pass
    for name in ("Downloads", "Téléchargements"):
        candidate = Path.home() / name
        if candidate.is_dir():
            return candidate
    return Path.home() / "Downloads"


def local_app_data() -> Path:
    if IS_WINDOWS:
        return Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    return Path.home() / ".local" / "share"


def roaming_app_data() -> Path:
    if IS_WINDOWS:
        return Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
    return Path.home()


def cache_dir() -> Path:
    path = app_data_dir() / "cache"
    path.mkdir(parents=True, exist_ok=True)
    return path
