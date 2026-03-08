package com.metrolist.android.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.metrolist.android.BuildConfig
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.updates_notification_text
import com.metrolist.shared.generated.resources.updates_notification_title
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.update.ReleaseVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Request
import org.jetbrains.compose.resources.getString
import java.io.File
import java.security.MessageDigest

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val assets: List<GitHubReleaseAsset> = emptyList(),
) {
    val version: String get() = tagName.removePrefix("v")
    val apk: GitHubReleaseAsset?
        get() {
            val candidates =
                assets.filter { asset ->
                    asset.state == "uploaded" &&
                        asset.name.endsWith(".apk", ignoreCase = true) &&
                        BLOCKED_APK_MARKERS.none { marker -> asset.name.contains(marker, ignoreCase = true) }
                }
            val preferredNames =
                PREFERRED_APK_NAMES + listOf("metrolist-android-$version.apk", "metrolist-android-$version-universal.apk")
            return preferredNames.firstNotNullOfOrNull { preferred ->
                candidates.singleOrNull { it.name.equals(preferred, ignoreCase = true) }
            }
        }
}

@Serializable
data class GitHubReleaseAsset(
    val name: String,
    val state: String,
    val size: Long,
    val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState

    data object Checking : AppUpdateState

    data class UpToDate(
        val checkedAt: Long,
    ) : AppUpdateState

    data class Available(
        val release: GitHubRelease,
    ) : AppUpdateState

    data class Downloading(
        val release: GitHubRelease,
        val progress: Int,
    ) : AppUpdateState

    data class Downloaded(
        val release: GitHubRelease,
        val file: File,
        val installing: Boolean = false,
    ) : AppUpdateState

    data class Error(
        val failure: UpdateFailure,
        val release: GitHubRelease? = null,
    ) : AppUpdateState
}

enum class UpdateFailure { CHECK, NO_APK, DOWNLOAD, DIGEST, INSTALL }

object AppUpdateManager {
    const val CHANNEL_ID = "updates"
    private const val RELEASES_URL = "https://api.github.com/repos/MetrolistGroup/Metrolist/releases?per_page=50"
    private const val NOTIFICATION_ID = 1400
    private const val MAX_APK_BYTES = 512L * 1024 * 1024
    private const val MIN_FREE_SPACE_BYTES = 16L * 1024 * 1024

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val client =
        sharedOkHttpClient
            .newBuilder()
            .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    private val mutableReleases = MutableStateFlow<List<GitHubRelease>>(emptyList())
    private val operationMutex = Mutex()

    @Volatile private var activeDownload: Call? = null

    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    val releases: StateFlow<List<GitHubRelease>> = mutableReleases.asStateFlow()

    suspend fun checkAtStartup(
        context: Context,
        networkEnabled: Boolean,
    ): GitHubRelease? {
        if (Settings.shared.updateCacheSource != CACHE_SOURCE) {
            Settings.shared.updateCacheSource = CACHE_SOURCE
            Settings.shared.lastUpdateCheckAttemptTimestamp = 0L
            Settings.shared.lastUpdateCheckTimestamp = 0L
            Settings.shared.cachedAvailableUpdateJson = ""
            Settings.shared.cachedUpdateReleasesJson = ""
            Settings.shared.pendingUpdateReleaseJson = ""
        }
        val now = System.currentTimeMillis()
        val lastAttempt = Settings.shared.lastUpdateCheckAttemptTimestamp
        val checkIsDue = lastAttempt !in 1..now || now - lastAttempt >= CHECK_INTERVAL_MILLIS
        if (networkEnabled && checkIsDue) {
            return check(context, notify = Settings.shared.updateNotifications)
        }

        val cached = withContext(Dispatchers.IO) { cachedAvailableRelease() }
        mutableState.value =
            if (cached == null) {
                AppUpdateState.UpToDate(Settings.shared.lastUpdateCheckTimestamp)
            } else {
                AppUpdateState.Available(cached)
            }
        return cached
    }

