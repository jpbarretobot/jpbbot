# Thème « Ambre d'Automne » pour Samsung Galaxy S22

Thème automnal orangé : fond de feuilles d'érable sur un dégradé cuivre, dossiers translucides aux reflets ambrés.

Samsung n'installe que les thèmes publiés sur le Galaxy Store. Ce pack s'installe donc en trois étapes avec les outils gratuits de Samsung.

## Contenu

| Fichier | Usage |
|---|---|
| `fond-accueil-ambre.png` | Fond d'écran d'accueil (1080 × 2340, résolution du S22) |
| `fond-accueil-sans-feuilles.png` | Variante plus calme, sans feuilles |
| `fond-verrouillage-ambre.png` | Fond d'écran de verrouillage |
| `fond-anime-verrouillage-ambre.mp4` | Fond animé de 15 s en boucle (feuilles qui tombent), pour l'écran de verrouillage |
| `dossier-verre-1/2/3.png` | Fonds d'icône en verre translucide avec reflet orangé (512 × 512, transparents) |

## Palette

Braise `#FF8A2B` · Citrouille `#E8601C` · Miel `#FFC46B` · Rouille `#9C3B12` · Écorce `#2B1810` · Crème `#FBE9D4`

## Installation

Copiez d'abord le dossier sur le téléphone (Téléchargements ou Galerie).

### 1. Fonds d'écran

1. **Paramètres › Fond d'écran et style › Changer les fonds d'écran › Galerie.**
2. Choisissez `fond-accueil-ambre.png` pour l'**écran d'accueil**.
3. Pour l'**écran de verrouillage**, choisissez `fond-anime-verrouillage-ambre.mp4` (version animée) ou `fond-verrouillage-ambre.png`.

### 2. Couleurs et icônes avec Theme Park

1. Installez **Theme Park** (gratuit, Galaxy Store), l'outil officiel de Samsung.
2. **Thème › Créer** puis importez `fond-accueil-ambre.png`. L'application en tire automatiquement une palette orangée.
3. Ajustez la couleur principale sur **Braise `#FF8A2B`** et les panneaux (volet rapide, clavier) sur **Écorce `#2B1810`**.
4. Onglet **Icônes** : choisissez une forme aux coins arrondis et un fond **Citrouille `#E8601C`**. Si votre version propose une image de fond d'icône, importez `dossier-verre-1.png`.
5. **Enregistrer › Appliquer.**

### 3. Dossiers translucides aux reflets orangés

Sous One UI, les dossiers de l'écran d'accueil sont déjà en verre flouté : ils laissent passer les couleurs du fond, ce qui crée l'effet ambré.

1. Ouvrez un dossier, puis touchez l'icône **palette** en haut à droite.
2. Choisissez une couleur personnalisée **Miel `#FFC46B`** ou **Braise `#FF8A2B`**. Le dossier se teinte d'orange tout en restant translucide.
3. Pour aller plus loin, installez **Good Lock** (Galaxy Store) puis le module **Home Up**. Selon votre version de One UI, ses réglages de dossier permettent d'ajuster le flou et la transparence.

## Régénérer les images

Le dossier `source/` contient le générateur (`gen.html`, dessin sur canvas) et le script `run.js` (Playwright) :

```
node source/run.js "$PWD/sortie"
ffmpeg -framerate 30 -i frames/f%04d.jpg -c:v libx264 -pix_fmt yuv420p sortie/fond-anime.mp4
```
