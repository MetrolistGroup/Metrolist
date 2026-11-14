package meow.nyx.spotlib

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HarTest {
    @Test
    fun `credentials use exact hosts and newest API tokens`() {
        val har = File.createTempFile("spotlib-test", ".har")
        try {
            har.writeText(
                """
                {
                  "log": {
                    "entries": [
                      {"request":{"url":"https://open.spotify.com.evil.example/","headers":[{"name":"Cookie","value":"sp_dc=evil"}]}},
                      {"request":{"url":"https://open.spotify.com/","headers":[{"name":"Cookie","value":"sp_dc=valid; sp_t=device"}]}},
                      {"request":{"url":"https://api-partner.spotify.com/pathfinder/v2/query","headers":[{"name":"Authorization","value":"Bearer old"},{"name":"Client-Token","value":"old-client"}]}},
                      {"request":{"url":"https://api-partner.spotify.com/pathfinder/v2/query","headers":[{"name":"Authorization","value":"Bearer newest"},{"name":"Client-Token","value":"newest-client"}]}}
                    ]
                  }
                }
                """.trimIndent(),
            )

            val credentials = Har.credentials(har)

            assertEquals("valid", credentials.cookies["sp_dc"])
            assertEquals("device", credentials.cookies["sp_t"])
            assertEquals("newest", credentials.accessToken)
            assertEquals("newest-client", credentials.clientToken)
        } finally {
            har.delete()
        }
    }
}
