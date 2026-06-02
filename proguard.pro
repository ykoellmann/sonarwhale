# =============================================================
# Sonarwhale — ProGuard rules
# Run via:  ./gradlew obfuscate
# Output:   build/libs/Sonarwhale-VERSION-obfuscated.jar
#           build/libs/mapping.txt  (keep for crash decoding!)
# =============================================================

# ----- Processing flags --------------------------------------------------
# Shrinking: risky for IntelliJ plugins — classes loaded via reflection would be removed.
# Optimization: requires a complete class hierarchy (incl. JDK) to resolve superclasses;
#               IntelliJ plugins don't ship the JDK, so this always fails.
# Obfuscation only is the correct mode for IntelliJ plugins.

-dontshrink
-dontoptimize

# ----- Attributes --------------------------------------------------------

-keepattributes *Annotation*
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes Exceptions,LineNumberTable,SourceFile

# ----- Plugin entry points (plugin.xml) ----------------------------------
# Instantiated via reflection by the IntelliJ Platform — names must not change.

-keep class com.sonarwhale.license.LicenseService                    { *; }
-keep class com.sonarwhale.service.RunHistoryService                 { *; }
-keep class com.sonarwhale.toolwindow.SonarwhaleToolWindowFactory    { *; }
-keep class com.sonarwhale.SonarwhaleStartupActivity                 { *; }
-keep class com.sonarwhale.settings.SonarwhaleConfigurable           { *; }
-keep class com.sonarwhale.settings.SonarwhaleSourcesConfigurable    { *; }
-keep class com.sonarwhale.actions.OpenInSonarwhaleAction            { *; }

# ----- All @Service classes ----------------------------------------------
# Covers all current and future project/application services.

-keep @com.intellij.openapi.components.Service class * { *; }

# ----- PersistentStateComponent / @State ---------------------------------
# Field names appear literally in sonarwhale.xml — renaming breaks persistence.

-keep @com.intellij.openapi.components.State class * { *; }
-keep class * implements com.intellij.openapi.components.PersistentStateComponent { *; }

# ----- Gson-serialized data models ---------------------------------------
# Field names appear literally in JSON files under .idea/sonarwhale/.
# Renaming them breaks deserialization of saved state.

-keep class com.sonarwhale.model.** {
    <init>(...);
    <fields>;
}

# ----- Rhino JavaScript engine (bundled) ---------------------------------
# Rhino does heavy internal reflection; obfuscating it breaks script execution.

-keep class org.mozilla.javascript.** { *; }

# ----- Gson (bundled) ----------------------------------------------------

-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ----- Kotlin ------------------------------------------------------------

-keep class kotlin.Metadata { *; }

# Data class structural methods (component1..N, copy, equals, hashCode, toString)
-keepclassmembers class ** {
    *** component1();
    *** component2();
    *** component3();
    *** component4();
    *** component5();
    *** copy(...);
    boolean equals(java.lang.Object);
    int hashCode();
    java.lang.String toString();
}

# Enums / sealed classes
-keepclassmembers class * extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Coroutines: volatile fields must survive (internal state machine)
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ----- Suppress noise ----------------------------------------------------
# We don't control these libraries; notes/warnings about them are irrelevant.

-dontnote kotlin.**
-dontnote kotlinx.**
-dontnote org.mozilla.javascript.**
-dontnote com.google.gson.**
-dontnote com.intellij.**
-dontnote org.jetbrains.**

-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn com.intellij.**
-dontwarn org.jetbrains.**
-dontwarn javax.**
-dontwarn java.**
-dontwarn sun.**
-dontwarn com.sun.**
-dontwarn com.google.errorprone.**

-dontnote com.google.errorprone.**
-dontnote com.sun.**
-dontnote javax.activation.**

# The IntelliJ platform classpath is intentionally incomplete (optional deps, runtime-only JARs).
# Remaining unresolvable references after the dontwarn rules above are safe to ignore.
-ignorewarnings

# ----- Resource adaptation -----------------------------------------------
# Updates fully-qualified class names inside XML resources after renaming.

-adaptresourcefilecontents **.xml
-keepdirectories

# ----- Known ProGuard + IntelliJ pitfall ---------------------------------
# -mergeinterfacesaggressively causes false positives in Plugin Verifier.
# Do NOT add it.
