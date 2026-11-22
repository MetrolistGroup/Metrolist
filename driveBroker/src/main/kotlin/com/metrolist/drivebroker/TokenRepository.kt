package com.metrolist.drivebroker

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

enum class ClientPlatform(
    val wireValue: String,
) {
    ANDROID("ANDROID"),
    IOS("IOS"),
    DESKTOP("DESKTOP"),
    ;

    companion object {
        fun parse(value: String): ClientPlatform? = entries.firstOrNull { it.wireValue == value }
    }
}

data class DriveAccount(
    val subject: String,
    val clientId: String,
    val email: String?,
    val refreshToken: String,
)

sealed interface SessionCreationResult {
    data class Created(
        val token: String,
    ) : SessionCreationResult

    data object StaleCredentials : SessionCreationResult
}

class TokenRepository(
    private val dataSource: DataSource,
    private val encryptionKey: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    fun currentDatabaseTime(): Instant =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT CURRENT_TIMESTAMP").use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next()) { "Database did not return a timestamp" }
                    result.getTimestamp(1).toInstant()
                }
            }
        }

    fun createSession(
        account: DriveAccount,
        platform: ClientPlatform,
        tokenIssuedAt: Instant,
    ): SessionCreationResult {
        val iv = ByteArray(12).also(random::nextBytes)
        val ciphertext = encrypt(account.refreshToken, iv, account.subject, account.clientId)
        val sessionToken = ByteArray(32).also(random::nextBytes)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                lockSubject(connection, account.subject)
                cleanupStaleRevocations(connection)
                rejectPendingRevocation(connection, account.subject)
                if (hasRevocationSince(connection, account.subject, tokenIssuedAt)) {
                    connection.rollback()
                    return SessionCreationResult.StaleCredentials
                }
                lockAccount(connection, account.subject, account.clientId)
                connection
                    .prepareStatement(
                        """INSERT INTO google_drive_accounts (google_subject, oauth_client_id, refresh_token_iv, refresh_token_ciphertext)
                       VALUES (?, ?, ?, ?) ON CONFLICT (google_subject, oauth_client_id)
                       DO UPDATE SET refresh_token_iv = EXCLUDED.refresh_token_iv,
                       refresh_token_ciphertext = EXCLUDED.refresh_token_ciphertext, updated_at = CURRENT_TIMESTAMP""",
                    ).use { statement ->
                        statement.setString(1, account.subject)
                        statement.setString(2, account.clientId)
                        statement.setBytes(3, iv)
                        statement.setBytes(4, ciphertext)
                        statement.executeUpdate()
                    }
                connection
                    .prepareStatement(
                        "INSERT INTO google_drive_sessions (token_hash, google_subject, oauth_client_id, platform) VALUES (?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setBytes(1, hash(sessionToken))
                        statement.setString(2, account.subject)
                        statement.setString(3, account.clientId)
                        statement.setString(4, platform.wireValue)
                        statement.executeUpdate()
                    }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
        return SessionCreationResult.Created(Base64.getUrlEncoder().withoutPadding().encodeToString(sessionToken))
    }

    fun accountForSession(sessionToken: String): DriveAccount? {
        val tokenBytes = decodeToken(sessionToken) ?: return null
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """SELECT a.google_subject, a.oauth_client_id, a.refresh_token_iv, a.refresh_token_ciphertext
                   FROM google_drive_sessions s JOIN google_drive_accounts a USING (google_subject, oauth_client_id)
                   WHERE s.token_hash = ?""",
                ).use { statement ->
                    statement.setBytes(1, hash(tokenBytes))
                    statement.executeQuery().use { result ->
                        if (!result.next()) return null
                        val subject = result.getString("google_subject")
                        val clientId = result.getString("oauth_client_id")
                        return DriveAccount(
                            subject,
                            clientId,
                            null,
                            decrypt(
                                result.getBytes("refresh_token_ciphertext"),
                                result.getBytes("refresh_token_iv"),
                                subject,
                                clientId,
                            ),
                        )
                    }
                }
        }
    }

    /**
     * The subject lock and revocation marker are held until Google has answered and the session is
     * deleted, so a sign-in cannot commit a token that the revocation is about to invalidate. A
     * failure rolls the whole transaction back, leaving the marker retriable.
     */
    suspend fun revokeSession(
        sessionToken: String,
        revoke: suspend (DriveAccount?) -> Unit,
    ): Boolean {
        val tokenHash = decodeToken(sessionToken)?.let(::hash) ?: return false
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val session =
                    findSession(connection, tokenHash) ?: run {
                        connection.rollback()
                        return false
                    }
                val subject = session.first
                val clientId = session.second
                lockSubject(connection, subject)
                cleanupStaleRevocations(connection)
                rejectPendingRevocation(connection, subject)
                lockAccount(connection, subject, clientId)
                markRevocation(connection, subject)
                val refreshToken = readRefreshToken(connection, subject, clientId)
                // Google revocation invalidates every client in the project, not just this account's OAuth client.
                val accountToRevoke =
                    if (refreshToken == null || hasSession(connection, subject, excludedTokenHash = tokenHash)) {
                        null
                    } else {
                        DriveAccount(subject, clientId, null, refreshToken)
                    }
                revoke(accountToRevoke)
                connection
                    .prepareStatement("DELETE FROM google_drive_sessions WHERE token_hash = ?")
                    .use { statement ->
                        statement.setBytes(1, tokenHash)
                        check(statement.executeUpdate() > 0) { "The revoked session is no longer stored" }
                    }
                if (!hasSession(connection, subject, clientId)) deleteAccount(connection, subject, clientId)
                finishRevocation(connection, subject)
                connection.commit()
                return true
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    private fun findSession(
        connection: Connection,
        tokenHash: ByteArray,
    ): Pair<String, String>? =
        connection
            .prepareStatement("SELECT google_subject, oauth_client_id FROM google_drive_sessions WHERE token_hash = ?")
            .use { statement ->
                statement.setBytes(1, tokenHash)
                statement.executeQuery().use { result ->
                    if (!result.next()) null else result.getString("google_subject") to result.getString("oauth_client_id")
                }
            }

    private fun readRefreshToken(
        connection: Connection,
        subject: String,
        clientId: String,
    ): String? =
        connection
            .prepareStatement(
                "SELECT refresh_token_iv, refresh_token_ciphertext FROM google_drive_accounts WHERE google_subject = ? AND oauth_client_id = ?",
            ).use { statement ->
                statement.setString(1, subject)
                statement.setString(2, clientId)
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        null
                    } else {
                        decrypt(
                            result.getBytes("refresh_token_ciphertext"),
                            result.getBytes("refresh_token_iv"),
                            subject,
                            clientId,
                        )
                    }
                }
            }

    private fun cleanupStaleRevocations(connection: Connection) {
        connection
            .prepareStatement(
                "DELETE FROM google_drive_revocations WHERE started_at < CURRENT_TIMESTAMP - INTERVAL '10 minutes'",
            ).use { statement -> statement.executeUpdate() }
    }

    private fun rejectPendingRevocation(
        connection: Connection,
        subject: String,
    ) {
        connection
            .prepareStatement("SELECT 1 FROM google_drive_revocations WHERE google_subject = ? AND finished_at IS NULL FOR UPDATE")
            .use { statement ->
                statement.setString(1, subject)
                statement.executeQuery().use { result ->
                    check(!result.next()) { "Google account revocation is in progress" }
                }
            }
    }

    private fun hasRevocationSince(
        connection: Connection,
        subject: String,
        tokenIssuedAt: Instant,
    ): Boolean =
        connection
            .prepareStatement(
                """SELECT 1 FROM google_drive_revocations
                   WHERE google_subject = ? AND (finished_at IS NULL OR finished_at >= ?)""",
            ).use { statement ->
                statement.setString(1, subject)
                statement.setTimestamp(2, Timestamp.from(tokenIssuedAt))
                statement.executeQuery().use { it.next() }
            }

    private fun markRevocation(
        connection: Connection,
        subject: String,
    ) {
        connection
            .prepareStatement(
                """INSERT INTO google_drive_revocations (google_subject, finished_at) VALUES (?, NULL)
                   ON CONFLICT (google_subject) DO UPDATE SET started_at = CURRENT_TIMESTAMP, finished_at = NULL""",
            ).use { statement ->
                statement.setString(1, subject)
                statement.executeUpdate()
            }
    }

    private fun finishRevocation(
        connection: Connection,
        subject: String,
    ) {
        connection
            .prepareStatement("UPDATE google_drive_revocations SET finished_at = CURRENT_TIMESTAMP WHERE google_subject = ?")
            .use { statement ->
                statement.setString(1, subject)
                statement.executeUpdate()
            }
    }

    private fun hasSession(
        connection: Connection,
        subject: String,
        clientId: String? = null,
        excludedTokenHash: ByteArray? = null,
    ): Boolean =
        connection
            .prepareStatement(
                buildString {
                    append("SELECT 1 FROM google_drive_sessions WHERE google_subject = ?")
                    if (clientId != null) append(" AND oauth_client_id = ?")
                    if (excludedTokenHash != null) append(" AND token_hash <> ?")
                    append(" LIMIT 1")
                },
            ).use { statement ->
                var index = 1
                statement.setString(index++, subject)
                clientId?.let { statement.setString(index++, it) }
                excludedTokenHash?.let { statement.setBytes(index++, it) }
                statement.executeQuery().use { it.next() }
            }

    private fun deleteAccount(
        connection: Connection,
        subject: String,
        clientId: String,
    ) {
        connection
            .prepareStatement("DELETE FROM google_drive_accounts WHERE google_subject = ? AND oauth_client_id = ?")
            .use { statement ->
                statement.setString(1, subject)
                statement.setString(2, clientId)
                statement.executeUpdate()
            }
    }

    private fun lockSubject(
        connection: Connection,
        subject: String,
    ) {
        connection
            .prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)")
            .use { statement ->
                statement.setString(1, subject)
                statement.executeQuery().use { it.next() }
            }
    }

    private fun lockAccount(
        connection: Connection,
        subject: String,
        clientId: String,
    ) {
        connection
            .prepareStatement(
                "SELECT 1 FROM google_drive_accounts WHERE google_subject = ? AND oauth_client_id = ? FOR UPDATE",
            ).use { statement ->
                statement.setString(1, subject)
                statement.setString(2, clientId)
                statement.executeQuery().use { it.next() }
            }
    }

    private fun encrypt(
        value: String,
        iv: ByteArray,
        subject: String,
        clientId: String,
    ): ByteArray = cipher(Cipher.ENCRYPT_MODE, iv, subject, clientId).doFinal(value.toByteArray(Charsets.UTF_8))

    private fun decrypt(
        value: ByteArray,
        iv: ByteArray,
        subject: String,
        clientId: String,
    ): String = cipher(Cipher.DECRYPT_MODE, iv, subject, clientId).doFinal(value).toString(Charsets.UTF_8)

    private fun cipher(
        mode: Int,
        iv: ByteArray,
        subject: String,
        clientId: String,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(128, iv))
            updateAAD("$subject\n$clientId".toByteArray(Charsets.UTF_8))
        }

    private fun decodeToken(value: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()?.takeIf { it.size == 32 }

    private fun hash(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
}
