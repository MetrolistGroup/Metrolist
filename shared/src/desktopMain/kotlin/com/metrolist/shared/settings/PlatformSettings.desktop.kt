/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.settings

import com.metrolist.shared.constants.LyricsOpenRouterOAuthKey
import com.metrolist.shared.constants.LyricsOpenRouterOAuthSessionKey
import com.metrolist.shared.utils.LogBridge
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.XdgUtils
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.sun.jna.platform.win32.Crypt32Util
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.russhwolf.settings.Settings as KmpSettings

private const val APP_DIR_NAME = "metrolist"
private const val SETTINGS_FILE_NAME = "settings.properties"
private const val SECRETS_FILE_NAME = "secrets.properties"
private const val WINDOWS_SECRET_ENTROPY = "MetrolistDesktopSecretsV1"
private const val MAC_KEYCHAIN_KEY_SERVICE = "com.metrolist.desktop.encryption-key-v1"
private const val MAC_KEYCHAIN_KEY_ACCOUNT = "desktop-secrets"
private const val MAC_LEGACY_KEYCHAIN_SERVICE = "com.metrolist.desktop"
private const val MAC_SECRET_KEY_BYTES = 32
private const val MAC_GCM_NONCE_BYTES = 12
private val MAC_ENCRYPTED_FILE_MAGIC = byteArrayOf(0x4d, 0x4c, 0x53, 0x4b, 0x31)
private const val LEGACY_PREFERENCES_MIGRATED = "desktopLegacyPreferencesMigrated"
private const val LEGACY_SECRET_CLEANUP_V2 = "desktopLegacySecretCleanupV2"
private const val AUTO_CACHE_DEFAULT_MIGRATION = "desktopAutoCacheDefaultDisabledV1"

private val secretKeys =
    setOf(
        "innerTubeCookie",
        "visitorData",
        "dataSyncId",
        "innerTubeAuthUser",
        "lastFmSession",
        "spotifySession",
        "listenBrainzToken",
        "lyricsAiApiKey",
        "lyricsAiApiKeys",
        LyricsOpenRouterOAuthKey,
        LyricsOpenRouterOAuthSessionKey,
        "lyricsDeepLApiKey",
        "accountName",
        "accountEmail",
        "accountChannelHandle",
        "accountThumbnail",
        "discordAccessToken",
        "discordRefreshToken",
        "listenTogetherDesktopSession",
        "deviceConnectPairedDevices",
        "listenTogetherPairedRoomCode",
        "listenTogetherAutoJoinPairedRoom",
    )

private val settingsWriter =
    Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "DesktopSettingsWriter").apply { isDaemon = true }
    }

@Volatile
private var desktopSettings: DesktopFileSettings? = null

fun flushDesktopSettings() {
    desktopSettings?.flush()
}

internal actual fun defaultKmpSettings(): KmpSettings {
    val configDir =
        File(XdgUtils.getXdgConfigHome(), APP_DIR_NAME).apply {
            mkdirs()
            restrictToOwner(this, true)
        }
    val stateDir =
        File(XdgUtils.getXdgStateHome(), APP_DIR_NAME).apply {
            mkdirs()
            restrictToOwner(this, true)
        }

    val settingsFile = File(configDir, SETTINGS_FILE_NAME)
    val secretsFile = File(stateDir, SECRETS_FILE_NAME)

    ensureFileExists(settingsFile)

    val publicProperties = loadPublicProperties(settingsFile)
    val secretStore =
        withStartupCredentialDeadline {
            when {
                OsUtil.isWindows -> {
                    WindowsEncryptedSecretStore(secretsFile)
                }

                OsUtil.isLinux -> {
                    LinuxSecretServiceStore(secretsFile)
                }

                OsUtil.isMac -> {
                    runCatching { MacOsKeychainSecretStore(secretsFile) }
                        .getOrElse { UnavailableSecretStore(secretsFile, it) { MacOsKeychainSecretStore(secretsFile) } }
                }

                else -> {
                    error("No secure desktop secret store is available on this platform")
                }
            }
        }

    if (!secretStore.recoveryPending && canReplaceSettingsFile(settingsFile) && canReplaceSettingsFile(secretsFile)) {
        migratePublicSecrets(publicProperties, secretStore, settingsFile)
        migrateLegacyPreferences(publicProperties, secretStore, settingsFile)
    }

    return DesktopFileSettings(
        settingsFile = settingsFile,
        publicProperties = publicProperties,
        secretStore = secretStore,
    ).also { settings ->
        desktopSettings = settings
        Runtime.getRuntime().addShutdownHook(Thread({ settings.flush() }, "DesktopSettingsFlush"))
    }
}

fun recoverDesktopCredentials(): Boolean? =
    desktopSettings?.recoverSecrets { Settings.shared.finishPendingSecretMigrations() }.also { flushDesktopCredentialWarnings() }

private val pendingCredentialWarnings = ConcurrentLinkedQueue<String>()

fun flushDesktopCredentialWarnings() {
    if (LogBridge.sink == null) return
    while (true) Logger.w("DesktopCredentials", pendingCredentialWarnings.poll() ?: break)
}

private fun credentialWarning(message: String) {
    if (LogBridge.sink == null) {
        pendingCredentialWarnings.offer(message)
        System.err.println(message)
    } else {
        Logger.w("DesktopCredentials", message)
    }
}

fun refreshRecoveredDesktopCredentials() {
    Settings.shared.refreshStoredValues()
}

