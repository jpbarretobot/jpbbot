"""Minecraft Java : mods (.jar Fabric/Forge/NeoForge/Quilt), packs de ressources et shaders."""

import json
import re
import tomllib
from pathlib import Path

from ..models import AUTO, Dependency, InstallPlan
from ..package import read_from_jar
from .. import net
from .base import Profile, installed, manual

IGNORED_DEPS = {"minecraft", "java", "fabricloader", "fabric-loader", "forge", "neoforge", "quilt_loader",
                "quilt_base", "mcp", "fml", "javafml"}
LOADER_INFO = {
    "fabric": ("Fabric Loader", "https://fabricmc.net/use/installer/"),
    "quilt": ("Quilt Loader", "https://quiltmc.org/install/"),
    "forge": ("Minecraft Forge", "https://files.minecraftforge.net/"),
    "neoforge": ("NeoForge", "https://neoforged.net/"),
}


def jar_metadata(jar: Path) -> dict | None:
    """Lit l'identité d'un mod Minecraft : chargeur, id, version de Minecraft, dépendances."""
    raw = read_from_jar(jar, "fabric.mod.json")
    loader = "fabric"
    if raw is None:
        raw, loader = read_from_jar(jar, "quilt.mod.json"), "quilt"
    if raw is not None:
        try:
            data = json.loads(raw.decode("utf-8-sig", "replace"), strict=False)
        except ValueError:
            return None
        if loader == "quilt":
            ql = data.get("quilt_loader", {})
            deps = {d if isinstance(d, str) else d.get("id", ""): "*" for d in ql.get("depends", [])}
            return {"loader": "quilt", "id": ql.get("id", ""), "mc": "", "depends": deps}
        depends = data.get("depends", {}) or {}
        mc = depends.get("minecraft", "")
        if isinstance(mc, list):
            mc = mc[0] if mc else ""
        return {"loader": "fabric", "id": data.get("id", ""), "mc": mc, "depends": depends}
    for member, loader in (("META-INF/neoforge.mods.toml", "neoforge"), ("META-INF/mods.toml", "forge")):
        raw = read_from_jar(jar, member)
        if raw is None:
            continue
        try:
            data = tomllib.loads(raw.decode("utf-8", "replace"))
        except tomllib.TOMLDecodeError:
            return {"loader": loader, "id": "", "mc": "", "depends": {}}
        mods = data.get("mods") or [{}]
        mod_id = mods[0].get("modId", "")
        depends, mc = {}, ""
        for dep_list in (data.get("dependencies") or {}).values():
            for dep in dep_list if isinstance(dep_list, list) else []:
                dep_id = dep.get("modId", "")
                required = dep.get("mandatory", dep.get("type", "required") in ("required", "REQUIRED"))
                if dep_id == "minecraft":
                    mc = dep.get("versionRange", "")
                elif required:
                    depends[dep_id] = dep.get("versionRange", "*")
        return {"loader": loader, "id": mod_id, "mc": mc, "depends": depends}
    if read_from_jar(jar, "mcmod.info") is not None:
        return {"loader": "forge", "id": "", "mc": "", "depends": {}}
    return None


def exact_mc_version(spec: str) -> str:
    """Extrait une version précise de Minecraft (« ~1.20.1 » → « 1.20.1 ») si possible."""
    match = re.search(r"\d+\.\d+(?:\.\d+)?", spec or "")
    if not match:
        return ""
    loose = spec.strip().startswith((">", "<", "(")) or "," in spec
    return "" if loose else match.group(0)


