package com.metrolist.music.playback.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MusicAlarmRescheduleReceiverTest {
    @Test
    fun `alarm rescheduling waits until credential storage is unlocked`() {
        val manifest =
            sequenceOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
                .first(File::isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val receivers = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("receiver")
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val receiver =
            (0 until receivers.length)
                .map { receivers.item(it) as Element }
                .single {
                    it.getAttributeNS(androidNamespace, "name") ==
                        ".playback.alarm.MusicAlarmRescheduleReceiver"
                }
        val actions = receiver.getElementsByTagName("action")
        val names =
            (0 until actions.length)
                .map { (actions.item(it) as Element).getAttributeNS(androidNamespace, "name") }

        assertFalse(receiver.getAttributeNS(androidNamespace, "directBootAware") == "true")
        assertFalse("android.intent.action.LOCKED_BOOT_COMPLETED" in names)
        assertTrue("android.intent.action.BOOT_COMPLETED" in names)
    }
}