internal fun migratePublicSecrets(
    publicProperties: Properties,
    secretStore: SecretStore,
    settingsFile: File,
) {
    val keysToMigrate = secretKeys.filter { publicProperties.containsKey(it) }
    if (keysToMigrate.isEmpty()) return

    var changed = false
    for (key in keysToMigrate) {
        val value = publicProperties.getProperty(key) ?: continue
        if (!secretStore.hasKey(key)) {
            secretStore.put(key, value)
            changed = true
        }
    }
    if (changed) secretStore.flush()

    var removedPublic = false
    for (key in keysToMigrate) {
        val value = publicProperties.getProperty(key)
        if (value != null) {
            check(secretStore.hasKey(key)) {
                "Secret key '$key' was removed from public settings but is absent from the secure store after migration"
            }
        }
        publicProperties.remove(key)
        removedPublic = true
    }
    if (removedPublic) atomicStore(settingsFile, publicProperties, "Metrolist desktop settings")
}

internal class DesktopFileSettings(
    private val settingsFile: File,
    private val publicProperties: Properties,
    private var secretStore: SecretStore,
) : KmpSettings,
    BatchSettings,
    FlushableSettings,
    DeferredSecretMigrationSettings {
    override val secretRecoveryPending: Boolean
        get() = secretStore.recoveryPending
    private var pendingWrite: ScheduledFuture<*>? = null
    private var publicRevision = 0L
    private var persistedPublicRevision = 0L
    private val secretWriteLock = Any()
    private val secretValues = secretStore.keys().associateWith { requireNotNull(secretStore.get(it)) }.toMutableMap()

    private val pendingBlobs = linkedMapOf<String, String?>()
    private val blobFiles =
        (
            largeSettingKeys +
                setOf(
                    "likedSongsJson",
                    "animatedCoverCacheJson",
                    "musicVideoCacheJson",
                    "appleMusicArtistCacheJson",
                )
        ).associateWith { File(settingsFile.parentFile, "settings-blobs/$it") }

    init {
        var migrated = false
        for ((key, file) in blobFiles) {
            val legacy = publicProperties.getProperty(key) ?: continue
            try {
                if (!file.exists()) {
                    check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
                    atomicWrite(file, legacy.toByteArray(StandardCharsets.UTF_8))
                }
                check(file.isFile)
                publicProperties.remove(key)
                migrated = true
            } catch (error: Exception) {
                System.err.println("Failed to migrate desktop settings blob $key: ${error.message}")
            }
        }
        if (migrated) flushPublic()
    }

    @get:Synchronized
    override val keys: Set<String>
        get() =
            (publicProperties.stringPropertyNames() + secretValues.keys + blobFiles.filterValues(File::exists).keys + pendingBlobs.keys)
                .filterTo(mutableSetOf()) { it !in pendingBlobs || pendingBlobs[it] != null }

    @get:Synchronized
    override val size: Int
        get() = keys.size

    override fun clear() {
        updateSecrets { clear() }
        synchronized(this) {
            blobFiles.keys.forEach { pendingBlobs[it] = null }
            publicProperties.clear()
            flushPublic()
        }
    }

    override fun remove(key: String) {
        if (key in secretKeys) {
            updateSecrets { remove(key) }
        } else {
            synchronized(this) {
                if (key in blobFiles) pendingBlobs[key] = null
                publicProperties.remove(key)
                flushPublic()
            }
        }
    }

    @Synchronized override fun hasKey(key: String): Boolean =
        if (key in pendingBlobs) pendingBlobs[key] != null else blobFiles[key]?.exists() == true || targetProperties(key).containsKey(key)

    override fun putInt(
        key: String,
        value: Int,
    ) = putString(key, value.toString())

    override fun getInt(
        key: String,
        defaultValue: Int,
    ): Int = getString(key, defaultValue.toString()).toIntOrNull() ?: defaultValue

    override fun getIntOrNull(key: String): Int? = getStringOrNull(key)?.toIntOrNull()

    override fun putLong(
        key: String,
        value: Long,
    ) = putString(key, value.toString())

    override fun getLong(
        key: String,
        defaultValue: Long,
    ): Long = getString(key, defaultValue.toString()).toLongOrNull() ?: defaultValue

    override fun getLongOrNull(key: String): Long? = getStringOrNull(key)?.toLongOrNull()

    override fun putString(
        key: String,
        value: String,
    ) {
        if (key in secretKeys) {
            updateSecrets { put(key, value) }
        } else {
            synchronized(this) {
                if (key in blobFiles) {
                    if (key in pendingBlobs && pendingBlobs[key] == value) return
                    pendingBlobs[key] = value
                    if (publicProperties.remove(key) != null) publicRevision++
                    scheduleWrite()
                    return
                }
                if (publicProperties[key] == value) return
                publicProperties[key] = value
                flushPublic()
            }
        }
    }

    private fun updateSecrets(change: MutableMap<String, String>.() -> Unit) {
        synchronized(secretWriteLock) {
            if (secretStore is LinuxSecretServiceStore) {
                val linux = secretStore as LinuxSecretServiceStore
                linux.update(change)
                synchronized(this) {
                    secretValues.clear()
                    secretValues.putAll(linux.snapshot())
                }
                return
            }
            val target = secretStore.asMutableMap()
            val previous = synchronized(this) { secretValues.toMap() }
            try {
                target.change()
                if (target.toMap() == previous) return
                secretStore.flush()
            } catch (error: Throwable) {
                target.clear()
                target.putAll(previous)
                throw error
            }
            synchronized(this) {
                secretValues.clear()
                secretValues.putAll(target)
            }
        }
    }

    internal fun recoverSecrets(afterRecovery: () -> Unit = {}): Boolean? {
        val linux = secretStore as? LinuxSecretServiceStore
        if (linux != null) {
            if (!linux.isPending && !linux.needsSync) return null
            val wasPending = linux.isPending
            val synced = linux.recover()
            if (!synced && (!wasPending || linux.isPending)) return false
            synchronized(secretWriteLock) {
                synchronized(this) {
                    secretValues.clear()
                    secretValues.putAll(linux.snapshot())
                }
            }
            if (!wasPending) return null
        }
        synchronized(secretWriteLock) {
            if (linux == null && !secretStore.recoveryPending) return null
            val recovered =
                when (val current = secretStore) {
                    is LinuxSecretServiceStore -> current
                    is UnavailableSecretStore -> current.recover() ?: return false
                    else -> return null
                }
            secretStore = recovered
            runCatching {
                migratePublicSecrets(publicProperties, recovered, settingsFile)
                migrateLegacyPreferences(publicProperties, recovered, settingsFile)
            }.onFailure { System.err.println("Failed to finish desktop credential migration: ${it.javaClass.simpleName}") }
            synchronized(this) {
                secretValues.clear()
                secretValues.putAll(recovered.asMutableMap())
            }
        }
        runCatching(afterRecovery)
            .onFailure { System.err.println("Failed to finish desktop settings migration: ${it.javaClass.simpleName}") }
        return true
    }

    override fun getString(
        key: String,
        defaultValue: String,
    ): String = getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? {
        val file =
            synchronized(this) {
                if (key in pendingBlobs) return pendingBlobs[key]
                val file = blobFiles[key] ?: return targetProperties(key)[key]
                if (!file.exists() && publicProperties.containsKey(key)) return publicProperties.getProperty(key)
                file
            }
        return if (Files.notExists(file.toPath())) null else file.readText(StandardCharsets.UTF_8)
    }

    override fun applyBatch(
        entries: List<PortableSetting>,
        removedKeys: Set<String>,
    ) {
        updateSecrets {
            removedKeys.filter { it in secretKeys }.forEach(::remove)
            entries.filter { it.key in secretKeys }.forEach { put(it.key, it.value) }
        }
        synchronized(this) {
            removedKeys.filterNot { it in secretKeys }.forEach(::remove)
            entries.filterNot { it.key in secretKeys }.forEach { putString(it.key, it.value) }
        }
    }

    override fun putFloat(
        key: String,
        value: Float,
    ) = putString(key, value.toString())

    override fun getFloat(
        key: String,
        defaultValue: Float,
    ): Float = getString(key, defaultValue.toString()).toFloatOrNull() ?: defaultValue

    override fun getFloatOrNull(key: String): Float? = getStringOrNull(key)?.toFloatOrNull()

    override fun putDouble(
        key: String,
        value: Double,
    ) = putString(key, value.toString())

    override fun getDouble(
        key: String,
        defaultValue: Double,
    ): Double = getString(key, defaultValue.toString()).toDoubleOrNull() ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? = getStringOrNull(key)?.toDoubleOrNull()

    override fun putBoolean(
        key: String,
        value: Boolean,
    ) = putString(key, value.toString())

    override fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean = getString(key, defaultValue.toString()).toBooleanStrictOrNull() ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? = getStringOrNull(key)?.toBooleanStrictOrNull()

    private fun flushPublic() {
        publicRevision++
        scheduleWrite()
    }

    private fun scheduleWrite() {
        if (pendingWrite != null) return
        pendingWrite =
            settingsWriter.schedule({
                synchronized(this) { pendingWrite = null }
                try {
                    persistPending()
                } catch (e: Exception) {
                    System.err.println("Failed to persist desktop settings: ${e.message}")
                }
            }, 250, TimeUnit.MILLISECONDS)
    }

    override fun flush() {
        synchronized(secretWriteLock) {
            val write =
                synchronized(this) {
                    pendingWrite?.cancel(false)
                    pendingWrite = null
                    settingsWriter.submit { persistPending() }
                }
            write.get()
        }
    }

    private fun persistPending() {
        val blobs = synchronized(this) { pendingBlobs.toMap() }
        for ((key, value) in blobs.entries.sortedBy { if (it.key == "persistentPlaybackPosition") 1 else 0 }) {
            val file = blobFiles.getValue(key)
            if (value == null) Files.deleteIfExists(file.toPath()) else atomicWrite(file, value.toByteArray(StandardCharsets.UTF_8))
            synchronized(this) {
                if (pendingBlobs.containsKey(key) && pendingBlobs[key] == value) pendingBlobs.remove(key)
            }
        }
        persistPublic()
    }

    private fun persistPublic() {
        val (snapshot, revision) =
            synchronized(this) {
                if (publicRevision == persistedPublicRevision) return
                (publicProperties.clone() as Properties) to publicRevision
            }
        atomicStore(settingsFile, snapshot, "Metrolist desktop settings", deferAccessDenied = true)
        synchronized(this) { persistedPublicRevision = revision }
    }

    private fun targetProperties(key: String): MutableMap<String, String> =
        if (key in secretKeys) {
            secretValues
        } else {
            publicProperties.asMutableStringMap()
        }
}

