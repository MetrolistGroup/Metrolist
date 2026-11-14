package com.metrolist.android.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun shareImage(
    context: Context,
    image: ImageBitmap,
    chooserTitle: String,
): Boolean {
    val uri =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "images").apply { mkdirs() }
            val file = File.createTempFile("image_", ".png", directory)
            try {
                file.outputStream().use { check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, chooserTitle, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}
