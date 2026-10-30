"""Jeux Bethesda : Skyrim, Fallout 3/NV/4, Oblivion, Starfield."""

import struct
from pathlib import Path

from ..models import InstallPlan
from ..paths import local_app_data
from . import fomod
from .base import Profile, ifind, installed, manual, nexus_search

PLUGIN_EXT = (".esp", ".esm", ".esl")
DATA_DIRS = {
    "meshes", "textures", "scripts", "interface", "sound", "music", "seq", "strings", "materials",
    "shadersfx", "lodsettings", "grass", "video", "distantlod", "menus", "fonts", "mcm", "nemesis_engine",
    "platform", "geometries", "vis", "lsdata", "dialogueviews", "calientetools", "skse", "f4se", "nvse",
    "fose", "obse", "sfse", "facegen", "trees", "shaders", "programs", "netscriptframework", "terrain",
}
EXTENDERS = {"skse", "f4se", "nvse", "fose", "obse", "sfse"}


def read_plugin_header(path: Path) -> tuple[int | None, list[str]]:
    """Lit l'en-tête TES4 d'un plugin : (version de format, fichiers maîtres requis)."""
    try:
        with open(path, "rb") as f:
            data = f.read(65536)
    except OSError:
        return None, []
    if len(data) < 24 or data[:4] != b"TES4":
        return None, []
    size = struct.unpack_from("<I", data, 4)[0]
    header = 20 if data[20:24] == b"HEDR" else 24
    form_version = struct.unpack_from("<H", data, 20)[0] if header == 24 else None
    masters, pos, end = [], header, min(header + size, len(data))
    while pos + 6 <= end:
        typ = data[pos:pos + 4]
        sub = struct.unpack_from("<H", data, pos + 4)[0]
        if typ == b"MAST":
            masters.append(data[pos + 6:pos + 6 + sub].split(b"\0")[0].decode("cp1252", "replace"))
        pos += 6 + sub
    return form_version, masters