private fun migrateLegacyPreferences(
    publicProperties: Properties,
    secretStore: SecretStore,
    settingsFile: File,
) {
    val root = Preferences.userRoot()
    val legacyNodes =
        listOf(
            root,
            root.node("/com/metrolist/composeApp/state"),
            root.node("/com/metrolist/desktop/state"),
        )
    val legacySecretAliases =
        mapOf(
            "innerTubeCookie" to listOf("innerTubeCookie", "COOKIES"),
            "visitorData" to listOf("visitorData", "VISITOR_DATA"),
            "dataSyncId" to listOf("dataSyncId", "DATASYNC_ID"),
            "innerTubeAuthUser" to listOf("innerTubeAuthUser"),
            "lastFmSession" to listOf("lastFmSession", "LASTFM_SESSION"),
            "accountName" to listOf("accountName"),
            "accountEmail" to listOf("accountEmail"),
            "accountChannelHandle" to listOf("accountChannelHandle"),
            "accountThumbnail" to listOf("accountThumbnail", "PFP_URL"),
        )

    if (!publicProperties.getProperty(LEGACY_SECRET_CLEANUP_V2).toBoolean()) {
        var migratedSecret = false
        for ((targetKey, aliases) in legacySecretAliases) {
            if (!secretStore.hasKey(targetKey)) {
                val value =
                    legacyNodes.firstNotNullOfOrNull { node ->
                        aliases.firstNotNullOfOrNull { alias -> runCatching { node.get(alias, null) }.getOrNull() }
                    }
                if (!value.isNullOrEmpty()) {
                    secretStore.put(targetKey, value)
                    migratedSecret = true
                }
            }
        }
        if (migratedSecret) secretStore.flush()

        for (node in legacyNodes) {
            for (alias in legacySecretAliases.values.flatten()) {
                runCatching { node.remove(alias) }
            }
            run { node.flush() }
        }
        publicProperties.setProperty(LEGACY_SECRET_CLEANUP_V2, true.toString())
    }

    if (!publicProperties.getProperty(LEGACY_PREFERENCES_MIGRATED).toBoolean()) {
        val publicKeys = listOf("themeMode", "appLayout", "enableSystemWindowFrame", "enableHapticFeedback")
        for (key in publicKeys) {
            if (publicProperties.containsKey(key)) continue
            val value = legacyNodes.firstNotNullOfOrNull { node -> runCatching { node.get(key, null) }.getOrNull() }
            if (!value.isNullOrEmpty()) publicProperties.setProperty(key, value)
        }
        publicProperties.setProperty(LEGACY_PREFERENCES_MIGRATED, true.toString())
    }

    if (!publicProperties.getProperty(AUTO_CACHE_DEFAULT_MIGRATION).toBoolean()) {
        publicProperties.setProperty("songCacheEnabled", false.toString())
        publicProperties.setProperty(AUTO_CACHE_DEFAULT_MIGRATION, true.toString())
    }

    atomicStore(settingsFile, publicProperties, "Metrolist desktop settings", deferAccessDenied = true)
}

