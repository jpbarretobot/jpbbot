"""Structures de données partagées : jeux, plans d'installation, dépendances."""

from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable


@dataclass
class Game:
    name: str
    path: Path
    profile: str = "generic"   # clé du profil de jeu (voir profiles/)
    source: str = "manuel"     # Steam, Epic, GOG, manuel...
    appid: str = ""

    @property
    def id(self) -> str:
        return str(self.path).lower()


@dataclass
class InstallPlan:
    game: Game
    profile_key: str
    copies: list[tuple[Path, Path]] = field(default_factory=list)  # (source, destination)
    notes: list[str] = field(default_factory=list)
    plugins: list[str] = field(default_factory=list)  # plugins Bethesda à activer

    def add(self, src: Path, dst: Path) -> None:
        self.copies.append((Path(src), Path(dst)))

    def add_tree(self, package, prefix: str, dest_root: Path, skip_meta: bool = True) -> None:
        """Copie tout le contenu d'un dossier du mod vers dest_root."""
        from .package import META_FILE

        for rel in package.under(prefix):
            if skip_meta and "/" not in rel and META_FILE.search(rel):
                continue
            src = package.path(f"{prefix}/{rel}" if prefix else rel)
            self.add(src, Path(dest_root) / rel)


# États possibles d'une dépendance
INSTALLED = "installed"   # déjà présente
AUTO = "auto"             # sera téléchargée et installée automatiquement
MANUAL = "manual"         # l'utilisateur doit la télécharger (on donne le lien)


@dataclass
class Dependency:
    name: str
    reason: str
    status: str
    url: str = ""                                    # page d'information / téléchargement manuel
    resolve: Callable[[], str] | None = None         # renvoie l'URL directe du fichier à télécharger
    filename: str = ""                               # nom du fichier téléchargé
    build_plan: Callable[[object, Game], InstallPlan] | None = None  # (package, game) -> plan
    key: str = ""                                    # identifiant unique pour éviter les doublons

    @property
    def status_label(self) -> str:
        return {
            INSTALLED: "Déjà installé",
            AUTO: "Sera téléchargé automatiquement",
            MANUAL: "À télécharger vous-même",
        }.get(self.status, self.status)
