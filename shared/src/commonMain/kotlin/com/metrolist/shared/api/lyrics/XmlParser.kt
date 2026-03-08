/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

internal sealed class XmlNode {
    var nextSibling: XmlNode? = null
    var previousSibling: XmlNode? = null
}

internal class XmlText(
    val value: String,
) : XmlNode()

internal class XmlElement(
    val tagName: String,
    val attributes: Map<String, String> = emptyMap(),
    val namespaceAttributes: Map<Pair<String, String>, String> = emptyMap(),
) : XmlNode() {
    val localName: String
        get() = tagName.substringAfter(':')

    val childNodes: MutableList<XmlNode> = mutableListOf()

    val children: List<XmlElement>
        get() = childNodes.filterIsInstance<XmlElement>()

    val firstChild: XmlNode?
        get() = childNodes.firstOrNull()

    val textContent: String
        get() =
            buildString {
                for (child in childNodes) {
                    when (child) {
                        is XmlText -> {
                            append(child.value)
                        }

                        is XmlElement -> {
                            append(child.textContent)
                        }
                    }
                }
            }

    fun getAttribute(name: String): String = attributes[name].orEmpty()

    fun getAttributeNS(
        ns: String,
        localName: String,
    ): String = namespaceAttributes[ns to localName].orEmpty()
}

internal fun parseXml(xml: String): XmlElement {
    val parser = SimpleXmlParser(xml)
    return parser.parseDocument()
}

private val xmlEntityRegex = Regex("&(?:amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);")

