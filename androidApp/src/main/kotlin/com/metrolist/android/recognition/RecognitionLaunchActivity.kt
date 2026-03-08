package com.metrolist.android.recognition

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import com.metrolist.android.MainActivity

@SuppressLint("CustomSplashScreen")
class RecognitionLaunchActivity : Activity() {
    override fun onResume() {
        super.onResume()
        // Microphone foreground services must start while the activity is visible.
        if (MusicRecognitionService.hasRecordPermission(this)) {
            RecognitionWidgetService.start(
                this,
                if (intent.action == RecognitionWidgetService.ACTION_TOGGLE) {
                    RecognitionWidgetService.ACTION_TOGGLE
                } else {
                    RecognitionWidgetService.ACTION_START
                },
            )
        } else {
            MusicRecognitionService.reset()
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_RECOGNITION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
