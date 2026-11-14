/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.update

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.update_not_published_stable
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.update.ReleaseVersion
import com.metrolist.shared.utils.OsUtil
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Request
import org.jetbrains.compose.resources.getString
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object DesktopUpdateManager {
    private const val API_BASE = "https://api.github.com/repos/MetrolistGroup/Metrolist"
    internal const val RELEASES_URL = "$API_BASE/releases?per_page=50"
    private const val REGISTRY_PATH = "Software\\Metrolist\\Desktop"
    private const val MAX_MANAGER_BYTES = 128L * 1024 * 1024

    private val client = sharedOkHttpClient
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableDownloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress = mutableDownloadProgress.asStateFlow()

    @Volatile private var activeDownload: Call? = null

    data class RecoveryRelease(
        val version: String,
        val url: String,
        val inApp: Boolean,
    )

    suspend fun checkForRecovery(): Result<RecoveryRelease?> =
        withContext(Dispatchers.IO) {
            runCatching {
                findUpdate(getReleaseJson(RELEASES_URL), BuildConfig.APP_VERSION)
                    ?.let {
                        RecoveryRelease(
                            it.version,
                            it.url,
                            it.canInstall(installationRoot() != null),
                        )
                    }
            }
        }

    suspend fun check(): Result<List<GitHubDesktopRelease>> =
        withContext(Dispatchers.IO) {
            runCatching {
                Settings.shared.lastUpdateCheckAttemptTimestamp = System.currentTimeMillis()
                val releases = parseReleases(getReleaseJson(RELEASES_URL))
                currentCoroutineContext().ensureActive()
                Settings.shared.cachedUpdateReleasesJson = json.encodeToString(releases)
                Settings.shared.lastUpdateCheckTimestamp = System.currentTimeMillis()
                releases
            }.onFailure { if (it is CancellationException) throw it }
        }

    suspend fun cachedReleases(): List<GitHubDesktopRelease> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Settings.shared.updateCacheSource != "desktop-github-releases-metrolist-v1") {
                    Settings.shared.cachedUpdateReleasesJson = ""
                    Settings.shared.lastUpdateCheckTimestamp = 0L
                    Settings.shared.lastUpdateCheckAttemptTimestamp = 0L
                    Settings.shared.updateCacheSource = "desktop-github-releases-metrolist-v1"
                }
                parseReleases(Settings.shared.cachedUpdateReleasesJson)
            }.getOrDefault(emptyList())
        }

    internal fun parseReleases(releasesJson: String): List<GitHubDesktopRelease> =
        json
            .decodeFromString<List<GitHubDesktopRelease>>(releasesJson)
            .filter { !it.draft && ReleaseVersion.parse(it.version) != null }
            .sortedByDescending { ReleaseVersion.parse(it.version) }

    internal fun findUpdate(
        releasesJson: String,
        currentVersion: String,
        includePrereleases: Boolean = true,
    ): GitHubDesktopRelease? =
        parseReleases(releasesJson)
            .firstOrNull { (includePrereleases || !it.prerelease) && isNewerDesktopVersion(it.version, currentVersion) }

    suspend fun canInstall(release: GitHubDesktopRelease): Boolean =
        withContext(Dispatchers.IO) { release.canInstall(installationRoot() != null) }

    fun cancelDownload() {
        activeDownload?.cancel()
    }

    suspend fun downloadAndLaunch(
        version: String,
        onLaunched: suspend () -> Unit = {},
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutableDownloadProgress.value = null
            runCatching {
                val root = checkNotNull(installationRoot()) { "Metrolist installation was not found" }.root
                val release = getRelease("$API_BASE/releases/tags/v$version")
                val errorMsg = getString(Res.string.update_not_published_stable)
                check(release.tagName == "v$version" && release.canInstall(registeredInstallation = true)) { errorMsg }
                val expectedName = "metrolist-installer-windows-x64-$version.exe"
                val asset =
                    checkNotNull(release.installer) {
                        "Release v$version does not contain $expectedName"
                    }
                check(asset.state == "uploaded") { "The update manager is not ready" }
                check(asset.size in 1..MAX_MANAGER_BYTES) { "The update manager has an invalid size" }
                val expectedDigest = parseGitHubSHA256(asset.digest)

                val managerDirectory = root.resolve("manager")
                Files.createDirectories(managerDirectory)
                val partial = managerDirectory.resolve("MetrolistManager.new.exe")
                val destination = managerDirectory.resolve("MetrolistManager.exe")
                Files.deleteIfExists(partial)
                try {
                    download(asset, partial, expectedDigest)
                    Files.move(
                        partial,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    Files.deleteIfExists(partial)
                }

                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    ProcessBuilder(
                        destination.toString(),
                        "update",
                        "--pid",
                        ProcessHandle.current().pid().toString(),
                    ).directory(managerDirectory.toFile()).start()
                    onLaunched()
                }
                Unit
            }.onFailure { if (it is CancellationException) throw it }
                .also { activeDownload = null }
        }

    private fun getRelease(url: String): GitHubDesktopRelease = json.decodeFromString(getReleaseJson(url))

    private fun getReleaseJson(url: String): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "Metrolist-Desktop/${BuildConfig.APP_VERSION}")
                .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub returned ${response.code}" }
            checkNotNull(response.body).string()
        }
    }

    private suspend fun download(
        asset: GitHubDesktopAsset,
        destination: Path,
        expectedDigest: ByteArray,
    ) {
        val request =
            Request
                .Builder()
                .url(asset.downloadUrl)
                .header("User-Agent", "Metrolist-Desktop/${BuildConfig.APP_VERSION}")
                .build()
        val call = client.newCall(request)
        activeDownload = call
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                check(response.isSuccessful) { "Manager download returned ${response.code}" }
                val body = checkNotNull(response.body)
                check(body.contentLength() < 0 || body.contentLength() == asset.size) { "Manager download size mismatch" }
                body.byteStream().buffered().use { input ->
                    Files.newOutputStream(destination).buffered().use { output ->
                        copyVerifiedUpdate(input, output, asset.size, expectedDigest) { mutableDownloadProgress.value = it }
                    }
                }
            }
        } finally {
            activeDownload = null
        }
    }

    private fun installationRoot(): DesktopInstallation? =
        runCatching {
            if (!OsUtil.isWindows) return null
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, REGISTRY_PATH)) return null
            val root =
                Path.of(
                    Advapi32Util.registryGetStringValue(
                        WinReg.HKEY_CURRENT_USER,
                        REGISTRY_PATH,
                        "InstallLocation",
                    ),
                )
            val registeredVersion =
                Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_CURRENT_USER,
                    REGISTRY_PATH,
                    "Version",
                )
            val marker = json.decodeFromString<DesktopInstallMarker>(Files.readString(root.resolve("manager/install.json")))
            check(marker.schema == 1 && marker.appId == "com.metrolist.desktop" && marker.version == registeredVersion) {
                "Metrolist installation metadata is invalid"
            }
            check(Files.isRegularFile(root.resolve("app/Metrolist.exe"))) { "Metrolist installation is incomplete" }
            check(
                isRunningRegisteredDesktop(
                    root,
                    ProcessHandle
                        .current()
                        .info()
                        .command()
                        .orElse(null),
                ),
            ) {
                "The running application is not the registered installation"
            }
            DesktopInstallation(root, registeredVersion)
        }.getOrNull()
}

