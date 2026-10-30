# 🎮 EasyMod – le gestionnaire de mods pour ceux qui n'y connaissent rien

EasyMod installe vos mods à votre place. Vous téléchargez un mod, EasyMod le repère dans
votre dossier **Téléchargements**, devine **à quel jeu il appartient**, le copie **au bon endroit**
et s'occupe de ses **dépendances**. Un clic suffit pour tout désinstaller.

## Ce que fait EasyMod

1. **Trouve vos jeux tout seul** : Steam (toutes vos bibliothèques), Epic Games, GOG, Minecraft.
   Un jeu manque ? Onglet *Mes jeux* → *Ajouter un jeu manuellement*.
2. **Surveille vos Téléchargements** : dès qu'un mod (.zip, .7z, .rar, .jar, .pak…) est téléchargé,
   EasyMod vous demande s'il faut l'installer.
3. **Analyse le mod** : il regarde ce qu'il y a dans l'archive et en déduit le jeu
   (structure des dossiers, types de fichiers, manifestes, nom du fichier…).
4. **Vous montre ce qu'il va faire** : le jeu choisi (modifiable), la liste des fichiers copiés,
   les dépendances nécessaires. Vous cliquez sur **Installer**.
5. **Gère les dépendances** :
   - téléchargées et installées **automatiquement** quand c'est possible (BepInEx, paquets
     Thunderstore, mods Minecraft via Modrinth, frameworks Cyberpunk, UE4SS) ;
   - sinon EasyMod vous dit **lesquelles manquent** et ouvre la bonne page de téléchargement
     (SKSE, SMAPI, fichiers maîtres `.esm` manquants, etc.).
6. **Désinstallation propre** : les fichiers du jeu remplacés par un mod sont sauvegardés
   et remis en place quand vous désinstallez.

## Jeux reconnus

| Jeu / famille | Ce qu'EasyMod sait faire |
|---|---|
| **Skyrim SE / LE, Fallout 3 / New Vegas / 4, Oblivion, Starfield** | Installe dans `Data`, gère les archives avec dossier `Data/` ou non, les variantes, les installateurs **FOMOD** (options recommandées), **active les plugins** (`plugins.txt`), détecte les **fichiers maîtres manquants** et le **Script Extender** (SKSE, F4SE…) |
| **Minecraft Java** | Mods Fabric / Forge / NeoForge / Quilt → `mods`, packs de ressources, shaders ; vérifie le chargeur de mods et **télécharge les mods requis depuis Modrinth** (ex. Fabric API) dans la bonne version |
| **Stardew Valley** | Mods SMAPI et packs de contenu → `Mods`, vérifie SMAPI et les mods requis (`manifest.json`) |
| **Cyberpunk 2077** | `.archive`, REDscript, CET, RED4ext, ArchiveXL, TweakXL ; **télécharge les frameworks manquants** depuis GitHub |
| **Tous les jeux Unity** (Valheim, Lethal Company, Risk of Rain 2, Content Warning…) | Mods BepInEx et paquets **Thunderstore** ; installe **BepInEx et les dépendances Thunderstore** automatiquement |
| **Jeux Unreal Engine** (Palworld, Hogwarts Legacy, Stray…) | Fichiers `.pak/.utoc/.ucas` → `Content/Paks/~mods`, mods **UE4SS** (et installation d'UE4SS) |
| **Baldur's Gate 3** | `.pak` → dossier Mods de l'utilisateur, Script Extender ; recommande BG3 Mod Manager pour l'activation |
| **N'importe quel autre jeu** | Mode universel : EasyMod compare l'arborescence du mod à celle du jeu pour trouver où il « s'emboîte » ; à défaut, il copie le mod à la racine du jeu en vous prévenant |

## Installation

### Option 1 – le plus simple (Windows)
1. Installez **Python 3.11 ou plus récent** depuis <https://www.python.org/downloads/>
   (cochez **« Add python.exe to PATH »** pendant l'installation).
2. Téléchargez ce projet (bouton vert *Code* → *Download ZIP*) et décompressez-le.
3. Double-cliquez sur **`Lancer EasyMod.bat`**.

Conseillé : installez aussi [7-Zip](https://www.7-zip.org) pour ouvrir les mods en `.7z` et `.rar`
(très courants sur Nexus Mods).

### Option 2 – créer un `EasyMod.exe` à partager
Double-cliquez sur **`build_exe.bat`** : il crée `dist\EasyMod.exe`, un programme unique que vous
pouvez donner à vos amis (ils n'auront pas besoin d'installer Python).

### Option 3 – en ligne de commande
```bash
pip install -r requirements.txt   # facultatif : .7z, .rar, glisser-déposer
python -m easymod                 # ouvre l'interface
python -m easymod MonMod.zip      # ouvre directement l'installation d'un mod
```

## Utilisation

1. Lancez EasyMod : l'onglet **Mes jeux** liste les jeux trouvés.
2. Téléchargez un mod comme d'habitude (Nexus Mods, Thunderstore, Modrinth, CurseForge, ModDB…).
3. EasyMod vous propose de l'installer → vérifiez le jeu proposé (★ = choix recommandé) → **Installer**.
   Vous pouvez aussi cliquer sur **Installer un mod…** et choisir le fichier vous-même.
4. Pour retirer un mod : onglet **Mods installés** → **Désinstaller**.

## Bon à savoir / limites

- EasyMod **copie** les fichiers dans le jeu (pas de système de fichiers virtuel comme Mod Organizer 2).
  Les fichiers écrasés sont sauvegardés et restaurés à la désinstallation.
- Si deux mods modifient le même fichier, désinstallez-les dans l'ordre inverse de leur installation.
- Les installateurs FOMOD sont installés avec les **options recommandées** ; pour choisir vous-même
  chaque option, un gestionnaire dédié (Vortex, MO2) reste plus adapté.
- L'ordre de chargement des plugins Bethesda n'est pas trié (utilisez LOOT si vous avez beaucoup de mods).
- Les téléchargements automatiques utilisent les API publiques de GitHub, Modrinth et Thunderstore.
  Nexus Mods n'autorise pas le téléchargement automatique sans compte Premium : pour les mods Nexus,
  EasyMod ouvre la page de recherche du mod manquant.
- Données d'EasyMod (configuration, liste des mods, sauvegardes) : `%APPDATA%\EasyMod`.

## Ajouter un jeu

Chaque famille de jeux est un petit module dans `easymod/profiles/`. Un profil indique comment
reconnaître le jeu (`owns_game`), comment reconnaître ses mods (`detect`), où les copier (`plan`)
et quelles dépendances vérifier (`dependencies`). Ajoutez-le ensuite à `easymod/profiles/__init__.py`.

## Tests

```bash
pip install -r requirements-dev.txt
python -m pytest
```
