package com.metrolist.android.playback

import android.app.AppOpsManager
import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppOpsManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], shadows = [AppMayTakeAudioFocusTest.ForegroundEvaluatingAppOpsShadow::class])
class AppMayTakeAudioFocusTest {
    @Before
    fun resetAppOps() {
        ForegroundEvaluatingAppOpsShadow.rawMode = AppOpsManager.MODE_ALLOWED
        ForegroundEvaluatingAppOpsShadow.evaluatedMode = AppOpsManager.MODE_ALLOWED
    }

    @Test
    fun foregroundAppOpRemainsAllowedWhenBackgroundEvaluationReturnsIgnored() {
        ForegroundEvaluatingAppOpsShadow.rawMode = AppOpsManager.MODE_FOREGROUND
        ForegroundEvaluatingAppOpsShadow.evaluatedMode = AppOpsManager.MODE_IGNORED

        val context = RuntimeEnvironment.getApplication()
        assertTrue(appMayTakeAudioFocus(context))
    }

    @Test
    fun explicitlyDeniedOrErroredAppOpSkipsAudioFocus() {
        val context = RuntimeEnvironment.getApplication()

        ForegroundEvaluatingAppOpsShadow.rawMode = AppOpsManager.MODE_IGNORED
        ForegroundEvaluatingAppOpsShadow.evaluatedMode = AppOpsManager.MODE_IGNORED
        assertFalse(appMayTakeAudioFocus(context))

        ForegroundEvaluatingAppOpsShadow.rawMode = AppOpsManager.MODE_ERRORED
        ForegroundEvaluatingAppOpsShadow.evaluatedMode = AppOpsManager.MODE_ERRORED
        assertFalse(appMayTakeAudioFocus(context))
    }

    @Test
    fun allowedAndDefaultAppOpModesKeepAudioFocusEnabled() {
        val context = RuntimeEnvironment.getApplication()

        ForegroundEvaluatingAppOpsShadow.rawMode = AppOpsManager.MODE_ALLOWED
        ForegroundEvaluatingAppOpsShadow.evaluatedMode = AppOpsManager.MODE_ALLOWED
        assertTrue(appMayTakeAudioFocus(context))

        ForegroundEvaluatingAppOpsShadow.rawMode = AppOpsManager.MODE_DEFAULT
        ForegroundEvaluatingAppOpsShadow.evaluatedMode = AppOpsManager.MODE_DEFAULT
        assertTrue(appMayTakeAudioFocus(context))
    }

    @Implements(AppOpsManager::class)
    class ForegroundEvaluatingAppOpsShadow : ShadowAppOpsManager() {
        @Implementation
        override fun unsafeCheckOpNoThrow(
            op: String,
            uid: Int,
            packageName: String,
        ): Int =
            if (op == "android:take_audio_focus" && uid == Process.myUid()) {
                evaluatedMode
            } else {
                super.unsafeCheckOpNoThrow(op, uid, packageName)
            }

        @Implementation
        override fun unsafeCheckOpRawNoThrow(
            op: String,
            uid: Int,
            packageName: String,
        ): Int =
            if (op == "android:take_audio_focus" && uid == Process.myUid()) {
                rawMode
            } else {
                super.unsafeCheckOpRawNoThrow(op, uid, packageName)
            }

        companion object {
            var rawMode: Int = AppOpsManager.MODE_ALLOWED
            var evaluatedMode: Int = AppOpsManager.MODE_ALLOWED
        }
    }
}
