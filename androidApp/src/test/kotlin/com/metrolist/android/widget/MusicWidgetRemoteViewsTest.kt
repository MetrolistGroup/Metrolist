package com.metrolist.android.widget

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.os.Parcel
import android.widget.RemoteViews
import com.metrolist.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [24, 31])
class MusicWidgetRemoteViewsTest {
    @Test
    fun orientationPublicationsRetainCompleteChildrenWithoutAccumulatingProgressActions() {
        verifyPublications(useSizeMap = false)
    }

    @Test
    @Config(sdk = [31])
    fun sizePublicationsRetainCompleteChildrenWithoutAccumulatingProgressActions() {
        verifyPublications(useSizeMap = true)
    }

    @Test
    @Config(sdk = [31])
    fun aSingleSizeRetainsItsMetadataAndUpdatedProgress() {
        val snapshot = MusicWidgetRemoteViews(listOf(280 to 150), listOf(template(R.layout.widget_music_player)), true)
        assertPlayerState(roundTrip(snapshot.create(7_000)), 7_000)
    }

    private fun verifyPublications(useSizeMap: Boolean) {
        val player = template(R.layout.widget_music_player)
        val wide = template(R.layout.widget_compact_wide)
        val snapshot = MusicWidgetRemoteViews(listOf(400 to 64, 280 to 150), listOf(wide, player), useSizeMap)
        var actionCount: Int? = null
        for (level in listOf(2_000, 6_000, 2_000)) {
            val children = children(roundTrip(snapshot.create(level)))
            assertEquals(2, children.size)
            val updated = children.single { it.layoutId == R.layout.widget_music_player }
            assertPlayerState(updated, level)
            assertEquals("Cached title", actionValue(children.single { it.layoutId == R.layout.widget_compact_wide }, "setText"))
            val count = actions(updated).size
            if (actionCount != null) assertEquals(actionCount, count)
            actionCount = count
        }
        assertPlayerState(roundTrip(player), 1_000)
    }

    private fun template(layoutId: Int): RemoteViews =
        RemoteViews(RuntimeEnvironment.getApplication().packageName, layoutId).apply {
            setTextViewText(R.id.widget_song_title, "Cached title")
            setImageViewBitmap(R.id.widget_album_art, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
            if (layoutId == R.layout.widget_music_player) setInt(R.id.widget_progress_fill, "setImageLevel", 1_000)
        }

    private fun assertPlayerState(
        views: RemoteViews,
        level: Int,
    ) {
        assertEquals("Cached title", actionValue(views, "setText"))
        assertEquals(level, actionValue(views, "setImageLevel"))
        assertTrue(actions(views).any { it.javaClass.simpleName == "BitmapReflectionAction" })
    }

    private fun actions(views: RemoteViews): List<Any> = ReflectionHelpers.getField(views, "mActions")

    private fun actionValue(
        views: RemoteViews,
        name: String,
    ): Any? =
        actions(views)
            .last { action ->
                runCatching { ReflectionHelpers.getField<String>(action, "methodName") }.getOrNull() == name
            }.let { ReflectionHelpers.getField(it, "value") }

    private fun children(views: RemoteViews): List<RemoteViews> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ReflectionHelpers.getField<List<RemoteViews>?>(views, "mSizedRemoteViews")?.let { return it }
        }
        return listOf(ReflectionHelpers.getField(views, "mLandscape"), ReflectionHelpers.getField(views, "mPortrait"))
    }

    private fun roundTrip(views: RemoteViews): RemoteViews {
        val parcel = Parcel.obtain()
        return try {
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
