# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

## Reproducible Build Support
# Disable obfuscation to ensure deterministic R8 output across different build environments.
# Without this, R8 assigns short names (e.g. `j`, `k`) to renamed classes in a non-deterministic
# order, causing byte-for-byte differences between builds. This is required for F-Droid / IzzyOnDroid
# Reproducible Build verification. Code shrinking (dead code removal) remains fully enabled.
# Since Metrolist is fully open-source, obfuscation provides no meaningful security benefit.
-dontobfuscate

# WebView JavaScript interfaces
-keepclassmembers class com.metrolist.music.utils.potoken.PoTokenWebView {
    @android.webkit.JavascriptInterface public *;
}

# Keep PO-token utility classes
-keep class com.metrolist.music.utils.potoken.** { *; }

# Keep coroutine continuation for WebView callbacks
-keepclassmembers class * {
    void resume(...);
    void resumeWithException(...);
}

## Kotlin Coroutines — Reproducible Build Rules
# Keep volatile fields in coroutine classes to prevent AtomicFieldUpdater optimisation issues
# and ensure R8 does not reorder or merge these across builds.
# Source: https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/jvm/resources/META-INF/proguard/coroutines.pro
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}

# Eliminate coroutines debug-only code paths so R8 sees a single, consistent
# control-flow graph regardless of build machine or JVM configuration.
# Source: https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/jvm/resources/META-INF/proguard/r8-from-1.6.0/coroutines.pro
-assumenosideeffects class kotlinx.coroutines.internal.MainDispatcherLoader {
    boolean FAST_SERVICE_LOADER_ENABLED return false;
}
-assumenosideeffects class kotlinx.coroutines.internal.FastServiceLoaderKt {
    boolean ANDROID_DETECTED return true;
}
-assumenosideeffects class kotlinx.coroutines.DebugKt {
    boolean getASSERTIONS_ENABLED() return false;
    boolean getDEBUG() return false;
    boolean getRECOVER_STACK_TRACES() return false;
}

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line number information for readable crash stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

## Kotlin Serialization
# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclasseswithmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclasseswithmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclasseswithmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-dontwarn javax.servlet.ServletContainerInitializer
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn org.slf4j.impl.StaticLoggerBinder

## Logging (does not affect Timber)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    ## Leave in release builds
    #public static int i(...);
    #public static int w(...);
    #public static int e(...);
}

# Generated automatically by the Android Gradle plugin.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# Keep all classes within the kuromoji package
-keep class com.atilika.kuromoji.** { *; }

## Queue Persistence Rules
# Keep queue-related classes to prevent serialization issues in release builds
-keep class com.metrolist.music.models.PersistQueue { *; }
-keep class com.metrolist.music.models.PersistPlayerState { *; }
-keep class com.metrolist.music.models.QueueData { *; }
-keep class com.metrolist.music.models.QueueType { *; }
-keep class com.metrolist.music.playback.queues.** { *; }

# Keep serialization methods for queue persistence
-keepclassmembers class * implements java.io.Serializable {
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

## UCrop Rules
-dontwarn com.yalantis.ucrop**
-keep class com.yalantis.ucrop** { *; }
-keep interface com.yalantis.ucrop** { *; }

## Google Cast Rules
-keep class com.metrolist.music.cast.** { *; }
-keep class com.google.android.gms.cast.** { *; }
-keep class androidx.mediarouter.** { *; }

## JSoup re2j optional dependency
-dontwarn com.google.re2j.**

# Vibra fingerprint library
-keep class com.metrolist.music.recognition.VibraSignature { *; }
-keepclassmembers class com.metrolist.music.recognition.VibraSignature {
    native <methods>;
}

## Kotlin Reflection Fix
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

## Ktor Serialization
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**

## Listen Together Protobuf
-keep class com.metrolist.music.listentogether.proto.** { *; }
-keepclassmembers class com.metrolist.music.listentogether.proto.** { *; }

## Shazam Models
-keep class com.metrolist.shazamkit.models.** { *; }
-keepclassmembers class com.metrolist.shazamkit.models.** {
    *;
}

## Discord RPC JNI
-keep class com.metrolist.music.discord.DiscordRpcManager { *; }
-keepclassmembers class com.metrolist.music.discord.DiscordRpcManager {
    native <methods>;
}

## Kotlinx Serialization
-keepattributes *Annotation*
-keepclassmembers class com.metrolist.shazamkit.models.** {
    *** Companion;
}
-keepclasseswithmembers class com.metrolist.shazamkit.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}
