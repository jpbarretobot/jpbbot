"""Interface graphique d'EasyMod (Tkinter, incluse avec Python)."""

import os
import queue
import subprocess
import sys
import threading
import traceback
import webbrowser
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
import tkinter as tk

from . import APP_NAME, __version__, profiles
from .archive import SUPPORTED_EXTENSIONS
from .config import Config
from .manager import ModManager
from .models import AUTO, INSTALLED, MANUAL
from .watcher import DownloadWatcher

try:  # glisser-déposer optionnel
    from tkinterdnd2 import DND_FILES, TkinterDnD
except ImportError:
    TkinterDnD = None

STATUS_COLORS = {INSTALLED: "#1e7d32", AUTO: "#1565c0", MANUAL: "#c62828"}
FILETYPES = [("Mods", " ".join(f"*{e}" for e in sorted(SUPPORTED_EXTENSIONS))), ("Tous les fichiers", "*.*")]


def open_folder(path: Path) -> None:
    path = Path(path)
    if sys.platform.startswith("win"):
        os.startfile(path)  # noqa: S606 - ouverture volontaire de l'explorateur
    elif sys.platform == "darwin":
        subprocess.Popen(["open", str(path)])
    else:
        subprocess.Popen(["xdg-open", str(path)])


def short_path(path: Path, root: Path) -> str:
    try:
        return Path(path).relative_to(root).as_posix()
    except ValueError:
        return str(path)


