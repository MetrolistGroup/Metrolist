package com.metrolist.shared.storage

import coil3.disk.DiskCache
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.XdgUtils
import okio.Path.Companion.toPath
import java.io.File

class DesktopAnimatedArtworkCache internal constructor(
    directory: File,
    maxSizeBytes: Long,
) {
    private val cache = ResizableImageDiskCache(directory.absolutePath.toPath(), maxSizeBytes)
    private var generation = 0L

    @Synchronized
    fun sizeBytes(): Long = cache.size

    @Synchronized
    internal fun generation(): Long = generation

    @Synchronized
    internal fun open(key: String): DiskCache.Snapshot? = cache.openSnapshot(key)

    @Synchronized
    internal fun store(
        key: String,
        file: File,
        startedAt: Long,
    ): DiskCache.Snapshot? {
        if (startedAt != generation || file.length() !in 1..cache.maxSize) return null
        cache.openSnapshot(key)?.let { return it }
        val editor = cache.openEditor(key) ?: return null
        try {
            editor.metadata.toFile().writeBytes(byteArrayOf())
            file.copyTo(editor.data.toFile(), overwrite = true)
        } catch (error: Exception) {
            editor.abort()
            throw error
        }
        return editor.commitAndOpenSnapshot()
    }

    @Synchronized
    fun resize(maxSizeBytes: Long) {
        if (cache.maxSize == maxSizeBytes) return
        generation++
        cache.resize(maxSizeBytes)
    }

    @Synchronized
    fun clear() {
        generation++
        cache.clear()
    }

    @Synchronized
    internal fun shutdown() = cache.shutdown()

    companion object {
        private val instance by lazy {
            val home = File(System.getProperty("user.home"))
            val directory =
                when {
                    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> {
                        File(
                            System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File) ?: File(home, "AppData/Local"),
                            "Metrolist",
                        )
                    }

                    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> {
                        File(home, "Library/Application Support/Metrolist")
                    }

                    else -> {
                        File(XdgUtils.getXdgDataHome(), "metrolist")
                    }
                }
            DesktopAnimatedArtworkCache(File(directory, "animated_artwork_cache"), Settings.shared.animatedArtworkCacheSize * 1024L * 1024)
        }

        fun get(): DesktopAnimatedArtworkCache = instance.also { it.resize(Settings.shared.animatedArtworkCacheSize * 1024L * 1024) }
    }
}
