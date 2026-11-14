/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Account identity for DeviceConnect.
 *
 * Account scope is a public filter, never authentication. Updated peers authorize
 * new pairings through mutual SAS approval and reconnect with saved pair secrets.
 * Credential-derived proofs are retained only for legacy peers; neither the
 * credential nor the raw account identifier is transmitted.
 */

package com.metrolist.shared.deviceconnect

import com.metrolist.shared.auth.supportedYouTubeAuthCredential
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.sanitizeDataSyncId
import kotlin.concurrent.Volatile

object AccountIdentity {
    private data class CachedIdentity(
        val cookie: String,
        val dataSyncId: String,
        val identity: AccountIdentitySnapshot,
    )

    @Volatile private var cached: CachedIdentity? = null

    fun accountSecret(): String = snapshot().secret

    fun snapshot(): AccountIdentitySnapshot {
        val cookie = Settings.shared.innerTubeCookie
        val rawDataSyncId = Settings.shared.dataSyncId
        cached?.let { if (it.cookie == cookie && it.dataSyncId == rawDataSyncId) return it.identity }
        return derive(cookie, rawDataSyncId).also { cached = CachedIdentity(cookie, rawDataSyncId, it) }
    }

    private fun derive(
        cookie: String,
        rawDataSyncId: String,
    ): AccountIdentitySnapshot {
        val dataSyncId = sanitizeDataSyncId(rawDataSyncId).trim()
        val credential =
            supportedYouTubeAuthCredential(cookie.trim())
                ?: return AccountIdentitySnapshot(
                    secret = "",
                    fingerprint = "",
                    anonymous = true,
                    resolved = true,
                )

        if (dataSyncId.isBlank()) {
            return AccountIdentitySnapshot(
                secret = UNRESOLVED_SENTINEL,
                fingerprint = "",
                anonymous = false,
                resolved = false,
            )
        }

        val secret =
            DeviceCrypto
                .hmacSha256(
                    credential.encodeToByteArray(),
                    "$ACCOUNT_PROOF_LABEL/$dataSyncId".encodeToByteArray(),
                ).toBase64()
        return AccountIdentitySnapshot(
            secret = secret,
            fingerprint = accountFingerprint(dataSyncId),
            anonymous = false,
            resolved = true,
        )
    }

    fun isAnonymous(secret: String = accountSecret()): Boolean = secret.isEmpty()

    fun accountFingerprint(accountScope: String): String =
        DeviceCrypto.sha256("$ACCOUNT_SCOPE_LABEL/$accountScope".encodeToByteArray()).toBase64()
}

data class AccountIdentitySnapshot(
    val secret: String,
    val fingerprint: String,
    val anonymous: Boolean,
    val resolved: Boolean,
)

private const val UNRESOLVED_SENTINEL = "device-connect-account-unresolved"
private const val ACCOUNT_PROOF_LABEL = "metrolist-device-connect-v2/account-proof"
private const val ACCOUNT_SCOPE_LABEL = "metrolist-device-connect-v2/account-scope"