class MinecraftProfile(Profile):
    key = "minecraft"
    label = "Minecraft (Java)"

    def owns_game(self, path: Path) -> bool:
        return (path / "launcher_profiles.json").exists() or (path / "versions").is_dir() and (path / "assets").is_dir()

    def _jars(self, pkg):
        return [f for f in pkg.with_ext(".jar") if jar_metadata(pkg.path(f))]

    def _kind(self, pkg) -> str:
        if self._jars(pkg):
            return "mods"
        if pkg.named("pack.mcmeta"):
            if any("/data/" in f"/{f}" for f in pkg.files) and not any("/assets/" in f"/{f}" for f in pkg.files):
                return "datapack"
            return "resourcepacks"
        if pkg.has_dir("shaders") and pkg.source.suffix.lower() == ".zip":
            return "shaderpacks"
        return ""

    def detect(self, pkg) -> int:
        return {"mods": 90, "resourcepacks": 70, "shaderpacks": 60, "datapack": 50}.get(self._kind(pkg), 0)

    def plan(self, pkg, game) -> InstallPlan:
        plan = InstallPlan(game, self.key)
        kind = self._kind(pkg)
        if kind == "mods":
            for jar in self._jars(pkg):
                plan.add(pkg.path(jar), game.path / "mods" / jar.rsplit("/", 1)[-1])
            plan.notes.append("Lancez Minecraft avec le profil du chargeur de mods (Fabric, Forge...) "
                              "correspondant dans le launcher.")
        elif kind in ("resourcepacks", "shaderpacks"):
            if pkg.source.suffix.lower() == ".zip":
                plan.add(pkg.source, game.path / kind / pkg.source.name)
            else:
                base = pkg.named("pack.mcmeta")[0].rpartition("/")[0] if kind == "resourcepacks" else pkg.wrapper_root()
                plan.add_tree(pkg, base, game.path / kind / pkg.name, skip_meta=False)
            plan.notes.append("Activez le pack dans Options → Packs de ressources (ou Shaders) du jeu.")
        elif kind == "datapack":
            plan.add(pkg.source, game.path / "datapacks_a_copier" / pkg.source.name)
            plan.notes.append("Les datapacks s'installent par monde : copiez le fichier depuis le dossier "
                              "« datapacks_a_copier » vers saves/<votre monde>/datapacks.")
        return plan

    # --- Dépendances --------------------------------------------------------------------
    def _installed_ids(self, game) -> set[str]:
        ids = set()
        mods = game.path / "mods"
        if mods.is_dir():
            for jar in mods.glob("*.jar"):
                meta = jar_metadata(jar)
                if meta and meta["id"]:
                    ids.add(meta["id"].lower())
        return ids

    def _loader_installed(self, game, loader: str) -> bool:
        versions = game.path / "versions"
        names = [p.name.lower() for p in versions.iterdir()] if versions.is_dir() else []
        token = {"fabric": "fabric-loader", "quilt": "quilt-loader", "forge": "forge", "neoforge": "neoforge"}[loader]
        return any(token in n for n in names)

    def dependencies(self, pkg, game, plan):
        deps = []
        present = self._installed_ids(game)
        metas = [jar_metadata(src) for src, _ in plan.copies if src.suffix.lower() == ".jar"]
        metas = [m for m in metas if m]
        present |= {m["id"].lower() for m in metas if m["id"]}
        loaders = {m["loader"] for m in metas}
        for loader in loaders:
            name, url = LOADER_INFO[loader]
            if self._loader_installed(game, loader):
                deps.append(installed(name, "Chargeur de mods requis"))
            else:
                deps.append(manual(name, "Chargeur de mods requis : lancez son installateur une fois.", url))
        for meta in metas:
            mc = exact_mc_version(meta["mc"])
            for dep_id in meta["depends"]:
                low = dep_id.lower()
                if low in IGNORED_DEPS or low in present or not low:
                    continue
                present.add(low)
                deps.append(self._modrinth_dependency(dep_id, meta, mc, game))
        return deps

    def _modrinth_dependency(self, dep_id, meta, mc, game) -> Dependency:
        slug = {"fabric": "fabric-api", "fabric-api": "fabric-api", "quilted_fabric_api": "qsl"}.get(dep_id, dep_id)
        loader = meta["loader"]

        def resolve():
            params = {"loaders": json.dumps([loader])}
            if mc:
                params["game_versions"] = json.dumps([mc])
            versions = net.get_json(f"https://api.modrinth.com/v2/project/{slug}/version", params)
            if not versions:
                raise net.NetworkError(f"Aucune version de « {slug} » pour {loader} {mc}".strip())
            files = versions[0]["files"]
            chosen = next((f for f in files if f.get("primary")), files[0])
            return chosen["url"]

        def build_plan(pkg, game_):
            plan = InstallPlan(game_, self.key)
            for jar in pkg.with_ext(".jar"):
                plan.add(pkg.path(jar), game_.path / "mods" / jar.rsplit("/", 1)[-1])
            return plan

        return Dependency(
            name=dep_id, reason=f"Mod requis par {meta['id'] or 'ce mod'}" + (f" (Minecraft {mc})" if mc else ""),
            status=AUTO, url=f"https://modrinth.com/mod/{slug}", resolve=resolve, build_plan=build_plan,
            key=f"minecraft:{dep_id.lower()}",
        )


PROFILES = [MinecraftProfile()]
