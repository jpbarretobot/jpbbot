"""Extraction des archives de mods (.zip, .7z, .rar) en toute sécurité."""

import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

from .paths import IS_WINDOWS, cache_dir

ARCHIVE_EXTENSIONS = {".zip", ".7z", ".rar"}
# Fichiers de mod qui se téléchargent tels quels, sans archive autour.
LOOSE_MOD_EXTENSIONS = {".jar", ".pak", ".esp", ".esm", ".esl", ".archive", ".dll", ".mcpack", ".ba2", ".bsa"}
SUPPORTED_EXTENSIONS = ARCHIVE_EXTENSIONS | LOOSE_MOD_EXTENSIONS


class ArchiveError(Exception):
    pass


def _safe_target(root: Path, member: str) -> Path:
    """Refuse les chemins qui sortiraient du dossier d'extraction (zip-slip)."""
    target = (root / member).resolve()
    if root.resolve() not in target.parents and target != root.resolve():
        raise ArchiveError(f"Chemin dangereux dans l'archive : {member}")
    return target


def _find_7z() -> str | None:
    found = shutil.which("7z") or shutil.which("7za") or shutil.which("7zz")
    if found:
        return found
    if IS_WINDOWS:
        for candidate in (r"C:\Program Files\7-Zip\7z.exe", r"C:\Program Files (x86)\7-Zip\7z.exe"):
            if Path(candidate).exists():
                return candidate
    return None


def _extract_zip(src: Path, dest: Path) -> None:
    with zipfile.ZipFile(src) as zf:
        for info in zf.infolist():
            name = info.filename.replace("\\", "/")
            target = _safe_target(dest, name)
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as fsrc, open(target, "wb") as fdst:
                shutil.copyfileobj(fsrc, fdst)


def _extract_with_7z_exe(src: Path, dest: Path) -> bool:
    exe = _find_7z()
    if not exe:
        return False
    result = subprocess.run(
        [exe, "x", "-y", f"-o{dest}", str(src)],
        capture_output=True,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    if result.returncode != 0:
        raise ArchiveError(result.stderr.decode(errors="replace") or "Échec de 7-Zip")
    for path in dest.rglob("*"):
        _safe_target(dest, str(path.relative_to(dest)))
    return True


def _extract_7z(src: Path, dest: Path) -> None:
    try:
        import py7zr
    except ImportError:
        py7zr = None
    if py7zr is not None:
        with py7zr.SevenZipFile(src) as archive:
            for name in archive.getnames():
                _safe_target(dest, name)
            archive.extractall(dest)
        return
    if not _extract_with_7z_exe(src, dest):
        raise ArchiveError("Pour ouvrir les fichiers .7z, installez 7-Zip (https://www.7-zip.org) "
                           "ou le module Python « py7zr ».")


def _extract_rar(src: Path, dest: Path) -> None:
    if _extract_with_7z_exe(src, dest):
        return
    try:
        import rarfile

        with rarfile.RarFile(src) as archive:
            for name in archive.namelist():
                _safe_target(dest, name)
            archive.extractall(dest)
        return
    except ImportError:
        pass
    except Exception as exc:  # rarfile lève ses propres exceptions
        raise ArchiveError(str(exc)) from exc
    raise ArchiveError("Pour ouvrir les fichiers .rar, installez 7-Zip (https://www.7-zip.org).")


def unpack(src: Path) -> Path:
    """Décompresse (ou copie) un mod téléchargé dans un dossier temporaire et renvoie ce dossier."""
    src = Path(src)
    if not src.exists():
        raise ArchiveError(f"Fichier introuvable : {src}")
    work = Path(tempfile.mkdtemp(prefix="unpack_", dir=cache_dir()))
    ext = src.suffix.lower()
    try:
        if src.is_dir():
            shutil.copytree(src, work / src.name)
        elif ext == ".zip" or (ext not in LOOSE_MOD_EXTENSIONS and zipfile.is_zipfile(src)):
            _extract_zip(src, work)
        elif ext == ".7z":
            _extract_7z(src, work)
        elif ext == ".rar":
            _extract_rar(src, work)
        elif ext in LOOSE_MOD_EXTENSIONS:
            shutil.copy2(src, work / src.name)
        else:
            raise ArchiveError(f"Format de fichier non pris en charge : {src.name}")
    except zipfile.BadZipFile as exc:
        shutil.rmtree(work, ignore_errors=True)
        raise ArchiveError(f"Archive corrompue ou incomplète : {src.name}") from exc
    except Exception:
        shutil.rmtree(work, ignore_errors=True)
        raise
    return work
