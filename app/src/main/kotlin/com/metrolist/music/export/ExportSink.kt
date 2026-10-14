/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** A file that is being written. Nothing is visible to the user until [commit]. */
internal class SinkFile(
    val stream: OutputStream,
    val displayName: String,
    val location: String,
    val uri: Uri,
    private val onCommit: () -> Unit,
    private val onAbort: () -> Unit,
) {
    fun commit() = onCommit()

    fun abort() = onAbort()
}

/** Where exported files end up: the public Music/Metrolist folder or a folder the user picked via SAF. */
internal interface ExportSink {
    fun exists(fileName: String): Boolean

    /**
     * Creates [fileName]. With [ConflictPolicy.REPLACE] an existing file is deleted first, otherwise a free name
     * is chosen; the existing file is never modified.
     */
    fun create(
        fileName: String,
        mimeType: String,
        policy: ConflictPolicy,
    ): SinkFile

    companion object {
        fun mimeTypeFor(extension: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: when (extension) {
                    "mp3" -> "audio/mpeg"
                    "m4a" -> "audio/mp4"
                    "opus" -> "audio/ogg"
                    "webm" -> "audio/webm"
                    else -> "application/octet-stream"
                }

        fun create(
            context: Context,
            destination: ExportDestination,
        ): ExportSink {
            val tree = destination.treeUri
            return when {
                tree != null -> TreeSink(context, tree.toUri())
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStoreSink(context.contentResolver)
                // Before Android 10, writing to shared storage needs a legacy permission; SAF does not.
                else -> throw ExportException(ExportError.AccessDenied)
            }
        }
    }
}

/** Android 10+: public `Music/Metrolist` through MediaStore. Needs no permission for files the app creates. */
@RequiresApi(Build.VERSION_CODES.Q)
internal class MediaStoreSink(private val resolver: ContentResolver) : ExportSink {
    private val collection: Uri
        get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val relativePath = "${Environment.DIRECTORY_MUSIC}/Metrolist/"

    override fun exists(fileName: String): Boolean = findIds(fileName).isNotEmpty()

    private fun findIds(fileName: String): List<Long> =
        resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(fileName, relativePath),
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }.orEmpty()

    override fun create(
        fileName: String,
        mimeType: String,
        policy: ConflictPolicy,
    ): SinkFile {
        var name = fileName
        if (policy == ConflictPolicy.REPLACE) {
            val deleted =
                try {
                    findIds(fileName).forEach { resolver.delete(ContentUris.withAppendedId(collection, it), null, null) }
                    true
                } catch (_: SecurityException) {
                    // The old file belongs to another app or install; keep it and use a free name.
                    false
                }
            if (!deleted) name = ExportFileNaming.uniqueName(fileName, ::exists)
        } else {
            name = ExportFileNaming.uniqueName(fileName, ::exists)
        }

        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri =
            try {
                resolver.insert(collection, values)
            } catch (error: SecurityException) {
                throw ExportException(ExportError.AccessDenied, error)
            } ?: throw ExportException(ExportError.WriteFailed)

        val stream =
            try {
                resolver.openOutputStream(uri, "w")
            } catch (error: FileNotFoundException) {
                runCatching { resolver.delete(uri, null, null) }
                throw ExportException(ExportError.WriteFailed, error)
            } ?: run {
                runCatching { resolver.delete(uri, null, null) }
                throw ExportException(ExportError.WriteFailed)
            }

        // MediaProvider may adjust the name; report what the user will really see.
        val finalName =
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: name

        return SinkFile(
            stream = stream,
            displayName = finalName,
            location = "${Environment.DIRECTORY_MUSIC}/Metrolist",
            uri = uri,
            onCommit = {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            },
            onAbort = { runCatching { resolver.delete(uri, null, null) } },
        )
    }
}

/** A folder chosen with the system picker (`ACTION_OPEN_DOCUMENT_TREE`). */
internal class TreeSink(
    context: Context,
    private val treeUri: Uri,
) : ExportSink {
    private val resolver = context.contentResolver
    private val parentUri: Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    init {
        val granted =
            resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
        if (!granted) throw ExportException(ExportError.AccessDenied)
    }

    override fun exists(fileName: String): Boolean = findDocument(fileName) != null

    private fun findDocument(fileName: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parentUri))
        return try {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == fileName) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                    }
                }
                null
            }
        } catch (error: SecurityException) {
            throw ExportException(ExportError.AccessDenied, error)
        } catch (error: IllegalArgumentException) {
            throw ExportException(ExportError.AccessDenied, error)
        }
    }

    override fun create(
        fileName: String,
        mimeType: String,
        policy: ConflictPolicy,
    ): SinkFile {
        val name =
            if (policy == ConflictPolicy.REPLACE) {
                findDocument(fileName)?.let { existing ->
                    try {
                        DocumentsContract.deleteDocument(resolver, existing)
                    } catch (error: Exception) {
                        throw ExportException(ExportError.AccessDenied, error)
                    }
                }
                fileName
            } else {
                ExportFileNaming.uniqueName(fileName, ::exists)
            }

        val document =
            try {
                DocumentsContract.createDocument(resolver, parentUri, mimeType, name)
            } catch (error: SecurityException) {
                throw ExportException(ExportError.AccessDenied, error)
            } catch (error: FileNotFoundException) {
                throw ExportException(ExportError.AccessDenied, error)
            } ?: throw ExportException(ExportError.WriteFailed)

        val stream =
            try {
                resolver.openOutputStream(document, "w")
            } catch (error: IOException) {
                runCatching { DocumentsContract.deleteDocument(resolver, document) }
                throw ExportException(ExportError.WriteFailed, error)
            } ?: run {
                runCatching { DocumentsContract.deleteDocument(resolver, document) }
                throw ExportException(ExportError.WriteFailed)
            }

        return SinkFile(
            stream = stream,
            displayName = name,
            location = describeTree(treeUri),
            uri = document,
            onCommit = {},
            onAbort = { runCatching { DocumentsContract.deleteDocument(resolver, document) } },
        )
    }

    companion object {
        /** "primary:Music/Metrolist" -> "Music/Metrolist". */
        fun describeTree(treeUri: Uri): String =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':') }
                .getOrNull()
                ?.ifBlank { null }
                ?: treeUri.toString()
    }
}
