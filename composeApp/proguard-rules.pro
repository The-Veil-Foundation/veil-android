-keep class go.** { *; }
-keep class xray.** { *; }
-keep class libbox.** { *; }

-keep class singbox.Singbox { *; }
-keep class singbox.Singbox$* { *; }
-keep class dev.local.veil.platform.XrayBridge { *; }
-keep class dev.local.veil.platform.SingboxBridge { *; }

-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class dev.local.veil.**$$serializer { *; }
-keepclassmembers class dev.local.veil.** { *** Companion; }
-keepclasseswithmembers class dev.local.veil.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Shizuku loads the UserService by class name and its Context constructor.
-keep class dev.local.veil.sharing.TetheringService { *; }
-keep class dev.local.veil.sharing.ITetheringService$* { *; }