internal interface SecretStore {
    fun get(key: String): String?

    fun put(
        key: String,
        value: String,
    )

    fun remove(key: String)

    fun hasKey(key: String): Boolean

    fun clear()

    fun keys(): Set<String>

    fun flush()

    fun asMutableMap(): MutableMap<String, String>
}

internal class UnavailableSecretStore(
    private val legacyFile: File,
    cause: Throwable,
    private val recoverStore: (() -> SecretStore)? = null,
) : SecretStore {
    internal val isPending: Boolean get() = recoverStore != null

    internal fun recover(): SecretStore? =
        runCatching {
            val recovered = recoverStore?.invoke() ?: return null
            properties.forEach { (key, value) -> recovered.put(key.toString(), value.toString()) }
            recovered.flush()
            recovered
        }.getOrNull()

    private val encryptedFilePresent =
        legacyFile.exists() &&
            legacyFile.length() > 0L &&
            legacyFile.inputStream().use { input ->
                val prefix = ByteArray(MAC_ENCRYPTED_FILE_MAGIC.size)
                input.read(prefix) == prefix.size && prefix.contentEquals(MAC_ENCRYPTED_FILE_MAGIC)
            }
    private val properties = if (encryptedFilePresent) Properties() else loadProperties(legacyFile)

    init {
        System.err.println(
            "Metrolist secure credential storage is unavailable; using an owner-only local fallback: ${cause.javaClass.simpleName}",
        )
    }

    override fun get(key: String): String? = properties.getProperty(key)

    override fun put(
        key: String,
        value: String,
    ) {
        properties.setProperty(key, value)
    }

    override fun remove(key: String) {
        properties.remove(key)
    }

    override fun hasKey(key: String): Boolean = properties.containsKey(key)

    override fun clear() {
        properties.clear()
    }

    override fun keys(): Set<String> = properties.stringPropertyNames()

    override fun flush() {
        if (encryptedFilePresent) {
            val recoveredStore =
                runCatching { MacOsKeychainSecretStore(legacyFile) }
                    .getOrElse {
                        throw IllegalStateException(
                            "Metrolist could not persist credentials while macOS Keychain is unavailable",
                            it,
                        )
                    }
            properties.forEach { (key, value) -> recoveredStore.put(key.toString(), value.toString()) }
            recoveredStore.flush()
            return
        }
        ensureFileExists(legacyFile)
        restrictToOwner(legacyFile, false)
        atomicStore(legacyFile, properties, "Metrolist desktop secrets")
        restrictToOwner(legacyFile, false)
    }

    override fun asMutableMap(): MutableMap<String, String> = properties.asMutableStringMap()
}

