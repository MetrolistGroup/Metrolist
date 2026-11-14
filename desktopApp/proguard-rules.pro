-keep,includedescriptorclasses class com.metrolist.** { *; }
-keep,includedescriptorclasses class dev.nyx.** { *; }
-keep,includedescriptorclasses class meow.nyx.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

# JNI and reflection-backed libraries.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
#noinspection ShrinkerUnresolvedReference
-keep class * implements com.sun.jna.Callback { *; }
-keep class * implements com.sun.jna.Structure { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class io.github.kdroidfilter.** { *; }
-keep class dev.nucleusframework.** { *; }
-keep interface io.github.kdroidfilter.** { *; }
-keep class uniffi.** { *; }
-keep interface uniffi.** { *; }
-keep class io.sentry.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class org.bytedeco.** { *; }
# FileKit opens Linux portal file dialogs through dbus-java, which is reflection-backed.
-keep class org.freedesktop.dbus.** { *; }
-keep class org.sqlite.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-keep interface org.jetbrains.skiko.** { *; }
-keep class androidx.compose.ui.awt.** { *; }
# EnumMap reflects this method when Kuromoji initializes its dictionary map.
-keepclassmembers enum com.atilika.kuromoji.viterbi.ViterbiNode$Type {
    public static **[] values();
}

# JmDNS uses EnumMap for DNS qualified-name fields.
-keepclassmembers enum javax.jmdns.ServiceInfo$Fields {
    public static **[] values();
}

# ICU4J builds EnumMaps and resolves units by name while loading relative date data.
-keepclassmembers enum com.ibm.icu.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Compose internals used by the custom window hit-test bridge.
-keep class androidx.compose.ui.node.HitTestResult { *; }
-keep class androidx.compose.ui.node.PointerInputModifierNode { *; }
-keep class androidx.compose.ui.scene.CanvasLayersComposeSceneImpl { *; }
-keep class androidx.compose.ui.scene.CanvasLayersComposeSceneImpl$AttachedComposeSceneLayer { *; }
-keep class androidx.compose.ui.scene.CopiedList { *; }
-keep class androidx.compose.ui.scene.PlatformLayersComposeSceneImpl { *; }

# ServiceLoader providers.
-keep class coil3.network.okhttp.internal.OkHttpNetworkFetcherServiceLoaderTarget { *; }
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keep class org.freedesktop.dbus.transport.jre.NativeTransportProvider { *; }
-keep class org.slf4j.simple.SimpleServiceProvider { *; }

# Ktor's optional named-module, receive(KClass), and development reload helpers.
# LocalDeviceServer uses direct modules, explicit serializers, and developmentMode=false.
# See docs/kotlin-reflect.md.
-dontwarn kotlin.reflect.full.KCallables
-dontwarn kotlin.reflect.full.KClasses
-dontwarn kotlin.reflect.full.KClassifiers
-dontwarn kotlin.reflect.jvm.ReflectJvmMapping

# Optional integrations absent from the runtime.
-dontwarn io.github.g00fy2.versioncompare.**
-dontwarn okhttp3.internal.graal.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bytedeco.javacpp.**
-dontwarn io.github.kdroidfilter.**
-dontwarn uniffi.**
-dontwarn com.charleskorn.kaml.**

-keepattributes Exceptions,InnerClasses,Signature,EnclosingMethod,*Annotation*
