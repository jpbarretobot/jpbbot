"""Profil universel : le mod reproduit l'arborescence du dossier du jeu."""

from pathlib import Path

from ..models import InstallPlan
from .base import Profile


def _game_entries(game_path: Path) -> set[str]:
    try:
        return {c.name.lower() for c in game_path.iterdir()}
    except OSError:
        return set()


class GenericProfile(Profile):
    key = "generic"
    label = "Installation générique (copie dans le dossier du jeu)"

    def _match(self, pkg, game_path: Path) -> tuple[str, Path, float]:
        """Cherche à quel endroit du jeu le contenu du mod « s'emboîte » le mieux."""
        best = ("", game_path, 0.0)
        names = _game_entries(game_path)
        base = pkg.wrapper_root(stop=names)
        entries = {e.lower() for e in pkg.top_entries(base)}
        if not entries:
            return best
        candidates = [(base, game_path)]
        try:
            candidates += [(base, d) for d in game_path.iterdir() if d.is_dir()]
        except OSError:
            pass
        for prefix, target in candidates:
            present = _game_entries(target)
            ratio = len(entries & present) / len(entries)
            if ratio > best[2]:
                best = (prefix, target, ratio)
        return best

    def score_for_game(self, pkg, game) -> int:
        return int(self._match(pkg, game.path)[2] * 60)

    def plan(self, pkg, game) -> InstallPlan:
        prefix, target, ratio = self._match(pkg, game.path)
        if ratio == 0:
            prefix, target = pkg.wrapper_root(), game.path
        plan = InstallPlan(game, self.key)
        plan.add_tree(pkg, prefix, target)
        if ratio < 0.5:
            plan.notes.append("Ce mod n'a pas été clairement reconnu : il sera copié tel quel dans le dossier "
                              "du jeu. Lisez son descriptif pour vérifier l'emplacement.")
        return plan


PROFILES = [GenericProfile()]
