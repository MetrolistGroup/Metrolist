package com.metrolist.android.playlistimport

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.metrolist.android.R
import com.metrolist.shared.playlistimport.CsvColumnMapping
import com.metrolist.shared.playlistimport.ImportProgressState
import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.PlaylistImportStatusManager
import com.metrolist.shared.playlistimport.UnifiedPlaylistImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

internal enum class PlaylistImportPreparationType {
    IMPORT_DATA,
    ACCOUNT_PLAYLISTS,
    SPOTIFY_PLAYLISTS,
    SPOTIFY_TRACKS,
    APPLE_PLAYLISTS,
    APPLE_TRACKS,
}

internal data class PlaylistImportPreparationResult(
    val requestId: String,
    val type: PlaylistImportPreparationType,
    val result: Result<Any?>,
    val previous: PlaylistImportPreparationResult? = null,
    val csvMapping: CsvColumnMapping? = null,
) {
    fun close() {
        try {
            (result.getOrNull() as? AutoCloseable)?.close()
        } finally {
            previous?.close()
        }
    }
}

internal class PlaylistImportPreparationStore {
    private val _result = MutableStateFlow<PlaylistImportPreparationResult?>(null)
    val result: StateFlow<PlaylistImportPreparationResult?> = _result.asStateFlow()

    @Synchronized
    fun publish(result: PlaylistImportPreparationResult) {
        check(_result.value == null) { "A playlist import result is already waiting" }
        _result.value = result
    }

    fun current(requestId: String): PlaylistImportPreparationResult? = _result.value?.takeIf { it.requestId == requestId }

    @Synchronized
    fun clear(requestId: String? = null) {
        if (requestId == null || _result.value?.requestId == requestId) take()?.close()
    }

    @Synchronized
    fun take(): PlaylistImportPreparationResult? = _result.value.also { _result.value = null }

    @Synchronized
    fun restorePrevious(): Boolean {
        val previous = _result.value?.previous ?: return false
        _result.value = previous
        return true
    }

    @Synchronized
    fun replaceImportData(
        data: ImportedPlaylistData,
        csvMapping: CsvColumnMapping? = null,
    ) {
        val current = checkNotNull(_result.value) { "No playlist import result is waiting" }
        _result.value =
            current.copy(
                type = PlaylistImportPreparationType.IMPORT_DATA,
                result = Result.success(data),
                previous = if (csvMapping == null) current else current.copy(csvMapping = csvMapping),
                csvMapping = null,
            )
    }

    fun hasResult(): Boolean = _result.value != null
}

object AndroidPlaylistImportTransfer {
    private val requestCounter = AtomicLong()
    private val preparationStore = PlaylistImportPreparationStore()
    private var pending: PendingRequest? = null
    private var runningId: String? = null

    internal val preparationResult: StateFlow<PlaylistImportPreparationResult?> = preparationStore.result

    suspend fun <T> runForeground(
        context: Context,
        playlistTitle: String,
        source: String,
        completed: ((T) -> ImportProgressState.Completed)? = null,
        block: suspend ((ImportProgressState) -> Unit) -> T,
    ): Result<T> {
        val request =
            createRequest(
                context = context,
                playlistTitle = playlistTitle,
                source = source,
                preparationType = null,
                completed = completed,
                block = block,
            ).getOrElse { return Result.failure(it) }
        startService(context, request)
        @Suppress("UNCHECKED_CAST")
        return request.result.await().map { it as T }
    }

    internal fun <T> prepareForeground(
        context: Context,
        playlistTitle: String,
        source: String,
        type: PlaylistImportPreparationType,
        replaceRetainedResult: Boolean = false,
        block: suspend ((ImportProgressState) -> Unit) -> T,
    ): Result<String> {
        val request =
            createRequest(
                context,
                playlistTitle,
                source,
                type,
                completed = null,
                replaceRetainedResult = replaceRetainedResult,
                block = block,
            ).getOrElse { return Result.failure(it) }
        return startService(context, request).map { request.requestId }
    }

