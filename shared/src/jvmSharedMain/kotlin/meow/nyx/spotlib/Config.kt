package meow.nyx.spotlib

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

enum class LogLevel {
    NONE,

    BODIES,

    SUMMARY,
}

fun interface SpotifyLogger {
    fun log(
        level: LogLevel,
        message: String,
    )
}

internal object Log {
    private var logger: SpotifyLogger = SpotifyLogger { _, message -> println(message) }
    private var level: LogLevel = LogLevel.SUMMARY

    fun configure(
        logger: SpotifyLogger?,
        level: LogLevel,
    ) {
        if (logger != null) this.logger = logger
        this.level = level
    }

    fun debug(message: String) {
        if (level == LogLevel.BODIES) logger.log(level, message)
    }

    fun info(message: String) {
        if (level != LogLevel.NONE) logger.log(level, message)
    }
}

data class SpotifyConfig(
    val userAgent: String = "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0",
    val appVersion: String = "1.2.96.390.gd46a85db",
    val webClientId: String = "d8a5ed958d274c2e8ee717e6a4b0971d",
    val logLevel: LogLevel = LogLevel.SUMMARY,
    val logger: SpotifyLogger? = null,
    val engine: HttpClientEngine,
    val allowAnonymous: Boolean = false,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
)
