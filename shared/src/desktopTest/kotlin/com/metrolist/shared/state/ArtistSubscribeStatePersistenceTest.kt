package com.metrolist.shared.state

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistSubscribeStatePersistenceTest {
    @Test
    fun rapidTogglesWaitForRemoteUpdatesAndConfirmCommittedState(): Unit =
        runBlocking {
            val path = Files.createTempFile("metrolist-artist-subscription-order", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val subscribeStarted = CompletableDeferred<Unit>()
            val finishSubscribe = CompletableDeferred<Unit>()
            val requests = mutableListOf<String>()
            val confirmations = mutableListOf<Boolean>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val action = request.url.encodedPath.substringAfterLast('/')
                        requests += action
                        if (action == "subscribe") {
                            subscribeStarted.complete(Unit)
                            finishSubscribe.await()
                        }
                        respond("{}", HttpStatusCode.OK)
                    },
                ) {
                    install(ContentNegotiation) { json() }
                }
            val innerTube = InnerTube(client).apply { cookie = "SAPISID=session" }
            val repository = YouTubeRepository(innerTube)
            try {
                withTimeout(5_000) {
                    val subscribe =
                        toggleArtistSubscription(
                            this,
                            database,
                            repository,
                            "artist",
                            "Artist",
                            onSubscriptionChanged = { confirmations += it },
                            onError = { error("Subscription should succeed") },
                        )
                    subscribeStarted.await()
                    assertFalse(database.isArtistSubscribedFlow("artist").first())
                    database.updateArtistSubscribed("artist", "Artist", subscribed = true)
                    val unsubscribe =
                        toggleArtistSubscription(
                            this,
                            database,
                            repository,
                            "artist",
                            "Artist",
                            onSubscriptionChanged = { confirmations += it },
                            onError = { error("Unsubscription should succeed") },
                        )
                    yield()
                    assertEquals(listOf("subscribe"), requests)
                    assertEquals(emptyList(), confirmations)
                    finishSubscribe.complete(Unit)
                    subscribe.join()
                    unsubscribe.join()
                    assertEquals(listOf("subscribe", "unsubscribe"), requests)
                    assertEquals(listOf(true, false), confirmations)
                    assertFalse(database.isArtistSubscribedFlow("artist").first())
                }
            } finally {
                innerTube.close()
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun subscriptionsPersistMetadataAndConcurrentTogglesStayAtomic(): Unit =
        runBlocking {
            val path = Files.createTempFile("metrolist-artist-subscription", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val artistId = "artist"
                assertFalse(database.isArtistSubscribedFlow(artistId).first())
                assertTrue(database.updateArtistSubscribed(artistId, "Artist"))
                assertTrue(database.isArtistSubscribedFlow(artistId).first())
                val saved = assertNotNull(database.dao().getArtistById(artistId))
                assertEquals("Artist", saved.name)
                assertNotNull(saved.bookmarkedAt)

                val complete = saved.copy(name = "Saved artist", thumbnailUrl = "cover.jpg")
                database.dao().upsert(complete)
                assertFalse(database.updateArtistSubscribed(artistId, "Different song artist"))
                assertFalse(database.isArtistSubscribedFlow(artistId).first())
                assertEquals(complete.copy(bookmarkedAt = null), database.dao().getArtistById(artistId))

                coroutineScope {
                    repeat(2) {
                        launch { database.updateArtistSubscribed(artistId, "Artist") }
                    }
                }
                assertNull(database.dao().getArtistById(artistId)?.bookmarkedAt)
                assertTrue(database.updateArtistSubscribed(artistId, "Artist"))
                assertNotNull(database.dao().getArtistById(artistId)?.bookmarkedAt)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun rejectedRemoteChangesPreserveBookmarksAndSignedOutChangesStayLocal() =
        runBlocking {
            val path = Files.createTempFile("artist-failed", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", HttpStatusCode.Forbidden)
                    },
                ) {
                    install(ContentNegotiation) { json() }
                }
            val innerTube = InnerTube(client).apply { cookie = "SAPISID=session" }
            try {
                val repository = YouTubeRepository(innerTube)
                var errors = 0
                val confirmations = mutableListOf<Boolean>()
                for (saved in listOf(false, true)) {
                    if (saved) database.updateArtistSubscribed("artist", "Artist")
                    val before = database.dao().getArtistById("artist")
                    toggleArtistSubscription(this, database, repository, "artist", "Artist", confirmations::add, { errors++ }).join()
                    assertEquals(before, database.dao().getArtistById("artist"))
                }
                assertEquals(2, errors)
                assertEquals(emptyList(), confirmations)
                innerTube.cookie = null
                toggleArtistSubscription(this, database, repository, "artist", "Artist", confirmations::add, { errors++ }).join()
                assertEquals(listOf(false), confirmations)
                assertEquals(2, requests)
                assertEquals(2, errors)
            } finally {
                innerTube.close()
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
