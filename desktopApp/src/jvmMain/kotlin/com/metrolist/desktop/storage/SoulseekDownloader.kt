package com.metrolist.desktop.storage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.AppState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.connect
import com.metrolist.shared.generated.resources.password
import com.metrolist.shared.generated.resources.soulseek_bit_depth
import com.metrolist.shared.generated.resources.soulseek_bitrate
import com.metrolist.shared.generated.resources.soulseek_download
import com.metrolist.shared.generated.resources.soulseek_download_description
import com.metrolist.shared.generated.resources.soulseek_download_failed
import com.metrolist.shared.generated.resources.soulseek_downloaded
import com.metrolist.shared.generated.resources.soulseek_downloading
import com.metrolist.shared.generated.resources.soulseek_fetch_failed
import com.metrolist.shared.generated.resources.soulseek_format_320
import com.metrolist.shared.generated.resources.soulseek_format_hires
import com.metrolist.shared.generated.resources.soulseek_format_lossless
import com.metrolist.shared.generated.resources.soulseek_helper_missing
import com.metrolist.shared.generated.resources.soulseek_login_description
import com.metrolist.shared.generated.resources.soulseek_login_rejected
import com.metrolist.shared.generated.resources.soulseek_login_title
import com.metrolist.shared.generated.resources.soulseek_no_match
import com.metrolist.shared.generated.resources.soulseek_peer_busy
import com.metrolist.shared.generated.resources.soulseek_sample_rate
import com.metrolist.shared.generated.resources.soulseek_searching
import com.metrolist.shared.generated.resources.soulseek_stream
import com.metrolist.shared.generated.resources.soulseek_versions
import com.metrolist.shared.generated.resources.soulseek_versions_playing
import com.metrolist.shared.generated.resources.username
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.settings.SoulseekSettingsHost
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.io.File

/** One downloadable version of a song, as listed by `metrolist-slsk search`. */
@Serializable
data class SoulseekOption(
    val format: String,
    val username: String,
    val filename: String,
    val size: Long,
    val extension: String,
    val bitrate: Int? = null,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val freeSlot: Boolean = false,
)

sealed interface SoulseekSearchState {
    data object Searching : SoulseekSearchState

    data class Results(
        val options: List<SoulseekOption>,
    ) : SoulseekSearchState

    data object NoMatch : SoulseekSearchState

    data class Failed(
        val message: String,
    ) : SoulseekSearchState
}

/** Downloads songs from Soulseek through the `metrolist-slsk` helper in `scripts/soulseek`. */
object SoulseekDownloader {
    private const val EXIT_NO_MATCH = 2
    private const val EXIT_NOT_LOGGED_IN = 3

    /** The order the picker lists formats in, matching the helper's `FORMAT_ORDER`. */
    val formatOrder = listOf("320", "lossless", "hires")

    private val json = Json { ignoreUnknownKeys = true }

    // One Soulseek login per account: a second concurrent helper would disconnect the first.
    private val mutex = Mutex()

    private val helperName = if (OsUtil.isWindows) "metrolist-slsk.exe" else "metrolist-slsk"

    /** The helper bundled in the app's resources, else one installed with `uv tool install scripts/soulseek` (source runs). */
    private fun helper(): File? =
        System
            .getProperty("compose.application.resources.dir")
            ?.let { File(it, helperName) }
            ?.takeIf { it.isFile }
            ?.let(::runnable)
            ?: (
                listOf(File(System.getProperty("user.home"), ".local/bin/$helperName")) +
                    System
                        .getenv("PATH")
                        .orEmpty()
                        .split(File.pathSeparator)
                        .map { File(it, helperName) }
            ).firstOrNull { it.isFile && it.canExecute() }

    // Packaging drops the executable bit; read-only installs (mounted DMG, AppImage) need a writable copy to set it.
    private fun runnable(bundled: File): File? {
        if (bundled.canExecute() || bundled.setExecutable(true)) return bundled
        val copy = File(XdgUtils.getXdgCacheHome(), "metrolist/$helperName")
        return runCatching {
            if (!copy.isFile || copy.length() != bundled.length() || copy.lastModified() < bundled.lastModified()) {
                copy.parentFile.mkdirs()
                bundled.copyTo(copy, overwrite = true)
            }
            copy.takeIf { it.canExecute() || it.setExecutable(true) }
        }.getOrNull()
    }

