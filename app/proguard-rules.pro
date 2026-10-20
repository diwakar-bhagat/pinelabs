# Pine SDK AAR ships its own consumer rules (JNA/UniFFI). Keep our serializable DTOs.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.bohemian.odoo.pos.**$$serializer { *; }
-keepclassmembers class com.bohemian.odoo.pos.** { *** Companion; }
-keepclasseswithmembers class com.bohemian.odoo.pos.** { kotlinx.serialization.KSerializer serializer(...); }
# Strip verbose logs from release builds (HARD RULE 11)
-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); }

# Tink / errorprone annotations
-dontwarn com.google.errorprone.annotations.**

