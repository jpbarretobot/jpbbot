"""Lecture tolérante de JSON (les manifestes de mods contiennent souvent des commentaires)."""

import json
import re
from pathlib import Path

_COMMENTS = re.compile(r'("(?:\\.|[^"\\])*")|//[^\n]*|/\*.*?\*/', re.S)
_TRAILING_COMMAS = re.compile(r'("(?:\\.|[^"\\])*")|,(\s*[}\]])', re.S)


def loads_lenient(text: str):
    text = text.lstrip("\ufeff")
    text = _COMMENTS.sub(lambda m: m.group(1) or "", text)
    text = _TRAILING_COMMAS.sub(lambda m: m.group(1) or m.group(2), text)
    return json.loads(text)


def load_file(path: Path, default=None):
    try:
        return loads_lenient(Path(path).read_text(encoding="utf-8", errors="replace"))
    except (OSError, ValueError):
        return default


def save_file(path: Path, data) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)
