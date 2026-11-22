package com.metrolist.shared.backup

import com.metrolist.shared.deviceconnect.DeviceCrypto
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.tryOpenUrl
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

private const val DRIVE_CALLBACK_PATH = "/oauth2redirect"
private const val DRIVE_SIGN_IN_TIMEOUT_MILLIS = 10 * 60 * 1000L
private val iosDriveSignInMutex = Mutex()

fun isIosDriveBrokerConfigured(): Boolean = iosDriveBrokerUrl() != null && iosGoogleScheme().startsWith("com.googleusercontent.apps.")

fun startIosDriveSignIn() {
    DriveAccountConnectionState.clearSignInFailure()
    DriveAccountConnectionState.signInInProgress.value = true
    CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
        iosDriveSignInMutex.withLock {
            try {
                if (Settings.shared.driveBrokerSession.isNotBlank()) {
                    DriveAccountConnectionState.refresh()
                    DriveAccountConnectionState.signInInProgress.value = false
                    return@withLock
                }
                val brokerUrl = checkNotNull(iosDriveBrokerUrl())
                val broker = DriveTokenBrokerClient(brokerUrl)
                val configuration =
                    try {
                        broker.configuration()
                    } finally {
                        broker.close()
                    }
                val scheme = googleIosScheme(configuration.iosClientId)
                check(scheme == iosGoogleScheme()) { "Google iOS URL scheme does not match the configured client ID" }
                val state = randomUrlSafe(32)
                val verifier = randomUrlSafe(64)
                val challenge = urlSafe(DeviceCrypto.sha256(verifier.encodeToByteArray()))
                val redirectUri = "$scheme:$DRIVE_CALLBACK_PATH"
                val authorizationUrl =
                    URLBuilder("https://accounts.google.com/o/oauth2/v2/auth")
                        .apply {
                            parameters.append("client_id", configuration.iosClientId)
                            parameters.append("redirect_uri", redirectUri)
                            parameters.append("response_type", "code")
                            parameters.append("scope", "openid email https://www.googleapis.com/auth/drive.file")
                            parameters.append("access_type", "offline")
                            parameters.append("prompt", "consent")
                            parameters.append("state", state)
                            parameters.append("code_challenge", challenge)
                            parameters.append("code_challenge_method", "S256")
                        }.buildString()
                Settings.shared.driveOAuthState = state
                Settings.shared.driveOAuthVerifier = verifier
                Settings.shared.flush()
                tryOpenUrl(authorizationUrl) { opened ->
                    if (!opened) {
                        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                            iosDriveSignInMutex.withLock {
                                if (Settings.shared.driveOAuthState == state) {
                                    clearPendingDriveOAuth()
                                    DriveAccountConnectionState.reportSignInFailure()
                                }
                            }
                        }
                    }
                }
                // Re-enable Connect if the browser is abandoned without a callback.
                CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                    delay(DRIVE_SIGN_IN_TIMEOUT_MILLIS)
                    iosDriveSignInMutex.withLock {
                        if (Settings.shared.driveOAuthState == state) clearPendingDriveOAuth()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clearPendingDriveOAuth()
                DriveAccountConnectionState.reportSignInFailure()
            }
        }
    }
}

fun handleIosDriveOAuthCallback(callbackUrl: String): Boolean {
    val callback = runCatching { Url(callbackUrl) }.getOrNull() ?: return false
    if (callback.protocol.name != iosGoogleScheme() || callback.encodedPath != DRIVE_CALLBACK_PATH) return false
    val state = callback.parameters["state"] ?: return true
    val code = callback.parameters["code"]
    CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
        iosDriveSignInMutex.withLock {
            val settings = Settings.shared
            if (state.isBlank() || state != settings.driveOAuthState) return@withLock
            val verifier = settings.driveOAuthVerifier
            clearPendingDriveOAuth()
            if (code.isNullOrBlank()) {
                DriveAccountConnectionState.reportSignInFailure()
                return@withLock
            }
            try {
                val brokerUrl = checkNotNull(iosDriveBrokerUrl())
                val broker = DriveTokenBrokerClient(brokerUrl)
                val session =
                    try {
                        val configuration = broker.configuration()
                        val redirectUri = "${googleIosScheme(configuration.iosClientId)}:$DRIVE_CALLBACK_PATH"
                        broker.exchangeCode(
                            DriveClientPlatform.IOS,
                            code,
                            redirectUri,
                            verifier,
                        )
                    } finally {
                        broker.close()
                    }
                settings.driveBrokerSession = session.sessionToken
                DriveAccountConnectionState.update(session.sessionToken)
                settings.flush()
                notifyIosAutomaticBackupSettingsChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriveAccountConnectionState.reportSignInFailure()
            }
        }
    }
    return true
}

private fun iosGoogleScheme(): String = (NSBundle.mainBundle.infoDictionary?.get("GoogleDriveOAuthScheme") as? String).orEmpty()

private fun googleIosScheme(clientId: String): String {
    val suffix = ".apps.googleusercontent.com"
    require(clientId.endsWith(suffix)) { "Invalid Google iOS client ID" }
    return "com.googleusercontent.apps.${clientId.removeSuffix(suffix)}"
}

private fun clearPendingDriveOAuth() {
    Settings.shared.driveOAuthState = ""
    Settings.shared.driveOAuthVerifier = ""
    Settings.shared.flush()
    DriveAccountConnectionState.signInInProgress.value = false
}

@OptIn(ExperimentalForeignApi::class)
private fun randomUrlSafe(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    bytes.usePinned { pinned ->
        check(SecRandomCopyBytes(kSecRandomDefault, byteCount.toULong(), pinned.addressOf(0)) == errSecSuccess)
    }
    return urlSafe(bytes)
}

@OptIn(ExperimentalForeignApi::class)
private fun urlSafe(bytes: ByteArray): String =
    bytes.usePinned { pinned ->
        NSData
            .create(pinned.addressOf(0), bytes.size.toULong())
            .base64EncodedStringWithOptions(0u)
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    }
