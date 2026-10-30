"""Cœur d'EasyMod : analyser un mod, choisir le jeu, l'installer et pouvoir tout annuler."""

import re
import shutil
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from . import jsonutil, library, net, profiles
from .models import AUTO, INSTALLED, MANUAL, Dependency, Game, InstallPlan
from .package import ModPackage
from .paths import app_data_dir, cache_dir

STOP_WORDS = {"the", "edition", "special", "game", "of", "and", "definitive", "remastered", "anniversary",
              "enhanced", "goty", "year", "deluxe", "complete", "java"}


def _tokens(text: str) -> list[str]:
    return [t for t in re.split(r"[^a-z0-9]+", text.lower()) if len(t) >= 3 and t not in STOP_WORDS]


@dataclass
class Candidate:
    game: Game
    profile_key: str
    score: int


@dataclass
class Analysis:
    package: ModPackage
    candidates: list[Candidate]
    mod_type: str = ""                     # type de mod reconnu (même si le jeu n'est pas installé)
    game: Game | None = None
    plan: InstallPlan | None = None
    deps: list[Dependency] = field(default_factory=list)

    @property
    def confident(self) -> bool:
        return bool(self.candidates) and self.candidates[0].score >= 40


class ModManager:
    def __init__(self, config):
        self.config = config
        self.db_path = app_data_dir() / "installed.json"
        self.db = jsonutil.load_file(self.db_path, {"mods": []}) or {"mods": []}
        self.games: list[Game] = []
        self.extra_manual: list[Dependency] = []

    # --- Jeux -----------------------------------------------------------------------------
    def refresh_games(self) -> list[Game]:
        self.games = library.find_games(self.config)
        return self.games

    def game_by_id(self, game_id: str) -> Game | None:
        return next((g for g in self.games if g.id == game_id), None)

    # --- Analyse --------------------------------------------------------------------------
    def analyze(self, source: Path) -> Analysis:
        pkg = ModPackage.open(Path(source))
        type_scores = {p.key: p.detect(pkg) for p in profiles.ALL if p.key != "generic"}
        generic = profiles.get("generic")
        filename_tokens = set(_tokens(pkg.source.name))
        candidates = []
        for game in self.games:
            options = [("generic", generic.score_for_game(pkg, game))]
            if game.profile != "generic":
                options.append((game.profile, type_scores.get(game.profile, 0)))
            key, score = max(options, key=lambda o: o[1])
            name_tokens = _tokens(game.name)
            if name_tokens and name_tokens[0] in filename_tokens:
                score += 25
            if score > 0:
                candidates.append(Candidate(game, key, score))
        candidates.sort(key=lambda c: c.score, reverse=True)
        best_type = max(type_scores.items(), key=lambda kv: kv[1], default=("", 0))
        analysis = Analysis(pkg, candidates)
        if best_type[1] >= 40:
            analysis.mod_type = profiles.get(best_type[0]).label
        if candidates and candidates[0].score >= 20:
            self.prepare(analysis, candidates[0].game, candidates[0].profile_key)
        return analysis

    def prepare(self, analysis: Analysis, game: Game, profile_key: str | None = None) -> Analysis:
        """(Re)calcule le plan d'installation et les dépendances pour le jeu choisi."""
        if profile_key is None:
            match = next((c for c in analysis.candidates if c.game.id == game.id), None)
            if match:
                profile_key = match.profile_key
            else:
                specific = profiles.get(game.profile)
                profile_key = game.profile if specific.detect(analysis.package) > 0 else "generic"
        profile = profiles.get(profile_key)
        analysis.game = game
        analysis.plan = profile.plan(analysis.package, game)
        analysis.deps = self._dedupe(profile.dependencies(analysis.package, game, analysis.plan), game)
        return analysis

    def _dedupe(self, deps: list[Dependency], game: Game) -> list[Dependency]:
        known = {m.get("dep_key") for m in self.db["mods"] if m["game_id"] == game.id and m.get("dep_key")}
        out, seen = [], set()
        for dep in deps:
            if dep.key in seen:
                continue
            seen.add(dep.key)
            if dep.status == AUTO and dep.key in known:
                dep.status = INSTALLED
            out.append(dep)
        return out

    # --- Installation ---------------------------------------------------------------------
    def install(self, analysis: Analysis, log=print, auto_deps: bool = True) -> list[str]:
        """Installe les dépendances automatiques puis le mod. Renvoie les messages à afficher."""
        plan, game = analysis.plan, analysis.game
        if plan is None or game is None:
            raise ValueError("Aucun jeu choisi pour ce mod.")
        if not plan.copies:
            raise ValueError("Rien à installer : ce mod ne contient aucun fichier utilisable pour ce jeu.")
        messages = []
        visited = set()
        self.extra_manual = []  # dépendances manuelles découvertes en installant d'autres dépendances
        for dep in analysis.deps:
            if dep.status == AUTO and auto_deps:
                messages += self._install_dependency(dep, game, log, visited)
            elif dep.status == AUTO or dep.status == MANUAL:
                dep.status = MANUAL
                messages.append(f"⚠ À installer vous-même : {dep.name}")
        name = analysis.package.name
        for old in [m for m in self.db["mods"] if m["game_id"] == game.id and m["name"] == name]:
            log(f"Ancienne version de « {name} » trouvée : remplacement.")
            self.uninstall(old["id"])
        log(f"Installation de « {name} » dans {game.name}…")
        self._apply(plan, name, analysis.package.source.name)
        profiles.get(plan.profile_key).after_install(plan)
        messages = plan.notes + messages
        log(f"✔ « {name} » installé ({len(plan.copies)} fichiers).")
        return messages

    def _install_dependency(self, dep: Dependency, game: Game, log, visited: set) -> list[str]:
        if dep.key in visited:
            return []
        visited.add(dep.key)
        try:
            log(f"Téléchargement de la dépendance {dep.name}…")
            url = dep.resolve()
            target_dir = cache_dir() / "downloads"
            file = net.download(url, target_dir, dep.filename)
            pkg = ModPackage.open(file)
        except Exception as exc:  # réseau, archive...
            log(f"✘ Échec du téléchargement de {dep.name} : {exc}")
            dep.status = MANUAL
            return [f"⚠ {dep.name} n'a pas pu être téléchargé automatiquement ({exc})."]
        messages = []
        try:
            plan = dep.build_plan(pkg, game)
            profile = profiles.BY_KEY.get(plan.profile_key)
            if profile is not None:
                for sub in self._dedupe(profile.dependencies(pkg, game, plan), game):
                    if sub.status == AUTO:
                        messages += self._install_dependency(sub, game, log, visited)
                    elif sub.status == MANUAL:
                        messages.append(f"⚠ À installer vous-même : {sub.name} — {sub.url}")
                        self.extra_manual.append(sub)
            self._apply(plan, dep.name, file.name, dep_key=dep.key)
            if profile is not None:
                profile.after_install(plan)
            log(f"✔ Dépendance {dep.name} installée.")
            messages.append(f"Dépendance installée automatiquement : {dep.name}")
        finally:
            pkg.cleanup()
        return messages

    def _apply(self, plan: InstallPlan, name: str, source_name: str, dep_key: str = "") -> dict:
        record_id = uuid.uuid4().hex[:12]
        backup_root = app_data_dir() / "backups" / record_id
        record = {"id": record_id, "name": name, "source": source_name, "game_id": plan.game.id,
                  "game_name": plan.game.name, "profile": plan.profile_key, "date": time.strftime("%Y-%m-%d %H:%M"),
                  "files": [], "backups": [], "dirs": [], "plugins": plan.plugins, "dep_key": dep_key}
        try:
            for i, (src, dst) in enumerate(plan.copies):
                parent = dst.parent
                while not parent.exists():
                    record["dirs"].append(str(parent))
                    parent = parent.parent
                dst.parent.mkdir(parents=True, exist_ok=True)
                if dst.is_file():
                    backup = backup_root / str(i)
                    backup.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(dst, backup)
                    record["backups"].append({"file": str(dst), "backup": str(backup)})
                shutil.copy2(src, dst)
                record["files"].append(str(dst))
        except OSError:
            self._revert(record)
            raise
        self.db["mods"].append(record)
        self._save()
        return record

    # --- Désinstallation ------------------------------------------------------------------
    def installed_mods(self, game_id: str | None = None) -> list[dict]:
        return [m for m in self.db["mods"] if game_id is None or m["game_id"] == game_id]

    def uninstall(self, record_id: str) -> None:
        record = next((m for m in self.db["mods"] if m["id"] == record_id), None)
        if record is None:
            return
        self._revert(record)
        game = self.game_by_id(record["game_id"]) or Game(record["game_name"], Path(record["game_id"]))
        profiles.get(record.get("profile", "generic")).after_uninstall(game, record)
        self.db["mods"].remove(record)
        self._save()

    def _revert(self, record: dict) -> None:
        for path in record["files"]:
            Path(path).unlink(missing_ok=True)
        for item in record["backups"]:
            backup = Path(item["backup"])
            if backup.exists():
                Path(item["file"]).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(backup, item["file"])
        for directory in sorted(record["dirs"], key=len, reverse=True):
            try:
                Path(directory).rmdir()
            except OSError:
                pass
        shutil.rmtree(app_data_dir() / "backups" / record["id"], ignore_errors=True)

    def _save(self) -> None:
        jsonutil.save_file(self.db_path, self.db)
