"""Lecture simplifiée des installateurs FOMOD : on applique les choix par défaut/recommandés."""

import xml.etree.ElementTree as ET

def _read_xml(data: bytes) -> ET.Element:
    for encoding in ("utf-8-sig", "utf-16"):
        try:
            text = data.decode(encoding)
            if "<" in text:
                text = text[text.index("<"):]
                if text.startswith("<?xml"):
                    text = text[text.index("?>") + 2:]
                return ET.fromstring(text)
        except (UnicodeDecodeError, ET.ParseError, ValueError):
            continue
    raise ValueError("ModuleConfig.xml illisible")


def _strip_ns(root: ET.Element) -> None:
    for el in root.iter():
        if isinstance(el.tag, str) and "}" in el.tag:
            el.tag = el.tag.split("}", 1)[1]


def _plugin_type(plugin: ET.Element) -> str:
    desc = plugin.find("typeDescriptor")
    if desc is None:
        return "Optional"
    simple = desc.find("type")
    if simple is not None:
        return simple.get("name", "Optional")
    default = desc.find("dependencyType/defaultType")
    return default.get("name", "Optional") if default is not None else "Optional"


def _entries(container: ET.Element | None) -> list[tuple[str, str, bool]]:
    if container is None:
        return []
    out = []
    for el in container:
        if el.tag in ("file", "folder"):
            source = (el.get("source") or "").replace("\\", "/").strip("/")
            dest = el.get("destination")
            if dest is None:
                dest = source if el.tag == "file" else ""
            out.append((source, dest.replace("\\", "/").strip("/"), el.tag == "folder"))
    return out


def default_selection(xml_bytes: bytes) -> tuple[list[tuple[str, str, bool]], list[str]]:
    """Renvoie (liste de (source, destination, est_dossier), notes)."""
    root = _read_xml(xml_bytes)
    _strip_ns(root)
    entries = _entries(root.find("requiredInstallFiles"))
    chosen_names = []
    for group in root.iter("group"):
        gtype = group.get("type", "SelectAny")
        plugins = list(group.iter("plugin"))
        types = [_plugin_type(p) for p in plugins]
        picked = []
        for plugin, ptype in zip(plugins, types):
            if gtype == "SelectAll" or ptype in ("Required", "Recommended"):
                picked.append(plugin)
        if gtype == "SelectExactlyOne":
            picked = picked[:1]
        if not picked and gtype in ("SelectExactlyOne", "SelectAtLeastOne"):
            usable = [p for p, t in zip(plugins, types) if t != "NotUsable"]
            picked = usable[:1]
        for plugin in picked:
            chosen_names.append(plugin.get("name", "?"))
            entries += _entries(plugin.find("files"))
    notes = []
    if chosen_names:
        notes.append("Installateur FOMOD : options choisies automatiquement → " + ", ".join(chosen_names))
    if root.find("conditionalFileInstalls") is not None:
        notes.append("Certaines options conditionnelles du FOMOD ont été ignorées.")
    return entries, notes


def expand(pkg, base: str, entries) -> list[tuple[str, str]]:
    """Transforme les entrées FOMOD en paires (fichier du mod, destination relative à Data)."""
    lower_map = {f.lower(): f for f in pkg.files}
    pairs = []
    for source, dest, is_folder in entries:
        src_full = "/".join(p for p in (base, source) if p).lower()
        if is_folder:
            prefix = src_full + "/" if src_full else ""
            for low, real in lower_map.items():
                if low.startswith(prefix):
                    rel = real[len(prefix):]
                    if not rel.lower().startswith("fomod/"):
                        pairs.append((real, f"{dest}/{rel}" if dest else rel))
        elif src_full in lower_map:
            pairs.append((lower_map[src_full], dest or source))
    return pairs
