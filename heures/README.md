# Heures

Application Android pour noter ses heures de travail, avec un widget pour l'écran d'accueil.

## Fonctionnalités

- **GO** : démarre un travail (l'heure de début est enregistrée).
- **FIN** : termine le travail en cours et enregistre la session.
- **Ajouter manuellement** : choisir la date, l'heure de début et l'heure de fin (utile si on a oublié GO ou FIN). Si la fin est avant le début, la session est considérée comme passant minuit.
- **Calculer les heures** : affiche les sessions d'un mois, avec le total en `Xh YYmin` et en décimal. Naviguer entre les mois avec `‹` et `›`. Un appui long sur une entrée la supprime.
- **Widget** : affiche l'état en cours et le total du mois, avec les boutons GO et FIN.

Les données sont stockées localement sur le téléphone (SharedPreferences).

## Compilation

Prérequis : JDK 17 et Android SDK (API 34).

```bash
cd heures
./gradlew assembleDebug        # APK dans app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # tests unitaires de la logique de calcul
```

Installation sur le téléphone : copier l'APK, ou `adb install app/build/outputs/apk/debug/app-debug.apk`.

Pour ajouter le widget : appui long sur l'écran d'accueil, puis Widgets, puis « Heures ».