internal class LinuxSecretServiceStore(
    private val legacyFile: File,
    private val secretToolPath: String? = findExecutable("secret-tool"),
    private val runCommand: (List<String>, String) -> CredentialCommandResult = { command, input ->
        runLinuxCredentialCommand(command, input)
    },
    private val findSecretTool: () -> String? = { findExecutable("secret-tool") },
) : SecretStore {
    private val lock = Any()
    private val ioLock = Any()
    private val properties = mutableMapOf<String, String>()
    private var lastLookupChars = 0

    private var pending = loadProperties(legacyFile)
    private var revision = 0L

    @Volatile
    internal var isPending = true
        private set

    @Volatile
    internal var needsSync = pending.isNotEmpty()
        private set

    init {
        applyPending(properties)
        recover()
    }

    internal fun snapshot(): Map<String, String> = synchronized(lock) { properties.toMap() }

    internal fun update(change: MutableMap<String, String>.() -> Unit) {
        synchronized(lock) {
            val values = properties.toMutableMap()
            val changes = pending.clone() as Properties
            val recording =
                object : MutableMap<String, String> by values {
                    override fun put(
                        key: String,
                        value: String,
                    ): String? {
                        changes.remove(REMOVED_PREFIX + key)
                        changes.setProperty(key, value)
                        return values.put(key, value)
                    }

                    override fun putAll(from: Map<out String, String>) = from.forEach { (key, value) -> put(key, value) }

                    override fun remove(key: String): String? {
                        changes.remove(key)
                        changes.setProperty(REMOVED_PREFIX + key, "true")
                        return values.remove(key)
                    }

                    override fun clear() {
                        changes.clear()
                        changes.setProperty(CLEAR_KEY, "true")
                        values.clear()
                    }
                }
            recording.change()
            if (changes == pending) return
            try {
                atomicStore(legacyFile, changes, "Metrolist pending desktop secrets")
            } catch (error: Exception) {
                credentialWarning("Credential save to pending file failed type=${error.javaClass.simpleName}")
                throw error
            }
            pending = changes
            properties.clear()
            properties.putAll(values)
            revision++
            needsSync = true
        }
    }

    internal fun recover(): Boolean =
        synchronized(ioLock) {
            if (!isPending && !needsSync) return true
            var operation = "load"
            try {
                check(canReplaceSettingsFile(legacyFile)) { "Cannot replace an unpreserved corrupt credentials file" }
                val tool =
                    secretToolPath ?: findSecretTool() ?: run {
                        credentialWarning(
                            "Metrolist cannot read the Linux keyring: install secret-tool; recovery will retry automatically",
                        )
                        return false
                    }
                val recovered = loadFromSecretService(tool).toMutableMap()
                var committed = false
                while (!committed) {
                    val (version, dirty) =
                        synchronized(lock) {
                            applyPending(recovered)
                            if (pending.isNotEmpty()) {
                                operation = "save"
                                val journal = (pending.clone() as Properties).apply { putAll(recovered) }
                                atomicStore(legacyFile, journal, "Metrolist pending desktop secrets")
                                pending = journal
                            }
                            properties.clear()
                            properties.putAll(recovered)
                            isPending = false
                            revision to pending.isNotEmpty()
                        }
                    if (dirty) {
                        operation = "save"
                        val payload =
                            ByteArrayOutputStream().use { output ->
                                Properties()
                                    .apply {
                                        putAll(recovered)
                                        if (isEmpty()) setProperty(CLEAR_KEY, "true")
                                    }.store(output, "Metrolist desktop secrets")
                                output.toString(StandardCharsets.UTF_8)
                            }
                        val result = runCommand(listOf(tool, "store", "--label=Metrolist Desktop Secrets") + attributes, payload)
                        if (result.exit != 0) credentialWarning("Credential keyring save failed exit=${result.exit}")
                        check(result.exit == 0) { "Secret Service write failed (${result.exit})" }
                        operation = "verify"
                        val readBack = loadFromSecretService(tool)
                        if (readBack != recovered) {
                            credentialWarning(
                                "Credential read-back mismatch expectedEntries=${recovered.size} actualEntries=${readBack.size} " +
                                    "expectedChars=${payload.length} actualChars=$lastLookupChars",
                            )
                            error("Secret Service read-back mismatch")
                        }
                    }
                    synchronized(lock) {
                        if (version == revision) {
                            Files.deleteIfExists(legacyFile.toPath())
                            pending.clear()
                            properties.clear()
                            properties.putAll(recovered)
                            isPending = false
                            needsSync = false
                            committed = true
                        }
                    }
                }
                true
            } catch (error: Exception) {
                credentialWarning("Credential $operation failed type=${error.javaClass.simpleName}; retaining pending changes for retry")
                false
            }
        }

    private fun applyPending(target: MutableMap<String, String>) {
        if (pending.getProperty(CLEAR_KEY).toBoolean()) target.clear()
        pending.stringPropertyNames().forEach { key ->
            when {
                key == CLEAR_KEY -> Unit
                key.startsWith(REMOVED_PREFIX) -> target.remove(key.removePrefix(REMOVED_PREFIX))
                else -> target[key] = pending.getProperty(key)
            }
        }
    }

    override fun get(key: String): String? = synchronized(lock) { properties[key] }

    override fun put(
        key: String,
        value: String,
    ) = update { put(key, value) }

    override fun remove(key: String) = update { remove(key) }

    override fun hasKey(key: String): Boolean = synchronized(lock) { properties.containsKey(key) }

    override fun clear() = update { clear() }

    override fun keys(): Set<String> = snapshot().keys

    override fun flush() = Unit

    override fun asMutableMap(): MutableMap<String, String> = snapshot().toMutableMap()

    private fun loadFromSecretService(tool: String): Map<String, String> {
        val (exit, output, error) = runCommand(listOf(tool, "lookup") + attributes, "")
        lastLookupChars = output.length
        val text =
            when {
                exit == 0 && output.isNotBlank() -> {
                    output
                }

                exit == 1 && error.isBlank() -> {
                    checkSecretServiceAbsence()
                    ""
                }

                else -> {
                    credentialWarning("Credential keyring lookup failed exit=$exit")
                    error("Secret Service lookup failed ($exit)")
                }
            }
        return Properties()
            .apply {
                text.byteInputStream().use { load(it) }
                remove(CLEAR_KEY)
            }.asMutableStringMap()
            .toMap()
    }

    private fun checkSecretServiceAbsence() {
        val search =
            runCommand(
                listOf(
                    "dbus-send",
                    "--session",
                    "--print-reply",
                    "--dest=org.freedesktop.secrets",
                    "/org/freedesktop/secrets",
                    "org.freedesktop.Secret.Service.SearchItems",
                    "dict:string:string:" + attributes.joinToString(","),
                ),
                "",
            )
        check(search.exit == 0 && Regex("array\\s*\\[").findAll(search.output).count() == 2 && !search.output.contains("object path")) {
            "Secret Service lookup is unresolved"
        }
        val collections = readServiceProperty("/org/freedesktop/secrets", "org.freedesktop.Secret.Service", "Collections")
        check(collections.contains("array [")) { "Secret Service collections are unavailable" }
        Regex("object path \"([^\"]+)\"").findAll(collections).forEach { match ->
            val locked = readServiceProperty(match.groupValues[1], "org.freedesktop.Secret.Collection", "Locked")
            check(Regex("boolean\\s+false").containsMatchIn(locked)) { "Secret Service collection is locked" }
        }
    }

    private fun readServiceProperty(
        path: String,
        interfaceName: String,
        name: String,
    ): String {
        val result =
            runCommand(
                listOf(
                    "dbus-send",
                    "--session",
                    "--print-reply",
                    "--dest=org.freedesktop.secrets",
                    path,
                    "org.freedesktop.DBus.Properties.Get",
                    "string:$interfaceName",
                    "string:$name",
                ),
                "",
            )
        check(result.exit == 0) { "Secret Service property lookup failed" }
        return result.output
    }

    private companion object {
        const val REMOVED_PREFIX = "__metrolist_removed__."
        const val CLEAR_KEY = "__metrolist_clear__"
        val attributes = listOf("service", "com.metrolist.desktop", "schema", "metrolist-settings", "account", "desktop-secrets")
    }
}

