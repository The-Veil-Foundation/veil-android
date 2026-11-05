# R8 для release. AAR ядер уже приносит keep-правила для go/xray/libbox,
# здесь — то, что нужно самому приложению.

# Ядра gomobile ищутся по именам: Class.forName + getMethod.
-keep class go.** { *; }
-keep class xray.** { *; }
-keep class libbox.** { *; }

# Мосты приложения к ядрам тоже вызываются через рефлексию
# (singbox.Singbox ищется по строке из SingboxBridge).
-keep class singbox.Singbox { *; }
-keep class singbox.Singbox$* { *; }
-keep class dev.local.veil.platform.XrayBridge { *; }
-keep class dev.local.veil.platform.SingboxBridge { *; }

# kotlinx.serialization: сгенерированные сериализаторы и Companion.
# (Библиотека сама поставляет consumer-правила, это страховка для наших моделей.)
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class dev.local.veil.**$$serializer { *; }
-keepclassmembers class dev.local.veil.** { *** Companion; }
-keepclasseswithmembers class dev.local.veil.** {
    kotlinx.serialization.KSerializer serializer(...);
}
