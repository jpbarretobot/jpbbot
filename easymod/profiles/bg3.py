"""Baldur's Gate 3 (.pak dans le dossier Mods de l'utilisateur)."""

from pathlib import Path

from ..models import InstallPlan
from ..paths import local_app_data
from .base import Profile, ipath, manual

BG3MM = "https://github.com/LaughingLeader/BG3ModManager/releases"


class BG3Profile(Profile):
    key = "bg3"
    label = "Baldur's Gate 3"

    def owns_game(self, path: Path) -> bool:
        return ipath(path, "bin/bg3.exe").is_file() or ipath(path, "bin/bg3_dx11.exe").is_file()

    def detect(self, pkg) -> int:
        score = 0
        if pkg.with_ext(".pak"):
            score += 30
            if pkg.named("info.json"):
                score += 55
        if pkg.named("ScriptExtenderSettings.json") or pkg.named("DWrite.dll"):
            score += 40
        return score

    def plan(self, pkg, game) -> InstallPlan:
        plan = InstallPlan(game, self.key)
        mods = local_app_data() / "Larian Studios" / "Baldur's Gate 3" / "Mods"
        for rel in pkg.with_ext(".pak"):
            plan.add(pkg.path(rel), mods / rel.rsplit("/", 1)[-1])
        for rel in pkg.named("DWrite.dll"):
            plan.add(pkg.path(rel), game.path / "bin" / "DWrite.dll")
        plan.notes.append("Ouvrez BG3 Mod Manager et glissez le mod dans la liste des mods actifs, puis "
                          "« Save Load Order ».")
        return plan

    def dependencies(self, pkg, game, plan):
        return [manual("BG3 Mod Manager", "Recommandé pour activer et ordonner les mods", BG3MM, key="bg3mm")]


PROFILES = [BG3Profile()]
