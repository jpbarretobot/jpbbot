# Règles ProGuard / R8 pour Studio Caméra.

# TensorFlow Lite : les classes JNI doivent conserver leurs noms.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ML Kit fournit ses propres règles consommateur ; on ignore les avertissements optionnels.
-dontwarn com.google.mlkit.**
