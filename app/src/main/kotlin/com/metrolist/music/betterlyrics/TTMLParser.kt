package com.metrolist.music.betterlyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import timber.log.Timber
import javax.xml.parsers.DocumentBuilderFactory

object TTMLParser {

    /** TTML timing attributes may appear unprefixed or as `ttp:*` (parameter namespace). */
    private const val TTML_PARAMETER_NS = "http://www.w3.org/ns/ttml#parameter"

    data class ParsedLine(
        val text: String,
        val startTime: Double,
        val words: List<ParsedWord>,
        val agent: String? = null,
        val isBackground: Boolean = false,
        val backgroundLines: List<ParsedLine> = emptyList()
    )
    
    data class ParsedWord(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val hasTrailingSpace: Boolean = true
    )
    
    private data class SpanInfo(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val hasTrailingSpace: Boolean
    )

    private fun getAttr(el: Element, localName: String): String {
        val ttm = el.getAttribute("ttm:$localName")
        if (ttm.isNotEmpty()) return ttm
        val direct = el.getAttribute(localName)
        if (direct.isNotEmpty()) return direct
        return el.getAttributeNS("http://www.w3.org/ns/ttml#metadata", localName)
    }

    private fun timingAttr(el: Element, localName: String): String {
        val direct = el.getAttribute(localName)
        if (direct.isNotEmpty()) return direct
        val param = el.getAttributeNS(TTML_PARAMETER_NS, localName)
        if (param.isNotEmpty()) return param
        return ""
    }

