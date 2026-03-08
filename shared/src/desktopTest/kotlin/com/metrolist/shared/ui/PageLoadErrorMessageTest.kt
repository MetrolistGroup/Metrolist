package com.metrolist.shared.ui

import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.page_load_failed_with_error
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PageLoadErrorMessageTest {
    @Test
    fun displaysHttpStatusWithoutRequestOrResponseData() =
        runBlocking {
            val error = InnerTubeHttpException("https://example.test/private?token=secret", HttpStatusCode.BadRequest)
            val message = error.localizedPageLoadError()
            assertEquals(getString(Res.string.page_load_failed_with_error, HttpStatusCode.BadRequest.toString()), message)
            assertFalse(message.contains("secret"))
        }

    @Test
    fun displaysTheFailureTypeWithoutLeakingExceptionMessages() =
        runBlocking {
            val message = IllegalStateException("private response body").localizedPageLoadError()
            assertEquals(getString(Res.string.page_load_failed_with_error, "IllegalStateException"), message)
            assertFalse(message.contains("private response body"))
        }
}
