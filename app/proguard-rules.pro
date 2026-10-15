# =============================================================================
# xyzterm R8 rules
#
# The previous version of this file ended with `-dontobfuscate` and carried a
# blanket `-keep` for java.io.**, kotlin.**, kotlinx.**, okio.** and four
# com.rk packages. Between them they kept essentially the whole program, so
# `isMinifyEnabled = true` was doing almost nothing: a large APK, longer install,
# and more dex for ART to verify on every launch.
#
# There is no name-based reflection anywhere in this codebase (no Class.forName,
# no getDeclaredConstructor, no service-loader plugin system), so nothing needs
# to keep its original class or member names for the framework to find it.
# Manifest-referenced components are kept automatically by AGP's generated
# aapt_rules.txt.
# =============================================================================

# --- Gson -------------------------------------------------------------------
# Settings are round-tripped through Gson, which reflects over fields.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.Serializable <fields>;
}
-dontwarn com.google.gson.**
-dontwarn sun.misc.**
# Gson instantiates its adapters reflectively.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Kotlin serialization ---------------------------------------------------
# Theme and icon-pack archives carry serialized classes.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.rk.**$$serializer { *; }
-keepclassmembers class com.rk.** {
    *** Companion;
}
-keepclasseswithmembers class com.rk.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Kotlin reflection ------------------------------------------------------
# Preference.preferenceTypes() walks Settings' declared properties and reads each
# delegate. The delegate fields are synthetic, so keep them by class.
-keep class com.rk.settings.CachedPreference { *; }
-keep class com.rk.settings.CachedExtensionPreference { *; }
-keep class com.rk.settings.Settings { *; }

# --- Third-party with its own requirements ---------------------------------
# ec4j-core (JGit): optional classes the plugin loader never touched.
-dontwarn org.ec4j.core.**
# ANRWatchDog: registers a watchdog thread reflectively by name.
-keep class com.github.anrwatchdog.** { *; }
-dontwarn com.github.anrwatchdog.**
# semver: parsed types resolved by name.
-keep class io.github.z4kn4fein.semver.** { *; }
-dontwarn io.github.z4kn4fein.semver.**
# AndroidSVG: DOM element names are looked up as strings.
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**
# Coil: uses a few internal utilities by name.
-keep class coil.util.** { *; }

# --- Compose ----------------------------------------------------------------
# Fix for AbstractMethodError: "double DoubleFunction.invoke(double)". Keep the
# interface shape, but allow the method bodies to still be optimised.
-keep,allowoptimization interface androidx.compose.ui.graphics.colorspace.DoubleFunction { *; }
-keep,allowoptimization class androidx.compose.ui.graphics.colorspace.TransferParameters { *; }
-keep,allowoptimization class androidx.compose.ui.graphics.colorspace.Rgb { *; }

# --- Noise ------------------------------------------------------------------
# The rootfs extraction and backup shells invoke JVM-adjacent helpers; these are
# references the shrinker cannot resolve. They cost nothing in APK size.
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn javax.script.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.servlet.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.**
-dontwarn sun.security.x509.X509Key

# --- Native -----------------------------------------------------------------
# proot's tracer re-execs a loader that this app writes into its data dir, not a
# packaged class. Nothing to keep, but never let R8 touch the .so payloads.
-keepclasseswithmembernames class * {
    native <methods>;
}