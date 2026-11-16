/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.backup

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal actual fun extractPrismFavoritesXmlText(xml: String): String? {
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
    val builder =
        factory.newDocumentBuilder().apply {
            setErrorHandler(
                object : DefaultHandler() {
                    override fun error(exception: SAXParseException) = throw exception

                    override fun fatalError(exception: SAXParseException) = throw exception
                },
            )
        }
    val document = builder.parse(InputSource(StringReader(xml)))
    val strings = document.getElementsByTagName("string")
    for (index in 0 until strings.length) {
        val element = strings.item(index) as? Element ?: continue
        if (element.getAttribute("name") == "favorites") return element.textContent
    }
    return null
}
