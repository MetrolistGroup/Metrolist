package com.metrolist.music.playback.aa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ArtworkUriResolverValidationTest {
    private lateinit var context: Context
    private lateinit var resolver: ArtworkUriResolver

    private companion object {
        const val MIN_VALID_FILE_SIZE = 100     // Equal to ArtworkUriResolver.MIN_ARTWORK_FILE_SIZE
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resolver = ArtworkUriResolver(context)
    }


    @Test
    fun truncatedJpeg_boundsPassButValidationFails() {
        val fullBytes = createImageBytes(Bitmap.CompressFormat.JPEG)

        val sosIndex = findJpegSos(fullBytes)
        Assert.assertTrue("SOS marker should be present", sosIndex > 0)

        val sosLength = jpegSegmentLength(fullBytes, sosIndex)
        val truncateAt = sosIndex + 2 + sosLength

        Assert.assertTrue(
            "Truncated file must exceed the minimum byte threshold",
            truncateAt >= MIN_VALID_FILE_SIZE
        )
        Assert.assertTrue(
            "Truncated file must be smaller than the original",
            truncateAt < fullBytes.size
        )

        val truncatedBytes = fullBytes.copyOfRange(0, truncateAt)
        val file = File.createTempFile("aa_truncated", ".jpg", context.cacheDir)

        println(fullBytes.contentToString())
        println(truncatedBytes.contentToString())

        try {
            file.writeBytes(truncatedBytes)
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

            Assert.assertTrue(
                "Bounds check should pass because the SOF is intact",
                boundsOptions.outWidth > 0 && boundsOptions.outHeight > 0
            )

            Assert.assertFalse(
                "Truncated JPEG should not pass full validation",
                resolver.isValidImageFile(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun validJpeg_passesValidation() {
        val file = File.createTempFile("aa_valid", ".jpg", context.cacheDir)

        try {
            file.writeBytes(createImageBytes(Bitmap.CompressFormat.JPEG))

            Assert.assertTrue(
                "Valid JPEG must pass validation",
                resolver.isValidImageFile(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun emptyFile_failsValidation() {
        val file = File.createTempFile("aa_empty", ".jpg", context.cacheDir)

        try {
            file.writeBytes(ByteArray(0))

            Assert.assertFalse(
                "Empty file must fail validation",
                resolver.isValidImageFile(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun randomBytes_failValidation() {
        val file = File.createTempFile("aa_random", ".jpg", context.cacheDir)

        try {
            val randomBytes = ByteArray(500) { (Math.random() * 256).toInt().toByte() }
            file.writeBytes(randomBytes)

            Assert.assertFalse(
                "Random bytes must not pass validation",
                resolver.isValidImageFile(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun validationCache_invalidatedWhenFileChanges() {
        val file = File.createTempFile("aa_cache", ".jpg", context.cacheDir)

        try {
            file.writeBytes(createImageBytes(Bitmap.CompressFormat.JPEG))

            Assert.assertTrue(
                "Initial valid JPEG must pass validation",
                resolver.isValidImageFile(file)
            )

            // Overwrite with invalid data
            file.writeBytes(ByteArray(500) { 0 })

            Assert.assertFalse(
                "Cache must invalidate when the file changes",
                resolver.isValidImageFile(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun png_ValidAndTruncated() {
        val fullBytes = createImageBytes(Bitmap.CompressFormat.PNG)
        val truncateAt = fullBytes.size / 2
        Assert.assertTrue(
            "Truncated file must exceed the minimum byte threshold",
            truncateAt >= MIN_VALID_FILE_SIZE
        )
        val truncatedBytes = fullBytes.copyOfRange(0, truncateAt)

        val validFile = File.createTempFile("aa_valid_png", ".png", context.cacheDir)
        val truncatedFile = File.createTempFile("aa_truncated_png", ".png", context.cacheDir)
        try {
            validFile.writeBytes(createImageBytes(Bitmap.CompressFormat.PNG))
            Assert.assertTrue(
                "Valid PNG must pass validation",
                resolver.isValidImageFile(validFile)
            )

            truncatedFile.writeBytes(truncatedBytes)
            Assert.assertFalse(
                "Truncated PNG should not pass full validation",
                resolver.isValidImageFile(truncatedFile)
            )
        } finally {
            validFile.delete()
            truncatedFile.delete()
        }
    }


    // Helper
    private fun createImageBytes(
        format: Bitmap.CompressFormat,
        size: Int = 100,
        color: Int = Color.RED
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val baos = ByteArrayOutputStream()
        bitmap.compress(format, 100, baos)
        bitmap.recycle()
        return baos.toByteArray()
    }

    private fun findJpegSos(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xDA.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun jpegSegmentLength(bytes: ByteArray, markerIndex: Int): Int {
        val hi = bytes[markerIndex + 2].toInt() and 0xFF
        val lo = bytes[markerIndex + 3].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    @Test
    fun prefetchSuccessWithoutValidSnapshot_retainsBackoff() {
        // Simulates the outcome of a successful prefetch on the network side
        // but with no disk cache entries to serve.
        val url = "https://random.url.com/artwork-missing"
        resolver.onPrefetchCompleted(url, isError = false)

        Assert.assertTrue(
            "Backoff must remain if there is no serviceable snapshot",
            resolver.isFailedRecently(url),
        )
    }
}