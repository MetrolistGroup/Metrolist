/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSXMLParser
import platform.Foundation.NSXMLParserDelegateProtocol
import platform.Foundation.NSXMLParserResolveExternalEntitiesNever
import platform.Foundation.create
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
internal actual fun extractPrismFavoritesXmlText(xml: String): String? {
    val bytes = xml.encodeToByteArray()
    if (bytes.isEmpty()) return null
    return bytes.usePinned { pinned ->
        val data = NSData.create(pinned.addressOf(0), bytes.size.toULong())
        val parser = NSXMLParser(data = data)
        val delegate = PrismPreferencesXmlDelegate()
        parser.delegate = delegate
        parser.externalEntityResolvingPolicy = NSXMLParserResolveExternalEntitiesNever
        if (parser.parse()) delegate.favoritesText else null
    }
}

private class PrismPreferencesXmlDelegate :
    NSObject(),
    NSXMLParserDelegateProtocol {
    private var readingFavorites = false
    private var foundFavorites = false
    private val text = StringBuilder()

    val favoritesText: String?
        get() = text.toString().takeIf { foundFavorites }

    override fun parser(
        parser: NSXMLParser,
        didStartElement: String,
        namespaceURI: String?,
        qualifiedName: String?,
        attributes: Map<Any?, *>,
    ) {
        if (!foundFavorites && didStartElement == "string" &&
            attributes.entries.any { (key, value) ->
                key?.toString() == "name" && value?.toString() == "favorites"
            }
        ) {
            readingFavorites = true
        }
    }

    override fun parser(
        parser: NSXMLParser,
        didEndElement: String,
        namespaceURI: String?,
        qualifiedName: String?,
    ) {
        if (readingFavorites && didEndElement == "string") {
            readingFavorites = false
            foundFavorites = true
        }
    }

    override fun parser(
        parser: NSXMLParser,
        foundCharacters: String,
    ) {
        if (readingFavorites) text.append(foundCharacters)
    }

    override fun parser(
        parser: NSXMLParser,
        foundCDATA: NSData,
    ) {
        if (!readingFavorites) return
        val cdata = foundCDATA.bytes?.readBytes(foundCDATA.length.toInt())?.decodeToString() ?: return
        text.append(cdata)
    }
}
