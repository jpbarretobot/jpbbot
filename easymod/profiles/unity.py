"""Jeux Unity : mods BepInEx (Valheim, Lethal Company, Risk of Rain 2...) et paquets Thunderstore."""

import re
from pathlib import Path

from .. import jsonutil
from ..models import AUTO, Dependency, InstallPlan
from .base import Profile, github_dependency, ifind, installed, ipath, manual

BEPINEX_DIRS = {"bepinex", "plugins", "patchers", "config", "core", "monomod"}
THUNDERSTORE_META = {"manifest.json", "icon.png", "readme.md", "changelog.md", "license", "license.md"}


def thunderstore_manifest(pkg, prefix: str = "") -> dict | None:
    path = pkg.path(f"{prefix}/manifest.json" if prefix else "manifest.json")
    data = jsonutil.load_file(path) if path.exists() else None
    if isinstance(data, dict) and "version_number" in data and "uniqueid" not in {k.lower() for k in data}:
        return data
    return None


def bepinex_installed(game_path: Path) -> bool:
    core = ipath(game_path, "BepInEx/core")
    return core.is_dir() and any(core.glob("BepInEx*.dll"))


def is_pack(dep: str) -> bool:
    return "bepinexpack" in dep.lower()


class UnityProfile(Profile):
    key = "unity"
    label = "Jeu Unity (BepInEx)"

    def owns_game(self, path: Path) -> bool:
        return ifind(path, "UnityPlayer.dll").is_file()

    def detect(self, pkg) -> int:
        base = pkg.wrapper_root(stop=BEPINEX_DIRS)
        score = 0
        if thunderstore_manifest(pkg, base):
            score += 55
        dirs = {d.lower() for f in pkg.files for d in f.split("/")[:-1]}
        if "bepinex" in dirs:
            score += 50
        if pkg.with_ext(".dll"):
            score += 20
        if pkg.named("winhttp.dll") and pkg.named("doorstop_config.ini"):
            score += 30  # c'est BepInEx lui-même
        return min(score, 85)

    def _pack_root(self, pkg) -> str | None:
        for f in pkg.named("winhttp.dll"):
            return f.rpartition("/")[0]
        return None

    def plan(self, pkg, game, folder_name: str = "") -> InstallPlan:
        plan = InstallPlan(game, self.key)
        bepinex = game.path / "BepInEx"
        pack = self._pack_root(pkg)
        if pack is not None:
            plan.add_tree(pkg, pack, game.path)
            return plan
        base = pkg.wrapper_root(stop=BEPINEX_DIRS)
        manifest = thunderstore_manifest(pkg, base)
        name = folder_name or (f"{manifest.get('name')}" if manifest else pkg.name)
        name = re.sub(r'[<>:"/\\|?*]', "_", name)
        entries = {e.lower(): e for e in pkg.top_entries(base)}
        join = lambda a, b: f"{a}/{b}" if a else b
        if "bepinex" in entries:
            plan.add_tree(pkg, base, game.path)
            return plan
        used_structured = False
        for sub, target in (("plugins", "plugins"), ("patchers", "patchers"), ("monomod", "monomod")):
            if sub in entries:
                plan.add_tree(pkg, join(base, entries[sub]), bepinex / target / name, skip_meta=False)
                used_structured = True
        if "config" in entries:
            plan.add_tree(pkg, join(base, entries["config"]), bepinex / "config", skip_meta=False)
            used_structured = True
        for rel in pkg.under(base):
            top = rel.split("/", 1)[0].lower()
            if used_structured and top in BEPINEX_DIRS:
                continue
            if "/" not in rel and rel.lower() in THUNDERSTORE_META:
                continue
            plan.add(pkg.path(join(base, rel)), bepinex / "plugins" / name / rel)
        if ifind(game.path, "GameAssembly.dll").is_file():
            plan.notes.append("Ce jeu utilise IL2CPP : il lui faut BepInEx 6 (version « bleeding edge »).")
        return plan

    def dependencies(self, pkg, game, plan):
        deps = []
        base = pkg.wrapper_root(stop=BEPINEX_DIRS)
        manifest = thunderstore_manifest(pkg, base) or {}
        is_self_pack = self._pack_root(pkg) is not None
        wanted = [d for d in manifest.get("dependencies", []) if isinstance(d, str)]
        if not is_self_pack:
            if bepinex_installed(game.path):
                deps.append(installed("BepInEx", "Chargeur de mods des jeux Unity", key="bepinex"))
            elif any(is_pack(d) for d in wanted):
                pass  # le pack Thunderstore spécifique au jeu sera installé ci-dessous
            elif ifind(game.path, "GameAssembly.dll").is_file():
                deps.append(manual("BepInEx 6", "Chargeur de mods (version IL2CPP) : décompressez-le dans le "
                                   "dossier du jeu.", "https://builds.bepinex.dev/projects/bepinex_be"))
            else:
                deps.append(github_dependency("BepInEx", "Chargeur de mods des jeux Unity", "BepInEx/BepInEx",
                                              r"^BepInEx_win_x64_5.*\.zip$", key="bepinex",
                                              stop=BEPINEX_DIRS))
        for dep in wanted:
            if is_pack(dep) and (is_self_pack or bepinex_installed(game.path)):
                continue
            deps.append(self._thunderstore_dependency(dep, game))
        return deps

    def _thunderstore_dependency(self, dep: str, game) -> Dependency:
        parts = dep.split("-")
        if len(parts) < 3:
            return manual(dep, "Dépendance Thunderstore", f"https://thunderstore.io/search/?q={dep}")
        namespace, name, version = parts[0], "-".join(parts[1:-1]), parts[-1]
        folder = f"{namespace}-{name}"
        if not is_pack(dep) and ipath(game.path, f"BepInEx/plugins/{folder}").exists():
            return installed(dep, "Dépendance Thunderstore", key=f"ts:{folder.lower()}")

        def build_plan(pkg, game_):
            return self.plan(pkg, game_, folder_name=folder)

        return Dependency(
            name=f"{name} ({namespace})", reason="Dépendance Thunderstore", status=AUTO,
            url=f"https://thunderstore.io/package/{namespace}/{name}/",
            resolve=lambda: f"https://thunderstore.io/package/download/{namespace}/{name}/{version}/",
            filename=f"{folder}-{version}.zip", build_plan=build_plan, key=f"ts:{folder.lower()}",
        )


PROFILES = [UnityProfile()]
