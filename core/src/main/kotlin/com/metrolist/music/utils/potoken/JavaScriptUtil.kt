package com.metrolist.music.utils.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * Parses the raw challenge data obtained from the Create endpoint and returns an object that can be
 * embedded in a JavaScript snippet.
 */
fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = Json.parseToJsonElement(rawChallengeData).jsonArray

    val challengeData = if (scrambled.size > 1 && scrambled[1].jsonPrimitive.isString) {
        val descrambled = descramble(scrambled[1].jsonPrimitive.content)
        Json.parseToJsonElement(descrambled).jsonArray
    } else {
        scrambled[0].jsonArray
    }

    val messageId = challengeData[0].jsonPrimitive.content
    val interpreterHash = challengeData[3].jsonPrimitive.content
    val program = challengeData[4].jsonPrimitive.content
    val globalName = challengeData[5].jsonPrimitive.content
    val clientExperimentsStateBlob = challengeData[7].jsonPrimitive.content

    val privateDoNotAccessOrElseSafeScriptWrappedValue = challengeData[1]
        .takeIf { it !is JsonNull }
        ?.jsonArray
        ?.find { it.jsonPrimitive.isString }
    val privateDoNotAccessOrElseTrustedResourceUrlWrappedValue = challengeData[2]
        .takeIf { it !is JsonNull }
        ?.jsonArray
        ?.find { it.jsonPrimitive.isString }

    return Json.encodeToString(
        JsonObject.serializer(), JsonObject(
            mapOf(
                "messageId" to JsonPrimitive(messageId),
                "interpreterJavascript" to JsonObject(
                    mapOf(
                        "privateDoNotAccessOrElseSafeScriptWrappedValue" to (privateDoNotAccessOrElseSafeScriptWrappedValue
                            ?: JsonNull),
                        "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to (privateDoNotAccessOrElseTrustedResourceUrlWrappedValue
                            ?: JsonNull)
                    )
                ),
                "interpreterHash" to JsonPrimitive(interpreterHash),
                "program" to JsonPrimitive(program),
                "globalName" to JsonPrimitive(globalName),
                "clientExperimentsStateBlob" to JsonPrimitive(clientExperimentsStateBlob)
            )
        )
    )
}

/**
 * Parses the raw integrity token data obtained from the GenerateIT endpoint to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code, and a [Long] representing the
 * duration of this token in seconds.
 */
fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = Json.parseToJsonElement(rawIntegrityTokenData).jsonArray
    return base64ToU8(integrityTokenData[0].jsonPrimitive.content) to integrityTokenData[1].jsonPrimitive.long
}

/**
 * Converts a string (usually the identifier used as input to `obtainPoToken`) to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code.
 */
fun stringToU8(identifier: String): String {
    return newUint8Array(identifier.toByteArray())
}

/**
 * Takes a poToken encoded as a sequence of bytes represented as integers separated by commas
 * (e.g. "97,98,99" would be "abc"), which is the output of `Uint8Array::toString()` in JavaScript,
 * and converts it to the specific base64 representation for poTokens.
 */
fun u8ToBase64(poToken: String): String {
    return poToken.split(",")
        .map { it.toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace("+", "-")
        .replace("/", "_")
}

/**
 * Takes the scrambled challenge, decodes it from base64, adds 97 to each byte.
 */
private fun descramble(scrambledChallenge: String): String {
    return base64ToByteString(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()
}

/**
 * Decodes a base64 string encoded in the specific base64 representation used by YouTube, and
 * returns a JavaScript `Uint8Array` that can be embedded directly in JavaScript code.
 */
private fun base64ToU8(base64: String): String {
    return newUint8Array(base64ToByteString(base64))
}

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"
}

/**
 * Decodes a base64 string encoded in the specific base64 representation used by YouTube.
 */
private fun base64ToByteString(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')

    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode"))
        .toByteArray()
}
