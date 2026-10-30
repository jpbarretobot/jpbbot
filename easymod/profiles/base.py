"""Classe de base des profils de jeux et outils communs."""

import re
from pathlib import Path

from ..models import AUTO, INSTALLED, MANUAL, Dependency, Game, InstallPlan
from .. import net


class Profile:
    """Un profil sait reconnaître les mods d'un type de jeu et où les installer."""

    key = "generic"
    label = "Jeu (installation générique)"

    def owns_game(self, path: Path) -> bool:
        """Ce dossier de jeu correspond-il à ce profil ?"""
        return False

    def detect(self, pkg) -> int:
        """Score 0..100 : à quel point ce mod ressemble à un mod pour ce type de jeu."""
        return 0

    def plan(self, pkg, game: Game) -> InstallPlan:
        raise NotImplementedError

    def dependencies(self, pkg, game: Game, plan: InstallPlan) -> list[Dependency]:
        return []

    def after_install(self, plan: InstallPlan) -> None:
        pass

    def after_uninstall(self, game: Game, record: dict) -> None:
        pass


def ifind(parent: Path, name: str) -> Path:
    """Trouve un sous-élément sans tenir compte de la casse (utile hors Windows)."""
    direct = parent / name
    if direct.exists() or not parent.is_dir():
        return direct
    lname = name.lower()
    for child in parent.iterdir():
        if child.name.lower() == lname:
            return child
    return direct


def ipath(root: Path, rel: str) -> Path:
    current = Path(root)
    for part in rel.replace("\\", "/").split("/"):
        if part:
            current = ifind(current, part)
    return current


def find_dirs(root: Path, rel: str, max_depth: int = 3) -> list[Path]:
    """Cherche les dossiers « rel » (ex. « Content/Paks ») sous root, jusqu'à max_depth niveaux."""
    results = []

    def walk(path: Path, depth: int):
        candidate = ipath(path, rel)
        if candidate.is_dir():
            results.append(candidate)
        if depth >= max_depth:
            return
        try:
            children = sorted(c for c in path.iterdir() if c.is_dir())
        except OSError:
            return
        for child in children:
            walk(child, depth + 1)

    walk(Path(root), 0)
    return results


def manual(name: str, reason: str, url: str, key: str = "") -> Dependency:
    return Dependency(name=name, reason=reason, status=MANUAL, url=url, key=key or name.lower())


def installed(name: str, reason: str, key: str = "") -> Dependency:
    return Dependency(name=name, reason=reason, status=INSTALLED, key=key or name.lower())


def github_dependency(name: str, reason: str, repo: str, pattern: str, dest=lambda game: game.path,
                      key: str = "", stop=()) -> Dependency:
    """Dépendance publiée sur GitHub, dont l'archive s'extrait telle quelle dans le jeu."""

    def build_plan(pkg, game: Game) -> InstallPlan:
        plan = InstallPlan(game, "dependency")
        plan.add_tree(pkg, pkg.wrapper_root(stop), dest(game))
        return plan

    return Dependency(
        name=name,
        reason=reason,
        status=AUTO,
        url=f"https://github.com/{repo}/releases/latest",
        resolve=lambda: net.github_latest_asset(repo, pattern),
        build_plan=build_plan,
        key=key or repo.lower(),
    )


def nexus_search(domain: str, text: str) -> str:
    text = re.sub(r"\.(esp|esm|esl)$", "", text, flags=re.I)
    return f"https://www.nexusmods.com/{domain}/search/?gsearch={net.urllib.parse.quote(text)}"
