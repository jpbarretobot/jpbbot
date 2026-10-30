"""Stardew Valley (mods SMAPI et packs de contenu)."""

from pathlib import Path

from .. import jsonutil
from ..models import InstallPlan
from .base import Profile, ifind, installed, manual, nexus_search

SMAPI_URL = "https://smapi.io/"


def _manifest(path: Path) -> dict:
    data = jsonutil.load_file(path, {})
    return {k.lower(): v for k, v in data.items()} if isinstance(data, dict) else {}


class StardewProfile(Profile):
    key = "stardew"
    label = "Stardew Valley"

    def owns_game(self, path: Path) -> bool:
        return any(ifind(path, n).exists() for n in ("Stardew Valley.exe", "Stardew Valley.dll", "StardewValley"))

    def _mod_folders(self, pkg) -> list[str]:
        """Dossiers du mod contenant un manifest.json SMAPI (avec UniqueID)."""
        folders = []
        for manifest in pkg.named("manifest.json"):
            if "uniqueid" in _manifest(pkg.path(manifest)):
                folders.append(manifest.rpartition("/")[0])
        # on ne garde que les dossiers les plus hauts (un mod peut contenir des sous-packs)
        return [f for f in folders if not any(f != o and f.startswith(o + "/") for o in folders if o)]

    def detect(self, pkg) -> int:
        return 90 if self._mod_folders(pkg) else 0

    def plan(self, pkg, game) -> InstallPlan:
        plan = InstallPlan(game, self.key)
        mods = ifind(game.path, "Mods")
        for folder in self._mod_folders(pkg):
            name = folder.rsplit("/", 1)[-1] if folder else pkg.name
            plan.add_tree(pkg, folder, mods / name, skip_meta=False)
        plan.notes.append("Lancez le jeu via SMAPI (StardewModdingAPI) pour que les mods soient chargés.")
        return plan

    def _installed_ids(self, game) -> set[str]:
        ids = set()
        mods = ifind(game.path, "Mods")
        if mods.is_dir():
            for manifest in mods.rglob("manifest.json"):
                uid = _manifest(manifest).get("uniqueid")
                if uid:
                    ids.add(str(uid).lower())
        return ids

    def dependencies(self, pkg, game, plan):
        deps = []
        if any(ifind(game.path, n).exists() for n in ("StardewModdingAPI.exe", "StardewModdingAPI",
                                                     "StardewModdingAPI.dll")):
            deps.append(installed("SMAPI", "Chargeur de mods de Stardew Valley"))
        else:
            deps.append(manual("SMAPI", "Chargeur de mods indispensable : téléchargez-le et lancez son "
                               "installateur.", SMAPI_URL))
        present = self._installed_ids(game) | {"smapi"}
        manifests = [_manifest(src) for src, dst in plan.copies if dst.name.lower() == "manifest.json"]
        present |= {str(m.get("uniqueid", "")).lower() for m in manifests}
        for m in manifests:
            wanted = []
            content_for = m.get("contentpackfor")
            if isinstance(content_for, dict):
                uid = next((v for k, v in content_for.items() if k.lower() == "uniqueid"), "")
                wanted.append(uid)
            for dep in m.get("dependencies") or []:
                if isinstance(dep, dict):
                    low = {k.lower(): v for k, v in dep.items()}
                    if low.get("isrequired", True) is not False:
                        wanted.append(low.get("uniqueid", ""))
            for uid in wanted:
                if uid and uid.lower() not in present:
                    present.add(uid.lower())
                    deps.append(manual(uid, f"Requis par {m.get('name', 'ce mod')}",
                                       nexus_search("stardewvalley", uid), key=f"stardew:{uid.lower()}"))
        return deps


PROFILES = [StardewProfile()]