    suspend fun start(
        context: Context,
        manager: PlaylistImportManager,
        data: ImportedPlaylistData,
        title: String,
        destination: PlaylistImportManager.Destination,
    ): Result<ImportProgressState.Completed> =
        runForeground(
            context = context,
            playlistTitle = title,
            source = data.source,
            completed = { it },
        ) { update ->
            UnifiedPlaylistImporter
                .executeImport(
                    manager = manager,
                    title = title,
                    tracks = data.tracks,
                    destination = destination,
                    onStateUpdate = update,
                    provider = data.provider,
                    sourceYouTubePlaylistId = data.sourceYouTubePlaylistId,
                    coverUrl = data.coverUrl,
                ).getOrThrow()
        }

    private fun <T> createRequest(
        context: Context,
        playlistTitle: String,
        source: String,
        preparationType: PlaylistImportPreparationType?,
        completed: ((T) -> ImportProgressState.Completed)?,
        replaceRetainedResult: Boolean = false,
        block: suspend ((ImportProgressState) -> Unit) -> T,
    ): Result<PendingRequest> =
        synchronized(this) {
            if (
                pending != null ||
                runningId != null ||
                PlaylistImportStatusManager.activeImport.value?.isRunning == true ||
                (preparationStore.hasResult() && !replaceRetainedResult)
            ) {
                return@synchronized Result.failure(IllegalStateException(context.getString(R.string.backup_restore_busy)))
            }
            val previous = if (replaceRetainedResult) preparationStore.take() else null
            val statusId = PlaylistImportStatusManager.startImport(playlistTitle, source)
            Result.success(
                PendingRequest(
                    requestId = requestCounter.incrementAndGet().toString(),
                    statusId = statusId,
                    playlistTitle = playlistTitle,
                    source = source,
                    preparationType = preparationType,
                    previousPreparation = previous,
                    result = CompletableDeferred(),
                    work = { update ->
                        val value = block(update)
                        TaskOutcome(value, completed?.invoke(value))
                    },
                ).also { pending = it },
            )
        }

    private fun startService(
        context: Context,
        request: PendingRequest,
    ): Result<Unit> {
        val intent =
            Intent(context, AndroidPlaylistImportService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_REQUEST_ID, request.requestId)
            }
        return try {
            ContextCompat.startForegroundService(context, intent)
            Result.success(Unit)
        } catch (error: Exception) {
            abandon(request.requestId, error)
            Result.failure(error)
        }
    }

    @Synchronized
    internal fun take(requestId: String): PendingRequest? {
        val request = pending?.takeIf { it.requestId == requestId } ?: return null
        pending = null
        runningId = requestId
        return request
    }

    @Synchronized
    internal fun complete(
        request: PendingRequest,
        result: Result<Any?>,
        replayPreparation: Boolean = true,
    ) {
        if (replayPreparation && request.preparationType != null) request.preparationResult = result
        request.result.complete(result)
    }

    @Synchronized
    internal fun finish(request: PendingRequest) {
        if (runningId == request.requestId) runningId = null
        if (pending?.requestId == request.requestId) pending = null
        val type = request.preparationType
        val result = request.preparationResult
        if (type != null && result != null) {
            preparationStore.publish(PlaylistImportPreparationResult(request.requestId, type, result, request.previousPreparation))
        } else {
            request.previousPreparation?.close()
        }
    }

    internal fun currentPreparationResult(requestId: String): PlaylistImportPreparationResult? = preparationStore.current(requestId)

    internal fun clearPreparationResult(requestId: String) = preparationStore.clear(requestId)

    internal fun replacePreparationResult(
        data: ImportedPlaylistData,
        csvMapping: CsvColumnMapping? = null,
    ) = preparationStore.replaceImportData(data, csvMapping)

    internal fun restorePreviousPreparation(): Boolean = preparationStore.restorePrevious()

    @Synchronized
    internal fun cancel(requestId: String): Boolean {
        val request = pending?.takeIf { it.requestId == requestId } ?: return false
        pending = null
        PlaylistImportStatusManager.cancel(request.statusId)
        complete(request, Result.failure(CancellationException()), replayPreparation = false)
        request.previousPreparation?.close()
        return true
    }

    @Synchronized
    private fun abandon(
        requestId: String,
        error: Throwable,
    ) {
        val request = pending?.takeIf { it.requestId == requestId } ?: return
        pending = null
        PlaylistImportStatusManager.fail(request.statusId, ImportProgressState.Failed(error.message.orEmpty(), error))
        request.result.complete(Result.failure(error))
        request.preparationType?.let { type ->
            preparationStore.publish(PlaylistImportPreparationResult(requestId, type, Result.failure(error), request.previousPreparation))
        }
    }

    internal data class PendingRequest(
        val requestId: String,
        val statusId: String,
        val playlistTitle: String,
        val source: String,
        val preparationType: PlaylistImportPreparationType?,
        val result: CompletableDeferred<Result<Any?>>,
        val work: suspend ((ImportProgressState) -> Unit) -> TaskOutcome,
        var preparationResult: Result<Any?>? = null,
        val previousPreparation: PlaylistImportPreparationResult? = null,
    )

    internal data class TaskOutcome(
        val value: Any?,
        val completed: ImportProgressState.Completed?,
    )

    const val ACTION_START = "com.metrolist.android.playlistimport.START"
    const val ACTION_CANCEL = "com.metrolist.android.playlistimport.CANCEL"
    const val EXTRA_REQUEST_ID = "request_id"
}