class BethesdaProfile(Profile):
    def __init__(self, key, label, exe, appids, archive, extender, loader, extender_url, appdata,
                 nexus, form_versions=(), star=True):
        self.key, self.label, self.exe, self.appids = key, label, exe, set(appids)
        self.archive, self.extender, self.loader = archive, extender, loader
        self.extender_url, self.appdata, self.nexus = extender_url, appdata, nexus
        self.form_versions, self.star = set(form_versions), star

    def owns_game(self, path: Path) -> bool:
        return ifind(path, self.exe).is_file()

    def data_dir(self, game) -> Path:
        return ifind(game.path, "Data")

    # --- Détection ----------------------------------------------------------------
    def detect(self, pkg) -> int:
        plugins = pkg.with_ext(*PLUGIN_EXT)
        dirs = {d.rsplit("/", 1)[-1].lower() for f in pkg.files for d in f.split("/")[:-1]}
        score = 0
        if plugins:
            score += 40
        if dirs & (DATA_DIRS - EXTENDERS):
            score += 25
        if pkg.has_dir("fomod"):
            score += 20
        if pkg.with_ext(self.archive):
            score += 15
        if pkg.with_ext(".ba2" if self.archive == ".bsa" else ".bsa"):
            score -= 30
        if self.extender.lower() in dirs:
            score += 30
        elif dirs & EXTENDERS:
            score -= 40
        if pkg.named(self.loader):
            score += 60
        for plugin in plugins[:3]:
            version, _ = read_plugin_header(pkg.path(plugin))
            if version is not None and self.form_versions:
                score += 15 if version in self.form_versions else -15
        if score and self.key == "skyrimse":
            score += 1  # à égalité, Skyrim SE est bien plus courant que l'ancienne édition
        return max(score, 0)

    # --- Plan d'installation --------------------------------------------------------
    def _is_data_level(self, pkg, prefix: str) -> bool:
        for entry in pkg.top_entries(prefix):
            low = entry.lower()
            if low in DATA_DIRS or low.endswith(PLUGIN_EXT + (".bsa", ".ba2", ".ini")):
                return True
        return False

    def plan(self, pkg, game) -> InstallPlan:
        plan = InstallPlan(game, self.key)
        data = self.data_dir(game)
        fomod_dirs = pkg.has_dir("fomod")
        config = [f for f in pkg.files if f.lower().endswith("fomod/moduleconfig.xml")]
        if fomod_dirs and config:
            base = config[0].rsplit("/", 2)[0] if config[0].count("/") >= 2 else ""
            try:
                entries, notes = fomod.default_selection(pkg.path(config[0]).read_bytes())
                for src, dest in fomod.expand(pkg, base, entries):
                    plan.add(pkg.path(src), data / dest)
                plan.notes += notes
            except ValueError:
                plan.notes.append("Installateur FOMOD illisible : installation de tout le contenu.")
        if not plan.copies:
            self._plan_plain(pkg, plan, data)
        plan.plugins = sorted({dst.name for _, dst in plan.copies
                               if dst.parent == data and dst.suffix.lower() in PLUGIN_EXT})
        if self.key == "starfield":
            plan.notes.append("Starfield : pensez à activer « bInvalidateOlderFiles » si les textures ne changent pas.")
        return plan

    def _plan_plain(self, pkg, plan, data):
        base = pkg.wrapper_root(stop=DATA_DIRS | {"data"})
        entries = {e.lower(): e for e in pkg.top_entries(base)}
        join = lambda *parts: "/".join(p for p in parts if p)
        if "data" in entries:
            # Le mod reproduit l'arborescence du jeu (Data/ + éventuellement des .exe/.dll)
            plan.add_tree(pkg, base, plan.game.path)
            return
        if self.loader.lower() in entries:
            plan.add_tree(pkg, base, plan.game.path)
            return
        if not self._is_data_level(pkg, base):
            # Plusieurs variantes dans des sous-dossiers : on prend la première qui ressemble à Data
            options = sorted(join(base, e) for e in pkg.top_entries(base)
                             if self._is_data_level(pkg, join(base, e)))
            if options:
                if len(options) > 1:
                    plan.notes.append(f"Plusieurs variantes trouvées ; « {options[0].rsplit('/', 1)[-1]} » "
                                      "a été choisie. Les autres : " +
                                      ", ".join(o.rsplit("/", 1)[-1] for o in options[1:]))
                base = options[0]
        plan.add_tree(pkg, base, data)

    # --- Dépendances --------------------------------------------------------------------
    def dependencies(self, pkg, game, plan):
        deps = []
        data = self.data_dir(game)
        present = {p.name.lower() for p in data.iterdir()} if data.is_dir() else set()
        present |= {dst.name.lower() for _, dst in plan.copies}
        seen = set()
        for src, dst in plan.copies:
            if dst.suffix.lower() not in PLUGIN_EXT:
                continue
            for master in read_plugin_header(src)[1]:
                if master.lower() not in present and master.lower() not in seen:
                    seen.add(master.lower())
                    deps.append(manual(master, f"Fichier maître requis par {dst.name}",
                                       nexus_search(self.nexus, master), key=f"{self.key}:{master.lower()}"))
        if any(f"/{self.extender.lower()}/" in f"/{dst.as_posix().lower()}" for _, dst in plan.copies):
            if ifind(game.path, self.loader).is_file():
                deps.append(installed(self.extender, "Script Extender requis par les plugins .dll"))
            else:
                deps.append(manual(f"{self.extender} (Script Extender)",
                                   f"Ce mod contient des plugins {self.extender}. Installez-le puis lancez le jeu "
                                   f"avec {self.loader}.", self.extender_url))
        return deps

    # --- Activation des plugins (plugins.txt) ------------------------------------------
    def plugins_file(self) -> Path:
        folder = local_app_data() / self.appdata
        return ifind(folder, "plugins.txt")

    def _edit_plugins(self, add: list[str], remove: list[str]):
        if not add and not remove:
            return
        path = self.plugins_file()
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []
        names = lambda line: line.lstrip("*").strip().lower()
        drop = {n.lower() for n in remove}
        lines = [l for l in lines if names(l) not in drop]
        existing = {names(l): i for i, l in enumerate(lines) if l and not l.startswith("#")}
        for plugin in add:
            entry = f"*{plugin}" if self.star else plugin
            if plugin.lower() in existing:
                lines[existing[plugin.lower()]] = entry
            else:
                lines.append(entry)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def after_install(self, plan):
        try:
            self._edit_plugins(plan.plugins, [])
            if plan.plugins:
                plan.notes.append("Plugins activés automatiquement : " + ", ".join(plan.plugins))
        except OSError as exc:
            plan.notes.append(f"Impossible d'activer les plugins automatiquement ({exc}).")

    def after_uninstall(self, game, record):
        try:
            self._edit_plugins([], record.get("plugins", []))
        except OSError:
            pass


PROFILES = [
    BethesdaProfile("skyrimse", "Skyrim Special Edition", "SkyrimSE.exe", {"489830"}, ".bsa", "SKSE",
                    "skse64_loader.exe", "https://skse.silverlock.org/", "Skyrim Special Edition",
                    "skyrimspecialedition", {44}),
    BethesdaProfile("skyrim", "Skyrim (édition originale)", "TESV.exe", {"72850"}, ".bsa", "SKSE",
                    "skse_loader.exe", "https://skse.silverlock.org/", "Skyrim", "skyrim", {43}, star=False),
    BethesdaProfile("fallout4", "Fallout 4", "Fallout4.exe", {"377160"}, ".ba2", "F4SE", "f4se_loader.exe",
                    "https://f4se.silverlock.org/", "Fallout4", "fallout4", {131}),
    BethesdaProfile("falloutnv", "Fallout: New Vegas", "FalloutNV.exe", {"22380"}, ".bsa", "NVSE",
                    "nvse_loader.exe", "https://github.com/xNVSE/NVSE/releases", "FalloutNV", "newvegas",
                    {15}, star=False),
    BethesdaProfile("fallout3", "Fallout 3", "Fallout3.exe", {"22300", "22370"}, ".bsa", "FOSE",
                    "fose_loader.exe", "https://fose.silverlock.org/", "Fallout3", "fallout3", {15}, star=False),
    BethesdaProfile("oblivion", "Oblivion", "Oblivion.exe", {"22330"}, ".bsa", "OBSE", "obse_loader.exe",
                    "https://github.com/llde/xOBSE/releases", "Oblivion", "oblivion", star=False),
    BethesdaProfile("starfield", "Starfield", "Starfield.exe", {"1716740"}, ".ba2", "SFSE", "sfse_loader.exe",
                    "https://www.nexusmods.com/starfield/mods/106", "Starfield", "starfield"),
]
