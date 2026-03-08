package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.network.sharedHttpEngine
import com.metrolist.shared.network.sharedHttpTransportHandlesFailures
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.XdgUtils
import com.metrolist.shared.youtube.logging.YtLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

@Serializable
private data class PotRequestBody(
    val content_binding: String,
)

@Serializable
private data class PotResponse(
    val poToken: String,
    val contentBinding: String,
)

class DesktopTokenProvider internal constructor(
    private val client: HttpClient,
    providerUrl: String? = configuredProviderUrl(),
    private val externalProviderKinds: Set<PoTokenProviderKind> = configuredExternalProviderKinds(),
    private val pageBoundRuntime: DesktopPoTokenJavaScriptRuntime? = null,
    private val startManagedServer: (suspend () -> Boolean)? = null,
    private val upcomingVideoIds: () -> List<String> = { emptyList() },
    maintenanceScope: CoroutineScope? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val preferSidecar: Boolean = false,
    elapsedMs: () -> Long = ::playbackElapsedMs,
) : TokenProvider {
    constructor() : this(createTokenHttpClient())

    constructor(
        pageBoundRuntime: DesktopPoTokenJavaScriptRuntime,
        upcomingVideoIds: () -> List<String> = { emptyList() },
    ) : this(
        createTokenHttpClient(),
        pageBoundRuntime = pageBoundRuntime,
        upcomingVideoIds = upcomingVideoIds,
    )

    companion object {
        private const val TAG = "DesktopTokenProvider"
        private const val POT_PROVIDER_REPOSITORY = "jim60105/bgutil-ytdlp-pot-provider-rs"
        private const val POT_PROVIDER_VERSION = "v0.8.1"
        private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        private const val MAX_SIDECAR_DOWNLOAD_BYTES = 64L * 1024L * 1024L
        private const val MAX_SIDECAR_RESPONSE_BYTES = 1 * 1024 * 1024
        private const val PROCESS_STOP_TIMEOUT_SECONDS = 1L
        private const val TOKEN_CACHE_TTL_MS = 10L * 60L * 1000L
        private val POT_RESPONSE_JSON = Json { ignoreUnknownKeys = true }
    }

    private val mutex = Mutex()
    private val pageBoundFailureCooldown = TokenFailureCooldown(elapsedMs)
    private val sidecarFailureCooldown = TokenFailureCooldown(elapsedMs)
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bootstrap: Deferred<Boolean>? = null
    private val maintenanceScope = maintenanceScope ?: bootstrapScope
    private var pageBoundIdleJob: Job? = null

    @Volatile
    private var pageBoundFallbackActivated = false
    private val selectedPageBoundRuntime: DesktopPoTokenJavaScriptRuntime?
        get() = pageBoundRuntime?.takeIf { endpoint.managed && (pageBoundFallbackActivated || (!preferSidecar && it.available)) }
    private val activePageBoundRuntime: DesktopPoTokenJavaScriptRuntime?
        get() = selectedPageBoundRuntime?.takeIf { it.available }
    private val pageBoundTokens = linkedMapOf<String, CachedPageBoundToken>()
    private var cacheVisitorData: String? = null
    private var cacheCookie: String? = null
    private val endpoint = DesktopPoTokenEndpoint.parse(providerUrl)
    private val sidecarClient =
        HttpClient(client.engine) {
            install(HttpFailureLog) { transportHandledByEngine = sharedHttpTransportHandlesFailures }
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 5 * 60 * 1_000L
                connectTimeoutMillis = 30_000L
                socketTimeoutMillis = 60_000L
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    override val capabilities: TokenProviderCapabilities
        get() =
            TokenProviderCapabilities(
                providers =
                    endpoint.providerKinds(externalProviderKinds) +
                        if (activePageBoundRuntime != null) {
                            setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION)
                        } else {
                            emptySet()
                        },
                usesWebView = activePageBoundRuntime != null,
            )
    private var process: Process? = null
    private var pageBoundMinter: DesktopPageBoundPoTokenMinter? = null
    private var minterVisitorData: String? = null
    private var minterCookie: String? = null
    private var playerRequestToken: String? = null
    private var sidecarPlayerRequestToken: String? = null
    private var sidecarPlayerVisitorData: String? = null
    private var sidecarPlayerCookie: String? = null
    private var sidecarPlayerExpiresAtMs = 0L

    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val startTime =
                    Clock.System
                        .now()
                        .toEpochMilliseconds()
                try {
                    activePageBoundRuntime?.let { runtime ->
                        return@withLock pageBoundToken(runtime, videoId, visitorData, cookie)
                    }
                    if (sidecarFailureCooldown.isCoolingDown(visitorData, cookie)) {
                        return@withLock pageBoundRuntime?.takeIf { endpoint.managed && it.available }?.let {
                            pageBoundFallbackActivated = true
                            pageBoundToken(it, videoId, visitorData, cookie)
                        }
                    }
                    if (!ensureServerRunning()) {
                        sidecarFailureCooldown.recordFailure(visitorData, cookie)
                        val fallback =
                            pageBoundRuntime?.takeIf {
                                preferSidecar && !pageBoundFallbackActivated && endpoint.managed &&
                                    it.available
                            }
                        if (fallback == null) return@withLock null
                        pageBoundFallbackActivated = true
                        YtLogger.w(TAG, "PO token sidecar unavailable, falling back to the page-bound WebView")
                        return@withLock pageBoundToken(fallback, videoId, visitorData, cookie)
                    }
                    val cachedPlayerToken =
                        sidecarPlayerRequestToken
                            ?.takeIf {
                                sidecarPlayerVisitorData == visitorData &&
                                    sidecarPlayerCookie == cookie &&
                                    System.currentTimeMillis() < sidecarPlayerExpiresAtMs
                            }?.let { PotResponse(it, visitorData) }
                    val (playerToken, streamingToken) =
                        coroutineScope {
                            val playerRequest = if (cachedPlayerToken == null) async { requestToken(visitorData) } else null
                            val streamingRequest = async { requestToken(videoId) }
                            (cachedPlayerToken ?: checkNotNull(playerRequest).await()) to streamingRequest.await()
                        }
                    if (playerToken.contentBinding != visitorData || streamingToken.contentBinding != videoId) {
                        sidecarFailureCooldown.recordFailure(visitorData, cookie)
                        return@withLock null
                    }
                    sidecarFailureCooldown.reset()
                    if (cachedPlayerToken == null) {
                        sidecarPlayerRequestToken = playerToken.poToken
                        sidecarPlayerVisitorData = visitorData
                        sidecarPlayerCookie = cookie
                        sidecarPlayerExpiresAtMs = System.currentTimeMillis() + TOKEN_CACHE_TTL_MS
                    }
                    YtLogger.d(
                        TAG,
                        "getPoToken provider=sidecar target=visitor+video elapsed=${Clock.System
                            .now()
                            .toEpochMilliseconds() - startTime}ms",
                    )
                    PoTokenResult(
                        playerRequestToken = playerToken.poToken,
                        streamingDataToken = streamingToken.poToken,
                        visitorData = visitorData,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sidecarFailureCooldown.recordFailure(visitorData, cookie)
                    YtLogger.w(TAG, "getPoToken failed target=visitor+video type=${e::class.simpleName}: ${e.message}")
                    null
                }
            }
        }

    private suspend fun requestToken(contentBinding: String): PotResponse {
        val response =
            sidecarClient.post("${endpoint.baseUrl}/get_pot") {
                contentType(ContentType.Application.Json)
                setBody(PotRequestBody(contentBinding))
            }
        if (!response.status.isSuccess()) {
            response.bodyAsChannel().cancel(null)
            error("PO token sidecar request failed with HTTP ${response.status.value}")
        }
        return POT_RESPONSE_JSON.decodeFromString(response.bodyAsTextLimited(MAX_SIDECAR_RESPONSE_BYTES))
    }

    override suspend fun prewarm(cookie: String?) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val startTime = System.currentTimeMillis()
                if (activePageBoundRuntime != null) return@withLock
                if (sidecarFailureCooldown.isCoolingDown(null, cookie)) return@withLock
                if (!ensureServerRunning()) {
                    sidecarFailureCooldown.recordFailure(null, cookie)
                    return@withLock
                }
                try {
                    requestToken("prewarm")
                    sidecarFailureCooldown.reset()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    sidecarFailureCooldown.recordFailure(null, cookie)
                }
                YtLogger.d(
                    TAG,
                    "PO token sidecar prewarmed elapsed=${System.currentTimeMillis() - startTime}ms",
                )
            }
        }
    }

    override suspend fun close() {
        bootstrapScope.cancel()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                bootstrap?.cancelAndJoin()
                bootstrap = null
                resetPageBoundMinter()
                resetSidecarTokenCache()
                pageBoundFailureCooldown.reset()
                sidecarFailureCooldown.reset()
                pageBoundIdleJob?.cancel()
                pageBoundTokens.clear()
                pageBoundRuntime?.reset()
                shutdown()
            }
        }
    }

    override suspend fun invalidateAttestation() {
        mutex.withLock {
            if (pageBoundRuntime?.available == true) pageBoundFallbackActivated = true
            pageBoundIdleJob?.cancel()
            pageBoundTokens.clear()
            resetPageBoundMinter()
            resetSidecarTokenCache()
            pageBoundRuntime?.reset()
        }
    }

    /** Caller must hold mutex; minting failure resets the WebView and returns null. */
    private suspend fun pageBoundToken(
        runtime: DesktopPoTokenJavaScriptRuntime,
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? {
        if (cacheVisitorData != visitorData || cacheCookie != cookie) {
            pageBoundTokens.clear()
            cacheVisitorData = visitorData
            cacheCookie = cookie
        }
        cachedPageBoundToken(videoId)?.let { return it }
        if (!runtime.available || pageBoundFailureCooldown.isCoolingDown(visitorData, cookie)) return null
        pageBoundIdleJob?.cancel()
        return try {
            try {
                val minter = getOrCreatePageBoundMinter(runtime, visitorData, cookie, videoId)
                mintPageBoundToken(minter, videoId, visitorData).also {
                    pageBoundFailureCooldown.reset()
                    YtLogger.d(TAG, "getPoToken provider=WEBPAGE_ATTESTATION target=visitor+video")
                }
            } finally {
                schedulePageBoundRelease(runtime, visitorData)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pageBoundIdleJob?.cancel()
            resetPageBoundMinter()
            pageBoundFailureCooldown.recordFailure(visitorData, cookie)
            runtime.reset()
            YtLogger.w(TAG, "page-bound PO token minter failed type=${e::class.simpleName}")
            null
        }
    }

    private data class CachedPageBoundToken(
        val result: PoTokenResult,
        val expiresAtMs: Long,
    )

    private fun cachedPageBoundToken(videoId: String): PoTokenResult? {
        pageBoundTokens.entries.removeAll { nowMs() >= it.value.expiresAtMs }
        return pageBoundTokens[videoId]?.result
    }

    private suspend fun mintPageBoundToken(
        minter: DesktopPageBoundPoTokenMinter,
        videoId: String,
        visitorData: String,
    ): PoTokenResult {
        val playerToken = playerRequestToken ?: minter.generate(visitorData).also { playerRequestToken = it }
        val result = PoTokenResult(playerToken, minter.generate(videoId), visitorData)
        pageBoundTokens[videoId] = CachedPageBoundToken(result, minter.expiresAtMs)
        while (pageBoundTokens.size > 32) pageBoundTokens.remove(pageBoundTokens.keys.first())
        return result
    }

    private fun schedulePageBoundRelease(
        runtime: DesktopPoTokenJavaScriptRuntime,
        visitorData: String,
    ) {
        pageBoundIdleJob =
            maintenanceScope.launch {
                delay(1_000)
                mutex.withLock {
                    val minter = pageBoundMinter?.takeIf { nowMs() < it.expiresAtMs }
                    if (minter != null) {
                        for (videoId in upcomingVideoIds().distinct().take(2)) {
                            if (cachedPageBoundToken(videoId) != null) continue
                            try {
                                withTimeoutOrNull(1_000) { mintPageBoundToken(minter, videoId, visitorData) }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }
                }
                delay(5_000)
                mutex.withLock {
                    resetPageBoundMinter()
                    runtime.reset()
                }
            }
    }

    private suspend fun getOrCreatePageBoundMinter(
        runtime: DesktopPoTokenJavaScriptRuntime,
        visitorData: String,
        cookie: String?,
        videoId: String,
    ): DesktopPageBoundPoTokenMinter {
        pageBoundMinter
            ?.takeIf { nowMs() < it.expiresAtMs && minterVisitorData == visitorData && minterCookie == cookie }
            ?.let { return it }
        resetPageBoundMinter()
        return DesktopPageBoundPoTokenMinter
            .create(client, runtime, visitorData, cookie, videoId, PAGE_BOUND_PO_TOKEN_USER_AGENT)
            .also {
                pageBoundMinter = it
                minterVisitorData = visitorData
                minterCookie = cookie
            }
    }

    private fun resetPageBoundMinter() {
        pageBoundMinter = null
        minterVisitorData = null
        minterCookie = null
        playerRequestToken = null
    }

    private fun resetSidecarTokenCache() {
        sidecarPlayerRequestToken = null
        sidecarPlayerVisitorData = null
        sidecarPlayerCookie = null
        sidecarPlayerExpiresAtMs = 0L
    }

    private suspend fun ensureServerRunning(): Boolean {
        if (isServerRunning()) return true
        if (!endpoint.managed) {
            YtLogger.w(TAG, "Configured external PO token provider is unavailable")
            return false
        }
        // A token timeout must not restart the roughly 51 MB initial sidecar download.
        val task =
            bootstrap ?: bootstrapScope
                .async {
                    YtLogger.d(TAG, "PO token sidecar not running, attempting start")
                    (startManagedServer ?: ::startServer)()
                }.also { bootstrap = it }
        return try {
            task.await()
        } finally {
            if (task.isCompleted) bootstrap = null
        }
    }

    private suspend fun isServerRunning(): Boolean =
        try {
            sidecarClient.prepareGet("${endpoint.baseUrl}/ping").execute { response ->
                response.bodyAsChannel().cancel(null)
                response.status.isSuccess()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }

    private suspend fun startServer(): Boolean {
        val architecture = System.getProperty("os.arch").lowercase()
        val isArm64 = architecture.contains("aarch64") || architecture.contains("arm64")
        val isX64 = architecture.contains("amd64") || architecture.contains("x86_64") || architecture == "x64"
        val binarySpec =
            when {
                OsUtil.isWindows && isX64 -> {
                    BinarySpec(
                        resourceRelativePath = "windows/bgutil-ytdlp-pot-provider-rs.exe",
                        assetName = "bgutil-pot-windows-x86_64.exe",
                        assetSha256 = "25d6b05c79176aa792454c3d1727922ca47e56cf11cb1e866615d751819b14a0",
                        binaryFileName = "bgutil-ytdlp-pot-provider-rs.exe",
                    )
                }

                OsUtil.isMac && isArm64 -> {
                    BinarySpec(
                        resourceRelativePath = "macos/bgutil-ytdlp-pot-provider-rs",
                        assetName = "bgutil-pot-macos-aarch64",
                        assetSha256 = "34b83baf0a557fecaa6d67a8177e53e169c2ccf987182883a4bae289a7176883",
                        binaryFileName = "bgutil-ytdlp-pot-provider-rs",
                    )
                }

                OsUtil.isMac && isX64 -> {
                    BinarySpec(
                        resourceRelativePath = "macos/bgutil-ytdlp-pot-provider-rs",
                        assetName = "bgutil-pot-macos-x86_64",
                        assetSha256 = "0391175fa938c7fabbb8b40a40bd43182ef75af97e1dd3fab56eb23b4ac3e113",
                        binaryFileName = "bgutil-ytdlp-pot-provider-rs",
                    )
                }

                OsUtil.isLinux && isArm64 -> {
                    BinarySpec(
                        resourceRelativePath = "linux/bgutil-ytdlp-pot-provider-rs",
                        assetName = "bgutil-pot-linux-aarch64",
                        assetSha256 = "4f4a1f681dba45e695e1c14d314517da180a1fd374afd09d634fd80ef6d0284b",
                        binaryFileName = "bgutil-ytdlp-pot-provider-rs",
                    )
                }

                OsUtil.isLinux && isX64 -> {
                    BinarySpec(
                        resourceRelativePath = "linux/bgutil-ytdlp-pot-provider-rs",
                        assetName = "bgutil-pot-linux-x86_64",
                        assetSha256 = "e7c264a574fa2705b6e5dc62283a8a4e80130f27b9d7e9df44e6b09aa6151a87",
                        binaryFileName = "bgutil-ytdlp-pot-provider-rs",
                    )
                }

                else -> {
                    null
                }
            }
        if (binarySpec == null) {
            YtLogger.w(TAG, "PO token sidecar is unavailable for os=${OsUtil.name} architecture=$architecture")
            return false
        }

        val path =
            bundledSidecarPath(binarySpec)
                ?: cachedSidecarPath(binarySpec)
        if (path == null) {
            YtLogger.w(TAG, "Bundled PO token sidecar is unavailable or not executable")
            return false
        }

        var launchedProcess: Process? = null
        try {
            val pb =
                ProcessBuilder(
                    path,
                    "server",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    endpoint.port.toString(),
                ).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            val p = pb.start()
            launchedProcess = p
            process = p
            val deadlineMs = 5_000L
            val pollIntervalMs = 100L
            val startMs = System.currentTimeMillis()
            var started = false
            while (System.currentTimeMillis() - startMs < deadlineMs) {
                Thread.sleep(pollIntervalMs)
                if (!p.isAlive) break
                if (isServerRunning()) {
                    started = true
                    break
                }
            }
            if (started) {
                YtLogger.d(TAG, "PO token sidecar started")
                return true
            } else {
                stopProcess(p)
                process = null
            }
        } catch (e: CancellationException) {
            launchedProcess?.let(::stopProcess)
            process = null
            throw e
        } catch (e: Exception) {
            launchedProcess?.let(::stopProcess)
            process = null
            if (e is InterruptedException) Thread.currentThread().interrupt()
            YtLogger.w(TAG, "Failed to start bundled PO token sidecar type=${e::class.simpleName}")
            return false
        }
        YtLogger.w(TAG, "Bundled PO token sidecar exited before it could accept connections")
        return false
    }

    /** Rejects bundled placeholders without ELF, Mach-O or PE magic so sidecar download can proceed. */
    private fun bundledSidecarPath(spec: BinarySpec): String? {
        val resourcesDir = System.getProperty("compose.application.resources.dir")
        val candidates =
            if (resourcesDir != null) {
                val dir = File(resourcesDir)
                listOf(
                    File(dir, spec.resourceRelativePath),
                    File(dir, spec.binaryFileName),
                )
            } else {
                listOf(
                    File("desktopApp/src/desktopMain/resources", spec.resourceRelativePath),
                    File("shared/src/desktopMain/resources", spec.resourceRelativePath),
                )
            }
        val candidate = candidates.firstOrNull { isLikelyNativeBinary(it) } ?: return null
        if (!candidate.canExecute()) {
            candidate.setExecutable(true)
            if (!candidate.canExecute()) return null
        }
        return candidate.absolutePath
    }

    private fun isLikelyNativeBinary(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) return false
                if (
                    header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
                ) {
                    return true
                }
                if (
                    header[0] == 0xCF.toByte() &&
                    header[1] == 0xFA.toByte() &&
                    header[2] == 0xED.toByte() &&
                    header[3] == 0xFE.toByte()
                ) {
                    return true
                }
                if (
                    header[0] == 0xFE.toByte() &&
                    header[1] == 0xED.toByte() &&
                    header[2] == 0xFA.toByte() &&
                    header[3] == 0xCE.toByte()
                ) {
                    return true
                }
                if (header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()) return true
                false
            }
        }.getOrDefault(false)
    }

    /** Downloads the upstream sidecar into $XDG_CACHE_HOME/metrolist/bgutil-pot-provider when absent. */
    private suspend fun cachedSidecarPath(spec: BinarySpec): String? {
        val cacheRoot = File(XdgUtils.getXdgCacheHome(), "metrolist/bgutil-pot-provider")
        val target = File(cacheRoot, spec.binaryFileName)
        if (isLikelyNativeBinary(target)) {
            if (sha256(target) == spec.assetSha256) {
                if (target.canExecute()) return target.absolutePath
                if (target.setExecutable(true) && target.canExecute()) return target.absolutePath
            } else {
                YtLogger.w(TAG, "Cached PO token sidecar failed its pinned SHA-256 check")
            }
        }

        val download = File(cacheRoot, "${spec.assetName}.download")
        return try {
            cacheRoot.mkdirs()
            download.delete()
            downloadAsset(spec.assetName, download)
            check(sha256(download) == spec.assetSha256) { "Downloaded PO token sidecar failed its SHA-256 check" }
            check(isLikelyNativeBinary(download)) { "Downloaded PO token sidecar is not a native executable" }
            runCatching {
                Files.move(
                    download.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(download.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (!target.setExecutable(true) || !target.canExecute()) {
                YtLogger.w(TAG, "Downloaded PO token sidecar is not executable")
                null
            } else {
                target.absolutePath
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.w(TAG, "Bootstrap of PO token sidecar failed type=${e::class.simpleName}")
            null
        } finally {
            download.delete()
        }
    }

    private suspend fun downloadAsset(
        assetName: String,
        destination: File,
    ) {
        val url =
            "https://github.com/$POT_PROVIDER_REPOSITORY/releases/download/$POT_PROVIDER_VERSION/$assetName"
        YtLogger.d(TAG, "Downloading pinned PO token sidecar asset=$assetName version=$POT_PROVIDER_VERSION")
        client.prepareGet(url).execute { response ->
            check(response.status.isSuccess()) { "PO token sidecar download returned HTTP ${response.status.value}" }
            val expectedLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            check(expectedLength == null || expectedLength in 1..MAX_SIDECAR_DOWNLOAD_BYTES) {
                "PO token sidecar download exceeded its size limit"
            }
            var downloadedLength = 0L
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            FileOutputStream(destination).use { output ->
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count == -1) break
                    if (count == 0) continue
                    check(downloadedLength <= MAX_SIDECAR_DOWNLOAD_BYTES - count) {
                        "PO token sidecar download exceeded its size limit"
                    }
                    output.write(buffer, 0, count)
                    downloadedLength += count
                }
            }
            check(downloadedLength > 0L) { "PO token sidecar download was empty" }
            check(expectedLength == null || downloadedLength == expectedLength) {
                "PO token sidecar download was incomplete"
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class BinarySpec(
        val resourceRelativePath: String,
        val assetName: String,
        val assetSha256: String,
        val binaryFileName: String,
    )

    fun shutdown() {
        bootstrapScope.cancel()
        process?.let(::stopProcess)
        process = null
        runCatching { sidecarClient.close() }
        runCatching { client.close() }
    }

    private fun stopProcess(activeProcess: Process) {
        activeProcess.destroyForcibly()
        val stopped =
            runCatching {
                activeProcess.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)
        if (!stopped && activeProcess.isAlive) YtLogger.w(TAG, "PO token sidecar did not stop within the cleanup timeout")
    }
}

internal data class DesktopPoTokenEndpoint(
    val baseUrl: String,
    val host: String,
    val port: Int,
    val managed: Boolean,
) {
    companion object {
        private const val DEFAULT_URL = "http://127.0.0.1:4416"

        fun parse(configuredUrl: String?): DesktopPoTokenEndpoint {
            val managed = configuredUrl.isNullOrBlank()
            val url = Url(if (managed) DEFAULT_URL else configuredUrl.trim())
            require(url.protocol == URLProtocol.HTTP) { "PO token provider must use loopback HTTP" }
            require(url.host == "127.0.0.1" || url.host == "::1") { "PO token provider must use a loopback address" }
            require(
                (url.encodedPath.isEmpty() || url.encodedPath == "/") &&
                    url.parameters.isEmpty() &&
                    url.fragment.isEmpty() &&
                    url.user == null &&
                    url.password == null,
            ) {
                "PO token provider URL must not contain user info, a path, query, or fragment"
            }
            return DesktopPoTokenEndpoint(
                baseUrl = url.toString().trimEnd('/'),
                host = url.host,
                port = url.port,
                managed = managed,
            )
        }
    }
}

private fun configuredProviderUrl(): String? =
    System
        .getProperty("innerTubePoTokenProviderUrl")
        ?.takeIf(String::isNotBlank)
        ?: System.getenv("INNERTUBE_PO_TOKEN_PROVIDER_URL")?.takeIf(String::isNotBlank)

private fun configuredExternalProviderKinds(): Set<PoTokenProviderKind> {
    val configured =
        System
            .getProperty("innerTubePoTokenProviderCapabilities")
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("INNERTUBE_PO_TOKEN_PROVIDER_CAPABILITIES")?.takeIf(String::isNotBlank)
            ?: return setOf(PoTokenProviderKind.WEB_BOTGUARD)
    val supported = setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION)
    return configured
        .split(',')
        .map { value ->
            val kind = PoTokenProviderKind.valueOf(value.trim().uppercase())
            require(kind in supported) { "Unsupported desktop PO token provider capability: $kind" }
            kind
        }.toSet()
        .also { require(it.isNotEmpty()) { "At least one desktop PO token provider capability is required" } }
}

internal fun DesktopPoTokenEndpoint.providerKinds(externalProviderKinds: Set<PoTokenProviderKind>): Set<PoTokenProviderKind> {
    if (managed) return setOf(PoTokenProviderKind.WEB_BOTGUARD)
    require(externalProviderKinds.isNotEmpty()) { "At least one external PO token provider capability is required" }
    require(
        externalProviderKinds.all {
            it == PoTokenProviderKind.WEB_BOTGUARD || it == PoTokenProviderKind.WEBPAGE_ATTESTATION
        },
    ) { "Desktop external PO token providers may declare only Web capabilities" }
    return externalProviderKinds
}

private fun createTokenHttpClient(): HttpClient =
    HttpClient(sharedHttpEngine) {
        install(HttpFailureLog) { transportHandledByEngine = sharedHttpTransportHandlesFailures }
        install(HttpTimeout) {
            requestTimeoutMillis = 5 * 60 * 1_000L
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = 60_000L
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
