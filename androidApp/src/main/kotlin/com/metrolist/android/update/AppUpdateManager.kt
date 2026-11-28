package com.metrolist.android.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.metrolist.android.BuildConfig
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.android.ui.screens.CrashActivity
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.updates_notification_text
import com.metrolist.shared.generated.resources.updates_notification_title
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.update.NIGHTLY_TAG
import com.metrolist.shared.update.ReleaseVersion
import com.metrolist.shared.update.checkNightly
import com.metrolist.shared.update.followsNightlyUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("target_commitish") val targetCommit: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
) {
    val version: String get() = tagName.removePrefix("v")
    val isNightly: Boolean get() = tagName == NIGHTLY_TAG && !draft

    /** Nightlies share one tag, so their package digest tells them apart. */
    val updateKey: String get() = if (isNightly) "$NIGHTLY_TAG:${apk?.digest}" else version
    val isPublishedStable: Boolean
        get() = !draft && !prerelease && ReleaseVersion.parse(tagName)?.prerelease?.isEmpty() == true
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
        val verified: Boolean = true,
    ) : AppUpdateState

    data class Error(
        val failure: UpdateFailure,
        val release: GitHubRelease? = null,
        val errorMessage: String? = null,
    ) : AppUpdateState
}

enum class UpdateFailure { CHECK, NO_APK, DOWNLOAD, DIGEST, INSTALL }

object AppUpdateManager {
    const val CHANNEL_ID = "updates"
    private const val RELEASES_URL = "https://api.github.com/repos/MetrolistGroup/Metrolist/releases?per_page=50"
    private const val NIGHTLY_RELEASE_URL = "https://api.github.com/repos/MetrolistGroup/Metrolist/releases/tags/$NIGHTLY_TAG"
    private const val NOTIFICATION_ID = 1400
    private const val INSTALL_RESULT_NOTIFICATION_ID = 1401
    private const val INSTALL_PROMPT_NOTIFICATION_ID = 1402
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
    private val autoInstallRunning = AtomicBoolean(false)

    @Volatile private var autoInstallFailed = false

    @Volatile private var backgroundUpdateKey: String? = null

    @Volatile private var foregroundActivityRef: WeakReference<Activity>? = null

    @Volatile private var activeSessionCallback: PackageInstaller.SessionCallback? = null

    @Volatile private var activeSessionId: Int = -1

    @Volatile private var ignoredSessionIds: Set<Int> = emptySet()

    @Volatile private var pendingConfirmationIntent: Intent? = null

    @Volatile private var pendingConfirmationSessionId: Int = -1

    private val processToken = UUID.randomUUID().toString()
    private val attemptCounter = AtomicLong(0L)
    private val installErrorVersion = AtomicLong(0L)

    @Volatile private var currentAttemptToken: String? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun nextAttemptToken(): String = "$processToken:${attemptCounter.incrementAndGet()}".also { currentAttemptToken = it }