    /** The song whose versions the picker shows, or null when it is closed. */
    var pickerSong by mutableStateOf<SongItem?>(null)
        private set

    var searchState by mutableStateOf<SoulseekSearchState>(SoulseekSearchState.Searching)
        private set

    /** [SoulseekLoginDialog] shows while this is true. */
    var loginVisible by mutableStateOf(false)
        private set

    /** The signed-in Soulseek username, or null; refreshed by [refreshStatus]. */
    var account by mutableStateOf<String?>(null)
        private set

    var helperInstalled by mutableStateOf(true)
        private set

    // The song whose search asked for the sign-in; its picker reopens after a successful login.
    private var pendingSong: SongItem? = null

    /** Runs the helper and returns its exit code and output lines (stdout and stderr). [stdin] keeps secrets out of the process list. */
    private suspend fun run(
        exe: File,
        args: List<String>,
        stdin: String? = null,
    ): Pair<Int, List<String>> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val process = ProcessBuilder(listOf(exe.path) + args).redirectErrorStream(true).start()
                    process.outputStream.bufferedWriter().use { writer -> stdin?.let { writer.write(it + "\n") } }
                    val output = process.inputStream.bufferedReader().readLines()
                    process.waitFor() to output
                }.getOrElse { -1 to listOf(it.message.orEmpty()) }
            }
        }

    fun openPicker(song: SongItem) {
        pickerSong = song
        searchState = SoulseekSearchState.Searching
        AppState.scope.launch {
            val exe = helper()
            if (exe == null) {
                searchState = SoulseekSearchState.Failed(getString(Res.string.soulseek_helper_missing))
                return@launch
            }
            val args =
                listOf("search", "--title", song.searchTitle, "--artist", song.searchArtist) +
                    song.duration?.let { listOf("--duration", it.toString()) }.orEmpty()
            val (exitCode, output) = run(exe, args)
            if (pickerSong != song) return@launch // closed or replaced while searching
            searchState =
                when (exitCode) {
                    0 -> {
                        // Progress goes to stderr; the JSON list is the last line printed.
                        output
                            .lastOrNull { it.startsWith("[") }
                            ?.let { runCatching { json.decodeFromString<List<SoulseekOption>>(it) }.getOrNull() }
                            ?.let { SoulseekSearchState.Results(it) }
                            ?: SoulseekSearchState.Failed(getString(Res.string.soulseek_download_failed, output.lastOrNull().orEmpty()))
                    }

                    EXIT_NO_MATCH -> {
                        SoulseekSearchState.NoMatch
                    }

                    EXIT_NOT_LOGGED_IN -> {
                        pendingSong = song
                        pickerSong = null
                        loginVisible = true
                        return@launch
                    }

                    else -> {
                        SoulseekSearchState.Failed(getString(Res.string.soulseek_download_failed, output.lastOrNull().orEmpty()))
                    }
                }
        }
    }

    fun closePicker() {
        pickerSong = null
    }

    fun fetch(
        song: SongItem,
        option: SoulseekOption,
    ) {
        pickerSong = null
        AppState.scope.launch {
            val exe = helper() ?: return@launch
            val downloadingMessage = getString(Res.string.soulseek_downloading, song.searchTitle)
            val downloading = launch { AppState.snackbarHostState.showSnackbar(downloadingMessage) }
            val (exitCode, output) = run(exe, listOf("fetch", "--username", option.username, "--filename", option.filename))
            downloading.cancel()
            val message =
                when (exitCode) {
                    0 -> {
                        val path = File(output.last())
                        SoulseekLibrary.add(
                            song.id,
                            SoulseekFile(
                                path = path.absolutePath,
                                format = option.format,
                                extension = option.extension,
                                size = path.length(),
                                bitrate = option.bitrate,
                                bitDepth = option.bitDepth,
                                sampleRate = option.sampleRate,
                            ),
                        )
                        getString(Res.string.soulseek_downloaded, path.name)
                    }

                    EXIT_NO_MATCH -> {
                        getString(Res.string.soulseek_fetch_failed, song.searchTitle)
                    }

                    EXIT_NOT_LOGGED_IN -> {
                        pendingSong = song
                        loginVisible = true
                        return@launch
                    }

                    else -> {
                        getString(Res.string.soulseek_download_failed, output.lastOrNull().orEmpty())
                    }
                }
            AppState.snackbarHostState.showSnackbar(message)
        }
    }

    suspend fun refreshStatus() {
        val exe = helper()
        helperInstalled = exe != null
        val (exitCode, output) = exe?.let { run(it, listOf("status")) } ?: (-1 to emptyList())
        account = output.lastOrNull().takeIf { exitCode == 0 }
    }

    fun requestLogin() {
        loginVisible = true
    }

    fun logout() {
        AppState.scope.launch {
            helper()?.let { run(it, listOf("logout")) }
            refreshStatus()
        }
    }

    /** Checks the credentials with Soulseek and saves them to the system keychain. Returns an error message, or null on success. */
    suspend fun login(
        username: String,
        password: String,
    ): String? {
        val exe = helper() ?: return getString(Res.string.soulseek_helper_missing)
        val (exitCode, output) = run(exe, listOf("login", "--username", username, "--password-stdin"), stdin = password)
        return when (exitCode) {
            0 -> {
                account = username
                loginVisible = false
                pendingSong?.let(::openPicker)
                pendingSong = null
                null
            }

            EXIT_NOT_LOGGED_IN -> {
                getString(Res.string.soulseek_login_rejected)
            }

            else -> {
                getString(Res.string.soulseek_download_failed, output.lastOrNull().orEmpty())
            }
        }
    }

    fun cancelLogin() {
        loginVisible = false
        pendingSong = null
    }
}

