"""Cyberpunk 2077 (.archive, REDscript, Cyber Engine Tweaks, RED4ext, ArchiveXL, TweakXL)."""

from pathlib import Path

from ..models import InstallPlan
from .base import Profile, github_dependency, installed, ipath

ROOT_DIRS = {"archive", "r6", "bin", "red4ext", "engine", "mods"}

# (nom, condition sur les fichiers installés, fichier prouvant l'installation, dépôt GitHub, motif)
FRAMEWORKS = [
    ("RED4ext", lambda p: p.startswith("red4ext/plugins/") or p.endswith(".xl") or p.startswith("r6/tweaks/"),
     "red4ext/RED4ext.dll", "wopss/RED4ext", r"^red4ext.*\.zip$"),
    ("redscript", lambda p: p.startswith("r6/scripts/") and p.endswith(".reds"),
     "engine/tools/scc.exe", "jac3km4/redscript", r"^redscript.*\.zip$"),
    ("Cyber Engine Tweaks", lambda p: p.startswith("bin/x64/plugins/cyber_engine_tweaks/"),
     "bin/x64/plugins/cyber_engine_tweaks.asi", "maximegmd/CyberEngineTweaks", r"^cet.*\.zip$"),
    ("ArchiveXL", lambda p: p.endswith(".xl"),
     "red4ext/plugins/ArchiveXL/ArchiveXL.dll", "psiberx/cp2077-archive-xl", r"^ArchiveXL.*\.zip$"),
    ("TweakXL", lambda p: p.startswith("r6/tweaks/"),
     "red4ext/plugins/TweakXL/TweakXL.dll", "psiberx/cp2077-tweak-xl", r"^TweakXL.*\.zip$"),
]


class CyberpunkProfile(Profile):
    key = "cyberpunk"
    label = "Cyberpunk 2077"

    def owns_game(self, path: Path) -> bool:
        return ipath(path, "bin/x64/Cyberpunk2077.exe").is_file()

    def detect(self, pkg) -> int:
        score = 0
        if pkg.with_ext(".archive"):
            score += 80
        dirs = {d.lower() for f in pkg.files for d in f.split("/")[:-1]}
        if {"r6", "red4ext", "cyber_engine_tweaks"} & dirs:
            score += 70
        if pkg.with_ext(".reds", ".xl"):
            score += 40
        return min(score, 100)

    def plan(self, pkg, game) -> InstallPlan:
        plan = InstallPlan(game, self.key)
        base = pkg.wrapper_root(stop=ROOT_DIRS)
        join = lambda a, b: f"{a}/{b}" if a else b
        for entry in sorted(pkg.top_entries(base)):
            full = join(base, entry)
            if entry.lower() in ROOT_DIRS:
                plan.add_tree(pkg, full, game.path / entry)
        handled = {src for src, _ in plan.copies}
        for rel in pkg.with_ext(".archive", ".xl"):
            src = pkg.path(rel)
            if src not in handled:
                plan.add(src, game.path / "archive" / "pc" / "mod" / rel.rsplit("/", 1)[-1])
        return plan

    def dependencies(self, pkg, game, plan):
        rels = [dst.relative_to(game.path).as_posix().lower() for _, dst in plan.copies]
        deps = []
        for name, needed, proof, repo, pattern in FRAMEWORKS:
            if not any(needed(r) for r in rels):
                continue
            if ipath(game.path, proof).exists():
                deps.append(installed(name, "Framework requis par ce mod", key=repo.lower()))
            else:
                deps.append(github_dependency(name, "Framework requis par ce mod", repo, pattern, stop=ROOT_DIRS))
        return deps


PROFILES = [CyberpunkProfile()]
