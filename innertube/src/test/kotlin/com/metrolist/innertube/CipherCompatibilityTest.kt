package com.metrolist.innertube

import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CipherCompatibilityTest {
    @Test
    fun es6PlayerUsesIasAndDeciphersFormatsWithMathBuiltins() = runBlocking {
        val supplied = "https://www.youtube.com/s/player/12345678/player_es6.vflset/en_US/base.js"
        val preferred = "https://www.youtube.com/s/player/12345678/player_ias.vflset/en_GB/base.js"
        val mediaUrl = "https://example.googlevideo.com/videoplayback"
        val engine = MockEngine { request ->
            assertEquals(preferred, request.url.toString())
            respond(
                """
                var g={};
                var S=function(a,b,input){return input.split('').reverse().join('');};
                g.X=function(raw,enabled){this.raw=raw;this.V={};};
                g.X.prototype.set=function(k,v){this.V[k]=v;};
                g.X.prototype.get=function(k){applyN(7,19,this);return this.V[k];};
                var applyN=function(a,b,o){if((a^b)==20){o.set('n',o.V.n + '_' + Math.pow(2,3));}};
                var decode=function(a,b,x){return x;};
                var landmark=function(M,k,X){M=new g.X(M,!0);M.set("alr","yes");X&&(X=S(1,2,decode(3,673,X)));};
                """.trimIndent(),
            )
        }
        HttpClient(engine).use { client ->
            val cipher = YouTubeCipherService(client)
            try {
                val format = PlayerResponse.StreamingData.Format(
                    itag = 140,
                    mimeType = "audio/mp4",
                    signatureCipher = "s=abc&sp=sig&url=$mediaUrl?n=challenge",
                )
                repeat(2) {
                    val solved = cipher.processFormats(supplied, listOf(format)).single()
                    val url = Url(requireNotNull(solved.url))
                    assertEquals("cba", url.parameters["sig"])
                    assertEquals("challenge_8", url.parameters["n"])
                    assertNull(solved.signatureCipher)
                }
                assertEquals(1, engine.requestHistory.size)
            } finally {
                cipher.dispose()
            }
        }
    }
}