private val SongItem.searchTitle get() = customTitle ?: title
private val SongItem.searchArtist get() = customArtist ?: artists.joinToString(" ") { it.name }

/** The Soulseek row in Settings > Integrations. */
@Composable
fun rememberSoulseekSettingsHost(): SoulseekSettingsHost {
    LaunchedEffect(Unit) { SoulseekDownloader.refreshStatus() }
    return SoulseekSettingsHost(
        account = SoulseekDownloader.account,
        helperInstalled = SoulseekDownloader.helperInstalled,
        onLogin = SoulseekDownloader::requestLogin,
        onLogout = SoulseekDownloader::logout,
    )
}

/** Lists the versions Soulseek has of a song, grouped by format, and downloads the one picked. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SoulseekPickerDialog() {
    val song = SoulseekDownloader.pickerSong ?: return
    val formatSize = rememberFileSizeFormatter()
    val formatTitles =
        mapOf(
            "320" to stringResource(Res.string.soulseek_format_320),
            "lossless" to stringResource(Res.string.soulseek_format_lossless),
            "hires" to stringResource(Res.string.soulseek_format_hires),
        )

    AlertDialog(
        onDismissRequest = SoulseekDownloader::closePicker,
        title = { Text(stringResource(Res.string.soulseek_download)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (val state = SoulseekDownloader.searchState) {
                    SoulseekSearchState.Searching -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(Res.string.soulseek_searching, song.searchTitle))
                        }
                    }

                    SoulseekSearchState.NoMatch -> {
                        Text(stringResource(Res.string.soulseek_no_match, song.searchTitle))
                    }

                    is SoulseekSearchState.Failed -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }

                    is SoulseekSearchState.Results -> {
                        val groups = state.options.groupBy { it.format }
                        SoulseekDownloader.formatOrder.forEach { format ->
                            val options = groups[format] ?: return@forEach
                            Text(
                                formatTitles.getValue(format),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            options.forEach { option ->
                                ListItem(
                                    modifier = Modifier.clickable { SoulseekDownloader.fetch(song, option) },
                                    headlineContent = { Text(optionDetails(option)) },
                                    supportingContent =
                                        if (option.freeSlot) null else ({ Text(stringResource(Res.string.soulseek_peer_busy)) }),
                                    trailingContent = { Text(formatSize(option.size)) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = SoulseekDownloader::closePicker, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@Composable
private fun optionDetails(option: SoulseekOption) =
    formatDetails(option.format, option.extension, option.bitrate, option.bitDepth, option.sampleRate)

@Composable
private fun fileDetails(file: SoulseekFile) = formatDetails(file.format, file.extension, file.bitrate, file.bitDepth, file.sampleRate)

/** "MP3 · 320 kbps" or "FLAC · 24-bit · 96 kHz". */
@Composable
private fun formatDetails(
    format: String,
    extension: String,
    bitrate: Int?,
    bitDepth: Int?,
    sampleRate: Int?,
): String {
    val kilohertz = sampleRate?.let { hz -> if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(hz / 1000.0) }
    return listOfNotNull(
        extension.uppercase(),
        bitrate?.takeIf { format == "320" }?.let { stringResource(Res.string.soulseek_bitrate, it) },
        bitDepth?.takeIf { format != "320" }?.let { stringResource(Res.string.soulseek_bit_depth, it) },
        kilohertz?.takeIf { format != "320" }?.let { stringResource(Res.string.soulseek_sample_rate, it) },
    ).joinToString(" · ")
}

