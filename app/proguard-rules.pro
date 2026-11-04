# kotlinx.serialization keeps its own generated serializers; keep annotations readable.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Wireless ADB and Shizuku installers.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class android.sun.security.** { *; }
-keep class org.conscrypt.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn io.github.muntashirakon.**
-dontwarn com.github.MuntashirAkon.**
-dontwarn org.conscrypt.**
-dontwarn android.sun.**
