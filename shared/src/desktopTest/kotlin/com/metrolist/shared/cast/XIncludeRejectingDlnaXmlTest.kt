package com.metrolist.shared.cast

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** Runs the DLNA contract with a factory that, like Android's, rejects every XInclude setting. */
class XIncludeRejectingDlnaXmlTest : DlnaXmlContract() {
    private var previousFactory: String? = null

    @BeforeTest
    fun selectRejectingFactory() {
        previousFactory = System.getProperty(FACTORY_PROPERTY)
        System.setProperty(FACTORY_PROPERTY, XIncludeRejectingDocumentBuilderFactory::class.java.name)
    }

    @AfterTest
    fun restoreFactory() {
        val previous = previousFactory
        if (previous == null) System.clearProperty(FACTORY_PROPERTY) else System.setProperty(FACTORY_PROPERTY, previous)
    }

    private companion object {
        const val FACTORY_PROPERTY = "javax.xml.parsers.DocumentBuilderFactory"
    }
}

class XIncludeRejectingDocumentBuilderFactory : DocumentBuilderFactory() {
    private val delegate = newDefaultInstance()

    override fun newDocumentBuilder(): DocumentBuilder =
        delegate
            .apply {
                isNamespaceAware = this@XIncludeRejectingDocumentBuilderFactory.isNamespaceAware
                isExpandEntityReferences = this@XIncludeRejectingDocumentBuilderFactory.isExpandEntityReferences
            }.newDocumentBuilder()

    override fun setAttribute(
        name: String,
        value: Any?,
    ) = delegate.setAttribute(name, value)

    override fun getAttribute(name: String): Any? = delegate.getAttribute(name)

    override fun setFeature(
        name: String,
        value: Boolean,
    ) = delegate.setFeature(name, value)

    override fun getFeature(name: String): Boolean = delegate.getFeature(name)

    override fun setXIncludeAware(state: Boolean): Unit = throw UnsupportedOperationException("XInclude is not supported")
}
