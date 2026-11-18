package com.metrolist.shared.cast

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal actual fun disallowDlnaDoctype(
    factory: DocumentBuilderFactory,
    bytes: ByteArray,
) {
    // Android's DOM factory has no disallow-doctype feature. Check tokens before
    // building the DOM, retaining the parser's XML encoding detection.
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    parser.setInput(ByteArrayInputStream(bytes), null)
    while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.DOCDECL) {
            throw XmlPullParserException("DOCTYPE is not allowed")
        }
    }
}
