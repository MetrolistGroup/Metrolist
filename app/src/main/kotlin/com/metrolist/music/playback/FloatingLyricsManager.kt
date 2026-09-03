/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.datastore.preferences.core.edit
import androidx.media3.common.Player
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.constants.FloatingLyricsBackgroundAlphaKey
import com.metrolist.music.constants.FloatingLyricsEnabledKey
import com.metrolist.music.constants.FloatingLyricsLockedKey
import com.metrolist.music.constants.FloatingLyricsPositionXKey
import com.metrolist.music.constants.FloatingLyricsPositionYKey
import com.metrolist.music.constants.FloatingLyricsShowNextLineKey
import com.metrolist.music.constants.FloatingLyricsTextSizeKey
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.lyricsTextLooksSynced
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

@Singleton
class FloatingLyricsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var overlayContainer: DraggableFrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var rootLayout: View? = null
    private var headerContainer: LinearLayout? = null
    private var lyricsContainer: LinearLayout? = null
    private var controlsContainer: LinearLayout? = null
    private var textSongInfo: TextView? = null
    private var textCurrentLyric: TextView? = null
    private var textNextLyric: TextView? = null
    private var btnLock: ImageView? = null
    private var btnPlayPause: ImageView? = null

    private var positionTickerJob: Job? = null
    private var autoCollapseJob: Job? = null
    private var preferenceCollectorJob: Job? = null

    private var isControlsExpanded = false
    private var isLocked = false
    private var showNextLine = true
    private var textSizeSp = 15f
    private var backgroundAlpha = 0.85f

    private var currentSongTitle: String? = null
    private var currentArtistName: String? = null
    private var currentLyricsOffset: Int = 0
    private var currentLyricsRaw: String? = null
    private var parsedLyrics: List<LyricsEntry> = emptyList()
    private var isSyncedLyrics: Boolean = false
    private var currentActiveIndex: Int = -1

    var player: Player? = null
        private set
    var musicService: MusicService? = null
        private set
    var isShowing: Boolean = false
        private set

    companion object {
        fun hasPermission(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }

        fun requestPermission(context: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /**
     * Attaches MusicService and Player to this manager, and observes preferences.
     */
    fun attach(service: MusicService, player: Player) {
        this.musicService = service
        this.player = player

        preferenceCollectorJob?.cancel()
        preferenceCollectorJob = scope.launch {
            // Collect enabled state
            launch {
                context.dataStore.data
                    .map { it[FloatingLyricsEnabledKey] ?: false }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        if (enabled) {
                            if (hasPermission(context)) {
                                show()
                            } else {
                                context.dataStore.edit { it[FloatingLyricsEnabledKey] = false }
                                hide()
                            }
                        } else {
                            hide()
                        }
                    }
            }

            // Collect opacity
            launch {
                context.dataStore.data
                    .map { it[FloatingLyricsBackgroundAlphaKey] ?: 0.85f }
                    .distinctUntilChanged()
                    .collect { alpha ->
                        backgroundAlpha = alpha
                        rootLayout?.alpha = alpha
                    }
            }

            // Collect text size
            launch {
                context.dataStore.data
                    .map { it[FloatingLyricsTextSizeKey] ?: 15f }
                    .distinctUntilChanged()
                    .collect { size ->
                        textSizeSp = size
                        applyTextSizes()
                    }
            }

            // Collect show next line
            launch {
                context.dataStore.data
                    .map { it[FloatingLyricsShowNextLineKey] ?: true }
                    .distinctUntilChanged()
                    .collect { nextLine ->
                        showNextLine = nextLine
                        if (!nextLine) {
                            textNextLyric?.visibility = View.GONE
                        } else if (isSyncedLyrics && currentActiveIndex >= 0) {
                            updateLyricViews(currentActiveIndex)
                        }
                    }
            }

            // Collect locked state
            launch {
                context.dataStore.data
                    .map { it[FloatingLyricsLockedKey] ?: false }
                    .distinctUntilChanged()
                    .collect { locked ->
                        isLocked = locked
                        overlayContainer?.isLocked = locked
                        btnLock?.setImageResource(if (locked) R.drawable.lock else R.drawable.lock_open)
                    }
            }
        }
    }

    /**
     * Detaches MusicService when it is destroyed.
     */
    fun detach() {
        preferenceCollectorJob?.cancel()
        preferenceCollectorJob = null
        hide()
        this.player = null
        this.musicService = null
    }

    /**
     * Shows the floating overlay window on the screen.
     */
    @SuppressLint("InflateParams")
    fun show() {
        if (isShowing || overlayContainer != null) return
        if (!hasPermission(context)) {
            Timber.w("Cannot show floating lyrics: overlay permission not granted")
            return
        }

        scope.launch {
            val savedX = context.dataStore.data.map { it[FloatingLyricsPositionXKey] }.first()
            val savedY = context.dataStore.data.map { it[FloatingLyricsPositionYKey] }.first()
            val savedLocked = context.dataStore.data.map { it[FloatingLyricsLockedKey] ?: false }.first()
            val savedAlpha = context.dataStore.data.map { it[FloatingLyricsBackgroundAlphaKey] ?: 0.85f }.first()
            val savedTextSize = context.dataStore.data.map { it[FloatingLyricsTextSizeKey] ?: 15f }.first()
            val savedShowNextLine = context.dataStore.data.map { it[FloatingLyricsShowNextLineKey] ?: true }.first()

            isLocked = savedLocked
            backgroundAlpha = savedAlpha
            textSizeSp = savedTextSize
            showNextLine = savedShowNextLine

            val metrics = context.resources.displayMetrics
            val initialX = savedX ?: ((metrics.widthPixels - (300 * metrics.density).toInt()) / 2).coerceAtLeast(0)
            val initialY = savedY ?: (metrics.heightPixels * 0.65f).toInt()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
            }
            layoutParams = params

            val container = DraggableFrameLayout(
                context = context,
                onDrag = { dx, dy ->
                    params.x = (params.x + dx).coerceIn(0, (metrics.widthPixels - (overlayContainer?.width ?: 0)).coerceAtLeast(0))
                    params.y = (params.y + dy).coerceIn(0, (metrics.heightPixels - (overlayContainer?.height ?: 0)).coerceAtLeast(0))
                    try {
                        windowManager.updateViewLayout(overlayContainer, params)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to update floating lyrics layout")
                    }
                },
                onDragEnd = {
                    scope.launch {
                        context.dataStore.edit {
                            it[FloatingLyricsPositionXKey] = params.x
                            it[FloatingLyricsPositionYKey] = params.y
                        }
                    }
                },
                onClick = {
                    toggleControlsExpanded()
                }
            )
            container.isLocked = isLocked

            val inflater = LayoutInflater.from(context)
            val contentView = inflater.inflate(R.layout.layout_floating_lyrics, container, true)

            rootLayout = contentView.findViewById(R.id.floating_root)
            headerContainer = contentView.findViewById(R.id.header_container)
            lyricsContainer = contentView.findViewById(R.id.lyrics_container)
            controlsContainer = contentView.findViewById(R.id.controls_container)
            textSongInfo = contentView.findViewById(R.id.text_song_info)
            textCurrentLyric = contentView.findViewById(R.id.text_current_lyric)
            textNextLyric = contentView.findViewById(R.id.text_next_lyric)
            btnLock = contentView.findViewById(R.id.btn_lock)
            btnPlayPause = contentView.findViewById(R.id.btn_play_pause)

            val btnClose: ImageView? = contentView.findViewById(R.id.btn_close)
            val btnPrev: ImageView? = contentView.findViewById(R.id.btn_prev)
            val btnNext: ImageView? = contentView.findViewById(R.id.btn_next)
            val btnOpenApp: ImageView? = contentView.findViewById(R.id.btn_open_app)

            // Setup styling
            rootLayout?.alpha = backgroundAlpha
            applyTextSizes()
            btnLock?.setImageResource(if (isLocked) R.drawable.lock else R.drawable.lock_open)

            // Button click listeners
            btnClose?.setOnClickListener {
                dismiss()
            }

            btnLock?.setOnClickListener {
                toggleLock()
            }

            btnPlayPause?.setOnClickListener {
                player?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                }
                resetAutoCollapseTimer()
            }

            btnPrev?.setOnClickListener {
                player?.seekToPrevious()
                resetAutoCollapseTimer()
            }

            btnNext?.setOnClickListener {
                player?.seekToNext()
                resetAutoCollapseTimer()
            }

            btnOpenApp?.setOnClickListener {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(launchIntent)
                collapseControls()
            }

            // Click on song title also opens the app
            textSongInfo?.setOnClickListener {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(launchIntent)
            }

            // Lyrics container click toggles controls
            lyricsContainer?.setOnClickListener {
                toggleControlsExpanded()
            }

            try {
                windowManager.addView(container, params)
                overlayContainer = container
                isShowing = true

                // Refresh content
                updateSong(currentSongTitle, currentArtistName, currentLyricsOffset)
                updateLyrics(currentLyricsRaw)
                updatePlaybackState(player?.isPlaying == true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to add floating lyrics view to WindowManager")
                overlayContainer = null
                isShowing = false
            }
        }
    }

    /**
     * Hides the floating overlay window from the screen.
     */
    fun hide() {
        if (!isShowing && overlayContainer == null) return
        positionTickerJob?.cancel()
        positionTickerJob = null
        autoCollapseJob?.cancel()
        autoCollapseJob = null

        overlayContainer?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove floating lyrics view from WindowManager")
            }
        }

        overlayContainer = null
        rootLayout = null
        headerContainer = null
        lyricsContainer = null
        controlsContainer = null
        textSongInfo = null
        textCurrentLyric = null
        textNextLyric = null
        btnLock = null
        btnPlayPause = null
        isShowing = false
        isControlsExpanded = false
    }

    /**
     * Dismisses the floating lyrics overlay and persists enabled = false.
     */
    fun dismiss() {
        hide()
        scope.launch {
            context.dataStore.edit { it[FloatingLyricsEnabledKey] = false }
        }
    }

    /**
     * Updates the song metadata displayed in the overlay.
     */
    fun updateSong(title: String?, artist: String?, lyricsOffset: Int) {
        currentSongTitle = title
        currentArtistName = artist
        currentLyricsOffset = lyricsOffset

        val songInfo = when {
            !title.isNullOrBlank() && !artist.isNullOrBlank() -> "$title • $artist"
            !title.isNullOrBlank() -> title
            !artist.isNullOrBlank() -> artist
            else -> ""
        }
        textSongInfo?.text = songInfo

        if (isShowing) {
            if (!isSyncedLyrics) {
                textCurrentLyric?.text = title ?: context.getString(R.string.no_song_playing)
                if (!artist.isNullOrBlank()) {
                    textNextLyric?.text = artist
                    textNextLyric?.visibility = View.VISIBLE
                } else {
                    textNextLyric?.visibility = View.GONE
                }
            } else {
                updateLyricViews(currentActiveIndex)
            }
        }
    }

    /**
     * Updates lyrics text and re-parses timed synchronization entries.
     */
    fun updateLyrics(lyrics: String?) {
        currentLyricsRaw = lyrics
        isSyncedLyrics = lyricsTextLooksSynced(lyrics)
        parsedLyrics = if (isSyncedLyrics && !lyrics.isNullOrBlank()) {
            LyricsUtils.parseLyrics(lyrics)
        } else {
            emptyList()
        }
        currentActiveIndex = -1

        if (isShowing) {
            if (!isSyncedLyrics) {
                textCurrentLyric?.text = currentSongTitle ?: context.getString(R.string.no_song_playing)
                if (!currentArtistName.isNullOrBlank()) {
                    textNextLyric?.text = currentArtistName
                    textNextLyric?.visibility = View.VISIBLE
                } else {
                    textNextLyric?.visibility = View.GONE
                }
            } else {
                val currentPos = (player?.currentPosition ?: 0L) + currentLyricsOffset
                val activeIndices = LyricsUtils.findActiveLineIndices(parsedLyrics, currentPos)
                val active = activeIndices.maxOrNull() ?: -1
                currentActiveIndex = active
                updateLyricViews(active)
            }
        }
    }

    /**
     * Updates the playback state (play/pause icon and ticker loop).
     */
    fun updatePlaybackState(isPlaying: Boolean) {
        btnPlayPause?.setImageResource(if (isPlaying) R.drawable.pause else R.drawable.play)
        if (isPlaying) {
            startPositionTicker()
        } else {
            positionTickerJob?.cancel()
            positionTickerJob = null
        }
    }

    /**
     * Updates the active lyric based on the current playback position.
     */
    fun updatePosition(pos: Long) {
        if (!isShowing || !isSyncedLyrics || parsedLyrics.isEmpty()) return

        val effectivePos = pos + currentLyricsOffset
        val activeIndices = LyricsUtils.findActiveLineIndices(parsedLyrics, effectivePos)
        val activeIndex = activeIndices.maxOrNull() ?: -1

        if (activeIndex != currentActiveIndex) {
            currentActiveIndex = activeIndex
            updateLyricViews(activeIndex)
        }
    }

    private fun updateLyricViews(index: Int) {
        if (index in parsedLyrics.indices) {
            val currentLine = parsedLyrics[index]
            textCurrentLyric?.text = currentLine.text.ifBlank { "…" }

            if (showNextLine) {
                val nextLine = parsedLyrics.getOrNull(index + 1)
                if (nextLine != null && nextLine.text.isNotBlank()) {
                    textNextLyric?.text = nextLine.text
                    textNextLyric?.visibility = View.VISIBLE
                } else {
                    textNextLyric?.visibility = View.GONE
                }
            } else {
                textNextLyric?.visibility = View.GONE
            }
        } else if (index < 0 && parsedLyrics.isNotEmpty()) {
            // Before playback reaches the first synced lyric line
            textCurrentLyric?.text = currentSongTitle ?: context.getString(R.string.no_song_playing)
            if (showNextLine) {
                textNextLyric?.text = parsedLyrics.first().text
                textNextLyric?.visibility = View.VISIBLE
            } else {
                textNextLyric?.visibility = View.GONE
            }
        }
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            while (isActive) {
                val p = player
                if (p != null && p.isPlaying) {
                    updatePosition(p.currentPosition)
                }
                delay(150)
            }
        }
    }

    private fun toggleControlsExpanded() {
        if (isControlsExpanded) {
            collapseControls()
        } else {
            expandControls()
        }
    }

    private fun expandControls() {
        isControlsExpanded = true
        headerContainer?.visibility = View.VISIBLE
        controlsContainer?.visibility = View.VISIBLE
        resetAutoCollapseTimer()
    }

    private fun collapseControls() {
        isControlsExpanded = false
        autoCollapseJob?.cancel()
        autoCollapseJob = null
        headerContainer?.visibility = View.GONE
        controlsContainer?.visibility = View.GONE
    }

    private fun resetAutoCollapseTimer() {
        autoCollapseJob?.cancel()
        autoCollapseJob = scope.launch {
            delay(4000L)
            collapseControls()
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        overlayContainer?.isLocked = isLocked
        btnLock?.setImageResource(if (isLocked) R.drawable.lock else R.drawable.lock_open)
        scope.launch {
            context.dataStore.edit { it[FloatingLyricsLockedKey] = isLocked }
        }
        resetAutoCollapseTimer()
    }

    private fun applyTextSizes() {
        textCurrentLyric?.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        textNextLyric?.setTextSize(TypedValue.COMPLEX_UNIT_SP, (textSizeSp * 0.8f).coerceAtLeast(10f))
    }
}

/**
 * Custom container handling drag gestures and clicks smoothly with touch slop detection.
 */
class DraggableFrameLayout(
    context: Context,
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onDragEnd: () -> Unit,
    private val onClick: () -> Unit,
) : FrameLayout(context) {

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var isLocked: Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isLocked) return false
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = ev.rawX
                initialTouchY = ev.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - initialTouchX
                val dy = ev.rawY - initialTouchY
                if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    isDragging = true
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLocked) return super.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (isDragging || hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    isDragging = true
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    onDrag(dx, dy)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    onDragEnd()
                } else {
                    onClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onDragEnd()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