    suspend fun loadReleaseHistory() =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                if (mutableReleases.value.isEmpty()) mutableReleases.value = cachedReleases()
            }
        }

    suspend fun check(
        context: Context,
        notify: Boolean = false,
        crashRecovery: Boolean = false,
    ): GitHubRelease? =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                when (val active = mutableState.value) {
                    is AppUpdateState.Downloading -> {
                        return@withLock active.release
                    }

                    is AppUpdateState.Downloaded -> {
                        if (!active.file.isFile) mutableState.value = AppUpdateState.Available(active.release)
                        return@withLock active.release
                    }

                    else -> {
                    }
                }
                mutableState.value = AppUpdateState.Checking
                if (!crashRecovery) Settings.shared.lastUpdateCheckAttemptTimestamp = System.currentTimeMillis()
                runCatching {
                    val request =
                        Request
                            .Builder()
                            .url(RELEASES_URL)
                            .header("Accept", "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .header("User-Agent", "Metrolist/${BuildConfig.VERSION_NAME}")
                            .build()
                    val publicReleases =
                        client.newCall(request).execute().use { response ->
                            check(response.isSuccessful) { "GitHub returned ${response.code}" }
                            json
                                .decodeFromString<List<GitHubRelease>>(checkNotNull(response.body).string())
                                .filter { release -> !release.draft && ReleaseVersion.parse(release.tagName) != null }
                                .sortedWith { left, right -> compareVersions(right.version, left.version) }
                        }
                    mutableReleases.value = publicReleases
                    if (!crashRecovery) Settings.shared.cachedUpdateReleasesJson = json.encodeToString(publicReleases)
                    val now = System.currentTimeMillis()
                    if (!crashRecovery) Settings.shared.lastUpdateCheckTimestamp = now
                    val update = publicReleases.firstOrNull { compareVersions(it.version, currentVersion) > 0 }
                    if (!crashRecovery) Settings.shared.cachedAvailableUpdateJson = update?.let { json.encodeToString(it) }.orEmpty()
                    mutableState.value = if (update == null) AppUpdateState.UpToDate(now) else AppUpdateState.Available(update)
                    if (update != null && notify && !crashRecovery) notifyAvailable(context, update)
                    update
                }.getOrElse {
                    val cached = if (crashRecovery) null else cachedAvailableRelease()
                    mutableState.value = cached?.let(AppUpdateState::Available) ?: AppUpdateState.Error(UpdateFailure.CHECK)
                    cached
                }
            }
        }

    @SuppressLint("UsableSpace")
    suspend fun download(
        context: Context,
        release: GitHubRelease,
        crashRecovery: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            when (val active = mutableState.value) {
                is AppUpdateState.Downloading -> if (active.release.version == release.version) return@withLock
                is AppUpdateState.Downloaded -> if (active.release.version == release.version && active.file.isFile) return@withLock
                else -> Unit
            }
            val asset = release.apk
            if (asset == null) {
                mutableState.value = AppUpdateState.Error(UpdateFailure.NO_APK, release)
                return@withLock
            }
            runCatching {
                check(asset.size in 1..MAX_APK_BYTES) { "Invalid update size" }
                val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
                updateDir.listFiles()?.forEach(File::delete)
                val partial = File(updateDir, "Metrolist-${release.version}.apk.part")
                val output = File(updateDir, "Metrolist-${release.version}.apk")
                val request =
                    Request
                        .Builder()
                        .url(asset.downloadUrl)
                        .header("User-Agent", "Metrolist/${BuildConfig.VERSION_NAME}")
                        .build()
                var downloaded = 0L
                val call = client.newCall(request).also { activeDownload = it }
                call.execute().use { response ->
                    check(response.isSuccessful) { "Download returned ${response.code}" }
                    val body = checkNotNull(response.body)
                    val responseLength = body.contentLength()
                    check(responseLength < 0 || responseLength == asset.size) { "Update response size mismatch" }
                    check(updateDir.usableSpace > asset.size + MIN_FREE_SPACE_BYTES) { "Insufficient space for update" }
                    val total = asset.size.takeIf { it > 0 } ?: body.contentLength()
                    body.byteStream().use { input ->
                        partial.outputStream().buffered().use { target ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(downloaded + count <= asset.size && downloaded + count <= MAX_APK_BYTES) {
                                    "Update exceeded the expected size"
                                }
                                check(updateDir.usableSpace > count + MIN_FREE_SPACE_BYTES) { "Insufficient space for update" }
                                target.write(buffer, 0, count)
                                downloaded += count
                                val progress = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                                mutableState.value = AppUpdateState.Downloading(release, progress)
                            }
                        }
                    }
                }
                if (downloaded != asset.size || !verifyDigest(partial, asset.digest)) {
                    partial.delete()
                    mutableState.value = AppUpdateState.Error(UpdateFailure.DIGEST, release)
                    return@runCatching
                }
                check(partial.renameTo(output)) { "Could not finalize update" }
                if (!verifyApkIdentity(context, output)) {
                    output.delete()
                    mutableState.value = AppUpdateState.Error(UpdateFailure.DIGEST, release)
                    return@runCatching
                }
                if (!crashRecovery) Settings.shared.pendingUpdateReleaseJson = json.encodeToString(release)
                mutableState.value = AppUpdateState.Downloaded(release, output)
            }.onFailure {
                mutableState.value =
                    if (activeDownload?.isCanceled() == true) {
                        AppUpdateState.Available(release)
                    } else {
                        AppUpdateState.Error(UpdateFailure.DOWNLOAD, release)
                    }
            }
            activeDownload = null
        }
    }

    fun cancelDownload() {
        activeDownload?.cancel()
    }

    // Before Android 8 unknown sources is a global setting checked by the installer prompt itself.
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    suspend fun install(
        context: Context,
        downloaded: AppUpdateState.Downloaded,
        crashRecovery: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutableState.value = downloaded.copy(installing = true)
            if (!downloaded.file.isFile || !verifyApkIdentity(context, downloaded.file)) {
                downloaded.file.delete()
                mutableState.value = AppUpdateState.Available(downloaded.release)
                return@withContext false
            }
            val installer = context.packageManager.packageInstaller
            var sessionId = -1
            runCatching {
                val params =
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(context.packageName)
                        setSize(downloaded.file.length())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                        }
                    }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    downloaded.file.inputStream().use { input ->
                        session.openWrite("base.apk", 0, downloaded.file.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val receiver = if (crashRecovery) CrashUpdateInstallReceiver::class.java else UpdateInstallReceiver::class.java
                    // FLAG_MUTABLE: the installer fills in the status extras.
                    val callback =
                        PendingIntent.getBroadcast(
                            context,
                            sessionId,
                            Intent(context, receiver),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                    session.commit(callback.intentSender)
                }
            }.onFailure {
                if (sessionId >= 0) {
                    runCatching { installer.abandonSession(sessionId) }
                }
                mutableState.value =
                    if (downloaded.file.isFile) downloaded.copy(installing = false) else AppUpdateState.Available(downloaded.release)
            }.isSuccess
        }

    internal fun onInstallFinished(success: Boolean) {
        val current = mutableState.value as? AppUpdateState.Downloaded ?: return
        mutableState.value =
            if (success) current.copy(installing = false) else AppUpdateState.Error(UpdateFailure.INSTALL, current.release)
    }

    internal fun onInstallAborted() {
        val current = mutableState.value as? AppUpdateState.Downloaded ?: return
        mutableState.value = current.copy(installing = false)
    }

    fun pendingReleaseForInstalledVersion(): GitHubRelease? =
        runCatching { json.decodeFromString<GitHubRelease>(Settings.shared.pendingUpdateReleaseJson) }
            .getOrNull()
            ?.takeIf { it.version == BuildConfig.VERSION_NAME.removePrefix("v") }

    fun clearPendingRelease() {
        Settings.shared.pendingUpdateReleaseJson = ""
    }

    private fun cachedAvailableRelease(): GitHubRelease? =
        runCatching { json.decodeFromString<GitHubRelease>(Settings.shared.cachedAvailableUpdateJson) }
            .getOrNull()
            ?.takeIf { compareVersions(it.version, currentVersion) > 0 }

    private fun cachedReleases(): List<GitHubRelease> =
        runCatching { json.decodeFromString<List<GitHubRelease>>(Settings.shared.cachedUpdateReleasesJson) }
            .getOrDefault(emptyList())

    fun releaseForInstalledVersion(): GitHubRelease? =
        mutableReleases.value.firstOrNull { it.version == BuildConfig.VERSION_NAME.removePrefix("v") }

    private suspend fun notifyAvailable(
        context: Context,
        release: GitHubRelease,
    ) {
        if (!Settings.shared.updateNotifications) return
        if (Settings.shared.lastNotifiedUpdateVersion == release.version) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_UPDATES
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(Res.string.updates_notification_title, release.version))
                .setContentText(getString(Res.string.updates_notification_text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        Settings.shared.lastNotifiedUpdateVersion = release.version
    }

    private fun verifyDigest(
        file: File,
        digest: String?,
    ): Boolean {
        val expected = digest?.takeIf { it.matches(Regex("sha256:[0-9a-fA-F]{64}")) }?.removePrefix("sha256:") ?: return false
        val messageDigest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                messageDigest.update(buffer, 0, count)
            }
        }
        val actual = messageDigest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    internal fun verifyApkIdentity(
        context: Context,
        file: File,
    ): Boolean =
        runCatching {
            val packageManager = context.packageManager
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_SIGNATURES
                }
            val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
            if (archive.packageName != context.packageName) return false
            val installed = packageManager.getPackageInfo(context.packageName, flags)
            signerDigestsMatch(
                installedCurrentSigners = installed.currentSignerBytes(),
                archiveCurrentSigners = archive.currentSignerBytes(),
                archiveSignerHistory = archive.signerHistoryBytes(),
            )
        }.getOrDefault(false)

    private fun PackageInfo.currentSignerBytes(): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            signatures.orEmpty().map { it.toByteArray() }
        }

    // Signing key rotation history only exists from Android 9.
    private fun PackageInfo.signerHistoryBytes(): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.signingCertificateHistory.orEmpty().map { it.toByteArray() }
        } else {
            emptyList()
        }

    internal fun signerDigestsMatch(
        installedCurrentSigners: List<ByteArray>,
        archiveCurrentSigners: List<ByteArray>,
        archiveSignerHistory: List<ByteArray>,
    ): Boolean {
        if (installedCurrentSigners.isEmpty() || archiveCurrentSigners.isEmpty()) return false
        val installed = installedCurrentSigners.mapTo(mutableSetOf(), ::sha256)
        val archive = archiveCurrentSigners.mapTo(mutableSetOf(), ::sha256)
        if (installed.size != 1 || archive.size != 1) return installed == archive
        return installed == archive || archiveSignerHistory.any { sha256(it) in installed }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    internal fun compareVersions(
        left: String,
        right: String,
    ): Int {
        val parsedLeft = ReleaseVersion.parse(left) ?: return 0
        val parsedRight = ReleaseVersion.parse(right) ?: return 0
        return parsedLeft.compareTo(parsedRight)
    }
}

private const val CHECK_INTERVAL_MILLIS = 60 * 60 * 1_000L
private const val CACHE_SOURCE = "github-releases-metrolist-v1"
private val PREFERRED_APK_NAMES =
    listOf(
        "Metrolist.apk",
        "Metrolist-Android-release.apk",
        "androidApp-release.apk",
        "nightly-Metrolist-Android-release.apk",
    )
private val BLOCKED_APK_MARKERS = listOf("debug", "unsigned", "unaligned", "test", "benchmark")
