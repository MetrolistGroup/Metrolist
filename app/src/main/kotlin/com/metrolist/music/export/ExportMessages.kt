/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import android.content.Context
import com.metrolist.music.R

/** User-facing texts for export results. */
fun ExportError.message(context: Context): String =
    when (this) {
        ExportError.NoStream -> context.getString(R.string.export_error_no_stream)
        ExportError.Network -> context.getString(R.string.export_error_network)
        ExportError.StreamExpired -> context.getString(R.string.export_error_stream_expired)
        is ExportError.InsufficientSpace -> context.getString(R.string.export_error_no_space)
        ExportError.AccessDenied -> context.getString(R.string.export_error_access_denied)
        ExportError.WriteFailed -> context.getString(R.string.export_error_write_failed)
        ExportError.Incomplete -> context.getString(R.string.export_error_incomplete)
        ExportError.UnsupportedSource -> context.getString(R.string.export_error_unsupported)
        ExportError.Mp3Unavailable -> context.getString(R.string.export_error_mp3_unavailable)
        ExportError.TranscodeFailed -> context.getString(R.string.export_error_transcode)
    }

fun ExportResult.message(context: Context): String {
    val base = context.getString(R.string.export_done, displayName, location)
    val notes =
        warnings.mapNotNull {
            when (it) {
                ExportWarning.COVER_UNAVAILABLE -> context.getString(R.string.export_warning_no_cover)
                ExportWarning.TAGS_NOT_SUPPORTED -> context.getString(R.string.export_warning_no_tags)
                ExportWarning.TAGS_FAILED -> context.getString(R.string.export_warning_tags_failed)
            }
        }
    return if (notes.isEmpty()) base else base + "\n" + notes.joinToString(" ")
}
