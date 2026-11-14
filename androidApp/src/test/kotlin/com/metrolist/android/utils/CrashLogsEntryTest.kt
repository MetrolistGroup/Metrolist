package com.metrolist.android.utils

import com.metrolist.android.ui.screens.CrashActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CrashLogsEntryTest {
    @Test
    fun publicLogsDestinationContainsNoRecoveryOrTerminationExtras() {
        val intent = CrashHandler.logsIntent(RuntimeEnvironment.getApplication())
        assertEquals(CrashActivity::class.java.name, intent.component?.className)
        assertEquals(CrashHandler.ACTION_LOGS, intent.action)
        assertNull(intent.extras)
        assertNull(intent.data)
        assertNull(intent.clipData)
    }

    @Test
    fun onlyTheLogsEntryIsExportedAndBothActivitiesSkipMainInitialization() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        val entries = (0 until activities.length).map { activities.item(it) as Element }.associateBy { it.getAttribute("android:name") }
        val recovery = entries.getValue(".ui.screens.CrashActivity")
        val logs = entries.getValue(".ui.screens.CrashLogsActivity")
        assertEquals("false", recovery.getAttribute("android:exported"))
        assertEquals("true", logs.getAttribute("android:exported"))
        assertEquals(0, recovery.getElementsByTagName("intent-filter").length)
        assertEquals(CrashHandler.ACTION_LOGS, (logs.getElementsByTagName("action").item(0) as Element).getAttribute("android:name"))
        assertEquals(":crash", recovery.getAttribute("android:process"))
        assertEquals(":crash", logs.getAttribute("android:process"))
    }
}
