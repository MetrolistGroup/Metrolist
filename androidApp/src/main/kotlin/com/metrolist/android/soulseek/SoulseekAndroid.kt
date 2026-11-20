package com.metrolist.android.soulseek

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.playback.PlaybackSnackbarEvents
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
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.soulseek.SoulseekCandidate
import com.metrolist.shared.soulseek.SoulseekClient
import com.metrolist.shared.soulseek.SoulseekFile
import com.metrolist.shared.soulseek.SoulseekLibrary
import com.metrolist.shared.soulseek.SoulseekLoginException
import com.metrolist.shared.soulseek.SoulseekMatching
import com.metrolist.shared.soulseek.SoulseekTransferException
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.SoulseekSettingsHost
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.io.File

sealed interface SoulseekSearchState {
    data object Searching : SoulseekSearchState

    data class Results(
        val options: List<SoulseekCandidate>,
    ) : SoulseekSearchState

    data object NoMatch : SoulseekSearchState

    data class Failed(
        val message: String,
    ) : SoulseekSearchState
}

/** Soulseek downloads on Android through the shared Kotlin client; credentials live in encrypted [Settings]. */
object SoulseekAndroid {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var downloadDirectory: File

    // One signed-in session: a download is queued over the connections its search opened.
    private var client: SoulseekClient? = null
    private val clientLock = Mutex()
    private var idleClose: Job? = null

    private class NotLoggedIn : Exception()

    var pickerSong by mutableStateOf<SongItem?>(null)
        private set
    var searchState by mutableStateOf<SoulseekSearchState>(SoulseekSearchState.Searching)
        private set
    var loginVisible by mutableStateOf(false)
        private set
    var account by mutableStateOf<String?>(null)
        private set

    // The song whose search asked for the sign-in; its picker reopens after a successful login.
    private var pendingSong: SongItem? = null

    /** Called once from the main process: where the version store and downloads live. */
    fun initialize(context: Context) {
        SoulseekLibrary.storeFile = File(context.filesDir, "soulseek-files.json")
        // App-specific Music folder: visible to file managers, needs no storage permission, removed on uninstall.
        downloadDirectory =
            File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music"), "Soulseek")
        account = Settings.shared.soulseekUsername.takeIf { it.isNotBlank() && Settings.shared.soulseekPassword.isNotBlank() }
    }

    private suspend fun session(): SoulseekClient =
        clientLock.withLock {
            idleClose?.cancel()
            client?.let { return it }
            val user = Settings.shared.soulseekUsername
            val password = Settings.shared.soulseekPassword
            if (user.isBlank() || password.isBlank()) throw NotLoggedIn()
            SoulseekClient(user, password).also {
                try {
                    it.login()
                } catch (e: Exception) {
                    it.close()
                    throw e
                }
                client = it
            }
        }

    private fun closeWhenIdle() {
        idleClose?.cancel()
        idleClose =
            scope.launch {
                delay(5 * 60_000L)
                dropSession()
            }
    }

    private fun dropSession() {
        client?.close()
        client = null
    }

    fun openPicker(song: SongItem) {
        pickerSong = song
        searchState = SoulseekSearchState.Searching
        scope.launch {
            val title = song.customTitle ?: song.title
            val artist = song.customArtist ?: song.artists.joinToString(" ") { it.name }
            val state =
                try {
                    val replies = session().search(SoulseekMatching.searchQuery(artist, title), waitMs = 10_000)
                    val options = SoulseekMatching.options(replies, artist, title, song.duration?.toInt())
                    if (options.isEmpty()) SoulseekSearchState.NoMatch else SoulseekSearchState.Results(options)
                } catch (_: NotLoggedIn) {
                    askToSignIn(song)
                    return@launch
                } catch (_: SoulseekLoginException) {
                    dropSession()
                    askToSignIn(song)
                    return@launch
                } catch (e: Exception) {
                    dropSession()
                    SoulseekSearchState.Failed(getString(Res.string.soulseek_download_failed, e.message.orEmpty()))
                } finally {
                    closeWhenIdle()
                }
            if (pickerSong == song) searchState = state
        }
    }

    private fun askToSignIn(song: SongItem) {
        pendingSong = song
        pickerSong = null
        loginVisible = true
    }

    fun closePicker() {
        pickerSong = null
    }

    // shortcut: runs in the app process without a foreground service, so Android may stop a long transfer once the app
    // is in the background; move it into a dataSync foreground service if that happens.
    fun fetch(
        song: SongItem,
        option: SoulseekCandidate,
    ) {
        pickerSong = null
        scope.launch {
            val title = song.customTitle ?: song.title
            PlaybackSnackbarEvents.emit(getString(Res.string.soulseek_downloading, title))
            val message =
                try {
                    val file = session().download(option.username, option.filename, downloadDirectory)
                    SoulseekLibrary.add(
                        song,
                        SoulseekFile(
                            path = file.absolutePath,
                            format = option.format,
                            extension = option.extension,
                            size = file.length(),
                            bitrate = option.bitrate,
                            bitDepth = option.bitDepth,
                            sampleRate = option.sampleRate,
                        ),
                    )
                    getString(Res.string.soulseek_downloaded, file.name)
                } catch (_: NotLoggedIn) {
                    askToSignIn(song)
                    return@launch
                } catch (_: SoulseekTransferException) {
                    getString(Res.string.soulseek_fetch_failed, title)
                } catch (e: Exception) {
                    dropSession()
                    getString(Res.string.soulseek_download_failed, e.message.orEmpty())
                } finally {
                    closeWhenIdle()
                }
            PlaybackSnackbarEvents.emit(message)
        }
    }

