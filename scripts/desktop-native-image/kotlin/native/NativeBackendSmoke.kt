package com.metrolist.desktop.nativeimage

import com.metrolist.desktop.window.jna.User32Extend
import com.metrolist.desktop.window.jna.structure.MENUITEMINFO
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Ole32
import kotlinx.serialization.serializer
import kotlinx.serialization.serializerOrNull
import org.bytedeco.ffmpeg.global.avutil
import org.jetbrains.skia.FontMgr
import java.awt.GraphicsEnvironment
import javax.sound.sampled.AudioSystem
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.typeOf

// Avoid createType(), which would probe unrelated kotlin-reflect members of each model.
private class SerializationProbeType(
    override val classifier: KClass<*>,
    arity: Int,
) : KType {
    override val annotations: List<Annotation> = emptyList()
    override val isMarkedNullable = false
    override val arguments = List(arity) { KTypeProjection.invariant(typeOf<String>()) }
}

private fun serializationSmoke() {
    val manifest =
        checkNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream("metrolist-serialization-types.tsv"),
        )
    val types = manifest.bufferedReader().use { it.readLines() }
    check(types.isNotEmpty())
    for (line in types) {
        val (name, arity) = line.split('\t')
        try {
            val type = SerializationProbeType(Class.forName(name).kotlin, arity.toInt())
            checkNotNull(serializerOrNull(type)) { "No serializer for $name" }
            serializer(type).descriptor
        } catch (error: Throwable) {
            throw IllegalStateException("Native serializer lookup failed for $name", error)
        }
    }
    println("[NativeBackendSmoke] serializers passed: ${types.size} types")
}

internal fun nativeBackendSmoke() {
    serializationSmoke()
    check(GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.isNotEmpty())
    check(FontMgr.default.familiesCount > 0)
    AudioSystem.getMixerInfo()
    check(avutil.av_version_info().string.isNotBlank())
    if (System.getProperty("os.name").startsWith("Windows")) {
        checkNotNull(User32Extend.instance).GetSystemMetrics(0)
        check(MENUITEMINFO().size() > 0)
        val result = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED)
        COMUtils.checkRC(result)
        Ole32.INSTANCE.CoUninitialize()
    }
    println("[NativeBackendSmoke] passed")
}