    /** When `<p>` has no `begin`, use the earliest `begin` on a direct child `<span>` (some exports omit line-level timing). */
    private fun findFirstSpanBegin(p: Element): String? {
        var child = p.firstChild
        var best: String? = null
        var bestSeconds = Double.POSITIVE_INFINITY
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == "span") {
                    val b = timingAttr(child, "begin")
                    if (b.isNotEmpty()) {
                        val s = parseTime(b)
                        if (s < bestSeconds) {
                            bestSeconds = s
                            best = b
                        }
                    }
                }
            }
            child = child.nextSibling
        }
        return best
    }
    
    fun parseTTML(ttml: String): List<ParsedLine> {
        val lines = mutableListOf<ParsedLine>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            
            // On Android, some features might not be supported and throw ParserConfigurationException
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (e: Exception) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (e: Exception) {}
            try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (e: Exception) {}
            try { factory.setXIncludeAware(false) } catch (e: Exception) {}
            try { factory.isExpandEntityReferences = false } catch (e: Exception) {}
            
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ttml.byteInputStream())
            val root = doc.documentElement

            var globalOffset = 0.0
            // Manual search for head/metadata/audio to avoid getElementsByTagName
            val head = findChild(root, "head")
            if (head != null) {
                val meta = findChild(head, "metadata")
                if (meta != null) {
                    val audio = findChild(meta, "audio")
                    if (audio != null) {
                        globalOffset = audio.getAttribute("lyricOffset").toDoubleOrNull() ?: 0.0
                    }
                }
            }

            val body = findChild(root, "body")
            if (body != null) {
                walk(body, lines, globalOffset, null)
            }
        } catch (e: Exception) {
            Timber.e(e, "TTMLParser.parseTTML: Failed to parse TTML")
            return emptyList()
        }
        return lines
    }

    private fun findChild(parent: Element, localName: String): Element? {
        var child = parent.firstChild
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == localName) return child
            }
            child = child.nextSibling
        }
        return null
    }

    private fun walk(element: Element, lines: MutableList<ParsedLine>, offset: Double, parentAgent: String?) {
        val name = element.localName ?: element.nodeName.substringAfterLast(':')
        var currentAgent = parentAgent

        when (name) {
            "div" -> {
                val a = getAttr(element, "agent")
                if (a.isNotEmpty()) currentAgent = a
            }
            "p" -> {
                parseP(element, lines, offset, currentAgent)
                return // Don't descend into p, parseP handles children
            }
        }

        var child = element.firstChild
        while (child != null) {
            if (child is Element) walk(child, lines, offset, currentAgent)
            child = child.nextSibling
        }
    }

    private fun parseP(p: Element, lines: MutableList<ParsedLine>, offset: Double, divAgent: String?) {
        var begin = p.getAttribute("begin")
        if (begin.isEmpty()) {
            begin = p.getAttributeNS(TTML_PARAMETER_NS, "begin")
        }
        if (begin.isEmpty()) {
            begin = findFirstSpanBegin(p) ?: return
        }

        val startTime = parseTime(begin) + offset
        val spanInfos = mutableListOf<SpanInfo>()
        val backgroundLines = mutableListOf<ParsedLine>()
        
        val agent = getAttr(p, "agent").ifEmpty { divAgent }
        val isPBackground = getAttr(p, "role") == "x-bg"
        
        var child = p.firstChild
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == "span") {
                    val role = getAttr(child, "role")
                    when (role) {
                        "x-bg" -> {
                            if (isPBackground) parseWordSpan(child, offset, spanInfos, child)
                            else parseBackgroundSpan(child, startTime, offset)?.let { backgroundLines.add(it) }
                        }
                        "x-translation", "x-roman" -> {}
                        else -> parseWordSpan(child, offset, spanInfos, child)
                    }
                }
            }
            child = child.nextSibling
        }
        
        val words = mergeSpansIntoWords(spanInfos)
        val lineText = if (words.isEmpty()) getDirectText(p).trim() else buildLineText(words)
        
        if (lineText.isNotEmpty()) {
            val bgLines = if (backgroundLines.isNotEmpty()) {
                listOf(ParsedLine(
                    text = backgroundLines.joinToString(" ") { it.text },
                    startTime = backgroundLines.minOf { it.startTime },
                    words = backgroundLines.flatMap { it.words },
                    isBackground = true
                ))
            } else emptyList()
            lines.add(ParsedLine(lineText, startTime, words, agent, isPBackground, bgLines))
        } else if (backgroundLines.isNotEmpty()) {
            lines.add(ParsedLine(
                text = backgroundLines.joinToString(" ") { it.text },
                startTime = backgroundLines.minOf { it.startTime },
                words = backgroundLines.flatMap { it.words },
                isBackground = true
            ))
        }
    }

    private fun parseWordSpan(span: Element, offset: Double, spanInfos: MutableList<SpanInfo>, node: Node) {
        val begin = timingAttr(span, "begin")
        val end = timingAttr(span, "end")
        val text = span.textContent ?: ""
        if (begin.isNotEmpty() && end.isNotEmpty()) {
            val next = node.nextSibling
            val space = (text.isNotEmpty() && text.last().isWhitespace()) || 
                        (next?.nodeType == Node.TEXT_NODE && next.textContent?.firstOrNull()?.isWhitespace() == true)
            spanInfos.add(SpanInfo(text, parseTime(begin) + offset, parseTime(end) + offset, space))
        }
    }

    private fun parseBackgroundSpan(span: Element, parentStart: Double, offset: Double): ParsedLine? {
        val begin = timingAttr(span, "begin")
        val start = if (begin.isNotEmpty()) parseTime(begin) + offset else parentStart
        val spanInfos = mutableListOf<SpanInfo>()
        
        var child = span.firstChild
        var hasSpans = false
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == "span") {
                    hasSpans = true
                    val role = getAttr(child, "role")
                    if (role != "x-translation" && role != "x-roman") parseWordSpan(child, offset, spanInfos, child)
                }
            }
            child = child.nextSibling
        }
        
        if (!hasSpans) {
            val text = span.textContent?.trim() ?: ""
            return ParsedLine(text, start, emptyList(), isBackground = true)
        }
        
        val words = mergeSpansIntoWords(spanInfos)
        val text = if (words.isEmpty()) getDirectText(span).trim() else buildLineText(words)
        return ParsedLine(text, start, words, isBackground = true)
    }

    private fun getDirectText(el: Element): String {
        val sb = StringBuilder()
        var child = el.firstChild
        while (child != null) {
            if (child.nodeType == Node.TEXT_NODE) sb.append(child.textContent)
            else if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                val role = getAttr(child, "role")
                if (name == "span" && role != "x-bg" && role != "x-translation" && role != "x-roman") {
                    sb.append(child.textContent)
                }
            }
            child = child.nextSibling
        }
        return sb.toString()
    }

    private fun buildLineText(words: List<ParsedWord>) = buildString {
        words.forEachIndexed { i, w ->
            append(w.text)
            if (w.hasTrailingSpace && !w.text.endsWith('-') && i < words.lastIndex) append(" ")
        }
    }.trim()

    private fun mergeSpansIntoWords(spanInfos: List<SpanInfo>): List<ParsedWord> {
        if (spanInfos.isEmpty()) return emptyList()
        val words = mutableListOf<ParsedWord>()
        var text = StringBuilder(spanInfos[0].text)
        var start = spanInfos[0].startTime
        var end = spanInfos[0].endTime
        
        for (i in 1 until spanInfos.size) {
            val prev = spanInfos[i - 1]
            val curr = spanInfos[i]
            if (prev.hasTrailingSpace && !prev.text.endsWith('-')) {
                words.add(ParsedWord(text.toString(), start, end, true))
                text = StringBuilder(curr.text)
                start = curr.startTime
                end = curr.endTime
            } else {
                text.append(curr.text)
                end = curr.endTime
            }
        }
        words.add(ParsedWord(text.toString(), start, end, spanInfos.last().hasTrailingSpace))
        return words.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
    }

    fun toLRC(lines: List<ParsedLine>): String {
        val agentMap = mutableMapOf<String, String>()
        
        // Phase 1: Preserve explicit v1, v2, v1000
        lines.forEach { line ->
            line.agent?.lowercase()?.let { raw ->
                if (raw == "v1" || raw == "v2" || raw == "v1000") {
                    agentMap[raw] = raw
                }
            }
        }
        
        // Phase 2: Map other agents to v1/v2 if available
        var nextNum = 1
        lines.forEach { line ->
            line.agent?.lowercase()?.let { raw ->
                if (!agentMap.containsKey(raw)) {
                    while (nextNum <= 2 && (agentMap.containsKey("v$nextNum") || agentMap.values.contains("v$nextNum"))) {
                        nextNum++
                    }
                    agentMap[raw] = if (nextNum <= 2) "v$nextNum" else "v1"
                }
            }
        }

        // v1000 (group) shares display slot with v2 when a primary v1 vocalist exists
        if (agentMap.containsKey("v1000") && agentMap.containsKey("v1")) {
            agentMap["v1000"] = "v2"
        }

        val hasBackgroundLine = lines.any { it.isBackground }
        val multi =
            agentMap.size > 1 ||
                (agentMap.size == 1 && !agentMap.containsKey("v1")) ||
                (hasBackgroundLine && agentMap.size == 1 && agentMap.containsKey("v1"))
        
        val sb = StringBuilder(lines.size * 128)
        var lastBg = false
        lines.forEach { line ->
            val time = formatLrcTime(line.startTime)
            val isBg = line.isBackground
            if (!isBg) lastBg = false
            
            val agentId = agentMap[line.agent?.lowercase()]
            val tag = when {
                isBg -> if (lastBg) "" else "{bg}"
                multi && agentId != null -> "{agent:$agentId}"
                else -> ""
            }
            if (isBg) lastBg = true

            sb.append(time).append(tag).append(line.text).append('\n')
            if (line.words.isNotEmpty()) {
                sb.append('<')
                line.words.forEachIndexed { i, w ->
                    sb.append(w.text).append(':').append(w.startTime).append(':').append(w.endTime)
                    if (i < line.words.lastIndex) sb.append('|')
                }
                sb.append(">\n")
            }
            line.backgroundLines.forEach { bg ->
                val bTag = if (lastBg) "" else "{bg}"
                sb.append(formatLrcTime(bg.startTime)).append(bTag).append(bg.text).append('\n')
                lastBg = true
                if (bg.words.isNotEmpty()) {
                    sb.append('<')
                    bg.words.forEachIndexed { i, w ->
                        sb.append(w.text).append(':').append(w.startTime).append(':').append(w.endTime)
                        if (i < bg.words.lastIndex) sb.append('|')
                    }
                    sb.append(">\n")
                }
            }
        }
        return sb.toString()
    }

    private fun formatLrcTime(time: Double): String {
        val ms = (time * 1000).toLong()
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val c = (ms % 1000) / 10
        val sb = StringBuilder(10)
        sb.append('[')
        if (m < 10) sb.append('0')
        sb.append(m).append(':')
        if (s < 10) sb.append('0')
        sb.append(s).append('.')
        if (c < 10) sb.append('0')
        sb.append(c).append(']')
        return sb.toString()
    }

    private fun parseTime(time: String): Double {
        val t = time.trim()
        val c1 = t.indexOf(':')
        if (c1 != -1) {
            val c2 = t.lastIndexOf(':')
            return if (c1 == c2) {
                (t.substring(0, c1).toIntOrNull() ?: 0) * 60.0 + (t.substring(c1 + 1).toDoubleOrNull() ?: 0.0)
            } else {
                (t.substring(0, c1).toIntOrNull() ?: 0) * 3600.0 + (t.substring(c1 + 1, c2).toIntOrNull() ?: 0) * 60.0 + (t.substring(c2 + 1).toDoubleOrNull() ?: 0.0)
            }
        }
        if (t.endsWith("ms")) return (t.substring(0, t.length - 2).toDoubleOrNull() ?: 0.0) / 1000.0
        val s = if (t.endsWith("s") || t.endsWith("m") || t.endsWith("h")) t.substring(0, t.length - 1) else t
        val v = s.toDoubleOrNull() ?: 0.0
        return when {
            t.endsWith("m") -> v * 60.0
            t.endsWith("h") -> v * 3600.0
            else -> v
        }
    }
}
