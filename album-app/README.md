# Album

Application Android personnelle : un album photo chronologique, qui ne montre que **vos** photos
(pas de feed, pas de likes, pas de compte). Les photos sont lues depuis la galerie en lecture seule.

## Stack

- Kotlin + Jetpack Compose (Material 3)
- MediaStore pour lire les photos (sans copie)
- Coil pour l'affichage des miniatures
- minSdk 26 (Android 8.0), targetSdk 35

## État actuel (MVP 0.1)

- [x] Demande de permission galerie (Android 13+ et anciennes versions)
- [x] Lecture des photos triée par date de prise de vue (repli sur la date d'ajout)
- [x] Vue chronologique en grille de 3 colonnes, regroupée par jour
- [ ] Albums personnalisés (stockage local, Room)
- [ ] Visionneuse plein écran et mode diaporama
- [ ] Favoris et tags
- [ ] Vue carte (coordonnées GPS EXIF)

## Lancer le projet

1. Installer **Android Studio** (récent).
2. Ouvrir le dossier `album-app/` avec *File > Open*. Android Studio génère le wrapper Gradle.
3. Brancher le téléphone en USB (débogage USB activé) ou utiliser un émulateur.
4. Cliquer sur *Run*.

La compilation n'a pas été vérifiée en ligne de commande : le code a été relu mais pas
exécuté. Premier lancement = premier vrai test.

## Structure

```
app/src/main/java/com/jpb/album/
├── MainActivity.kt          # point d'entrée, permissions, écran principal
├── data/PhotoRepository.kt  # lecture du MediaStore, modèle Photo
└── ui/Timeline.kt           # grille chronologique regroupée par jour
```