internal class MacOsKeychainSecretStore(
    private val file: File,
    private val runCommand: (List<String>) -> CredentialCommandResult = { runCredentialCommand(it) },
) : SecretStore {
    private val properties = Properties()
    private val encryptionKey = loadOrCreateEncryptionKey()

    init {
        var shouldMigrate = false
        if (file.exists() && file.length() > 0L) {
            val bytes = file.readBytes()
            if (bytes.startsWith(MAC_ENCRYPTED_FILE_MAGIC)) {
                try {
                    decrypt(bytes).inputStream().use { properties.load(it) }
                } catch (error: Exception) {
                    preserveUnreadableSecrets(file, error, "macOS")
                    properties.clear()
                }
            } else {
                readProperties(file)?.let {
                    properties.putAll(it)
                    shouldMigrate = true
                }
            }
        }

        val legacyPayload =
            runSecurity(
                "find-generic-password",
                "-a",
                "desktop-secrets",
                "-s",
                MAC_LEGACY_KEYCHAIN_SERVICE,
                "-w",
                allowMissing = true,
            )
        if (!legacyPayload.isNullOrBlank()) {
            val legacy = Properties()
            parseKeychainPayload(legacyPayload).byteInputStream().use { legacy.load(it) }
            legacy.forEach { (key, value) -> if (!properties.containsKey(key)) properties[key] = value }
            shouldMigrate = true
        }

        if (shouldMigrate) {
            flush()
            runSecurity(
                "delete-generic-password",
                "-a",
                "desktop-secrets",
                "-s",
                MAC_LEGACY_KEYCHAIN_SERVICE,
                allowMissing = true,
            )
        }
    }

    override fun get(key: String): String? = properties.getProperty(key)

    override fun put(
        key: String,
        value: String,
    ) {
        properties.setProperty(key, value)
    }

    override fun remove(key: String) {
        properties.remove(key)
    }

    override fun hasKey(key: String): Boolean = properties.containsKey(key)

    override fun clear() {
        properties.clear()
    }

    override fun keys(): Set<String> = properties.stringPropertyNames()

    override fun asMutableMap(): MutableMap<String, String> = properties.asMutableStringMap()

    override fun flush() {
        val payload =
            ByteArrayOutputStream().use { output ->
                properties.store(output, "Metrolist desktop secrets")
                output.toString(StandardCharsets.UTF_8)
            }
        atomicWrite(file, encrypt(payload.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun loadOrCreateEncryptionKey(): ByteArray {
        val stored =
            runSecurity(
                "find-generic-password",
                "-a",
                MAC_KEYCHAIN_KEY_ACCOUNT,
                "-s",
                MAC_KEYCHAIN_KEY_SERVICE,
                "-w",
                allowMissing = true,
            )
        if (!stored.isNullOrBlank()) {
            return Base64.getDecoder().decode(stored).also {
                check(it.size == MAC_SECRET_KEY_BYTES) { "Invalid macOS secrets encryption key" }
            }
        }

        val generated = ByteArray(MAC_SECRET_KEY_BYTES).also(SecureRandom()::nextBytes)
        runSecurity(
            "add-generic-password",
            "-U",
            "-a",
            MAC_KEYCHAIN_KEY_ACCOUNT,
            "-s",
            MAC_KEYCHAIN_KEY_SERVICE,
            "-w",
            Base64.getEncoder().encodeToString(generated),
        )
        return generated
    }

    private fun encrypt(input: ByteArray): ByteArray {
        val nonce = ByteArray(MAC_GCM_NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(128, nonce))
        return MAC_ENCRYPTED_FILE_MAGIC + nonce + cipher.doFinal(input)
    }

    private fun decrypt(input: ByteArray): ByteArray {
        check(input.startsWith(MAC_ENCRYPTED_FILE_MAGIC)) { "Invalid macOS secrets file" }
        val nonceStart = MAC_ENCRYPTED_FILE_MAGIC.size
        val payloadStart = nonceStart + MAC_GCM_NONCE_BYTES
        check(input.size > payloadStart) { "Truncated macOS secrets file" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(encryptionKey, "AES"),
            GCMParameterSpec(128, input.copyOfRange(nonceStart, payloadStart)),
        )
        return cipher.doFinal(input.copyOfRange(payloadStart, input.size))
    }

    private fun parseKeychainPayload(output: String): String {
        val trimmed = output.trim()
        return if (trimmed.matches(Regex("^[0-9a-fA-F]+$")) && trimmed.length % 2 == 0) {
            try {
                val bytes = ByteArray(trimmed.length / 2)
                for (i in bytes.indices) {
                    bytes[i] = trimmed.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                String(bytes, StandardCharsets.UTF_8)
            } catch (_: Exception) {
                trimmed
            }
        } else {
            trimmed
        }
    }

    private fun runSecurity(
        vararg args: String,
        allowMissing: Boolean = false,
    ): String? {
        val (exit, output) = runCommand(listOf("security") + args)
        if (exit == 0) return output.trimEnd()
        if (allowMissing && exit == 44) return null
        throw IllegalStateException("macOS Keychain command failed ($exit)")
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

internal class WindowsEncryptedSecretStore(
    private val file: File,
    private val encrypt: (ByteArray) -> ByteArray = ::encryptWindowsSecrets,
    private val decrypt: (ByteArray) -> ByteArray = ::decryptWindowsSecrets,
) : SecretStore {
    private val properties = Properties()

    init {
        if (!Files.notExists(file.toPath())) {
            try {
                decrypt(file.readBytes()).inputStream().use { properties.load(it) }
            } catch (error: Exception) {
                preserveUnreadableSecrets(file, error, "Windows")
                properties.clear()
            }
        }
    }

    override fun get(key: String): String? = properties.getProperty(key)

    override fun put(
        key: String,
        value: String,
    ) {
        properties.setProperty(key, value)
    }

    override fun remove(key: String) {
        properties.remove(key)
    }

    override fun hasKey(key: String): Boolean = properties.containsKey(key)

    override fun clear() {
        properties.clear()
    }

    override fun keys(): Set<String> = properties.stringPropertyNames()

    override fun flush() {
        val payload =
            ByteArrayOutputStream().use { output ->
                properties.store(output, "Metrolist desktop secrets")
                output.toByteArray()
            }
        atomicWrite(file, encrypt(payload))
    }

    override fun asMutableMap(): MutableMap<String, String> = properties.asMutableStringMap()
}

private fun preserveUnreadableSecrets(
    file: File,
    error: Exception,
    platform: String,
) {
    preserveCorruptSettingsFile(file, error)
    settingsRecoveryWarning("Starting with empty $platform credentials after an unreadable file.")
}

private fun encryptWindowsSecrets(input: ByteArray): ByteArray =
    Crypt32Util.cryptProtectData(input, WINDOWS_SECRET_ENTROPY.toByteArray(StandardCharsets.UTF_8), 0, null, null)

private fun decryptWindowsSecrets(input: ByteArray): ByteArray =
    Crypt32Util.cryptUnprotectData(input, WINDOWS_SECRET_ENTROPY.toByteArray(StandardCharsets.UTF_8), 0, null)

internal data class CredentialCommandResult(
    val exit: Int,
    val output: String,
    val error: String,
)

private val startupCredentialDeadline = ThreadLocal<Long>()

internal fun <T> withStartupCredentialDeadline(
    timeoutMs: Long = 1_000L,
    block: () -> T,
): T {
    startupCredentialDeadline.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs))
    return try {
        block()
    } finally {
        startupCredentialDeadline.remove()
    }
}

private class CredentialServiceTimeoutException : IllegalStateException("Credential service timed out")

private val SecretStore.recoveryPending: Boolean
    get() =
        when (this) {
            is LinuxSecretServiceStore -> isPending
            is UnavailableSecretStore -> isPending
            else -> false
        }

internal fun runCredentialCommand(
    command: List<String>,
    input: String = "",
    timeoutMs: Long = 30_000L,
): CredentialCommandResult {
    val deadline = credentialDeadlineNanos(timeoutMs)
    if (deadline <= System.nanoTime()) throw CredentialServiceTimeoutException()
    val process = ProcessBuilder(command).start()
    val workers = Executors.newFixedThreadPool(3) { task -> Thread(task, "DesktopCredentialIO").apply { isDaemon = true } }

    fun remainingNanos(): Long = (deadline - System.nanoTime()).coerceAtLeast(0L)
    val output = workers.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
    val error = workers.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }
    val writing = workers.submit { process.outputStream.bufferedWriter().use { it.write(input) } }
    try {
        if (!process.waitFor(remainingNanos(), TimeUnit.NANOSECONDS)) throw CredentialServiceTimeoutException()
        writing.get(remainingNanos(), TimeUnit.NANOSECONDS)
        return CredentialCommandResult(
            process.exitValue(),
            output.get(remainingNanos(), TimeUnit.NANOSECONDS),
            error.get(remainingNanos(), TimeUnit.NANOSECONDS),
        )
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("Credential service interrupted", interrupted)
    } finally {
        process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
        // Process.destroyForcibly also closes streams and can block behind a stalled pipe writer.
        process.toHandle().destroyForcibly()
        workers.shutdownNow()
    }
}

internal fun credentialDeadlineNanos(timeoutMs: Long = 30_000L): Long =
    minOf(
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs),
        startupCredentialDeadline.get() ?: Long.MAX_VALUE,
    )

internal fun mergeFallbackSecrets(
    secure: Properties,
    fallback: Properties,
) {
    fallback.forEach { (key, value) -> secure[key] = value }
}

private fun Properties.asMutableStringMap(): MutableMap<String, String> =
    object : MutableMap<String, String> {
        override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
            get() =
                stringPropertyNames().mapTo(mutableSetOf()) { key ->
                    object : MutableMap.MutableEntry<String, String> {
                        override val key: String = key
                        override val value: String = getProperty(key)

                        override fun setValue(newValue: String): String {
                            val oldValue = getProperty(key)
                            setProperty(key, newValue)
                            return oldValue
                        }
                    }
                }
        override val keys: MutableSet<String>
            get() = stringPropertyNames().toMutableSet()
        override val size: Int
            get() = stringPropertyNames().size
        override val values: MutableCollection<String>
            get() = stringPropertyNames().map { getProperty(it) }.toMutableList()

        override fun clear() = this@asMutableStringMap.clear()

        override fun isEmpty(): Boolean = stringPropertyNames().isEmpty()

        override fun remove(key: String): String? = this@asMutableStringMap.remove(key) as? String

        override fun putAll(from: Map<out String, String>) = from.forEach { (k, v) -> setProperty(k, v) }

        override fun put(
            key: String,
            value: String,
        ): String? = (setProperty(key, value) as? String)

        override fun get(key: String): String? = getProperty(key)

        override fun containsValue(value: String): Boolean = stringPropertyNames().any { getProperty(it) == value }

        override fun containsKey(key: String): Boolean = this@asMutableStringMap.containsKey(key)
    }

private val unpreservedSettingsFiles = ConcurrentHashMap.newKeySet<Path>()

private fun settingsPath(file: File): Path = file.toPath().toAbsolutePath().normalize()

private fun canReplaceSettingsFile(file: File): Boolean =
    settingsPath(file) !in unpreservedSettingsFiles && settingsPath(pendingFile(file)) !in unpreservedSettingsFiles

private fun settingsRecoveryWarning(message: String) {
    PlaybackEventLog.warn(PlaybackEventKind.RECOVERY, message)
    System.err.println(message)
}

private fun preserveCorruptSettingsFile(
    file: File,
    error: Exception,
) {
    try {
        val backup = Files.createTempFile(settingsPath(file).parent, "${file.name}.corrupt-${System.currentTimeMillis()}-", ".bak")
        restrictToOwner(backup.toFile(), false)
        file.inputStream().use { input ->
            backup.toFile().outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        Files.delete(file.toPath())
        unpreservedSettingsFiles.remove(settingsPath(file))
        settingsRecoveryWarning("Preserved unreadable ${file.name} as ${backup.fileName} (${error.javaClass.simpleName}).")
    } catch (backupError: Exception) {
        unpreservedSettingsFiles.add(settingsPath(file))
        settingsRecoveryWarning(
            "Could not preserve unreadable ${file.name} (${backupError.javaClass.simpleName}); keeping the original and blocking replacement.",
        )
    }
}

private fun readProperties(file: File): Properties? {
    if (!file.exists()) return null
    return try {
        Properties().apply { file.inputStream().use { load(it) } }
    } catch (error: IllegalArgumentException) {
        preserveCorruptSettingsFile(file, error)
        null
    } catch (error: IOException) {
        unpreservedSettingsFiles.add(settingsPath(file))
        settingsRecoveryWarning("Cannot read ${file.name} (${error.javaClass.simpleName}); keeping the original and blocking replacement.")
        null
    }
}

private fun loadProperties(file: File): Properties = readProperties(file) ?: Properties()

internal fun loadPublicProperties(file: File): Properties {
    val pending = readProperties(pendingFile(file))
    val main = readProperties(file)
    return pending ?: main ?: Properties()
}

private fun pendingFile(file: File): File = File(file.parentFile, ".${file.name}.pending")

private fun ensureFileExists(file: File) {
    file.parentFile?.mkdirs()
    if (!file.exists()) {
        file.createNewFile()
    }
    restrictToOwner(file, false)
}

internal fun atomicStore(
    file: File,
    properties: Properties,
    comment: String,
    deferAccessDenied: Boolean = false,
    replace: (Path, Path) -> Unit = ::moveReplacing,
) {
    val bytes =
        ByteArrayOutputStream().use { output ->
            properties.store(output, comment)
            output.toByteArray()
        }
    atomicWrite(file, bytes, deferAccessDenied, replace)
}

private fun atomicWrite(
    file: File,
    bytes: ByteArray,
    deferAccessDenied: Boolean = false,
    replace: (Path, Path) -> Unit = ::moveReplacing,
) {
    check(canReplaceSettingsFile(file)) { "Cannot replace an unpreserved corrupt settings file" }
    file.parentFile?.mkdirs()
    val temp = Files.createTempFile(file.parentFile.toPath(), ".${file.name}.", ".tmp")
    try {
        restrictToOwner(temp.toFile(), false)
        temp.toFile().outputStream().use { output ->
            bytes.inputStream().copyTo(output)
            output.fd.sync()
        }
        val staged = if (deferAccessDenied) pendingFile(file).toPath() else file.toPath()
        replace(temp, staged)
        if (deferAccessDenied) {
            try {
                replace(staged, file.toPath())
            } catch (e: AccessDeniedException) {
                System.err.println("Metrolist desktop settings replacement deferred: ${e.message}")
            }
        } else {
            Files.deleteIfExists(pendingFile(file).toPath())
        }
        restrictToOwner(if (Files.exists(staged)) staged.toFile() else file, false)
    } finally {
        Files.deleteIfExists(temp)
    }
}

private fun moveReplacing(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun restrictToOwner(
    file: File,
    directory: Boolean,
) {
    if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
        val permissions =
            if (directory) {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
        Files.setPosixFilePermissions(file.toPath(), permissions)
    }
}

private fun findExecutable(name: String): String? =
    System
        .getenv("PATH")
        .orEmpty()
        .split(File.pathSeparator)
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
