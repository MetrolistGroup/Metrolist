package com.metrolist.android

import android.app.Activity
import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33], shadows = [ExitActivityShadow::class])
class RootExitTest {
    @Test
    fun tvExitRemovesTaskOnlyWhenStopOnTaskClearIsEnabled() {
        val original = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            for (televisionMode in listOf(false, true)) {
                for (leanback in listOf(false, true)) {
                    for (stopOnClear in listOf(false, true)) {
                        val activity = Robolectric.buildActivity(Activity::class.java).get()
                        val manager = activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
                        shadowOf(manager).setCurrentModeType(
                            if (televisionMode) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL,
                        )
                        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, leanback)
                        Settings.shared.stopMusicOnTaskClear = stopOnClear
                        activity.exitApp()
                        val shadow = Shadow.extract<ExitActivityShadow>(activity)
                        val removesTask = (televisionMode || leanback) && stopOnClear
                        assertEquals("mode=$televisionMode leanback=$leanback stop=$stopOnClear", removesTask, shadow.taskRemoved)
                        assertEquals(!removesTask, shadow.finishedWithoutRemoval)
                        assertTrue(activity.isFinishing)
                    }
                }
            }
        } finally {
            Settings.replaceSharedForTest(original)
        }
    }
}

@Implements(Activity::class)
class ExitActivityShadow : ShadowActivity() {
    var taskRemoved = false
        private set
    var finishedWithoutRemoval = false
        private set

    @Implementation
    override fun finishAndRemoveTask() {
        taskRemoved = true
        super.finishAndRemoveTask()
    }

    @Implementation
    override fun finish() {
        finishedWithoutRemoval = true
        super.finish()
    }
}
