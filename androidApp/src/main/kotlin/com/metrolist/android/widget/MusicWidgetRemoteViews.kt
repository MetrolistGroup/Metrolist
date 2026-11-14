package com.metrolist.android.widget

import android.os.Build
import android.os.Parcel
import android.util.SizeF
import android.widget.RemoteViews
import com.metrolist.android.R

internal class MusicWidgetRemoteViews(
    val sizes: List<Pair<Int, Int>>,
    private val layouts: List<RemoteViews>,
    private val useSizeMap: Boolean,
) {
    fun create(progressLevel: Int? = null): RemoteViews {
        val updated =
            layouts.map { template ->
                template.copyRemoteViews().apply {
                    if (progressLevel != null && layoutId == R.layout.widget_music_player) {
                        setInt(R.id.widget_progress_fill, "setImageLevel", progressLevel)
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