class App:
    def __init__(self, initial_files=()):
        self.root = TkinterDnD.Tk() if TkinterDnD else tk.Tk()
        self.root.title(f"{APP_NAME} {__version__} – gestionnaire de mods")
        self.root.geometry("960x640")
        self.root.minsize(760, 500)
        self.config = Config()
        self.manager = ModManager(self.config)
        self.events: queue.Queue = queue.Queue()
        self.busy = False
        self.watcher = None
        self._build_ui()
        self.root.after(100, self._process_events)
        self.refresh_games(then=lambda: [self.handle_file(Path(f)) for f in initial_files])
        if self.config["watch_downloads"]:
            self.toggle_watch(force=True)

    # --- Construction de l'interface ----------------------------------------------------
    def _build_ui(self):
        style = ttk.Style(self.root)
        if "vista" in style.theme_names():
            style.theme_use("vista")
        elif "clam" in style.theme_names():
            style.theme_use("clam")
        style.configure("Title.TLabel", font=("Segoe UI", 18, "bold"))
        style.configure("Sub.TLabel", foreground="#555")
        style.configure("Big.TButton", font=("Segoe UI", 12, "bold"), padding=10)

        header = ttk.Frame(self.root, padding=(16, 12, 16, 4))
        header.pack(fill="x")
        ttk.Label(header, text=f"🎮 {APP_NAME}", style="Title.TLabel").pack(anchor="w")
        hint = "Choisissez un mod téléchargé : EasyMod trouve le bon jeu et l'installe pour vous."
        if TkinterDnD:
            hint += " Vous pouvez aussi glisser le fichier dans cette fenêtre."
        ttk.Label(header, text=hint, style="Sub.TLabel").pack(anchor="w")

        actions = ttk.Frame(self.root, padding=(16, 8))
        actions.pack(fill="x")
        ttk.Button(actions, text="📦  Installer un mod…", style="Big.TButton",
                   command=self.pick_file).pack(side="left")
        ttk.Button(actions, text="🔄 Rechercher mes jeux", command=self.refresh_games).pack(side="left", padx=8)
        self.watch_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(actions, text="Surveiller mes Téléchargements", variable=self.watch_var,
                        command=self.toggle_watch).pack(side="left", padx=8)
        ttk.Button(actions, text="📁 Changer de dossier", command=self.pick_downloads).pack(side="left")

        self.tabs = ttk.Notebook(self.root)
        self.tabs.pack(fill="both", expand=True, padx=16, pady=8)
        self._build_mods_tab()
        self._build_games_tab()
        self._build_log_tab()

        self.status = tk.StringVar(value="Prêt.")
        ttk.Label(self.root, textvariable=self.status, relief="sunken", padding=(8, 2)).pack(fill="x", side="bottom")

        if TkinterDnD:
            self.root.drop_target_register(DND_FILES)
            self.root.dnd_bind("<<Drop>>", lambda e: [self.handle_file(Path(f)) for f in self.root.tk.splitlist(e.data)])

    def _tree(self, parent, columns):
        frame = ttk.Frame(parent)
        frame.pack(fill="both", expand=True)
        tree = ttk.Treeview(frame, columns=[c for c, _, _ in columns], show="headings", selectmode="browse")
        for col, title, width in columns:
            tree.heading(col, text=title)
            tree.column(col, width=width, anchor="w")
        scroll = ttk.Scrollbar(frame, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=scroll.set)
        tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        return tree

    def _build_mods_tab(self):
        tab = ttk.Frame(self.tabs, padding=8)
        self.tabs.add(tab, text="Mods installés")
        self.mods_tree = self._tree(tab, [("name", "Mod", 320), ("game", "Jeu", 260), ("files", "Fichiers", 70),
                                          ("date", "Installé le", 130)])
        bar = ttk.Frame(tab, padding=(0, 8, 0, 0))
        bar.pack(fill="x")
        ttk.Button(bar, text="🗑 Désinstaller", command=self.uninstall_selected).pack(side="left")
        ttk.Button(bar, text="Ouvrir le dossier du jeu", command=self.open_mod_game).pack(side="left", padx=8)

    def _build_games_tab(self):
        tab = ttk.Frame(self.tabs, padding=8)
        self.tabs.add(tab, text="Mes jeux")
        self.games_tree = self._tree(tab, [("name", "Jeu", 260), ("type", "Type de mods", 220),
                                           ("source", "Trouvé via", 120), ("path", "Dossier", 300)])
        bar = ttk.Frame(tab, padding=(0, 8, 0, 0))
        bar.pack(fill="x")
        ttk.Button(bar, text="➕ Ajouter un jeu manuellement…", command=self.add_game).pack(side="left")
        ttk.Button(bar, text="Retirer", command=self.remove_game).pack(side="left", padx=8)
        ttk.Button(bar, text="Ouvrir le dossier", command=self.open_game_folder).pack(side="left")
        ttk.Label(tab, text="Un jeu manque ? Ajoutez son dossier d'installation (celui qui contient le .exe).",
                  style="Sub.TLabel").pack(anchor="w", pady=(6, 0))

    def _build_log_tab(self):
        tab = ttk.Frame(self.tabs, padding=8)
        self.tabs.add(tab, text="Journal")
        self.log_text = tk.Text(tab, height=10, state="disabled", wrap="word", font=("Consolas", 10))
        self.log_text.pack(fill="both", expand=True)

    # --- Outils ------------------------------------------------------------------------
    def log(self, message: str):
        """Utilisable depuis n'importe quel fil d'exécution."""
        self.events.put(("log", message))

    def _append_log(self, message: str):
        self.log_text.configure(state="normal")
        self.log_text.insert("end", message + "\n")
        self.log_text.see("end")
        self.log_text.configure(state="disabled")
        self.status.set(message)

    def run_bg(self, func, on_done=None, busy_text="Veuillez patienter…"):
        """Exécute func dans un fil séparé pour ne pas figer la fenêtre."""
        self.busy = True
        self.status.set(busy_text)
        self.root.config(cursor="watch")

        def worker():
            try:
                result = func()
                self.events.put(("done", (on_done, result)))
            except Exception as exc:
                traceback.print_exc()
                self.events.put(("error", exc))

        threading.Thread(target=worker, daemon=True).start()

    def _process_events(self):
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "log":
                    self._append_log(payload)
                elif kind in ("done", "error"):
                    self.busy = False
                    self.root.config(cursor="")
                    if kind == "done":
                        callback, result = payload
                        if callback:
                            callback(result)
                    else:
                        self._append_log(f"Erreur : {payload}")
                        messagebox.showerror(APP_NAME, f"Une erreur est survenue :\n\n{payload}")
                elif kind == "new_download":
                    self._on_new_download(payload)
        except queue.Empty:
            pass
        self.root.after(100, self._process_events)

    # --- Jeux ----------------------------------------------------------------------------
    def refresh_games(self, then=None):
        def done(games):
            self.games_tree.delete(*self.games_tree.get_children())
            for game in games:
                self.games_tree.insert("", "end", iid=game.id, values=(
                    game.name, profiles.get(game.profile).label, game.source, str(game.path)))
            self.log(f"{len(games)} jeu(x) trouvé(s).")
            self.refresh_mods()
            if then:
                then()

        self.run_bg(self.manager.refresh_games, done, "Recherche des jeux installés…")

    def add_game(self):
        folder = filedialog.askdirectory(title="Choisissez le dossier d'installation du jeu")
        if not folder:
            return
        path = Path(folder)
        detected = profiles.profile_for_game(path)
        labels = {p.label: p.key for p in profiles.ALL}
        dialog = tk.Toplevel(self.root)
        dialog.title("Ajouter un jeu")
        dialog.transient(self.root)
        dialog.grab_set()
        frm = ttk.Frame(dialog, padding=16)
        frm.pack(fill="both", expand=True)
        ttk.Label(frm, text="Nom du jeu :").grid(row=0, column=0, sticky="w")
        name_var = tk.StringVar(value=path.name)
        ttk.Entry(frm, textvariable=name_var, width=40).grid(row=0, column=1, pady=4)
        ttk.Label(frm, text="Type de jeu :").grid(row=1, column=0, sticky="w")
        type_var = tk.StringVar(value=profiles.get(detected).label)
        ttk.Combobox(frm, textvariable=type_var, values=list(labels), state="readonly", width=38).grid(
            row=1, column=1, pady=4)
        ttk.Label(frm, text="(détecté automatiquement, ne changez que si vous savez)", style="Sub.TLabel").grid(
            row=2, column=1, sticky="w")

        def ok():
            self.config.add_manual_game(name_var.get().strip() or path.name, str(path), labels[type_var.get()])
            dialog.destroy()
            self.refresh_games()

        ttk.Button(frm, text="Ajouter", command=ok).grid(row=3, column=1, sticky="e", pady=(12, 0))

    def _selected_game(self):
        sel = self.games_tree.selection()
        return self.manager.game_by_id(sel[0]) if sel else None

    def remove_game(self):
        game = self._selected_game()
        if not game:
            return
        if game.source != "Ajouté manuellement":
            messagebox.showinfo(APP_NAME, "Ce jeu a été trouvé automatiquement et ne peut pas être retiré.")
            return
        self.config.remove_manual_game(str(game.path))
        self.refresh_games()

    def open_game_folder(self):
        game = self._selected_game()
        if game:
            open_folder(game.path)

    # --- Mods installés -------------------------------------------------------------------
    def refresh_mods(self):
        self.mods_tree.delete(*self.mods_tree.get_children())
        for mod in reversed(self.manager.installed_mods()):
            label = mod["name"] + ("  (dépendance)" if mod.get("dep_key") else "")
            self.mods_tree.insert("", "end", iid=mod["id"], values=(label, mod["game_name"], len(mod["files"]),
                                                                    mod["date"]))

    def uninstall_selected(self):
        sel = self.mods_tree.selection()
        if not sel:
            messagebox.showinfo(APP_NAME, "Sélectionnez d'abord un mod dans la liste.")
            return
        mod = next(m for m in self.manager.installed_mods() if m["id"] == sel[0])
        if not messagebox.askyesno(APP_NAME, f"Désinstaller « {mod['name']} » de {mod['game_name']} ?\n\n"
                                             "Les fichiers d'origine remplacés par le mod seront restaurés."):
            return
        self.manager.uninstall(mod["id"])
        self.log(f"« {mod['name']} » a été désinstallé.")
        self.refresh_mods()

    def open_mod_game(self):
        sel = self.mods_tree.selection()
        if sel:
            mod = next(m for m in self.manager.installed_mods() if m["id"] == sel[0])
            game = self.manager.game_by_id(mod["game_id"])
            if game:
                open_folder(game.path)

    # --- Téléchargements -------------------------------------------------------------------
    def pick_downloads(self):
        folder = filedialog.askdirectory(title="Dossier à surveiller", initialdir=str(self.config.downloads))
        if folder:
            self.config["downloads_dir"] = folder
            if self.watcher:
                self.watcher.stop()
                self.watcher = None
                self.toggle_watch(force=True)

    def toggle_watch(self, force=False):
        enable = True if force else self.watch_var.get()
        self.watch_var.set(enable)
        self.config["watch_downloads"] = enable
        if enable:
            self.watcher = DownloadWatcher(self.config.downloads, lambda p: self.events.put(("new_download", p)))
            self.watcher.start()
            self.log(f"Surveillance du dossier {self.config.downloads}")
        elif self.watcher:
            self.watcher.stop()
            self.watcher = None
            self.log("Surveillance des téléchargements arrêtée.")

    def _on_new_download(self, path: Path):
        self.root.deiconify()
        self.root.lift()
        if messagebox.askyesno(APP_NAME, f"Nouveau fichier téléchargé :\n\n{path.name}\n\nEst-ce un mod à installer ?"):
            self.handle_file(path)

    # --- Installation ------------------------------------------------------------------------
    def pick_file(self):
        file = filedialog.askopenfilename(title="Choisissez le mod téléchargé", initialdir=str(self.config.downloads),
                                          filetypes=FILETYPES)
        if file:
            self.handle_file(Path(file))

    def handle_file(self, path: Path):
        if self.busy:
            self.root.after(500, lambda: self.handle_file(path))
            return
        self.log(f"Analyse de {path.name}…")
        self.run_bg(lambda: self.manager.analyze(path), self.show_install_dialog, f"Analyse de {path.name}…")

    def show_install_dialog(self, analysis):
        InstallDialog(self, analysis)


