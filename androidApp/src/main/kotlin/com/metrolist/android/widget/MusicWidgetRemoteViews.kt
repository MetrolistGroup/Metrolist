/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.widget

import android.content.Context
import android.os.Build
import android.os.Parcel
import android.util.SizeF
import android.widget.RemoteViews
import com.metrolist.android.R

internal class MusicWidgetRemoteViews(
    private val context: Context,
    val sizes: List<Pair<Int, Int>>,
    private val layouts: List<RemoteViews>,
    private val useSizeMap: Boolean,
) {
    fun create(
        duration: Long? = null,
        currentPosition: Long? = null,
    ): RemoteViews {
        val updated =
            layouts.map { template ->
                template.copyRemoteViews().apply {
                    if (duration != null && currentPosition != null && duration > 0) {
                        when (layoutId) {
                            R.layout.widget_music_player -> {
                                setWidgetProgress(musicWidgetProgressIds, duration, currentPosition)
                                setTextViewText(R.id.widget_time_current, formatWidgetDuration(context, currentPosition))
                                setTextViewText(R.id.widget_time_duration, formatWidgetDuration(context, duration))
                            }

                            R.layout.widget_compact_square -> {
                                setWidgetProgress(musicWidgetProgressIds, duration, currentPosition)
                                setTextViewText(R.id.widget_square_time, formatWidgetTimePair(context, currentPosition, duration))
                            }

                            R.layout.widget_compact -> {
                                setWidgetProgress(musicWidgetProgressIds, duration, currentPosition)
                            }
                        }
                    }
                }
            }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useSizeMap) {
            RemoteViews(sizes.zip(updated).associate { (size, views) -> SizeF(size.first.toFloat(), size.second.toFloat()) to views })
        } else {
            RemoteViews(updated[0], updated[1])
        }
    }

    private fun RemoteViews.copyRemoteViews(): RemoteViews {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return RemoteViews(this)
        val parcel = Parcel.obtain()
        return try {
            writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
