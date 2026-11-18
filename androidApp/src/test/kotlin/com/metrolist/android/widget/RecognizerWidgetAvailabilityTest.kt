package com.metrolist.android.widget

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class RecognizerWidgetAvailabilityTest {
    @Test
    fun startupDoesNotRequireTheWidgetSystemService() =
        runBlocking {
            val context =
                object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                    override fun getSystemService(name: String): Any? =
                        when (name) {
                            Context.APPWIDGET_SERVICE -> null
                            else -> super.getSystemService(name)
                        }
                }

            RecognizerWidgets.update(context)
        }
}