    fun ensureNotificationChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.updates_channel_name))
                .setDescription(context.getString(R.string.updates_channel_description))
                .build(),
        )
    }

    fun restoreInstallFailure(
        errorMessage: String,
        attemptToken: String? = null,
    ) {
        val detail = errorMessage.trim().takeIf { it.isNotEmpty() } ?: return
        if (autoInstallRunning.get()) return
        val current = mutableState.value
        if (current is AppUpdateState.Downloading || current is AppUpdateState.Downloaded) return
        val activeToken = currentAttemptToken
        if (attemptToken != null && activeToken != null && attemptToken != activeToken) return
        installErrorVersion.incrementAndGet()
        val release =
            when (current) {
                is AppUpdateState.Available -> current.release
                is AppUpdateState.Error -> current.release ?: cachedAvailableRelease()
                else -> cachedAvailableRelease()
            }
        mutableState.value = AppUpdateState.Error(UpdateFailure.INSTALL, release, detail)
    }

    fun setForegroundActivity(
        activity: Activity?,
        active: Boolean,
    ) {
        if (active && activity != null && !activity.isFinishing && !activity.isDestroyed) {
            foregroundActivityRef = WeakReference(activity)
            val pending = pendingConfirmationIntent
            val pendingSession = pendingConfirmationSessionId
            val currentSession = activeSessionId
            if (pending != null && (currentSession < 0 || pendingSession < 0 || pendingSession == currentSession)) {
                if (runCatching { activity.startActivity(Intent(pending)) }.isSuccess) {
                    pendingConfirmationIntent = null
                    pendingConfirmationSessionId = -1
                    cancelInstallPromptNotification(activity)
                } else if (!canPostNotifications(activity)) {
                    val targetSession = pendingSession.takeIf { it >= 0 } ?: currentSession
                    onInstallFinished(
                        context = activity,
                        success = false,
                        sessionId = targetSession,
                        crashRecovery = activity is CrashActivity,
                    )
                    abandonPendingSession(activity, targetSession)
                }
            }
        } else if (!active && foregroundActivityRef?.get() === activity) {
            foregroundActivityRef = null
        }
    }

    /** True for a release an automatic update downloaded. Screens must not install it on their own; only the Install button may. */
    fun isBackgroundUpdate(release: GitHubRelease): Boolean = autoInstallRunning.get() || release.updateKey == backgroundUpdateKey

    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    val releases: StateFlow<List<GitHubRelease>> = mutableReleases.asStateFlow()

    suspend fun checkAtStartup(
        context: Context,
        networkEnabled: Boolean,
    ): GitHubRelease? {
        if (!BuildConfig.UPDATER_ENABLED) return null
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
            return check(context, notify = Settings.shared.updateNotifications, preserveInstallError = true)
        }

        val cached = withContext(Dispatchers.IO) { cachedAvailableRelease() }
        // A background download, playback-paused update, or notification-restored install failure must survive a startup check.
        when (val active = mutableState.value) {
            is AppUpdateState.Downloading -> if (active.release.updateKey == cached?.updateKey) return cached
            is AppUpdateState.Downloaded -> if (active.release.updateKey == cached?.updateKey && active.file.isFile) return cached
            is AppUpdateState.Error -> if (active.failure == UpdateFailure.INSTALL) return cached
            else -> Unit
        }
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
        preserveInstallError: Boolean = false,
    ): GitHubRelease? =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                if (!BuildConfig.UPDATER_ENABLED) return@withLock null
                when (val active = mutableState.value) {
                    is AppUpdateState.Downloading -> {
                        return@withLock active.release
                    }

                    is AppUpdateState.Downloaded -> {
                        if (!active.file.isFile) mutableState.value = AppUpdateState.Available(active.release)
                        return@withLock active.release
                    }

                    is AppUpdateState.Error -> {
                        if (preserveInstallError && active.failure == UpdateFailure.INSTALL) {
                            return@withLock active.release
                        }
                    }

                    else -> {
                    }
                }
                if (!preserveInstallError) installErrorVersion.incrementAndGet()
                val errorVersionAtStart = installErrorVersion.get()
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
                    val allReleases =
                        client.newCall(request).execute().use { response ->
                            check(response.isSuccessful) { "GitHub returned ${response.code}" }
                            json.decodeFromString<List<GitHubRelease>>(checkNotNull(response.body).string())
                        }
                    val publicReleases =
                        allReleases
                            .filter { release -> !release.draft && ReleaseVersion.parse(release.tagName) != null }
                            .sortedWith { left, right -> compareVersions(right.version, left.version) }
                    mutableReleases.value = publicReleases
                    if (!crashRecovery) Settings.shared.cachedUpdateReleasesJson = json.encodeToString(publicReleases)
                    val now = System.currentTimeMillis()
                    if (!crashRecovery) Settings.shared.lastUpdateCheckTimestamp = now
                    val update =
                        publicReleases.firstOrNull { release ->
                            (crashRecovery || release.isPublishedStable) &&
                                compareVersions(release.version, currentVersion) > 0
                        } ?: allReleases.firstOrNull(GitHubRelease::isNightly)?.takeIf { nightly ->
                            // A crash offers the nightly even on the stable channel, since it may already carry the fix.
                            (crashRecovery || followsNightlyUpdates(Settings.shared.updateChannel, currentVersion)) &&
                                isNewNightly(nightly, confirm = !crashRecovery)
                        }
                    if (!crashRecovery) Settings.shared.cachedAvailableUpdateJson = update?.let { json.encodeToString(it) }.orEmpty()
                    if (preserveInstallError && installErrorVersion.get() != errorVersionAtStart) return@withLock update
                    mutableState.value = if (update == null) AppUpdateState.UpToDate(now) else AppUpdateState.Available(update)
                    if (update != null && notify && !crashRecovery) notifyAvailable(context, update)
                    update
                }.getOrElse {
                    val cached = if (crashRecovery) null else cachedAvailableRelease()
                    if (preserveInstallError && installErrorVersion.get() != errorVersionAtStart) return@withLock cached
                    mutableState.value = cached?.let(AppUpdateState::Available) ?: AppUpdateState.Error(UpdateFailure.CHECK)
                    cached
                }
            }
        }

    suspend fun download(
        context: Context,
        release: GitHubRelease,
        crashRecovery: Boolean = false,
        skipVerification: Boolean = false,
    ) {
        if (!downloadOnce(context, release, crashRecovery, skipVerification) || !release.isNightly) return
        // A nightly rebuilt after the check replaced its asset. The fresh digest still comes from the GitHub API,
        // and the signing-key check still applies to the new package.
        val fresh = withContext(Dispatchers.IO) { runCatching(::fetchNightlyRelease).getOrNull() } ?: return
        if (fresh.apk?.digest == null || fresh.updateKey == release.updateKey) return
        if (!crashRecovery) Settings.shared.cachedAvailableUpdateJson = json.encodeToString(fresh)
        downloadOnce(context, fresh, crashRecovery, skipVerification)
    }

    private fun fetchNightlyRelease(): GitHubRelease? {
        val request =
            Request
                .Builder()
                .url(NIGHTLY_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Metrolist/${BuildConfig.VERSION_NAME}")
                .build()
        return client
            .newCall(request)
            .execute()
            .use { response ->
                check(response.isSuccessful) { "GitHub returned ${response.code}" }
                json.decodeFromString<GitHubRelease>(checkNotNull(response.body).string())
            }.takeIf(GitHubRelease::isNightly)
    }

    /** Returns true when the package did not match the release's size or digest. */
    @SuppressLint("UsableSpace")
    private suspend fun downloadOnce(
        context: Context,
        release: GitHubRelease,
        crashRecovery: Boolean,
        skipVerification: Boolean,
    ) = withContext(Dispatchers.IO) {
        var packageMismatch = false
        operationMutex.withLock {
            when (val active = mutableState.value) {
                is AppUpdateState.Downloading -> if (active.release.updateKey == release.updateKey) return@withLock
                is AppUpdateState.Downloaded -> if (active.release.updateKey == release.updateKey && active.file.isFile) return@withLock
                else -> Unit
            }
            nextAttemptToken()
            cancelInstallResultNotification(context)
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
                    if (responseLength >= 0 && responseLength != asset.size) {
                        packageMismatch = true
                        error("Update response size mismatch")
                    }
                    check(updateDir.usableSpace > asset.size + MIN_FREE_SPACE_BYTES) { "Insufficient space for update" }
                    val total = asset.size.takeIf { it > 0 } ?: body.contentLength()
                    body.byteStream().use { input ->
                        partial.outputStream().buffered().use { target ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (downloaded + count > asset.size) packageMismatch = true
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
                if (downloaded != asset.size || (!skipVerification && !verifyDigest(partial, asset.digest))) {
                    packageMismatch = true
                    partial.delete()
                    mutableState.value = AppUpdateState.Error(UpdateFailure.DIGEST, release)
                    return@runCatching
                }
                check(partial.renameTo(output)) { "Could not finalize update" }
                // Android's installer still rejects an update signed by a different key.
                if (!skipVerification && !verifyApkIdentity(context, output)) {
                    output.delete()
                    mutableState.value = AppUpdateState.Error(UpdateFailure.DIGEST, release)
                    return@runCatching
                }
                if (!crashRecovery) Settings.shared.pendingUpdateReleaseJson = json.encodeToString(release)
                mutableState.value = AppUpdateState.Downloaded(release, output, verified = !skipVerification)
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
        packageMismatch
    }

    fun cancelDownload() {
        activeDownload?.cancel()
    }

    // Before Android 8 unknown sources is a global setting checked by the installer prompt itself.
    fun canInstallPackages(
        context: Context,
        crashRecovery: Boolean = false,
    ): Boolean =
        installMethod(crashRecovery) != UpdateInstallMethod.SYSTEM ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun installMethod(crashRecovery: Boolean = false): UpdateInstallMethod {
        val method =
            UpdateInstallMethod.entries.firstOrNull { it.name == Settings.shared.updateInstallMethod } ?: UpdateInstallMethod.SYSTEM
        // Shizuku hands its binder only to the main process, and crash recovery runs in the :crash process.
        return if (crashRecovery && method == UpdateInstallMethod.SHIZUKU) UpdateInstallMethod.SYSTEM else method
    }

    /** Switches the install method after the user grants root or Shizuku access, then claims Metrolist as its own installer. */
    suspend fun selectInstallMethod(
        context: Context,
        method: UpdateInstallMethod,
    ): PrivilegedAccess {
        val access = PrivilegedInstaller.requestAccess(method)
        if (access != PrivilegedAccess.GRANTED) return access
        Settings.shared.updateInstallMethod = method.name
        if (method != UpdateInstallMethod.SYSTEM) {
            withContext(Dispatchers.IO) { PrivilegedInstaller.claimInstaller(method, context.packageName) }
        }
        return access
    }

    /** True when an update can install with no prompt: through root or Shizuku, or on Android 12+ when Metrolist may install apps and owns its updates. */
    fun canInstallWithoutPrompt(context: Context): Boolean =
        when (installMethod()) {
            UpdateInstallMethod.ROOT -> {
                true
            }

            UpdateInstallMethod.SHIZUKU -> {
                PrivilegedInstaller.shizukuGranted()
            }

            UpdateInstallMethod.SYSTEM -> {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    context.packageManager.canRequestPackageInstalls() &&
                    ownsUpdates(context)
            }
        }

    // Android 12+ skips the prompt when an app updates itself, unless Android 14+ gave update ownership to another store.
    @RequiresApi(Build.VERSION_CODES.S)
    private fun ownsUpdates(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            runCatching {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .updateOwnerPackageName
                    .let { it == null || it == context.packageName }
            }.getOrDefault(false)

    /** Downloads [release] and installs it once the user stops playback. Installing replaces the running app. */
    suspend fun autoInstall(
        context: Context,
        release: GitHubRelease,
        playbackActive: Flow<Boolean>,
    ) {
        // After a failed install (root revoked, Shizuku stopped) wait for the next launch instead of retrying hourly.
        if (autoInstallFailed || !Settings.shared.autoInstallUpdates || !canInstallWithoutPrompt(context)) return
        if (!autoInstallRunning.compareAndSet(false, true)) return
        try {
            backgroundUpdateKey = release.updateKey
            try {
                download(context, release)
            } finally {
                // A rebuilt nightly replaces the requested release during download; record it even if this job is cancelled.
                (mutableState.value as? AppUpdateState.Downloaded)
                    ?.takeIf { it.release.version == release.version }
                    ?.let { backgroundUpdateKey = it.release.updateKey }
            }
            playbackActive.first { !it }
            val downloaded = mutableState.value as? AppUpdateState.Downloaded ?: return
            if (downloaded.installing || playbackActive.first()) return
            if (!Settings.shared.autoInstallUpdates || !canInstallWithoutPrompt(context)) return
            if (!install(context, downloaded)) autoInstallFailed = true
        } finally {
            autoInstallRunning.set(false)
        }
    }

    suspend fun install(
        context: Context,
        downloaded: AppUpdateState.Downloaded,
        crashRecovery: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            nextAttemptToken()
            cancelInstallResultNotification(context)
            mutableState.value = downloaded.copy(installing = true)
            if (!downloaded.file.isFile || (downloaded.verified && !verifyApkIdentity(context, downloaded.file))) {
                downloaded.file.delete()
                mutableState.value = AppUpdateState.Available(downloaded.release)
                return@withContext false
            }
            val method = installMethod(crashRecovery)
            if (method != UpdateInstallMethod.SYSTEM) {
                // pm usually replaces this process on success before returning.
                val installed = PrivilegedInstaller.install(method, context.packageName, downloaded.file)
                onInstallFinished(context = context, success = installed, crashRecovery = crashRecovery)
                return@withContext installed
            }
            val installer = context.packageManager.packageInstaller
            var sessionId = -1
            runCatching {
                val staleIds = installer.mySessions.mapTo(mutableSetOf()) { it.sessionId }
                ignoredSessionIds = staleIds
                activeSessionId = -1
                pendingConfirmationIntent = null
                pendingConfirmationSessionId = -1
                staleIds.forEach { staleId ->
                    runCatching { installer.abandonSession(staleId) }
                }
                val params =
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(context.packageName)
                        setSize(downloaded.file.length())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                        }
                    }
                sessionId = installer.createSession(params)
                activeSessionId = sessionId
                registerSessionCallback(installer, sessionId)
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
            }.onFailure { error ->
                activeSessionId = -1
                pendingConfirmationIntent = null
                pendingConfirmationSessionId = -1
                unregisterSessionCallback(installer)
                if (sessionId >= 0) {
                    runCatching { installer.abandonSession(sessionId) }
                }
                mutableState.value =
                    if (downloaded.file.isFile) {
                        downloaded.copy(installing = false)
                    } else {
                        AppUpdateState.Error(UpdateFailure.INSTALL, downloaded.release, error.localizedMessage)
                    }
            }.isSuccess
        }

    internal fun launchConfirmationPrompt(
        context: Context,
        confirm: Intent,
        sessionId: Int = -1,
        crashRecovery: Boolean = false,
    ): Boolean {
        if (isStaleSession(sessionId)) return true
        val foreground = foregroundActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (foreground != null && runCatching { foreground.startActivity(confirm) }.isSuccess) {
            pendingConfirmationIntent = null
            pendingConfirmationSessionId = -1
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val started =
                runCatching {
                    context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            if (started) {
                pendingConfirmationIntent = null
                pendingConfirmationSessionId = -1
                return true
            }
        }
        val notified = notifyInstallPrompt(context, confirm, sessionId, crashRecovery)
        if (notified) {
            // Replay the confirmation automatically if the user returns to the update screen instead of tapping the notification.
            pendingConfirmationIntent = Intent(confirm)
            pendingConfirmationSessionId = sessionId
        } else {
            pendingConfirmationIntent = null
            pendingConfirmationSessionId = -1
        }
        return notified
    }

    internal fun abandonPendingSession(
        context: Context,
        sessionId: Int,
    ) {
        if (sessionId < 0) return
        ignoredSessionIds = ignoredSessionIds + sessionId
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    internal fun onInstallFinished(
        context: Context? = null,
        success: Boolean,
        status: Int = PackageInstaller.STATUS_SUCCESS,
        message: String? = null,
        sessionId: Int = -1,
        crashRecovery: Boolean = false,
    ) {
        if (isStaleSession(sessionId)) return
        activeSessionId = -1
        pendingConfirmationIntent = null
        pendingConfirmationSessionId = -1
        context?.let {
            cancelInstallPromptNotification(it)
            unregisterSessionCallback(it.packageManager.packageInstaller)
        }
        val resolvedMessage = if (success) null else resolveInstallErrorMessage(context, status, message)
        val current = mutableState.value as? AppUpdateState.Downloaded
        if (current != null) {
            if (!success) stopAutoInstallAfterFailure(current.release)
            mutableState.value =
                if (success) {
                    current.copy(installing = false)
                } else {
                    AppUpdateState.Error(
                        UpdateFailure.INSTALL,
                        current.release,
                        resolvedMessage,
                    )
                }
        }
        if (!success && context != null) {
            val foreground = foregroundActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            if (current == null || foreground == null) {
                notifyInstallFailure(context, resolvedMessage, crashRecovery)
            }
        }
    }

    internal fun onInstallAborted(
        context: Context? = null,
        sessionId: Int = -1,
    ) {
        if (isStaleSession(sessionId)) return
        activeSessionId = -1
        pendingConfirmationIntent = null
        pendingConfirmationSessionId = -1
        context?.let {
            cancelInstallPromptNotification(it)
            val installer = it.packageManager.packageInstaller
            unregisterSessionCallback(installer)
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
        }
        val current = mutableState.value as? AppUpdateState.Downloaded ?: return
        stopAutoInstallAfterFailure(current.release)
        mutableState.value = current.copy(installing = false)
    }

    private fun isStaleSession(sessionId: Int): Boolean {
        if (sessionId < 0) return false
        if (sessionId in ignoredSessionIds) return true
        return activeSessionId >= 0 && sessionId != activeSessionId
    }

    internal fun notifyPackageReplaced(context: Context) {
        pendingConfirmationIntent = null
        pendingConfirmationSessionId = -1
        cancelInstallPromptNotification(context)
        if (!BuildConfig.UPDATER_ENABLED || !Settings.shared.updateNotifications || !canPostNotifications(context)) return
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_UPDATES
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                INSTALL_RESULT_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.updates_installed_notification_title, BuildConfig.VERSION_NAME))
                .setContentText(context.getString(R.string.updates_installed_notification_text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(INSTALL_RESULT_NOTIFICATION_ID, notification)
    }

    private fun registerSessionCallback(
        installer: PackageInstaller,
        targetSessionId: Int,
    ) {
        unregisterSessionCallback(installer)
        val callback =
            object : PackageInstaller.SessionCallback() {
                override fun onCreated(sessionId: Int) = Unit

                override fun onBadgingChanged(sessionId: Int) = Unit

                override fun onActiveChanged(
                    sessionId: Int,
                    active: Boolean,
                ) = Unit

                override fun onProgressChanged(
                    sessionId: Int,
                    progress: Float,
                ) = Unit

                override fun onFinished(
                    sessionId: Int,
                    success: Boolean,
                ) {
                    if (sessionId == targetSessionId) {
                        unregisterSessionCallback(installer)
                    }
                }
            }
        activeSessionCallback = callback
        installer.registerSessionCallback(callback, mainHandler)
    }

    private fun unregisterSessionCallback(installer: PackageInstaller) {
        activeSessionCallback?.let { callback ->
            runCatching { installer.unregisterSessionCallback(callback) }
            activeSessionCallback = null
        }
    }

    private fun resolveInstallErrorMessage(
        context: Context?,
        status: Int,
        rawMessage: String?,
    ): String? {
        val statusRes =
            when (status) {
                PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.updates_install_status_blocked
                PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.updates_install_status_conflict
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.updates_install_status_incompatible
                PackageInstaller.STATUS_FAILURE_INVALID -> R.string.updates_install_status_invalid
                PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.updates_install_status_storage
                else -> null
            }
        val statusText = statusRes?.let { res -> context?.getString(res) }
        val detail = rawMessage?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(statusText, detail).distinct().joinToString("\n").ifBlank { null }
    }

    private fun notifyInstallPrompt(
        context: Context,
        confirm: Intent,
        sessionId: Int,
        crashRecovery: Boolean,
    ): Boolean {
        if (!canPostNotifications(context)) return false
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                INSTALL_PROMPT_NOTIFICATION_ID,
                Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val receiver = if (crashRecovery) CrashUpdateInstallReceiver::class.java else UpdateInstallReceiver::class.java
        val dismissIntent =
            PendingIntent.getBroadcast(
                context,
                INSTALL_PROMPT_NOTIFICATION_ID,
                Intent(context, receiver).apply {
                    putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
                    if (sessionId >= 0) putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.updates_install_prompt_notification_title))
                .setContentText(context.getString(R.string.updates_install_prompt_notification_text))
                .setContentIntent(pendingIntent)
                .setDeleteIntent(dismissIntent)
                .setAutoCancel(true)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(INSTALL_PROMPT_NOTIFICATION_ID, notification)
        return true
    }

    private fun cancelInstallPromptNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(INSTALL_PROMPT_NOTIFICATION_ID)
    }

    private fun cancelInstallResultNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(INSTALL_RESULT_NOTIFICATION_ID)
    }

    private fun notifyInstallFailure(
        context: Context,
        errorMessage: String?,
        crashRecovery: Boolean,
    ) {
        if (!Settings.shared.updateNotifications || !canPostNotifications(context)) return
        val detail = errorMessage ?: context.getString(R.string.updates_installer_failed)
        val targetActivity = if (crashRecovery) CrashActivity::class.java else MainActivity::class.java
        val intent =
            Intent(context, targetActivity).apply {
                action = if (crashRecovery) CrashActivity.ACTION_UPDATES else MainActivity.ACTION_UPDATES
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_UPDATE_INSTALL_ERROR, detail)
                currentAttemptToken?.let { putExtra(MainActivity.EXTRA_UPDATE_INSTALL_ATTEMPT, it) }
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                INSTALL_RESULT_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.updates_install_failed_notification_title))
                .setContentText(errorMessage ?: context.getString(R.string.updates_install_failed_notification_text))
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText(errorMessage ?: context.getString(R.string.updates_install_failed_notification_text)),
                ).setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(INSTALL_RESULT_NOTIFICATION_ID, notification)
    }

    private fun canPostNotifications(context: Context): Boolean {
        val hasPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ensureNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    // The package installer reports its result after install() returns, so a later rejection or a declined prompt lands here.
    private fun stopAutoInstallAfterFailure(release: GitHubRelease) {
        if (release.updateKey == backgroundUpdateKey) autoInstallFailed = true
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
            ?.takeIf {
                if (it.isNightly) {
                    followsNightlyUpdates(Settings.shared.updateChannel, currentVersion) && isNewNightly(it, confirm = true)
                } else {
                    it.isPublishedStable && compareVersions(it.version, currentVersion) > 0
                }
            }

    private fun isNewNightly(
        release: GitHubRelease,
        confirm: Boolean,
    ): Boolean {
        val result = checkNightly(currentVersion, release.targetCommit, release.apk?.digest, Settings.shared.installedNightlyDigest)
        if (confirm) Settings.shared.installedNightlyDigest = result.installedDigest
        return result.available
    }

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
        if (Settings.shared.lastNotifiedUpdateVersion == release.updateKey) return
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
        Settings.shared.lastNotifiedUpdateVersion = release.updateKey
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
                    // Android 9 and 10 only read archive signers when GET_SIGNATURES is set.
                    PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
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
