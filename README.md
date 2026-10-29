# Météo Paysage — widget Android

Widget météo pour l'écran d'accueil (pensé pour le Galaxy S22) : une vraie photo de paysage qui
change selon le temps **et** l'heure, avec animations réelles, la date, la température et le lieu.

| Moment / temps | Fond | Animation |
|---|---|---|
| Lever du soleil (±45 min) | lac brumeux et doré, ponton | nuages qui dérivent si couvert |
| Coucher du soleil (±45 min) | lac au soleil couchant entre les montagnes | nuages qui dérivent si couvert |
| Grand soleil / nuit claire | lac et forêt de pins / Voie lactée sur un lac | — |
| Nuageux | lac sous les nuages / lune voilée sur un fjord | deux couches de nuages qui défilent |
| Pluie / averses | lac sous un ciel de pluie (jour et nuit) | pluie qui tombe (deux couches) |
| Orage | tempête sur un lac | pluie + éclairs |
| Neige | lac de cratère enneigé / village sous la neige la nuit | flocons qui tombent |
| Brouillard | forêt et lac dans la brume dorée / arbres dans la brume | nappes de brume qui glissent |

Photos : Wikimedia Commons (licences Creative Commons, crédits dans l'appli et
`app/src/main/res/raw/photo_credits.txt`), choisies via `tools/fetch_photos.py`.

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
- Textures animées (pluie, neige, nuages, brume) générées par `tools/gen_scenes.py`.
- Changer une photo : modifier `tools/photo_selection.json` et pousser (le workflow « Photos de paysage » l'applique).
- Compilation : GitHub Actions (`.github/workflows/build-apk.yml`), ou `./gradlew assembleRelease`.
