import struct
import zipfile
from pathlib import Path

import pytest


@pytest.fixture(autouse=True)
def isolated_home(tmp_path, monkeypatch):
    monkeypatch.setenv("EASYMOD_HOME", str(tmp_path / "easymod_home"))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "localappdata"))
    monkeypatch.setattr("easymod.profiles.bethesda.local_app_data", lambda: tmp_path / "localappdata")
    monkeypatch.setattr("easymod.profiles.bg3.local_app_data", lambda: tmp_path / "localappdata")
    monkeypatch.setattr("easymod.library.find_games", lambda config: [])
    return tmp_path


def make_zip(path: Path, files: dict) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        for name, content in files.items():
            zf.writestr(name, content if isinstance(content, bytes) else content.encode())
    return path


def make_plugin(masters=(), form_version=44) -> bytes:
    subs = b"HEDR" + struct.pack("<H", 12) + b"\0" * 12
    for m in masters:
        data = m.encode() + b"\0"
        subs += b"MAST" + struct.pack("<H", len(data)) + data
        subs += b"DATA" + struct.pack("<H", 8) + b"\0" * 8
    header = b"TES4" + struct.pack("<IIIIHH", len(subs), 0, 0, 0, form_version, 0)
    return header + subs


def touch(path: Path, content: bytes = b"x") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path
