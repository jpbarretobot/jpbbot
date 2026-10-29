# Thème « Brume Dorée » pour Samsung Galaxy S22

Un lac au crépuscule, des pins dans la brume, un ponton sous la pluie. Lumière du soir, rouille et forêt sombre.

## Pourquoi il n'y a pas de fichier « un clic »

Sur un Galaxy, seuls les thèmes publiés sur le Galaxy Store peuvent s'installer d'un coup. Un fichier installé à la main (APK) est bloqué par le téléphone. Le thème se monte donc avec **Theme Park**, l'application officielle et gratuite de Samsung. Comptez une dizaine de minutes.

## Contenu

| Dossier / fichier | Usage |
|---|---|
| `fonds/brume-doree-accueil.png` | Fond d'écran d'accueil (1080 × 2340) |
| `fonds/brume-doree-verrouillage.png` | Fond d'écran de verrouillage |
| `fonds/brume-doree-verrouillage-anime.mp4` | Version animée de 12 s : brume qui dérive, pluie fine, lampadaires qui vacillent |
| `icones/*.png` | 25 icônes (512 × 512) : téléphone, messages, appareil photo, galerie, agenda, horloge, météo, notes, musique, paramètres… |

## Palette

Lumière du soir `#D58A1F` · Rouille automnale `#A54F1B` · Terre mouillée `#5A2E14` · Brume profonde `#2E2A24` · Pierre froide `#6B5A45` · Forêt sombre `#1F3024`

## Installation

Décompressez le fichier zip sur le téléphone (application **Mes fichiers**, appui long sur le zip, **Extraire**).

### 1. Créer le thème avec Theme Park

1. Installez **Theme Park** depuis le Galaxy Store.
2. Onglet **Thème › Créer**, puis choisissez `fonds/brume-doree-accueil.png`.
3. Réglez la couleur principale sur **Lumière du soir `#D58A1F`** et les fonds (volet rapide, clavier, paramètres) sur **Brume profonde `#2E2A24`**.
4. Pour l'écran de verrouillage, choisissez `fonds/brume-doree-verrouillage.png`.
5. **Enregistrer › Appliquer.**

### 2. Les icônes

Toujours dans Theme Park, onglet **Icônes › Créer** :

1. Choisissez la forme carrée aux coins arrondis.
2. Touchez une application, puis importez l'image correspondante du dossier `icones/` (par exemple `camera.png` pour l'Appareil photo).
3. Pour les applications sans icône dédiée, réglez le fond sur **Brume profonde `#2E2A24`** et le pictogramme sur **Lumière du soir `#D58A1F`**. Elles resteront ainsi dans le même esprit.
4. **Enregistrer › Appliquer.**

Selon la version de Theme Park, l'import d'image par application peut s'appeler différemment ou ne pas être proposé. Les couleurs de l'étape 3 donnent alors le rendu le plus proche.

### 3. Fond animé (facultatif)

**Paramètres › Fond d'écran et style › Changer les fonds d'écran › Galerie**, puis choisissez `brume-doree-verrouillage-anime.mp4` pour l'**écran de verrouillage**.

### 4. Dossiers et widgets

- **Dossiers** : ouvrez un dossier, touchez l'icône **palette** et choisissez `#D58A1F`. Le dossier reste translucide et prend un reflet doré.
- **Widgets** : l'horloge et la météo de Samsung reprennent les couleurs du thème. Les widgets illustrés sur le prototype (citation, liste du jour, lecteur) demanderaient l'application KWGT.

## Régénérer les images

`source/` contient le dessin procédural (`scene.js`, `gen.html`) et le script de rendu Playwright (`run.js`).
