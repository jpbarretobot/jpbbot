"""Surveillance du dossier Téléchargements : prévient quand un nouveau mod arrive."""

import threading
from pathlib import Path

from .archive import SUPPORTED_EXTENSIONS

PARTIAL = (".crdownload", ".part", ".tmp", ".download", ".opdownload")


class DownloadWatcher:
    def __init__(self, folder: Path, on_new_file, interval: float = 2.0):
        self.folder = Path(folder)
        self.on_new_file = on_new_file
        self.interval = interval
        self._stop = threading.Event()
        self._thread = None
        self._known: set[Path] = set()
        self._pending: dict[Path, int] = {}

    def _scan(self) -> dict[Path, int]:
        result = {}
        try:
            for path in self.folder.iterdir():
                if path.is_file() and path.suffix.lower() in SUPPORTED_EXTENSIONS and not path.name.endswith(PARTIAL):
                    result[path] = path.stat().st_size
        except OSError:
            pass
        return result

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._known = set(self._scan())  # les fichiers déjà présents ne sont pas proposés
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    @property
    def running(self) -> bool:
        return bool(self._thread and self._thread.is_alive() and not self._stop.is_set())

    def _run(self) -> None:
        while not self._stop.wait(self.interval):
            current = self._scan()
            for path, size in current.items():
                if path in self._known:
                    continue
                # On attend que la taille ne bouge plus (téléchargement terminé)
                if self._pending.get(path) == size and size > 0:
                    self._known.add(path)
                    self._pending.pop(path, None)
                    self.on_new_file(path)
                else:
                    self._pending[path] = size
            self._known &= set(current)
