# Studio Caméra

Application Android de photographie assistée par intelligence artificielle : un second appareil photo
professionnel qui combine contrôles manuels, photographie computationnelle, séparation des plans et
retouche par couches. Kotlin, framework Android, traitement entièrement local.

## Sommaire

- [Fonctionnalités](#fonctionnalités)
- [Architecture](#architecture)
- [Compiler et installer](#compiler-et-installer)
- [Tests](#tests)
- [Modèles d'IA optionnels](#modèles-dia-optionnels)
- [Limites connues et ce qui n'a pas été vérifié](#limites-connues-et-ce-qui-na-pas-été-vérifié)

## Fonctionnalités

### Appareil photo (Camera2)

- Aperçu en direct, orienté portrait, avec grille des tiers et mise au point au toucher.
- Objectifs détectés sur l'appareil : chaque caméra physique d'un ensemble multi-objectifs est proposée
  séparément, libellée par sa focale équivalente 35 mm (aucune focale n'est supposée).
- Zoom optique (ratio natif Android 11+) et zoom numérique 1× / 2× / 4×.
- Mode Auto (style choisi, réglages automatiques) et mode Manuel : ISO, vitesse, mise au point, balance des
  blancs (température en kelvins), compensation d'exposition. Chaque curseur accepte « Auto ».
- Flash (auto, activé, torche), HDR matériel lorsqu'il est annoncé, minuteur 3 s / 10 s.
- Résolution : maximale, ≈ 12 MP ou ≈ 5 MP, choisie parmi les tailles réellement proposées par le capteur.
- JPEG, ou JPEG + DNG (RAW) lorsque le capteur l'annonce.

### Vingt styles photographiques en trois familles

| Famille | Styles |
|---|---|
| Professionnelle | Perspective indépendante, Portrait cinéma, Compression des distances, Architecture, Macro intelligente, HDR professionnel, Nuit et astrophoto, Pose longue, Netteté intégrale |
| IA créative | Perspective multicouche, Zoom indépendant du sujet, Zoom indépendant de l'arrière-plan, Cadrage intelligent, Recomposition spatiale, Lumière intelligente |
| Cinématique | Cinéma, Lumière parfaite, Mouvement, Panorama intelligent, Photo de groupe parfaite |

Chaque style décrit sa **capture** (rafale, expositions décalées, mises au point successives, balayage…),
ses **étapes de traitement** et ses **exigences matérielles**. Avant la prise, l'application indique si le style
est disponible, disponible avec repli (texte explicite), ou indisponible sur l'appareil.

### Traitement

| Étape | Méthode réelle |
|---|---|
| Alignement | Pyramide multi-échelle, comparaison de contours (robuste aux écarts d'exposition) |
| Fusion HDR | Fusion d'expositions de Mertens, pyramides streamées (faible empreinte mémoire) |
| Netteté intégrale / macro | Empilement pondéré par la netteté locale ; carte de profondeur relative issue des poids |
| Nuit / astro | Moyenne écrêtée autour de la médiane (rejet des éléments mobiles) |
| Pose longue | Pose réelle si le capteur le permet, sinon simulation par empilement (signalée comme telle) |
| Panorama | Positionnement par translation, fusion en plume |
| Photo de groupe | Choix de la prise la plus favorable ; yeux ouverts restaurés par zone recalée et fondue |
| Mouvement | Sujet net (prise figée) sur arrière-plan filé (prise lente), masque du sujet |
| Architecture | Détection des verticales, redressement par cisaillement et perspective verticale |
| Étalonnage | Exposition, contraste, saturation, température, ombres, hautes lumières, rendu pellicule, vignettage |

Le traitement s'exécute dans un **service de premier plan** : les prises sont écrites sur disque avant tout calcul,
le viseur reste utilisable, et une interruption ne fait jamais perdre une photo.

### Séparation des plans et Studio IA

- **Sujet** : segmentation de personnes embarquée (ML Kit, sur l'appareil) ou modèle TFLite importé.
- **Profondeur** : modèle monoculaire TFLite importé, ou repli heuristique explicitement étiqueté.
- **Couches** : sujet, ciel, premier plan, plan intermédiaire, arrière-plan. Les masques forment une partition de
  l'unité : leur somme vaut 1 en chaque pixel.
- **Rendu par couche** : zoom (100 % = neutre), position, perspective, luminosité, contraste, saturation, netteté, flou.
  Un changement sur une couche ne modifie pas les autres, sauf pour combler les zones révélées.
- **Zones révélées** : complétées par push-pull depuis la couche située derrière. Ces pixels sont **marqués comme
  générés** et peuvent être affichés en magenta. Sans modification, le rendu est exactement l'image capturée.
- **Studio** : comparaison avant/après (maintenir le doigt sur l'aperçu), affichage/masquage des couches,
  annuler/rétablir, versions multiples.

### Galerie et stockage

- Galerie par date et par album (famille de styles), filtre « Studio IA » pour les projets multicouches.
- L'original (`original.jpg`) et le DNG sont écrits une seule fois et ne sont jamais écrasés : chaque rendu ou
  sauvegarde du Studio crée une nouvelle version.
- Les métadonnées EXIF utiles (date, appareil, réglages) sont recopiées dans chaque version.
- Export JPEG et DNG vers `Pictures/StudioCamera` ; partage par le sélecteur système.
- Confidentialité : l'application **ne demande pas la permission INTERNET**. Rien n'est envoyé hors de l'appareil.

## Architecture

```
app/src/main/java/dev/studiocamera/
├── StudioApp.kt            conteneur de dépendances (une instance par service)
├── imaging/                cœur pur Kotlin, sans dépendance Android (testable sur JVM)
│   ├── Plane.kt            plans flottants : flou, pyramides, gradients
│   ├── Frame.kt            images ARGB, canaux, redimensionnement
│   ├── Geometry.kt         homographies 3×3
│   ├── Warp.kt             rétro-projection bilinéaire
│   ├── Alignment.kt        alignement translationnel par pyramide
│   ├── ExposureFusion.kt   fusion de Mertens (HDR)
│   ├── FocusStack.kt       netteté intégrale et profondeur relative
│   ├── Stacking.kt         moyenne écrêtée (nuit) et moyenne (pose longue)
│   ├── Panorama.kt         assemblage par translation et plume
│   ├── VerticalCorrection.kt  redressement des verticales
│   ├── Grading.kt          étalonnage (global ou par masque)
│   └── Inpaint.kt          comblement push-pull, zones générées signalées
├── layers/                 moteur multicouche
│   ├── LayerModel.kt       couches, réglages par couche, état d'édition immuable
│   ├── LayerBuilder.kt     profondeur + sujet → partition de l'unité
│   └── LayerRenderer.kt    transformation, réglages, composition, zones générées
├── camera/                 Camera2
│   ├── Capabilities.kt     détection des objectifs et capacités réelles
│   ├── CameraEngine.kt     aperçu, session, rafales, JPEG/DNG, mise au point
│   └── ManualSettings.kt   réglages (null = automatique)
├── capture/                styles
│   ├── Style.kt            modèle de style : capture, étapes, exigences
│   ├── StyleCatalog.kt     catalogue des vingt styles (extensible par register())
│   ├── Compatibility.kt    Prêt / Repli / Indisponible selon le matériel
│   └── CaptureOrchestrator.kt  style → réglages par prise → capture
├── processing/             exécution des étapes d'un style
│   ├── Pipeline.kt
│   └── GroupSelection.kt
├── ai/                     modèles
│   ├── Segmentation.kt     ML Kit (personne) et TFLite importé
│   ├── Depth.kt            TFLite importé ou heuristique étiquetée
│   ├── FaceAnalysis.kt     ML Kit : visages, yeux ouverts, sourires
│   └── AiServices.kt       choix automatique du meilleur moteur disponible
├── storage/PhotoLibrary.kt bibliothèque locale, projets, versions, export MediaStore
├── work/                   file persistante des travaux et service de premier plan
├── settings/AppSettings.kt réglages persistants
└── ui/                     interface en vues Android (sans Compose ni AndroidX)
    ├── MainActivity.kt     quatre espaces + superpositions plein écran
    ├── camera/CameraScreen.kt
    ├── studio/StudioScreen.kt
    ├── gallery/GalleryScreen.kt
    └── settings/SettingsScreen.kt
```

**Choix techniques.** Camera2 est utilisé directement pour les contrôles manuels et les rafales (CameraX ne
les expose pas assez finement). L'interface est construite en vues Android pour limiter les dépendances.
Toutes les opérations caméra passent par un thread unique. Les traitements sont bornés en résolution de travail
(1920 px par défaut, 2560 px en qualité haute) pour rester dans la mémoire d'un téléphone.

**Ajouter un style** : une entrée dans `StyleCatalog`, ou un appel à `StyleCatalog.register(...)`. Aucune autre
partie de l'application n'a besoin d'être modifiée tant que les étapes existent déjà.

## Compiler et installer

Prérequis : Android Studio récent, JDK 17, SDK Android 35.

```bash
./gradlew :app:assembleDebug          # APK de développement
./gradlew :app:testDebugUnitTest      # tests unitaires JVM
```

Ou ouvrir le dossier dans Android Studio et lancer l'application sur un téléphone physique : la caméra
et les capacités matérielles ne sont pas émulées de façon fiable.

## Tests

`app/src/test/java` contient 18 tests des algorithmes purs :

- alignement qui retrouve un décalage connu ;
- fusion HDR bornée par ses entrées ; empilement de nuit qui rejette un élément mobile ;
- netteté intégrale qui retient la zone nette de chaque plan ;
- comblement push-pull qui propage la couleur connue ;
- homographies, warp, étalonnage ;
- redressement des verticales inclinées ;
- partition de l'unité des couches, rendu identique sans modification, zones générées lors d'un déplacement ;
- invariants du catalogue (identifiants uniques, vingt styles, familles peuplées, présets cohérents).

## Modèles d'IA optionnels

Sans modèle importé, l'application fonctionne avec les modules embarqués (ML Kit : personnes et visages)
et une profondeur heuristique, signalée comme telle dans l'interface.

Pour une profondeur réelle, importez dans **Paramètres → Modèles d'IA** un fichier `.tflite` de profondeur
monoculaire (entrée 256×256, normalisation ImageNet, sortie de profondeur inverse, de type MiDaS).
Pour la segmentation, un modèle TFLite d'entrée 256×256 et de sortie probabilité sujet est accepté.
Aucun modèle n'est téléchargé automatiquement.

## Limites connues et ce qui n'a pas été vérifié

**Honnêteté sur l'état du projet.** Ce dépôt contient le code source complet, compilé et testé sur les parties
pures. Il n'a **pas été exécuté sur un téléphone**, et l'APK n'a pas été généré dans l'environnement de
développement, faute d'accès au SDK Android. Plus précisément :

- Le code applicatif a été compilé contre les API Android réelles (framework Android 14, via le jar `android-all`),
  avec TensorFlow Lite 2.16.1. Les bibliothèques ML Kit n'étaient pas accessibles : leurs signatures ont été
  vérifiées à l'aide de stubs locaux, pas du code réel.
- Les 18 tests JVM passent. Le comportement caméra (aperçu, orientation, mise au point au toucher, rafales,
  DNG) **n'est pas validé** et doit être testé sur appareil. L'orientation de l'aperçu suppose un capteur à 90°
  et un écran en portrait.
- Les fichiers `build.gradle.kts` et les ressources n'ont pas été évalués par AGP.

**Limites fonctionnelles assumées :**

- Résolution de travail limitée (1920 px par défaut) : les couches et fusions ne travaillent pas à pleine
  résolution du capteur.
- Alignement et panorama **par translation uniquement** : pas de rotation, ni de projection cylindrique. Les
  empilements supposent une prise à main levée à faible déplacement.
- Le comblement des zones révélées est **classique** (push-pull), pas génératif. Les pixels reconstruits sont
  signalés, mais la qualité est limitée sur les grandes zones.
- La segmentation embarquée ne détecte que les personnes ; les autres sujets exigent un modèle importé.
- Sans modèle de profondeur importé, la profondeur est heuristique : les effets de couches sont alors approximatifs.
- Balance des blancs manuelle : approximation par température de couleur, pas un profil colorimétrique calibré.
- Pose longue : réelle seulement dans les limites d'exposition du capteur ; sinon simulée par empilement.
- Redressement des verticales : estimation heuristique, appliquée seulement si des verticales fiables sont détectées.
- Mode avancé : affiche des valeurs numériques complémentaires (pourcentage de pixels reconstruits, profondeur
  moyenne) ; les curseurs affichent déjà leur valeur dans tous les modes.
- La galerie charge les vignettes sans pagination : fluide pour quelques centaines de photos, à optimiser au-delà.
- Les projets multicouches sont conservés (masques PNG, JSON, image de travail) mais ne sont pas exportés dans un
  format de couches standard.

## Licence

À définir par le propriétaire du dépôt.