class AndroidPlaylistImportService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var latestStartId = 0

    @Volatile
    private var runningRequestId: String? = null

    @Volatile
    private var runningStatusId: String? = null

    @Volatile
    private var runningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AndroidPlaylistImportNotifier.ensureChannel(this)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        latestStartId = startId
        val requestId = intent?.getStringExtra(AndroidPlaylistImportTransfer.EXTRA_REQUEST_ID)
        if (intent?.action == AndroidPlaylistImportTransfer.ACTION_CANCEL) {
            val cancelledRunning = requestId != null && requestId == runningRequestId
            if (cancelledRunning) {
                runningStatusId?.let(PlaylistImportStatusManager::cancel)
                runningJob?.cancel()
            }
            val cancelledPending = requestId != null && AndroidPlaylistImportTransfer.cancel(requestId)
            if (cancelledRunning || cancelledPending || runningRequestId == null) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        if (intent?.action != AndroidPlaylistImportTransfer.ACTION_START || requestId == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val request = AndroidPlaylistImportTransfer.take(requestId)
        if (request == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        runningRequestId = request.requestId
        runningStatusId = request.statusId
        ServiceCompat.startForeground(
            this,
            AndroidPlaylistImportNotifier.PROGRESS_NOTIFICATION_ID,
            AndroidPlaylistImportNotifier.progressNotification(
                context = this,
                requestId = request.requestId,
                playlistTitle = request.playlistTitle,
                statusText = getString(R.string.import_status_fetching_metadata),
                current = 0,
                total = null,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (PlaylistImportStatusManager.activeImport.value?.id != request.statusId) {
            AndroidPlaylistImportTransfer.complete(
                request,
                Result.failure(CancellationException()),
                replayPreparation = false,
            )
            finishRequest(request)
            return START_NOT_STICKY
        }

        val job =
            serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val outcome =
                        try {
                            withTimeout(IMPORT_TIMEOUT_MILLIS.milliseconds) {
                                request.work { state ->
                                    PlaylistImportStatusManager.updateProgress(request.statusId, state)
                                    showProgress(request, state)
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            throw ImportTimeoutException()
                        }
                    outcome.completed?.let { completed ->
                        PlaylistImportStatusManager.complete(request.statusId, completed)
                        AndroidPlaylistImportNotifier.showSuccess(
                            this@AndroidPlaylistImportService,
                            completed.playlistTitle,
                            completed.importedTracks,
                            completed.unresolvedTracks,
                        )
                    } ?: run {
                        PlaylistImportStatusManager.clear(request.statusId)
                        AndroidPlaylistImportNotifier.cancelProgress(this@AndroidPlaylistImportService)
                    }
                    AndroidPlaylistImportTransfer.complete(request, Result.success(outcome.value))
                } catch (error: ImportTimeoutException) {
                    failRequest(request, error)
                } catch (error: CancellationException) {
                    PlaylistImportStatusManager.finishCancellation(request.statusId, error)
                    val cancelled =
                        PlaylistImportStatusManager.activeImport.value
                            ?.takeIf { it.id == request.statusId }
                            ?.state
                    if (cancelled is ImportProgressState.Cancelled) {
                        AndroidPlaylistImportNotifier.showCancelled(this@AndroidPlaylistImportService, cancelled)
                    } else {
                        AndroidPlaylistImportNotifier.cancelProgress(this@AndroidPlaylistImportService)
                    }
                    AndroidPlaylistImportTransfer.complete(
                        request,
                        Result.failure(error),
                        replayPreparation = false,
                    )
                } catch (error: Throwable) {
                    failRequest(request, error)
                }
            }
        runningJob = job
        job.invokeOnCompletion { finishRequest(request) }
        PlaylistImportStatusManager.attachJob(request.statusId, job)
        job.start()
        return START_NOT_STICKY
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        runningJob?.cancel(ImportTimeoutException())
        super.onTimeout(startId, fgsType)
    }

    override fun onDestroy() {
        runningJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun failRequest(
        request: AndroidPlaylistImportTransfer.PendingRequest,
        error: Throwable,
    ) {
        val message = error.message ?: getString(R.string.import_error_generic)
        val failed = ImportProgressState.Failed(message, error)
        PlaylistImportStatusManager.fail(request.statusId, failed)
        AndroidPlaylistImportNotifier.showFailure(this, request.playlistTitle, message)
        AndroidPlaylistImportTransfer.complete(request, Result.failure(error))
    }

    private fun finishRequest(request: AndroidPlaylistImportTransfer.PendingRequest) {
        if (runningRequestId == request.requestId) {
            runningRequestId = null
            runningStatusId = null
            runningJob = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(latestStartId)
        }
        AndroidPlaylistImportTransfer.finish(request)
    }

    private fun showProgress(
        request: AndroidPlaylistImportTransfer.PendingRequest,
        state: ImportProgressState,
    ) {
        val (current, total) = state.progressCounts()
        AndroidPlaylistImportNotifier.showProgress(
            context = this,
            requestId = request.requestId,
            playlistTitle = request.playlistTitle,
            statusText = state.notificationText(),
            current = current,
            total = total,
        )
    }

    private fun ImportProgressState.progressCounts(): Pair<Int, Int?> =
        when (this) {
            is ImportProgressState.LoadingTracks -> loaded to total
            is ImportProgressState.ResolvingTracks -> resolved to total
            is ImportProgressState.SavingToLibrary -> saved to total
            else -> 0 to null
        }

    private fun ImportProgressState.notificationText(): String =
        when (this) {
            is ImportProgressState.FetchingMetadata -> {
                getString(R.string.import_status_fetching_metadata)
            }

            is ImportProgressState.LoadingTracks -> {
                getString(
                    R.string.import_status_fetching_tracks,
                    loaded,
                    total?.toString() ?: getString(R.string.unknown),
                )
            }

            is ImportProgressState.ResolvingTracks -> {
                getString(R.string.import_status_matching_tracks_short, resolved, total)
            }

            is ImportProgressState.SavingToLibrary -> {
                getString(R.string.import_status_saving_tracks, saved, total)
            }

            is ImportProgressState.Completed -> {
                getString(R.string.import_status_completed)
            }

            is ImportProgressState.Failed -> {
                message
            }

            else -> {
                getString(R.string.import_notifier_title)
            }
        }

    private class ImportTimeoutException : CancellationException()

    companion object {
        private const val IMPORT_TIMEOUT_MILLIS = 6L * 60L * 60L * 1000L
    }
}