class InstallDialog:
    """Fenêtre de confirmation : quel jeu, quels fichiers, quelles dépendances."""

    def __init__(self, app: App, analysis):
        self.app, self.analysis = app, analysis
        self.win = tk.Toplevel(app.root)
        self.win.title(f"Installer « {analysis.package.name} »")
        self.win.geometry("780x600")
        self.win.transient(app.root)
        self.win.grab_set()
        self.win.protocol("WM_DELETE_WINDOW", self.close)
        body = ttk.Frame(self.win, padding=16)
        body.pack(fill="both", expand=True)
        self.body = body

        ttk.Label(body, text=analysis.package.name, style="Title.TLabel").pack(anchor="w")
        kind = analysis.mod_type or "type de mod non reconnu"
        ttk.Label(body, text=f"Type reconnu : {kind}", style="Sub.TLabel").pack(anchor="w")

        game_row = ttk.Frame(body, padding=(0, 10))
        game_row.pack(fill="x")
        ttk.Label(game_row, text="Jeu :", font=("Segoe UI", 11, "bold")).pack(side="left")
        candidates = {c.game.id for c in analysis.candidates}
        ordered = [c.game for c in analysis.candidates] + [g for g in app.manager.games if g.id not in candidates]
        self.games = ordered
        best = analysis.candidates[0].game.id if analysis.candidates else None
        labels = [("★ " if g.id == best and analysis.confident else "") + f"{g.name}  ({g.source})" for g in ordered]
        self.game_var = tk.StringVar()
        self.combo = ttk.Combobox(game_row, textvariable=self.game_var, values=labels, state="readonly", width=60)
        self.combo.pack(side="left", padx=8)
        self.combo.bind("<<ComboboxSelected>>", self.on_game_change)

        self.warning = ttk.Label(body, foreground="#c62828", wraplength=740, justify="left")
        self.warning.pack(anchor="w")

        self.details = ttk.Frame(body)
        self.details.pack(fill="both", expand=True)

        buttons = ttk.Frame(body, padding=(0, 10, 0, 0))
        buttons.pack(fill="x")
        self.install_btn = ttk.Button(buttons, text="✔  Installer", style="Big.TButton", command=self.install)
        self.install_btn.pack(side="right")
        ttk.Button(buttons, text="Annuler", command=self.close).pack(side="right", padx=8)

        if not ordered:
            self.warning.configure(text="Aucun jeu n'a été trouvé sur ce PC. Ajoutez votre jeu dans l'onglet "
                                        "« Mes jeux » (bouton « Ajouter un jeu manuellement »).")
            self.install_btn.state(["disabled"])
            return
        if analysis.game is not None:
            self.combo.current(0)
            self.render()
        else:
            self.install_btn.state(["disabled"])
            msg = "Impossible de savoir automatiquement à quel jeu appartient ce mod. Choisissez le jeu ci-dessus."
            self.warning.configure(text=msg)
        if analysis.mod_type and not analysis.confident:
            self.warning.configure(text=f"Ce mod ressemble à un mod pour « {analysis.mod_type} », mais ce jeu n'a pas "
                                        "été trouvé. Ajoutez-le dans « Mes jeux » si besoin, ou choisissez le jeu.")

    def on_game_change(self, _event=None):
        game = self.games[self.combo.current()]
        self.analysis.plan = None
        self.app.manager.prepare(self.analysis, game)
        self.install_btn.state(["!disabled"])
        self.warning.configure(text="")
        self.render()

    def render(self):
        for child in self.details.winfo_children():
            child.destroy()
        plan, game = self.analysis.plan, self.analysis.game

        files = ttk.LabelFrame(self.details, text=f"Fichiers qui seront copiés ({len(plan.copies)})", padding=6)
        files.pack(fill="both", expand=True, pady=(4, 6))
        listbox = tk.Listbox(files, height=8)
        scroll = ttk.Scrollbar(files, orient="vertical", command=listbox.yview)
        listbox.configure(yscrollcommand=scroll.set)
        listbox.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        for _, dst in plan.copies[:2000]:
            listbox.insert("end", short_path(dst, game.path))
        if not plan.copies:
            listbox.insert("end", "(aucun fichier utilisable pour ce jeu)")
            self.install_btn.state(["disabled"])

        deps = ttk.LabelFrame(self.details, text="Ce dont le mod a besoin", padding=6)
        deps.pack(fill="x", pady=6)
        if not self.analysis.deps:
            ttk.Label(deps, text="Aucune dépendance détectée.").pack(anchor="w")
        for dep in self.analysis.deps:
            row = ttk.Frame(deps)
            row.pack(fill="x", pady=2)
            ttk.Label(row, text=dep.name, font=("Segoe UI", 10, "bold")).grid(row=0, column=0, sticky="w")
            tk.Label(row, text=dep.status_label, fg=STATUS_COLORS.get(dep.status, "black")).grid(
                row=0, column=1, sticky="w", padx=12)
            ttk.Label(row, text=dep.reason, wraplength=560, justify="left", style="Sub.TLabel").grid(
                row=1, column=0, columnspan=2, sticky="w")
            if dep.url and dep.status != INSTALLED:
                ttk.Button(row, text="Ouvrir la page", command=lambda u=dep.url: webbrowser.open(u)).grid(
                    row=0, column=2, rowspan=2, sticky="e")
            row.columnconfigure(1, weight=1)

        if plan.notes:
            notes = ttk.LabelFrame(self.details, text="À savoir", padding=6)
            notes.pack(fill="x", pady=6)
            for note in plan.notes:
                ttk.Label(notes, text="• " + note, wraplength=720, justify="left").pack(anchor="w")

    def install(self):
        self.install_btn.state(["disabled"])
        auto = self.app.config["auto_download_dependencies"]

        def done(messages):
            self.app.refresh_mods()
            self.close()
            text = f"« {self.analysis.package.name} » est installé dans {self.analysis.game.name} !"
            if messages:
                text += "\n\n" + "\n".join("• " + m for m in messages)
            manual = [d for d in self.analysis.deps if d.status == MANUAL and d.url]
            manual += [d for d in self.app.manager.extra_manual if d.url]
            if manual:
                text += "\n\nCertaines dépendances doivent être téléchargées par vous : voulez-vous ouvrir leurs pages ?"
                if messagebox.askyesno(APP_NAME, text):
                    for dep in manual:
                        webbrowser.open(dep.url)
            else:
                messagebox.showinfo(APP_NAME, text)

        self.app.run_bg(lambda: self.app.manager.install(self.analysis, log=self.app.log, auto_deps=auto), done,
                        "Installation en cours…")

    def close(self):
        self.win.grab_release()
        self.win.destroy()
        self.analysis.package.cleanup()


def main():
    files = [a for a in sys.argv[1:] if Path(a).exists()]
    app = App(files)
    app.root.mainloop()
