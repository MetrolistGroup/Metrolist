package com.metrolist.music.utils

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SpotifyAuthorizationTest {
    private fun callback(redirect: String, state: String, query: String = "&error=access_denied"): String {
        val uri = Uri.parse(redirect)
        return Socket("127.0.0.1", uri.port).use {
            it.soTimeout = 3000
            it.getOutputStream().write("GET /callback?state=$state$query HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            it.getInputStream().bufferedReader().readText()
        }
    }

    @Test fun rejectsWrongStateAndReportsDeniedAuthorization() = runBlocking {
        val client = SpotifyImportClient(RuntimeEnvironment.getApplication())
        client.disconnect()
        var responses: Deferred<Triple<String, String, String>>? = null
        try {
            client.connect("a".repeat(32)) { url ->
                val auth = Uri.parse(url)
                assertEquals("user-library-read", auth.getQueryParameter("scope"))
                assertEquals("S256", auth.getQueryParameter("code_challenge_method"))
                assertEquals(43, auth.getQueryParameter("code_challenge")?.length)
                assertEquals("code", auth.getQueryParameter("response_type"))
                responses = async(Dispatchers.IO) {
                    val redirect = auth.getQueryParameter("redirect_uri")!!
                    Triple(callback(redirect, "incorrect-state"), callback(redirect, auth.getQueryParameter("state")!!, ""),
                        callback(redirect, auth.getQueryParameter("state")!!))
                }
            }
            fail("Denied authorization must fail")
        } catch (error: SpotifyApiException) {
            assertEquals(401, error.status)
        }
        val result = responses!!.await()
        assertTrue(result.first.startsWith("HTTP/1.1 400"))
        assertTrue(result.second.startsWith("HTTP/1.1 400"))
        assertTrue(result.third.startsWith("HTTP/1.1 200"))
    }

    @Test fun cancelsPendingSignInWithoutSavingCredentials() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val client = SpotifyImportClient(context)
        client.disconnect()
        val opened = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) { client.connect("a".repeat(32)) { opened.complete(Unit) } }
        opened.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(java.io.File(context.noBackupFilesDir, "spotify-connection").exists())
    }
}
