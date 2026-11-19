package com.metrolist.drivebroker

import java.security.MessageDigest
import java.security.SecureRandom
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

class TokenRepository(
    private val dataSource: DataSource,
    private val encryptionKey: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    fun createSession(
        account: DriveAccount,
        platform: ClientPlatform,
    ): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val ciphertext = encrypt(account.refreshToken, iv, account.subject, account.clientId)
        val sessionToken = ByteArray(32).also(random::nextBytes)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
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
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sessionToken)
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
                        return DriveAccount(
                            result.getString("google_subject"),
                            result.getString("oauth_client_id"),
                            null,
                            decrypt(
                                result.getBytes("refresh_token_ciphertext"),
                                result.getBytes("refresh_token_iv"),
                                result.getString("google_subject"),
                                result.getString("oauth_client_id"),
                            ),
                        )
                    }
                }
        }
    }

    fun revokeSessionAndDeleteOrphanAccount(sessionToken: String): DriveAccount? {
        val tokenBytes = decodeToken(sessionToken) ?: return null
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val account =
                    connection
                        .prepareStatement(
                            """DELETE FROM google_drive_sessions WHERE token_hash = ? RETURNING google_subject, oauth_client_id""",
                        ).use { statement ->
                            statement.setBytes(1, hash(tokenBytes))
                            statement.executeQuery().use { result ->
                                if (!result.next()) {
                                    connection.rollback()
                                    return null
                                }
                                result.getString("google_subject") to result.getString("oauth_client_id")
                            }
                        }
                val remaining =
                    connection
                        .prepareStatement(
                            "SELECT 1 FROM google_drive_sessions WHERE google_subject = ? AND oauth_client_id = ? LIMIT 1",
                        ).use { statement ->
                            statement.setString(1, account.first)
                            statement.setString(2, account.second)
                            statement.executeQuery().use { it.next() }
                        }
                val revoked =
                    if (remaining) {
                        null
                    } else {
                        connection
                            .prepareStatement(
                                """SELECT refresh_token_iv, refresh_token_ciphertext FROM google_drive_accounts
                       WHERE google_subject = ? AND oauth_client_id = ? FOR UPDATE""",
                            ).use { statement ->
                                statement.setString(1, account.first)
                                statement.setString(2, account.second)
                                statement.executeQuery().use { result ->
                                    if (!result.next()) {
                                        null
                                    } else {
                                        DriveAccount(
                                            account.first,
                                            account.second,
                                            null,
                                            decrypt(
                                                result.getBytes("refresh_token_ciphertext"),
                                                result.getBytes("refresh_token_iv"),
                                                account.first,
                                                account.second,
                                            ),
                                        )
                                    }
                                }
                            }
                    }
                if (!remaining) {
                    connection
                        .prepareStatement(
                            "DELETE FROM google_drive_accounts WHERE google_subject = ? AND oauth_client_id = ?",
                        ).use { statement ->
                            statement.setString(1, account.first)
                            statement.setString(2, account.second)
                            statement.executeUpdate()
                        }
                }
                connection.commit()
                return revoked
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
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
