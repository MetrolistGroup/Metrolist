package com.metrolist.shared.settings

import android.content.Context
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import com.metrolist.shared.constants.AiSlopBlocklistCacheKey
import com.russhwolf.settings.Settings
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal class AndroidFileSettings(
    context: Context,
    private val delegate: Settings,
) : Settings by delegate,
    FlushableSettings,
    BatchSettings {
    private val files =
        largeSettingKeys.associateWith { key ->
            val root = if (key == AiSlopBlocklistCacheKey) context.cacheDir else context.noBackupFilesDir
            AtomicFile(File(root, "settings-blobs/$key"))
        }
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "settings-blobs").apply { isDaemon = true } }
    private val writeFailures = mutableMapOf<String, Exception>()

    init {
        worker.submit {
            for ((key, file) in files) {
                try {
                    migrateLargeSetting(delegate, key, file.exists()) { write(file, it) }
                } catch (error: Exception) {
                    Log.e("Settings", "Could not migrate blob $key", error)
                }
            }
        }
    }

    private fun <T> read(block: () -> T): T = worker.submit(Callable(block)).get()

    private fun mutate(
        key: String,
        block: () -> Unit,
    ) {
        val future =
            worker.submit {
                try {
                    block()
                    writeFailures.remove(key)
                } catch (error: Exception) {
                    writeFailures[key] = error
                    Log.e("Settings", "Could not persist settings blob", error)
                    throw error
                }
            }
        if (Looper.myLooper() != Looper.getMainLooper()) future.get()
    }

    override fun flush() {
        try {
            read { writeFailures.values.firstOrNull()?.let { throw it } }
        } finally {
            (delegate as? FlushableSettings)?.flush()
        }
    }

    override fun applyBatch(
        entries: List<PortableSetting>,
        removedKeys: Set<String>,
    ) {
        val (special, ordinary) = entries.partition { it.key in largeSettingKeys }
        removedKeys.filter { it in largeSettingKeys }.forEach { key -> mutate(key) { files.getValue(key).delete() } }
        special.forEach { entry -> mutate(entry.key) { write(files.getValue(entry.key), entry.value) } }
        delegate.applyBatch(ordinary, removedKeys + special.map { it.key })
    }

    override val keys: Set<String>
        get() = read { delegate.keys + files.filterValues { it.exists() }.keys }

    override val size: Int get() = keys.size

    override fun hasKey(key: String): Boolean =
        files[key]?.let { file -> read { file.exists() || delegate.hasKey(key) } } ?: delegate.hasKey(key)

    override fun getString(
        key: String,
        defaultValue: String,
    ): String = getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? {
        val file = files[key] ?: return delegate.getStringOrNull(key)
        return read { if (file.exists()) file.openRead().bufferedReader().use { it.readText() } else delegate.getStringOrNull(key) }
    }

    override fun putString(
        key: String,
        value: String,
    ) {
        val file = files[key]
        if (file == null) {
            delegate.putString(key, value)
        } else {
            mutate(key) {
                write(file, value)
                if (delegate.hasKey(key)) delegate.remove(key)
            }
        }
    }

    override fun remove(key: String) {
        val file = files[key]
        if (file == null) {
            delegate.remove(key)
        } else {
            mutate(key) {
                if (delegate.hasKey(key)) delegate.remove(key)
                file.delete()
            }
        }
    }

    override fun clear() {
        mutate("clear") {
            delegate.clear()
            files.values.forEach(AtomicFile::delete)
            writeFailures.clear()
        }
    }

    private fun AtomicFile.exists(): Boolean =
        try {
            openRead().use { }
            true
        } catch (_: FileNotFoundException) {
            false
        }

    private fun write(
        file: AtomicFile,
        value: String,
    ) {
        val output = file.startWrite()
        try {
            output.write(value.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}
