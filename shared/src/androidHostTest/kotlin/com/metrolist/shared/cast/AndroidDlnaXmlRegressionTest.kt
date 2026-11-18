package com.metrolist.shared.cast

import android.util.Xml
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AndroidDlnaXmlRegressionTest : DlnaXmlContract() {
    private var previousFactory: String? = null

    @Before
    fun selectAndroidXmlImplementation() {
        previousFactory = System.getProperty(FACTORY_PROPERTY)
        // The host JDK otherwise supplies Xerces, unlike Android's default Harmony factory.
        System.setProperty(FACTORY_PROPERTY, "org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl")
        assertEquals("org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl", DocumentBuilderFactory.newInstance().javaClass.name)
        assertEquals("com.android.org.kxml2.io.KXmlParser", Xml.newPullParser().javaClass.name)
    }

    @After
    fun restoreXmlImplementation() {
        val previous = previousFactory
        if (previous == null) System.clearProperty(FACTORY_PROPERTY) else System.setProperty(FACTORY_PROPERTY, previous)
    }

    private companion object {
        const val FACTORY_PROPERTY = "javax.xml.parsers.DocumentBuilderFactory"
    }
}
