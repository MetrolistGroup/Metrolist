package com.metrolist.drivebroker

import java.util.Base64

data class BrokerConfiguration(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val encryptionKey: ByteArray,
    val webClientId: String,
    val webClientSecret: String,
    val androidClientId: String,
    val iosClientId: String,
    val desktopClientId: String,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BrokerConfiguration =
            BrokerConfiguration(
                port =
                    (environment["PORT"]?.toIntOrNull() ?: 8080).also {
                        require(it in 1..65535) { "PORT must be between 1 and 65535" }
                    },
                databaseUrl = environment.required("DATABASE_URL"),
                databaseUser = environment.required("DATABASE_USER"),
                databasePassword = environment.required("DATABASE_PASSWORD"),
                encryptionKey =
                    runCatching { Base64.getDecoder().decode(environment.required("DRIVE_REFRESH_TOKEN_ENCRYPTION_KEY")) }
                        .getOrElse { throw IllegalArgumentException("DRIVE_REFRESH_TOKEN_ENCRYPTION_KEY must be base64") }
                        .also { require(it.size == 32) { "DRIVE_REFRESH_TOKEN_ENCRYPTION_KEY must decode to 32 bytes" } },
                webClientId = environment.required("GOOGLE_WEB_CLIENT_ID"),
                webClientSecret = environment.required("GOOGLE_WEB_CLIENT_SECRET"),
                androidClientId = environment.required("GOOGLE_ANDROID_CLIENT_ID"),
                iosClientId = environment.required("GOOGLE_IOS_CLIENT_ID"),
                desktopClientId = environment.required("GOOGLE_DESKTOP_CLIENT_ID"),
            )
    }
}

private fun Map<String, String>.required(key: String): String =
    get(key)?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Missing required configuration: $key")
