"""Jeux Unreal Engine : mods .pak (Palworld, Hogwarts Legacy, Stray...) et scripts UE4SS."""

from pathlib import Path

from ..models import InstallPlan
from .base import Profile, find_dirs, github_dependency, installed

PAK_EXT = (".pak", ".utoc", ".ucas", ".sig")


def paks_dir(game_path: Path) -> Path | None:
    candidates = [d for d in find_dirs(game_path, "Content/Paks", max_depth=2)
                  if d.parent.parent.name.lower() != "engine"]
    return candidates[0] if candidates else None


class UnrealProfile(Profile):
    key = "unreal"
    label = "Jeu Unreal Engine (.pak)"

    def owns_game(self, path: Path) -> bool:
        return paks_dir(path) is not None

    def _ue4ss_mods(self, pkg) -> list[str]:
        """Dossiers de mods UE4SS (contenant Scripts/main.lua ou dlls/main.dll)."""
        found = set()
        for f in pkg.files:
            low = f.lower()
            for marker in ("/scripts/main.lua", "/dlls/main.dll"):
                if low.endswith(marker) or low == marker[1:]:
                    found.add(f[: len(f) - len(marker)])
        return sorted(found)

    def detect(self, pkg) -> int:
        score = 0
        if pkg.with_ext(".pak"):
            score += 50
        if pkg.with_ext(".utoc", ".ucas"):
            score += 15
        if self._ue4ss_mods(pkg):
            score += 45
        return score

    def plan(self, pkg, game) -> InstallPlan:
        plan = InstallPlan(game, self.key)
        paks = paks_dir(game.path) or game.path / "Content" / "Paks"
        logic = any(d.lower() == "logicmods" for f in pkg.files for d in f.split("/")[:-1])
        target = paks / ("LogicMods" if logic else "~mods")
        for rel in pkg.with_ext(*PAK_EXT):
            plan.add(pkg.path(rel), target / rel.rsplit("/", 1)[-1])
        win64 = self.win64(game)
        if win64 is not None:
            mods_root = win64 / "ue4ss" / "Mods" if (win64 / "ue4ss").is_dir() else win64 / "Mods"
            for folder in self._ue4ss_mods(pkg):
                name = folder.rsplit("/", 1)[-1] or pkg.name
                plan.add_tree(pkg, folder, mods_root / name, skip_meta=False)
                plan.notes.append(f"Activez « {name} » dans le fichier mods.txt de UE4SS si nécessaire.")
        return plan

    def win64(self, game) -> Path | None:
        paks = paks_dir(game.path)
        if paks is None:
            return None
        win64 = paks.parent.parent / "Binaries" / "Win64"
        return win64 if win64.is_dir() else None

    def dependencies(self, pkg, game, plan):
        needs_ue4ss = bool(self._ue4ss_mods(pkg)) or any(
            "logicmods" in dst.as_posix().lower() for _, dst in plan.copies)
        win64 = self.win64(game)
        if not needs_ue4ss or win64 is None:
            return []
        if (win64 / "UE4SS.dll").exists() or (win64 / "ue4ss" / "UE4SS.dll").exists():
            return [installed("UE4SS", "Chargeur de scripts Unreal", key="ue4ss")]
        return [github_dependency("UE4SS", "Chargeur de scripts Unreal requis par ce mod",
                                  "UE4SS-RE/RE-UE4SS", r"^UE4SS_v[\d.]+\.zip$", dest=lambda g: self.win64(g),
                                  key="ue4ss")]


PROFILES = [UnrealProfile()]
