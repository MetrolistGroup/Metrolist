package com.metrolist.android.ui

import android.content.Intent
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncomingIntentTest {
    @Test
    fun `content csv mime types route to PlaylistImport`() {
        val uri = "content://com.android.providers.downloads/document/123".toUri()
        val mimeTypes =
            listOf(
                "text/csv",
                "text/comma-separated-values",
                "text/tab-separated-values",
                "application/csv",
                "text/csv; charset=utf-8",
            )
        for (mimeType in mimeTypes) {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
            assertEquals(IncomingDestination.PlaylistImport(null, uri), intent.toIncomingDestination())
        }
    }

    @Test
    fun `file csv without mime type or with text plain routes to PlaylistImport`() {
        val csvUri = "file:///storage/emulated/0/Download/playlist.csv".toUri()
        assertEquals(
            IncomingDestination.PlaylistImport(null, csvUri),
            Intent(Intent.ACTION_VIEW, csvUri).toIncomingDestination(),
        )
        assertEquals(
            IncomingDestination.PlaylistImport(null, csvUri),
            Intent(Intent.ACTION_VIEW).setDataAndType(csvUri, "text/plain").toIncomingDestination(),
        )

        val tsvUri = "file:///storage/emulated/0/Download/playlist.tsv".toUri()
        assertEquals(
            IncomingDestination.PlaylistImport(null, tsvUri),
            Intent(Intent.ACTION_VIEW, tsvUri).toIncomingDestination(),
        )
    }

    @Test
    fun `non csv file or http csv is ignored`() {
        val txtIntent = Intent(Intent.ACTION_VIEW).setDataAndType("file:///storage/emulated/0/Download/notes.txt".toUri(), "text/plain")
        assertNull(txtIntent.toIncomingDestination())

        val httpIntent = Intent(Intent.ACTION_VIEW, "https://example.com/playlist.csv".toUri())
        assertNull(httpIntent.toIncomingDestination())
    }
}
