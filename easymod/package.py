"""Représentation d'un mod décompressé, avec des outils pour explorer son contenu."""

import re
import shutil
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

from .archive import unpack

# Fichiers de présentation qu'on n'a jamais besoin de copier dans un jeu.
META_FILE = re.compile(r"(readme|changelog|license|licence|credits)[^/]*$|\.(url|nfo)$", re.I)


@dataclass
class ModPackage:
    source: Path          # fichier téléchargé (archive, .jar, ...)
    root: Path            # dossier où il a été décompressé
    files: list[str] = field(default_factory=list)  # chemins relatifs, séparateur "/"

    @classmethod
    def open(cls, source: Path) -> "ModPackage":
        root = unpack(source)
        return cls.from_dir(source, root)

    @classmethod
    def from_dir(cls, source: Path, root: Path) -> "ModPackage":
        files = sorted(p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file())
        return cls(Path(source), Path(root), files)

    # --- Informations générales -------------------------------------------------
    @property
    def name(self) -> str:
        """Nom lisible du mod, déduit du nom de fichier (sans les numéros de version Nexus)."""
        stem = self.source.stem if self.source.is_file() else self.source.name
        stem = re.sub(r"-\d+(-[\d.]+)*-\d{9,}$", "", stem)  # Nexus: Nom-1234-1-0-1690000000
        return stem.replace("_", " ").strip() or "Mod"

    def path(self, rel: str) -> Path:
        return self.root / rel

    def cleanup(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)

    # --- Recherche ---------------------------------------------------------------
    def with_ext(self, *exts: str) -> list[str]:
        exts = tuple(e.lower() for e in exts)
        return [f for f in self.files if f.lower().endswith(exts)]

    def named(self, filename: str) -> list[str]:
        filename = filename.lower()
        return [f for f in self.files if f.lower().rsplit("/", 1)[-1] == filename]

    def has_dir(self, dirname: str) -> list[str]:
        """Préfixes (dossiers) dont le dernier composant vaut dirname, insensible à la casse."""
        dirname = dirname.lower().strip("/")
        found = set()
        for f in self.files:
            parts = f.split("/")[:-1]
            for i, part in enumerate(parts):
                if part.lower() == dirname:
                    found.add("/".join(parts[: i + 1]))
        return sorted(found, key=len)

    def under(self, prefix: str) -> list[str]:
        """Fichiers sous un dossier (préfixe), renvoyés relativement à ce dossier."""
        if not prefix:
            return list(self.files)
        prefix = prefix.rstrip("/") + "/"
        return [f[len(prefix):] for f in self.files if f.startswith(prefix)]

    def wrapper_root(self, stop=()) -> str:
        """Descend dans les dossiers « emballages » (un seul dossier, aucun fichier),
        sans entrer dans un dossier dont le nom (en minuscules) figure dans stop."""
        stop = {s.lower() for s in stop}
        prefix = ""
        while True:
            entries = {f.split("/", 1)[0] + ("/" if "/" in f else "") for f in self.under(prefix)}
            dirs = [e for e in entries if e.endswith("/")]
            loose = [e for e in entries if not e.endswith("/") and not META_FILE.search(e)]
            if len(dirs) == 1 and not loose and dirs[0].rstrip("/").lower() not in stop:
                prefix = (prefix + "/" if prefix else "") + dirs[0].rstrip("/")
            else:
                return prefix

    def top_entries(self, prefix: str = "") -> set[str]:
        return {f.split("/", 1)[0] for f in self.under(prefix)}


def read_from_jar(jar: Path, member: str) -> bytes | None:
    try:
        with zipfile.ZipFile(jar) as zf:
            for name in zf.namelist():
                if name.lower() == member.lower():
                    return zf.read(name)
    except (zipfile.BadZipFile, OSError):
        return None
    return None
