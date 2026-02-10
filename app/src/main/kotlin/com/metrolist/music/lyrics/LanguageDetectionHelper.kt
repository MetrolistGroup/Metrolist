/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

object LanguageDetectionHelper {
    private var languageIdentifier: LanguageIdentifier? = null

    private fun getOrCreateIdentifier(): LanguageIdentifier? {
        return try {
            if (languageIdentifier == null) {
                languageIdentifier = LanguageIdentification.getClient()
            }
            languageIdentifier
        } catch (e: Exception) {
            Timber.e(e, "Failed to create LanguageIdentifier")
            null
        }
    }

    suspend fun identifyLanguage(text: String): String? = suspendCancellableCoroutine { cont ->
        val identifier = getOrCreateIdentifier()
        if (identifier == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        
        try {
            identifier.identifyLanguage(text)
                .addOnSuccessListener { languageCode ->
                    if (languageCode == "und") {
                        cont.resume(null)
                    } else {
                        cont.resume(languageCode)
                    }
                }
                .addOnFailureListener { exception ->
                    Timber.e(exception, "Language identification failed")
                    cont.resume(null)
                }
        } catch (e: Exception) {
            Timber.e(e, "Language identification threw exception")
            cont.resume(null)
        }
    }
}
