# Météo Paysage — widget Android

Widget météo pour l'écran d'accueil (pensé pour le Galaxy S22) : un paysage illustré qui change
selon le temps **et** l'heure, avec animations réelles, la date, la température et le lieu.

| Moment / temps | Fond | Animation |
|---|---|---|
| Lever du soleil (±45 min) | lac rose et doré | nuages qui dérivent si couvert |
| Coucher du soleil (±45 min) | lac orangé, soleil bas | nuages qui dérivent si couvert |
| Grand soleil / nuit claire | lac bleu / ciel étoilé + lune | quelques nuages légers |
| Nuageux | ciel gris | deux couches de nuages qui défilent (parallaxe) |
| Pluie / averses | lac sous la pluie, chalet éclairé la nuit | pluie qui tombe (deux couches) |
| Orage | ciel violet sombre | pluie + éclairs |
| Neige | paysage enneigé | flocons qui tombent (deux couches) |
| Brouillard | brume épaisse | nappes de brume qui glissent |

Chaque fond peut être remplacé par **vos propres photos** depuis l'appli (section « Mes photos »).

## Installer sur le téléphone

1. Ouvrir la page **Releases** du dépôt sur le téléphone et télécharger `MeteoPaysage.apk`
   (la dernière version est construite automatiquement à chaque modification).
2. Ouvrir le fichier et autoriser l'installation d'applications de cette source.
3. Ouvrir l'appli **Météo Paysage** une fois : autoriser la localisation (ou chercher une ville).
4. Appui long sur l'écran d'accueil ▸ **Widgets** ▸ **Météo Paysage** ▸ glisser le widget (4 × 2).

Conseil Samsung : Paramètres ▸ Applications ▸ Météo Paysage ▸ Batterie ▸ **Non restreinte**,
pour que la météo se mette à jour régulièrement.

## Technique

- Météo : [Open-Meteo](https://open-meteo.com) (gratuit, sans clé), mise à jour toutes les 15–25 min.
- Animations : `ViewFlipper` + animations de translation sur des textures qui se raccordent,
  la seule façon d'animer en continu un widget Android sans vider la batterie.
- Illustrations générées par `tools/gen_scenes.py` (Python + NumPy + Pillow) :
  `python3 tools/gen_scenes.py app/src/main/res/drawable-nodpi`
- Compilation : GitHub Actions (`.github/workflows/build-apk.yml`), ou `./gradlew assembleRelease`.
