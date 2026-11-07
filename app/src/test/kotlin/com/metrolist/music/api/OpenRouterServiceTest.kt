package com.metrolist.music.api

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterServiceTest {
    @Test
    fun `translation parsing handles fenced and short responses`() {
        assertEquals(
            listOf("uno", ""),
            parseTranslationContent("```json\n[\"uno\"]\n```", 2).getOrThrow(),
        )
    }

    @Test
    fun `translation parsing handles the structured output object shape`() {
        assertEquals(
            listOf("uno", "dos"),
            parseTranslationContent("""{"lines": ["uno", "dos"]}""", 2).getOrThrow(),
        )
    }

    @Test
    fun `translation parsing rejects unexpected JSON objects before fallbacks`() {
        val invalidObjects =
            listOf(
                """{"translation":"uno"}""",
                """{"translation":["uno"]}""",
                """{}""",
                """{"lines":"uno"}""",
                """{"lines":[1, true, null]}""",
                """{"lines":[{"text":"uno"}]}""",
                """{"lines":[["uno"]]}""",
            )

        invalidObjects.forEach { content ->
            assertTrue(content, parseTranslationContent(content, 1).isFailure)
            assertTrue(content, parseTranslationContent("```json\n$content\n```", 1).isFailure)
        }
    }

    @Test
    fun `translation parsing preserves valid fenced objects and empty lines`() {
        assertEquals(
            listOf("uno", "", "dos"),
            parseTranslationContent("```json\n{\"lines\":[\"uno\",\"\",\"dos\"]}\n```", 3).getOrThrow(),
        )
    }

    @Test
    fun `translation parsing preserves plain text and embedded array fallbacks`() {
        assertEquals(listOf("uno", "dos"), parseTranslationContent("uno\ndos", 2).getOrThrow())
        assertEquals(listOf("uno", ""), parseTranslationContent("Here: [\"uno\"]", 2).getOrThrow())
    }

    @Test
    fun `request uses structured outputs with a lines schema and strict parameter routing`() {
        val request =
            buildTranslationRequest(
                text = "one\ntwo",
                targetLanguage = "Spanish",
                model = "model",
                mode = "Translated",
                customSystemPrompt = "",
            )

        val responseFormat = request.getValue("response_format").jsonObject
        assertEquals("json_schema", responseFormat.getValue("type").jsonPrimitive.content)
        val schema = responseFormat.getValue("json_schema").jsonObject.getValue("schema").jsonObject
        assertEquals("object", schema.getValue("type").jsonPrimitive.content)
        val lines = schema.getValue("properties").jsonObject.getValue("lines").jsonObject
        assertEquals("array", lines.getValue("type").jsonPrimitive.content)
        assertEquals("string", lines.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(request.getValue("provider").jsonObject.getValue("require_parameters").jsonPrimitive.boolean)
    }

    @Test
    fun `deepseek uses json object response format instead of json schema`() {
        val request =
            buildTranslationRequest(
                text = "one\ntwo",
                targetLanguage = "Spanish",
                model = "deepseek-flash",
                mode = "Translated",
                customSystemPrompt = "",
                baseUrl = "https://api.deepseek.com/chat/completions",
            )

        val responseFormat = request.getValue("response_format").jsonObject
        assertEquals("json_object", responseFormat.getValue("type").jsonPrimitive.content)
        assertTrue("json_schema" !in responseFormat)
        assertTrue("provider" !in request)
    }

    @Test
    fun `deepseek response format matches only the URL hostname`() {
        val endpoints =
            mapOf(
                "https://API.DEEPSEEK.COM/v1/chat/completions" to "json_object",
                "https://api.deepseek.com:443/chat/completions" to "json_object",
                "https://example.com/api.deepseek.com/chat/completions" to "json_schema",
                "https://example.com/chat/completions?provider=api.deepseek.com" to "json_schema",
                "https://api.deepseek.com.example.com/chat/completions" to "json_schema",
                "https://api.deepseek.com@example.com/chat/completions" to "json_schema",
            )

        endpoints.forEach { (baseUrl, expectedFormat) ->
            val request =
                buildTranslationRequest(
                    text = "one",
                    targetLanguage = "Spanish",
                    model = "model",
                    mode = "Translated",
                    customSystemPrompt = "",
                    baseUrl = baseUrl,
                )
            val responseFormat = request.getValue("response_format").jsonObject
            assertEquals(baseUrl, expectedFormat, responseFormat.getValue("type").jsonPrimitive.content)
            assertEquals(baseUrl, expectedFormat == "json_schema", "json_schema" in responseFormat)
        }
    }

    @Test
    fun `openrouter-only provider routing is omitted for direct providers`() {
        val request =
            buildTranslationRequest(
                text = "one",
                targetLanguage = "Spanish",
                model = "mercury-2",
                mode = "Translated",
                customSystemPrompt = "",
                baseUrl = "https://api.inceptionlabs.ai/v1/chat/completions",
            )

        assertTrue("provider" !in request)
        assertTrue(request.containsKey("response_format"))
    }

    @Test
    fun `openai requests omit parameters gpt-5 models reject`() {
        val request =
            buildTranslationRequest(
                text = "one",
                targetLanguage = "Spanish",
                model = "gpt-5.6-luna",
                mode = "Translated",
                customSystemPrompt = "",
                baseUrl = "https://api.openai.com/v1/chat/completions",
            )

        assertTrue("max_tokens" !in request)
        assertTrue("temperature" !in request)
    }

    @Test
    fun `streaming romanization uses the romanization prompt`() {
        val request =
            buildTranslationRequest(
                text = "東京",
                targetLanguage = "English",
                model = "model",
                mode = "Romanized",
                customSystemPrompt = "",
                stream = true,
            )

        assertTrue(request.getValue("stream").jsonPrimitive.boolean)
        assertTrue(
            request
                .getValue("messages")
                .jsonArray[1]
                .jsonObject
                .getValue("content")
                .jsonPrimitive
                .content
                .startsWith("Romanize"),
        )
    }
}
