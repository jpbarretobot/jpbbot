"""Configuration utilisateur persistée dans config.json."""

from pathlib import Path

from . import jsonutil
from .paths import app_data_dir, downloads_dir

DEFAULTS = {
    "downloads_dir": "",
    "watch_downloads": True,
    "auto_download_dependencies": True,
    "manual_games": [],  # [{"name":..., "path":..., "profile":...}]
}


class Config:
    def __init__(self, path: Path | None = None):
        self.path = path or app_data_dir() / "config.json"
        self.data = dict(DEFAULTS)
        self.data.update(jsonutil.load_file(self.path, {}) or {})

    def __getitem__(self, key):
        return self.data.get(key, DEFAULTS.get(key))

    def __setitem__(self, key, value):
        self.data[key] = value
        self.save()

    def save(self):
        jsonutil.save_file(self.path, self.data)

    @property
    def downloads(self) -> Path:
        return Path(self["downloads_dir"]) if self["downloads_dir"] else downloads_dir()

    def add_manual_game(self, name: str, path: str, profile: str = "") -> None:
        games = [g for g in self["manual_games"] if Path(g["path"]) != Path(path)]
        games.append({"name": name, "path": str(path), "profile": profile})
        self["manual_games"] = games

    def remove_manual_game(self, path: str) -> None:
        self["manual_games"] = [g for g in self["manual_games"] if Path(g["path"]) != Path(path)]
