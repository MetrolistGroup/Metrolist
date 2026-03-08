package com.metrolist.shared.api.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XmlParserTest {
    @Test
    fun commentsCannotHideNestingFromParserLimits() {
        val xml = "<a><!-- ></a> -->".repeat(2000) + "<!-- ><a> --></a>".repeat(2000)
        assertTrue(isTtmlWithinLimits(xml))
        assertFailsWith<IllegalArgumentException> { parseXml(xml) }
        assertEquals("", parseTtmlToMlx(xml, "Title", "Artist", "test"))
    }

    @Test
    fun limitsCountElementsTextAndCdataNodes() {
        assertFailsWith<IllegalArgumentException> { parseXml("<a>" + "<b/>".repeat(MAX_TTML_NODES) + "</a>") }
        assertFailsWith<IllegalArgumentException> { parseXml("<a>" + "x<![CDATA[y]]>".repeat(MAX_TTML_NODES / 2) + "</a>") }
        assertEquals("ok", parseXml("<a>".repeat(MAX_TTML_DEPTH) + "ok" + "</a>".repeat(MAX_TTML_DEPTH)).textContent)
    }

    @Test
    fun whitespaceAroundAttributeEqualsPreservesValuesAndEntities() {
        val root =
            parseXml(
                """<p begin = "1s" end=
 '2s' title = "A &amp; B">plain text</p>""",
            )
        assertEquals("1s", root.getAttribute("begin"))
        assertEquals("2s", root.getAttribute("end"))
        assertEquals("A & B", root.getAttribute("title"))
        assertEquals("plain text", root.textContent)
    }

    @Test
    fun namespacesInheritShadowAndIgnoreAttributeOrder() {
        val root =
            parseXml(
                """<tt xmlns:m="urn:metadata" xmlns="urn:default"><p m:agent="a" xml:lang="pl" agent="plain"><span m:agent="b" xmlns:m="urn:other"/></p><p m:agent="c"/></tt>""",
            )
        val first = root.children.first()
        assertEquals("a", first.getAttributeNS("urn:metadata", "agent"))
        assertEquals("pl", first.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang"))
        assertEquals("", first.getAttributeNS("urn:default", "agent"))
        assertEquals("b", first.children.single().getAttributeNS("urn:other", "agent"))
        assertEquals("c", root.children.last().getAttributeNS("urn:metadata", "agent"))
    }

    @Test
    fun entitiesDecodeOnceIncludingSupplementaryScalarsButNotCdata() {
        val root =
            parseXml(
                """<p title="&amp;lt; &#39; &#x2019; &#x1F3B5;">&amp;lt; &#39; &#x2019; &#x1F3B5;<![CDATA[&amp;]]>&#xD800;&#1114112;</p>""",
            )
        assertEquals("&lt; ' ’ 🎵", root.getAttribute("title"))
        assertEquals("&lt; ' ’ 🎵&amp;&#xD800;&#1114112;", root.textContent)
    }
}