    /** Checks the credentials with Soulseek, then stores them encrypted. Returns an error message, or null on success. */
    suspend fun login(
        username: String,
        password: String,
    ): String? {
        val candidate = SoulseekClient(username, password)
        try {
            candidate.login()
        } catch (_: SoulseekLoginException) {
            candidate.close()
            return getString(Res.string.soulseek_login_rejected)
        } catch (e: Exception) {
            candidate.close()
            return getString(Res.string.soulseek_download_failed, e.message.orEmpty())
        }
        clientLock.withLock {
            dropSession()
            client = candidate
        }
        closeWhenIdle()
        Settings.shared.soulseekUsername = username
        Settings.shared.soulseekPassword = password
        account = username
        loginVisible = false
        pendingSong?.let(::openPicker)
        pendingSong = null
        return null
    }

    fun requestLogin() {
        loginVisible = true
    }

    fun cancelLogin() {
        loginVisible = false
        pendingSong = null
    }

    fun logout() {
        dropSession()
        Settings.shared.soulseekUsername = ""
        Settings.shared.soulseekPassword = ""
        account = null
    }
}

/** The Soulseek row in Settings > Integrations. */
fun soulseekSettingsHost(): SoulseekSettingsHost =
    SoulseekSettingsHost(
        account = SoulseekAndroid.account,
        helperInstalled = true,
        onLogin = SoulseekAndroid::requestLogin,
        onLogout = SoulseekAndroid::logout,
    )

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
            SoulseekAndroid.openPicker(song)
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

/** The Soulseek picker, versions and sign-in dialogs; mounted once at the app root. */
@Composable
fun SoulseekDialogs() {
    SoulseekPickerDialog()
    SoulseekVersionsDialog()
    SoulseekLoginDialog()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SoulseekPickerDialog() {
    val song = SoulseekAndroid.pickerSong ?: return
    val formatSize = rememberFileSizeFormatter()
    val formatTitles =
        mapOf(
            "320" to stringResource(Res.string.soulseek_format_320),
            "lossless" to stringResource(Res.string.soulseek_format_lossless),
            "hires" to stringResource(Res.string.soulseek_format_hires),
        )
    val title = song.customTitle ?: song.title
    AlertDialog(
        onDismissRequest = SoulseekAndroid::closePicker,
        title = { Text(stringResource(Res.string.soulseek_download)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (val state = SoulseekAndroid.searchState) {
                    SoulseekSearchState.Searching -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(Res.string.soulseek_searching, title))
                        }
                    }

                    SoulseekSearchState.NoMatch -> {
                        Text(stringResource(Res.string.soulseek_no_match, title))
                    }

                    is SoulseekSearchState.Failed -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }

                    is SoulseekSearchState.Results -> {
                        val groups = state.options.groupBy { it.format }
                        SoulseekMatching.FORMAT_ORDER.forEach { format ->
                            val options = groups[format] ?: return@forEach
                            Text(
                                formatTitles.getValue(format),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            options.forEach { option ->
                                ListItem(
                                    modifier = Modifier.clickable { SoulseekAndroid.fetch(song, option) },
                                    headlineContent = {
                                        Text(
                                            formatDetails(
                                                option.format,
                                                option.extension,
                                                option.bitrate,
                                                option.bitDepth,
                                                option.sampleRate,
                                            ),
                                        )
                                    },
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
            TextButton(onClick = SoulseekAndroid::closePicker, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SoulseekVersionsDialog() {
    val song = SoulseekLibrary.versionsSong ?: return
    val playerConnection = LocalPlayerConnection.current
    val formatSize = rememberFileSizeFormatter()
    val versions = SoulseekLibrary.versions(song.id)
    val selected = SoulseekLibrary.selected(song.id)?.path

    fun choose(path: String?) {
        SoulseekLibrary.select(song.id, path)
        SoulseekLibrary.closeVersions()
        playerConnection?.playSong(song, "")
    }

    AlertDialog(
        onDismissRequest = SoulseekLibrary::closeVersions,
        title = { Text(stringResource(Res.string.soulseek_versions)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
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
private fun SoulseekLoginDialog() {
    if (!SoulseekAndroid.loginVisible) return
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(Settings.shared.soulseekUsername) }
    val passwordState = remember { TextFieldState("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!connecting) SoulseekAndroid.cancelLogin() },
        title = { Text(stringResource(Res.string.soulseek_login_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        error = SoulseekAndroid.login(username.trim(), passwordState.text.toString())
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
            TextButton(onClick = SoulseekAndroid::cancelLogin, enabled = !connecting, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

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
