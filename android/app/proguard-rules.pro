# Модели протокола и каталога десериализуются через kotlinx.serialization:
# сгенерированные сериализаторы и их Companion должны пережить R8.
-keepclassmembers class dev.hardwaredisplay.protocol.** {
    *** Companion;
}
-keepclasseswithmembers class dev.hardwaredisplay.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room генерирует реализации DAO и базы по именам классов.
-keep class dev.hardwaredisplay.data.**_Impl { *; }
-keep class dev.hardwaredisplay.data.**_Impl$* { *; }

# ML Kit загружает модель распознавания по имени класса.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
