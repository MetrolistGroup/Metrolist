package com.metrolist.android.utils

import com.metrolist.shared.crash.redactCrashReport
import com.metrolist.shared.network.sharedOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal data class CrashPaste(
    val id: String,
    val url: String,
)

internal fun redactCrashLog(value: String): String = redactCrashReport(value)

internal class CrashLogUploader(
    private val client: OkHttpClient = sharedOkHttpClient,
    private val baseUrl: HttpUrl = PASTE_BASE_URL.toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun upload(crashLog: String): CrashPaste =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(baseUrl.newBuilder().addPathSegments("api/v2/pastes").build())
                    .post(
                        json
                            .encodeToString(CreatePasteRequest(redactCrashLog(crashLog)))
                            .toRequestBody(JSON_MEDIA_TYPE),
                    ).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Crash log upload failed with HTTP ${response.code}")
                }

                val id =
                    runCatching {
                        json.decodeFromString<CreatePasteResponse>(response.body.string()).id
                    }.getOrElse { cause ->
                        throw IOException("Crash log upload returned an invalid response", cause)
                    }.takeIf(String::isNotBlank)
                        ?: throw IOException("Crash log upload returned an empty paste ID")

                CrashPaste(
                    id = id,
                    url =
                        baseUrl
                            .newBuilder()
                            .addPathSegment(id)
                            .build()
                            .toString(),
                )
            }
        }

    @Serializable
    private data class CreatePasteRequest(
        val content: String,
    )

    @Serializable
    private data class CreatePasteResponse(
        val id: String,
    )

    private companion object {
        const val PASTE_BASE_URL = "https://paste.crdroid.net/"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