@Serializable
private data class DesktopInstallMarker(
    val schema: Int,
    val appId: String,
    val version: String,
)

@Serializable
data class GitHubDesktopRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubDesktopAsset>,
) {
    val version: String get() = tagName.removePrefix("v")
    val installer: GitHubDesktopAsset?
        get() = assets.singleOrNull { it.name == "metrolist-installer-windows-x64-$version.exe" }
    val url: String get() = "https://github.com/MetrolistGroup/Metrolist/releases/tag/$tagName"

    internal fun canInstall(registeredInstallation: Boolean): Boolean =
        registeredInstallation && !draft && !prerelease && ReleaseVersion.parse(version)?.prerelease?.isEmpty() == true
}

@Serializable
data class GitHubDesktopAsset(
    val name: String,
    val state: String,
    val size: Long,
    val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String,
)

internal fun parseGitHubSHA256(value: String?): ByteArray {
    val digest = value?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
    require(digest != null && digest.length == 64 && digest.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        "The GitHub asset is missing a valid SHA-256 digest"
    }
    return digest.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

internal fun isNewerDesktopVersion(
    candidate: String,
    current: String,
): Boolean {
    val candidateVersion = ReleaseVersion.parse(candidate) ?: return false
    val currentVersion = ReleaseVersion.parse(current) ?: return false
    return candidateVersion > currentVersion
}

private data class DesktopInstallation(
    val root: Path,
    val version: String,
)

internal fun isRunningRegisteredDesktop(
    root: Path,
    command: String?,
): Boolean = command != null && runCatching { Files.isSameFile(root.resolve("app/Metrolist.exe"), Path.of(command)) }.getOrDefault(false)

internal suspend fun copyVerifiedUpdate(
    input: InputStream,
    output: OutputStream,
    expectedSize: Long,
    expectedDigest: ByteArray,
    onProgress: (Int) -> Unit,
) {
    require(expectedSize > 0)
    val digest = MessageDigest.getInstance("SHA-256")
    var downloaded = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    onProgress(0)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        check(downloaded + count <= expectedSize) { "Manager download exceeded its expected size" }
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        downloaded += count
        onProgress(((downloaded * 100) / expectedSize).toInt())
    }
    currentCoroutineContext().ensureActive()
    check(downloaded == expectedSize) { "Manager download was incomplete" }
    check(MessageDigest.isEqual(digest.digest(), expectedDigest)) { "Manager SHA-256 verification failed" }
}

internal fun desktopUpdateCheckIsDue(
    lastAttempt: Long,
    now: Long,
): Boolean = lastAttempt !in 1..now || now - lastAttempt >= 60 * 60 * 1_000L