private class SimpleXmlParser(
    private val input: String,
) {
    private var pos = 0
    private var nodes = 0

    private fun countNode() {
        require(++nodes <= MAX_TTML_NODES) { "XML node limit exceeded" }
    }

    fun parseDocument(): XmlElement {
        while (pos < input.length) {
            skipWhitespace()
            if (pos >= input.length) break
            if (input[pos] != '<') {
                pos++
                continue
            }
            if (pos + 1 < input.length && input[pos + 1] == '?') {
                val end = input.indexOf("?>", pos + 2)
                pos = if (end != -1) end + 2 else input.length
                continue
            }
            if (pos + 1 < input.length && input[pos + 1] == '!') {
                if (pos + 3 < input.length && input.startsWith("<!--", pos)) {
                    val end = input.indexOf("-->", pos + 4)
                    pos = if (end != -1) end + 3 else input.length
                    continue
                }
                val rest = input.substring(pos, minOf(pos + 9, input.length))
                if (rest.startsWith("<!DOCTYPE", ignoreCase = true) || rest.startsWith("<!ENTITY", ignoreCase = true)) {
                    throw IllegalArgumentException("DOCTYPE and ENTITY declarations are not allowed")
                }
            }
            return parseElement()
        }
        return XmlElement("", emptyMap(), emptyMap())
    }

    private fun parseElement(
        inheritedNamespaces: Map<String, String> = emptyMap(),
        depth: Int = 1,
    ): XmlElement {
        require(depth <= MAX_TTML_DEPTH) { "XML nesting limit exceeded" }
        countNode()
        pos++
        val name = readName()
        val attrs = mutableMapOf<String, String>()
        var selfClosing = false

        while (pos < input.length) {
            skipSpaces()
            if (pos >= input.length) break
            if (input[pos] == '/' && pos + 1 < input.length && input[pos + 1] == '>') {
                pos += 2
                selfClosing = true
                break
            }
            if (input[pos] == '>') {
                pos++
                break
            }
            val attrName = readName()
            if (attrName.isEmpty()) {
                pos++
                continue
            }
            skipSpaces()
            if (pos < input.length && input[pos] == '=') {
                pos++
                skipSpaces()
                val value = readQuotedValue()
                attrs[attrName] = value
            }
        }

        val namespaces = inheritedNamespaces.toMutableMap()
        attrs.forEach { (key, value) ->
            if (key.startsWith("xmlns:")) namespaces[key.substringAfter(':')] = value
        }
        namespaces["xml"] = "http://www.w3.org/XML/1998/namespace"
        val nsAttrs =
            attrs
                .mapNotNull { (key, value) ->
                    if (':' !in key || key.startsWith("xmlns:")) return@mapNotNull null
                    val namespace = namespaces[key.substringBefore(':')] ?: return@mapNotNull null
                    (namespace to key.substringAfter(':')) to value
                }.toMap()
        val element = XmlElement(name, attrs, nsAttrs)
        if (!selfClosing) parseChildren(element, namespaces, depth)
        return element
    }

    private fun addChild(
        parent: XmlElement,
        child: XmlNode,
    ) {
        val prev = parent.childNodes.lastOrNull()
        if (prev != null) {
            prev.nextSibling = child
            child.previousSibling = prev
        }
        parent.childNodes.add(child)
    }

    private fun parseChildren(
        parent: XmlElement,
        namespaces: Map<String, String>,
        depth: Int,
    ) {
        while (pos < input.length) {
            if (input[pos] != '<') {
                val text = readUntil('<')
                if (text.isNotEmpty()) {
                    countNode()
                    addChild(parent, XmlText(decodeXmlEntities(text)))
                }
                continue
            }
            if (pos + 1 < input.length && input[pos + 1] == '/') {
                pos += 2
                readName()
                while (pos < input.length && input[pos] != '>') pos++
                if (pos < input.length) pos++
                return
            }
            if (pos + 1 < input.length && input[pos + 1] == '!') {
                if (pos + 8 < input.length && input.substring(pos, pos + 9) == "<![CDATA[") {
                    val end = input.indexOf("]]>", pos + 9)
                    val cdataText = if (end != -1) input.substring(pos + 9, end) else input.substring(pos + 9)
                    pos = if (end != -1) end + 3 else input.length
                    if (cdataText.isNotEmpty()) {
                        countNode()
                        addChild(parent, XmlText(cdataText))
                    }
                } else if (pos + 3 < input.length && input.substring(pos, pos + 4) == "<!--") {
                    val end = input.indexOf("-->", pos + 4)
                    pos = if (end != -1) end + 3 else input.length
                } else {
                    val rest = input.substring(pos, minOf(pos + 9, input.length))
                    if (rest.startsWith("<!DOCTYPE", ignoreCase = true) || rest.startsWith("<!ENTITY", ignoreCase = true)) {
                        throw IllegalArgumentException("DOCTYPE and ENTITY declarations are not allowed")
                    }
                    pos++
                }
                continue
            }
            if (pos + 1 < input.length && input[pos + 1] == '?') {
                val end = input.indexOf("?>", pos + 2)
                pos = if (end != -1) end + 2 else input.length
                continue
            }
            addChild(parent, parseElement(namespaces, depth + 1))
        }
    }

    private fun readName(): String {
        val start = pos
        while (pos < input.length &&
            (input[pos].isLetterOrDigit() || input[pos] == '_' || input[pos] == '-' || input[pos] == '.' || input[pos] == ':')
        ) {
            pos++
        }
        return input.substring(start, pos)
    }

    private fun readQuotedValue(): String {
        if (pos >= input.length) return ""
        val quote = input[pos]
        if (quote != '"' && quote != '\'') return ""
        pos++
        val start = pos
        while (pos < input.length && input[pos] != quote) {
            pos++
        }
        val raw = input.substring(start, pos)
        if (pos < input.length) pos++
        return decodeXmlEntities(raw)
    }

    private fun decodeXmlEntities(text: String): String =
        if ('&' !in text) {
            text
        } else {
            xmlEntityRegex.replace(text) { match ->
                val entity = match.value.substring(1, match.value.lastIndex)
                when (entity) {
                    "amp" -> {
                        "&"
                    }

                    "lt" -> {
                        "<"
                    }

                    "gt" -> {
                        ">"
                    }

                    "quot" -> {
                        "\""
                    }

                    "apos" -> {
                        "'"
                    }

                    else -> {
                        val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                        when {
                            code == null || code !in 1..0x10FFFF || code in 0xD800..0xDFFF -> {
                                match.value
                            }

                            code <= 0xFFFF -> {
                                code.toChar().toString()
                            }

                            else -> {
                                buildString {
                                    append((0xD800 + ((code - 0x10000) shr 10)).toChar())
                                    append((0xDC00 + ((code - 0x10000) and 0x3FF)).toChar())
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun readUntil(char: Char): String {
        val start = pos
        while (pos < input.length && input[pos] != char) pos++
        return input.substring(start, pos)
    }

    private fun skipSpaces() {
        while (pos < input.length && (input[pos] == ' ' || input[pos] == '\t' || input[pos] == '\n' || input[pos] == '\r')) pos++
    }

    private fun skipWhitespace() {
        while (pos < input.length && (input[pos] == ' ' || input[pos] == '\t' || input[pos] == '\n' || input[pos] == '\r')) pos++
    }
}
