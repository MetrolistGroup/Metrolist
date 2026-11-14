@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.innertubex.models.AccountInfo
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.auth.hasSupportedYouTubeAuthCookie
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.dismiss
import com.metrolist.shared.generated.resources.save_changes
import com.metrolist.shared.generated.resources.token_adv_login_description
import com.metrolist.shared.generated.resources.token_login_error_missing_auth_cookie
import com.metrolist.shared.generated.resources.token_login_save_failed
import com.metrolist.shared.generated.resources.token_login_saved
import com.metrolist.shared.generated.resources.token_login_saving
import com.metrolist.shared.generated.resources.token_login_sensitive_warning_generic
import com.metrolist.shared.generated.resources.token_login_sensitive_warning_title
import com.metrolist.shared.generated.resources.token_login_show_token
import com.metrolist.shared.generated.resources.token_login_title
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.ExpressiveSwitch
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.tv.RequestTvFocus
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.BlockScreenshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource

val COOKIE_REGEX =
    Regex("""\*\*\*INNERTUBE COOKIE\*\*\*[ \t]*=?[ \t]*(.*?)(?=\n\*\*\*|$)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
val VISITOR_DATA_REGEX =
    Regex("""\*\*\*VISITOR DATA\*\*\*[ \t]*=?[ \t]*(.*?)(?=\n\*\*\*|$)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
val DATASYNC_ID_REGEX =
    Regex("""\*\*\*DATASYNC ID\*\*\*[ \t]*=?[ \t]*(.*?)(?=\n\*\*\*|$)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
val AUTH_USER_REGEX =
    Regex(
        """(?:\*\*\*AUTH USER\*\*\*|x-goog-authuser|X-Goog-AuthUser|SESSION_INDEX)["']?\s*[:=]?\s*["']?([0-9]+)""",
        setOf(RegexOption.IGNORE_CASE),
    )
private val ACCOUNT_NAME_REGEX = tokenFieldRegex("ACCOUNT NAME")
private val ACCOUNT_EMAIL_REGEX = tokenFieldRegex("ACCOUNT EMAIL")
private val ACCOUNT_CHANNEL_HANDLE_REGEX = tokenFieldRegex("ACCOUNT CHANNEL HANDLE")

data class ParsedToken(
    val cookie: String,
    val visitorData: String?,
    val dataSyncId: String?,
    val authUser: String?,
    val isStructured: Boolean,
    val accountName: String?,
    val accountEmail: String?,
    val accountChannelHandle: String?,
) {
    fun resolveAccountInfo(fallback: AccountInfo): AccountInfo =
        AccountInfo(
            name = accountName?.takeIf(String::isNotBlank) ?: fallback.name,
            email = accountEmail?.takeIf(String::isNotBlank) ?: fallback.email,
            channelHandle = accountChannelHandle?.takeIf(String::isNotBlank) ?: fallback.channelHandle,
            thumbnailUrl = fallback.thumbnailUrl,
        )
}

data class TokenLoginCredentials(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val authUser: String,
    val accountInfo: AccountInfo,
)

fun sanitizeCookie(cookie: String): String = normalizeCookieInput(cookie).removeSuffix(";").trim()

fun normalizeCookieInput(input: String): String {
    val value = input.trim()
    if (value.isEmpty()) return ""

    val jsonCookies =
        runCatching {
            val root = Json.parseToJsonElement(value)
            val cookies =
                when (root) {
                    is JsonArray -> root
                    is JsonObject -> root["cookies"] as? JsonArray ?: return@runCatching null
                    else -> return@runCatching null
                }
            cookies
                .mapNotNull { element ->
                    val cookie = element.jsonObject
                    val domain = cookie["domain"]?.jsonPrimitive?.contentOrNull
                    if (!isYouTubeCookieDomain(domain)) return@mapNotNull null
                    val name =
                        cookie["name"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.trim()
                            .orEmpty()
                    val cookieValue = cookie["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    cookieEntry(name, cookieValue)
                }
        }.getOrNull()
    if (jsonCookies != null) return jsonCookies.joinToString("; ")

    val headerCookies = mutableListOf<String>()
    val netscapeCookies = mutableListOf<String>()
    value.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.startsWith("Cookie:", ignoreCase = true) -> {
                line
                    .substringAfter(':')
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(headerCookies::add)
            }

            line.startsWith("Set-Cookie:", ignoreCase = true) -> {
                line
                    .substringAfter(':')
                    .substringBefore(';')
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(headerCookies::add)
            }

            line.count { it == '\t' } >= 6 -> {
                val fields = line.split('\t')
                val domain = fields.firstOrNull()?.removePrefix("#HttpOnly_")
                if (!isYouTubeCookieDomain(domain)) return@forEach
                val name = fields.getOrNull(fields.lastIndex - 1)?.trim().orEmpty()
                val cookieValue = fields.lastOrNull()?.trim().orEmpty()
                cookieEntry(name, cookieValue)?.let(netscapeCookies::add)
            }
        }
    }
    if (headerCookies.isNotEmpty()) return headerCookies.joinToString("; ")
    if (netscapeCookies.isNotEmpty()) return netscapeCookies.joinToString("; ")

    return value
        .removePrefix("Cookie:")
        .removePrefix("cookie:")
        .trim()
}

private val cookieNamePattern = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

private fun cookieEntry(
    name: String?,
    value: String?,
): String? {
    if (name.isNullOrBlank() || !cookieNamePattern.matches(name) || value == null) return null
    if (value.any { it <= '\u001F' || it == '\u007F' || it == ';' }) return null
    return "$name=$value"
}

private fun isYouTubeCookieDomain(domain: String?): Boolean {
    val normalized = domain?.trim()?.removePrefix(".")?.lowercase() ?: return false
    return normalized == "youtube.com" || normalized.endsWith(".youtube.com")
}

fun sanitizeDataSyncId(dataSyncId: String): String =
    dataSyncId
        .trim()
        .substringBefore("||")
        .ifBlank {
            dataSyncId.trim().substringAfter("||", "").trim()
        }

// Pasted tokens often wrap long visitor data; header values cannot contain the resulting line breaks.
fun sanitizeVisitorData(visitorData: String): String = visitorData.filterNot(Char::isWhitespace).removePrefix("=")

fun sanitizeAuthUser(authUser: String): String = authUser.filter { it.isDigit() }.ifBlank { "0" }

fun hasAuthCookie(cookie: String): Boolean = hasSupportedYouTubeAuthCookie(cookie)

private fun tokenFieldRegex(name: String) =
    Regex("""\*\*\*$name\*\*\*[ \t]*=?[ \t]*(.*?)(?=\n\*\*\*|$)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

private fun Regex.tokenField(token: String): String? = find(token)?.groupValues?.get(1)?.trim()

fun ParsedToken.resolveCredentials(
    existingCookie: String,
    existingVisitorData: String,
    existingDataSyncId: String,
    existingAuthUser: String,
    existingAccountInfo: AccountInfo,
): TokenLoginCredentials {
    val normalizedCookie = sanitizeCookie(cookie)
    val sameCookie = normalizedCookie == sanitizeCookie(existingCookie)
    val resolvedDataSyncId = sanitizeDataSyncId(dataSyncId ?: existingDataSyncId.takeIf { sameCookie }.orEmpty())
    val resolvedAuthUser =
        sanitizeAuthUser(
            authUser ?: existingAuthUser.takeIf { sameCookie && !isStructured }.orEmpty(),
        )
    val sameAccount =
        sameCookie &&
            resolvedDataSyncId == sanitizeDataSyncId(existingDataSyncId) &&
            resolvedAuthUser == sanitizeAuthUser(existingAuthUser)
    val resolvedAccountInfo =
        if (sameAccount) {
            resolveAccountInfo(existingAccountInfo)
        } else {
            AccountInfo(
                name = accountName?.takeIf { it.isNotBlank() && it != existingAccountInfo.name }.orEmpty(),
                email = accountEmail?.takeIf { it.isNotBlank() && it != existingAccountInfo.email.orEmpty() },
                channelHandle =
                    accountChannelHandle?.takeIf {
                        it.isNotBlank() && it != existingAccountInfo.channelHandle.orEmpty()
                    },
                thumbnailUrl = null,
            )
        }
    return TokenLoginCredentials(
        cookie = normalizedCookie,
        visitorData = sanitizeVisitorData(visitorData ?: existingVisitorData.takeIf { sameCookie }.orEmpty()),
        dataSyncId = resolvedDataSyncId,
        authUser = resolvedAuthUser,
        accountInfo = resolvedAccountInfo,
    )
}

fun parseToken(token: String): ParsedToken {
    val isStructured = token.contains("***")
    val cookie =
        COOKIE_REGEX
            .find(token)
            ?.groupValues
            ?.get(1)
            ?.let(::sanitizeCookie)
            ?: if (!isStructured) sanitizeCookie(token) else ""
    val visitorData =
        VISITOR_DATA_REGEX
            .find(token)
            ?.groupValues
            ?.get(1)
            ?.let(::sanitizeVisitorData)
    val dataSyncId =
        DATASYNC_ID_REGEX
            .find(token)
            ?.groupValues
            ?.get(1)
            ?.let(::sanitizeDataSyncId)
    val authUser =
        AUTH_USER_REGEX
            .find(token)
            ?.groupValues
            ?.get(1)
            ?.let(::sanitizeAuthUser)
    return ParsedToken(
        cookie = cookie,
        visitorData = visitorData,
        dataSyncId = dataSyncId,
        authUser = authUser,
        isStructured = isStructured,
        accountName = ACCOUNT_NAME_REGEX.tokenField(token),
        accountEmail = ACCOUNT_EMAIL_REGEX.tokenField(token),
        accountChannelHandle = ACCOUNT_CHANNEL_HANDLE_REGEX.tokenField(token),
    )
}

fun buildTokenText(
    cookie: String,
    visitorData: String,
    dataSyncId: String,
    authUser: String,
    accountName: String,
    accountEmail: String,
    accountChannelHandle: String,
): String =
    buildList {
        val sanitizedCookie = sanitizeCookie(cookie)
        val sanitizedVisitorData = sanitizeVisitorData(visitorData)
        val sanitizedDataSyncId = sanitizeDataSyncId(dataSyncId)

        if (sanitizedCookie.isNotBlank()) {
            add("***INNERTUBE COOKIE*** =$sanitizedCookie")
        }
        if (sanitizedVisitorData.isNotBlank()) {
            add("***VISITOR DATA*** =$sanitizedVisitorData")
        }
        if (sanitizedDataSyncId.isNotBlank()) {
            add("***DATASYNC ID*** =$sanitizedDataSyncId")
        }
        val sanitizedAuthUser = sanitizeAuthUser(authUser)
        add("***AUTH USER*** =$sanitizedAuthUser")
        add("***ACCOUNT NAME*** =$accountName")
        add("***ACCOUNT EMAIL*** =$accountEmail")
        add("***ACCOUNT CHANNEL HANDLE*** =$accountChannelHandle")
    }.joinToString("\n")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenLoginScreen(
    onNavigateBack: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
    onCredentialsSaved: (TokenLoginCredentials) -> Unit = { SongLikeState.refreshLikesFromRemoteAsync() },
) {
    val authManager = remember { AuthManager() }
    var saved by remember { mutableStateOf(authManager.tokenLoginCredentials()) }
    var tokenText by remember { mutableStateOf(saved.toTokenText()) }
    var isSaving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultIsError by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val showTokenFocus = remember { FocusRequester() }
    val invalidToken = stringResource(Res.string.token_login_error_missing_auth_cookie)
    val savedMessage = stringResource(Res.string.token_login_saved)
    val failedMessage = stringResource(Res.string.token_login_save_failed)

    LaunchedEffect(Unit) {
        if (saved.visitorData.isBlank()) {
            val initial = saved
            try {
                val visitorData = GlobalInnerTube.instance.fetchFreshVisitorData()
                if (!visitorData.isNullOrBlank() && tokenText == initial.toTokenText() && saved == initial && !isSaving) {
                    saved = initial.copy(visitorData = visitorData)
                    tokenText = saved.toTokenText()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }

    BlockScreenshots()
    RequestTvFocus(showTokenFocus)

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.token_login_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !isSaving, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScrollWithDrag(rememberScrollState())
                    .padding(16.dp)
                    .padding(bottom = bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (!Settings.shared.tokenLoginWarningDismissed) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(MetrolistIcons.Outlined.Warning, null)
                            Column {
                                Text(
                                    stringResource(Res.string.token_login_sensitive_warning_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(Res.string.token_login_sensitive_warning_generic),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Button(
                                onClick = {
                                    Settings.shared.tokenLoginWarningDismissed = true
                                    showTokenFocus.requestFocus()
                                },
                                modifier = Modifier.align(Alignment.End).tvFocusHighlight(),
                                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(50)),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                            ) { Text(stringResource(Res.string.dismiss)) }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(MetrolistIcons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(Res.string.token_adv_login_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val switchShape =
                        RoundedCornerShape(
                            topStart = 24.dp,
                            topEnd = 24.dp,
                            bottomStart = if (showToken) 4.dp else 24.dp,
                            bottomEnd = if (showToken) 4.dp else 24.dp,
                        )
                    Surface(shape = switchShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(showTokenFocus)
                                    .tvFocusHighlight(shape = switchShape)
                                    .toggleable(
                                        value = showToken,
                                        role = Role.Switch,
                                        onValueChange = {
                                            focusManager.clearFocus()
                                            showToken = it
                                            showTokenFocus.requestFocus()
                                        },
                                    ).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                stringResource(Res.string.token_login_show_token),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            ExpressiveSwitch(checked = showToken, onCheckedChange = null)
                        }
                    }
                    if (showToken) {
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TokenLoginTextField(
                                    value = tokenText,
                                    onValueChange = {
                                        tokenText = it
                                        result = null
                                        resultIsError = false
                                    },
                                    enabled = !isSaving,
                                    isError = resultIsError,
                                )
                                result?.let {
                                    Text(
                                        it,
                                        color = if (resultIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                val parsed = parseToken(tokenText)
                                if (!hasAuthCookie(parsed.cookie)) {
                                    resultIsError = true
                                    result = invalidToken
                                } else {
                                    val credentials =
                                        parsed.resolveCredentials(
                                            existingCookie = saved.cookie,
                                            existingVisitorData = saved.visitorData,
                                            existingDataSyncId = saved.dataSyncId,
                                            existingAuthUser = saved.authUser,
                                            existingAccountInfo = saved.accountInfo,
                                        )
                                    isSaving = true
                                    result = null
                                    resultIsError = false
                                    scope.launch {
                                        try {
                                            val validated =
                                                withTimeoutOrNull(10_000L) {
                                                    authManager.validateAndSaveCredentials(
                                                        cookie = credentials.cookie,
                                                        visitorData = credentials.visitorData,
                                                        dataSyncId = credentials.dataSyncId,
                                                        authUser = credentials.authUser,
                                                    )
                                                } == true
                                            if (validated) {
                                                saved = authManager.tokenLoginCredentials()
                                                tokenText = saved.toTokenText()
                                                onCredentialsSaved(saved)
                                            }
                                            resultIsError = !validated
                                            result = if (validated) savedMessage else failedMessage
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            resultIsError = true
                                            result = failedMessage
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                }
                            },
                            enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).tvFocusHighlight(),
                            shapes =
                                ButtonDefaults.shapes(
                                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
                                ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isSaving) LoadingIndicator(Modifier.size(24.dp))
                                Text(
                                    stringResource(if (isSaving) Res.string.token_login_saving else Res.string.save_changes),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenLoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
) {
    val isTv = LocalIsTv.current
    val label = stringResource(Res.string.token_login_title)
    var fieldFocused by remember { mutableStateOf(false) }
    var keyboardRequested by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        isError = isError,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                showKeyboardOnFocus = !isTv || keyboardRequested,
            ),
        minLines = 12,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .tvFocusHighlight()
                .onFocusChanged {
                    fieldFocused = it.isFocused
                    if (!it.hasFocus) keyboardRequested = false
                }.onPreviewKeyEvent { event ->
                    if (!isTv || !fieldFocused) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            if (event.type == KeyEventType.KeyUp) {
                                keyboardRequested = true
                                keyboard?.show()
                            }
                            true
                        }

                        Key.DirectionUp -> {
                            event.type == KeyEventType.KeyDown && focus.moveFocus(FocusDirection.Up)
                        }

                        Key.DirectionDown -> {
                            event.type == KeyEventType.KeyDown && focus.moveFocus(FocusDirection.Down)
                        }

                        else -> {
                            false
                        }
                    }
                },
    )
}

private fun AuthManager.tokenLoginCredentials() =
    TokenLoginCredentials(
        cookie = innerTubeCookie,
        visitorData = visitorData,
        dataSyncId = dataSyncId,
        authUser = authUser,
        accountInfo = AccountInfo(accountName, accountEmail, accountChannelHandle, accountThumbnail),
    )

private fun TokenLoginCredentials.toTokenText(): String =
    buildTokenText(
        cookie,
        visitorData,
        dataSyncId,
        authUser,
        accountInfo.name,
        accountInfo.email.orEmpty(),
        accountInfo.channelHandle.orEmpty(),
    )
