package com.metrolist.shared.cast

import javax.xml.parsers.DocumentBuilderFactory

internal actual fun disallowDlnaDoctype(
    factory: DocumentBuilderFactory,
    bytes: ByteArray,
) {
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
}