/** Picks which downloaded Soulseek version of a song plays, or goes back to streaming, and plays it. */
@Composable
fun SoulseekVersionsDialog() {
    val song = SoulseekLibrary.versionsSong ?: return
    val playback = LocalPlaybackController.current
    val formatSize = rememberFileSizeFormatter()
    val versions = SoulseekLibrary.versions(song.id)
    val selected = SoulseekLibrary.selected(song.id)?.path

    fun choose(path: String?) {
        SoulseekLibrary.select(song.id, path)
        SoulseekLibrary.closeVersions()
        playback.play(song)
    }

    AlertDialog(
        onDismissRequest = SoulseekLibrary::closeVersions,
        title = { Text(stringResource(Res.string.soulseek_versions)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                ListItem(
                    modifier = Modifier.clickable { choose(null) },
                    leadingContent = { RadioButton(selected = selected == null, onClick = null) },
                    headlineContent = { Text(stringResource(Res.string.soulseek_stream)) },
                )
                versions.forEach { file ->
                    ListItem(
                        modifier = Modifier.clickable { choose(file.path) },
                        leadingContent = { RadioButton(selected = selected == file.path, onClick = null) },
                        headlineContent = { Text(fileDetails(file)) },
                        supportingContent = { Text(File(file.path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text(formatSize(file.size)) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = SoulseekLibrary::closeVersions, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SoulseekLoginDialog() {
    if (!SoulseekDownloader.loginVisible) return
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    val passwordState = remember { TextFieldState("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!connecting) SoulseekDownloader.cancelLogin() },
        title = { Text(stringResource(Res.string.soulseek_login_title)) },
        text = {
            Column(modifier = Modifier.width(IntrinsicSize.Min), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.soulseek_login_description))
                OutlinedTextField(
                    value = username,
                    modifier = Modifier.semantics { contentType = ContentType.Username },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    onValueChange = { username = it },
                    label = { Text(stringResource(Res.string.username)) },
                    singleLine = true,
                    enabled = !connecting,
                )
                OutlinedSecureTextField(
                    state = passwordState,
                    modifier = Modifier.semantics { contentType = ContentType.Password },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(Res.string.password)) },
                    enabled = !connecting,
                    textObfuscationMode = TextObfuscationMode.Hidden,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !connecting && username.isNotBlank() && passwordState.text.isNotEmpty(),
                onClick = {
                    connecting = true
                    error = null
                    scope.launch {
                        error = SoulseekDownloader.login(username.trim(), passwordState.text.toString())
                        if (error != null) passwordState.clearText()
                        connecting = false
                    }
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                if (connecting) {
                    LoadingIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(Res.string.connect))
            }
        },
        dismissButton = {
            TextButton(
                onClick = SoulseekDownloader::cancelLogin,
                enabled = !connecting,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
fun soulseekMenuActions(
    song: SongItem?,
    onDismiss: () -> Unit,
): List<PlayerMenuAction> {
    if (song == null || song.isLocal || song.isUploaded || song.podcastId != null) return emptyList()
    val versions = SoulseekLibrary.versions(song.id)
    val playing = SoulseekLibrary.selected(song.id)
    return listOfNotNull(
        PlayerMenuAction(
            MetrolistIcons.Outlined.Download,
            stringResource(Res.string.soulseek_download),
            stringResource(Res.string.soulseek_download_description),
        ) {
            SoulseekDownloader.openPicker(song)
            onDismiss()
        },
        versions.takeIf { it.isNotEmpty() }?.let {
            PlayerMenuAction(
                MetrolistIcons.Outlined.LibraryMusic,
                stringResource(Res.string.soulseek_versions),
                playing?.let { stringResource(Res.string.soulseek_versions_playing, fileDetails(it)) }
                    ?: stringResource(Res.string.soulseek_stream),
            ) {
                SoulseekLibrary.openVersions(song)
                onDismiss()
            }
        },
    )
}
