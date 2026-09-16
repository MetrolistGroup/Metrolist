
package com.metrolist.music.betterlyrics

import org.junit.Test
import org.junit.Assert.*

class TTMLParserTest {

    @Test
    fun testV1000AgentMapping() {
        val ttmlBackground = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <ttm:agent xml:id="v1000" type="group"/>
                  <ttm:agent xml:id="v1" type="person"/>
                </metadata>
              </head>
              <body>
                <div>
                  <p begin="00:01.500" ttm:agent="v1000">
                    <span>(Group vocal)</span>
                  </p>
                  <p begin="00:02.000" ttm:agent="v1">
                    <span>Main vocal</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttmlBackground)
        val lrc = TTMLParser.toLRC(parsedLines)
        
        // v1 is prioritized, so v1000 becomes v2
        assertTrue("v1000 should be mapped to agent:v2", lrc.contains("{agent:v2}(Group vocal)"))
        assertTrue("v1 should be mapped to agent:v1", lrc.contains("{agent:v1}Main vocal"))
    }

    @Test
    fun testTimeFormats() {
        val ttmlTimes = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="1.5s">
                    <span>1.5 seconds</span>
                  </p>
                  <p begin="2000ms">
                    <span>2000 milliseconds</span>
                  </p>
                  <p begin="00:03.50">
                    <span>Standard format</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttmlTimes)
        val lrc = TTMLParser.toLRC(parsedLines)
        val lrcLines = lrc.trim().lines()
        
        assertTrue("1.5s should be [00:01.50]", lrcLines[0].startsWith("[00:01.50]"))
        assertTrue("2000ms should be [00:02.00]", lrcLines[1].startsWith("[00:02.00]"))
        assertTrue("00:03.50 should be [00:03.50]", lrcLines[2].startsWith("[00:03.50]"))
    }

    @Test
    fun testRoleXBgMapping() {
        val ttmlRoleBg = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:05.000">
                    <span ttm:role="x-bg">Background role</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttmlRoleBg)
        val lrc = TTMLParser.toLRC(parsedLines)
        
        assertTrue("ttm:role='x-bg' should result in {bg} tag", lrc.contains("{bg}Background role"))
    }

    @Test
    fun testWordLevelSync() {
        val ttmlWordSync = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:10.000">
                    <span begin="00:10.000" end="00:10.500">Hello</span>
                    <span begin="00:10.600" end="00:11.000">world</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttmlWordSync)
        val lrc = TTMLParser.toLRC(parsedLines)
        
        assertTrue("Should contain word-level sync tags", lrc.contains("<Hello:10.0:10.5|world:10.6:11.0>"))
    }

    @Test
    fun testSingleVocalistWithBackground() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:01.000" ttm:agent="v1">
                    <span>Main line</span>
                  </p>
                  <p begin="00:01.200" ttm:agent="v1">
                    <span ttm:role="x-bg">bg</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttml)
        val lrc = TTMLParser.toLRC(parsedLines)
        
        assertTrue("Should contain agent:v1 when background vocals exist to distinguish them", lrc.contains("{agent:v1}"))
        assertTrue("Should contain {bg} for background vocal", lrc.contains("{bg}bg"))
    }

    @Test
    fun testSingleVocalistNotV1() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:01.000" ttm:agent="v2">
                    <span>Only singer is v2</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttml)
        val lrc = TTMLParser.toLRC(parsedLines)
        
        // v2 should be preserved
        assertTrue("Should contain agent:v2 since it was explicitly named", lrc.contains("{agent:v2}"))
    }

    @Test
    fun testLyricOffset() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <head>
                <metadata>
                  <audio lyricOffset="10.5"/>
                </metadata>
              </head>
              <body>
                <div>
                  <p begin="00:01.000">
                    <span begin="00:01.000" end="00:02.000">Hello</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttml)
        // 1.0 + 10.5 = 11.5 seconds = [00:11.50]
        val lrc = TTMLParser.toLRC(parsedLines)
        
        assertTrue("Timestamp should include offset: [00:11.50] was expected in $lrc", lrc.contains("[00:11.50]"))
        assertTrue("Word data should include offset: 11.5:12.5 was expected", lrc.contains("Hello:11.5:12.5"))
    }

    @Test
    fun testTranslationAndRomanSpansAreSkipped() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:01.000">
                    <span begin="00:01.000" end="00:02.000">Main lyric</span>
                    <span ttm:role="x-roman">Romanization</span>
                    <span ttm:role="x-translation">Translation</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttml)
        assertEquals(1, parsedLines.size)
        assertEquals("Main lyric", parsedLines[0].text)
    }

    @Test
    fun testSplitSyllableSpansAreMerged() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000">
                    <span begin="00:01.000" end="00:01.500">hel</span><span begin="00:01.500" end="00:02.000">lo</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttml)
        assertEquals(1, parsedLines.size)
        val line = parsedLines[0]
        assertEquals("hello", line.text)
        assertEquals(1, line.words.size)
        val word = line.words[0]
        assertEquals("hello", word.text)
        assertEquals(1.0, word.startTime, 0.001)
        assertEquals(2.0, word.endTime, 0.001)
    }

    @Test
    fun testMultipleBackgroundSpansInOneP() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:01.000">
                    <span begin="00:01.000" end="00:02.000">Main lyric</span>
                    <span ttm:role="x-bg" begin="00:01.200">
                      <span begin="00:01.200" end="00:01.500">Bg1</span>
                    </span>
                    <span ttm:role="x-bg" begin="00:01.600">
                      <span begin="00:01.600" end="00:01.900">Bg2</span>
                    </span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttml)
        val lrc = TTMLParser.toLRC(parsedLines)
        
        // Should be merged into one {bg} line
        assertTrue("Should contain merged background line", lrc.contains("{bg}Bg1 Bg2"))
        assertTrue("Should contain combined word data", lrc.contains("<Bg1:1.2:1.5|Bg2:1.6:1.9>"))
    }

    @Test
    fun testV2000AgentMapping() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <ttm:agent xml:id="v1" type="person"/>
                  <ttm:agent xml:id="v2000" type="other"/>
                </metadata>
              </head>
              <body>
                <div>
                  <p begin="00:01.000" ttm:agent="v1">
                    <span>Main vocal</span>
                  </p>
                  <p begin="00:02.000" ttm:agent="v2000">
                    <span>Outro vocal</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttml)
        val lrc = TTMLParser.toLRC(parsedLines)
        
        // v1 remains v1, v2000 becomes v2
        assertTrue("v1 should be mapped to agent:v1", lrc.contains("{agent:v1}Main vocal"))
        assertTrue("v2000 should be mapped to agent:v2", lrc.contains("{agent:v2}Outro vocal"))
    }

    @Test
    fun testSequentialBackgroundDeDuplication() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <ttm:agent xml:id="v1" type="person"/>
                  <ttm:agent xml:id="v2" type="person"/>
                </metadata>
              </head>
              <body>
                <div>
                  <p begin="00:01.000" ttm:role="x-bg" ttm:agent="v1">
                    <span>Line 1</span>
                  </p>
                  <p begin="00:02.000" ttm:role="x-bg" ttm:agent="v1">
                    <span>Line 2</span>
                  </p>
                  <p begin="00:03.000" ttm:agent="v1">
                    <span>Main Line</span>
                    <span ttm:role="x-bg" begin="00:03.500">
                      <span>Nested Bg</span>
                    </span>
                  </p>
                  <p begin="00:04.000" ttm:role="x-bg" ttm:agent="v1">
                    <span>Line 4</span>
                  </p>
                  <p begin="00:05.000" ttm:agent="v2">
                    <span>Line 5</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttml)
        val lrc = TTMLParser.toLRC(parsedLines)
        val lines = lrc.trim().lines().filter { it.startsWith("[") }
        
        assertTrue("First background line should have {bg}", lines[0].contains("{bg}Line 1"))
        assertFalse("Second background line should NOT have {bg}", lines[1].contains("{bg}"))
        assertTrue("Main line should have {agent:v1}", lines[2].contains("{agent:v1}Main Line"))
        assertTrue("Nested background line should have {bg}", lines[3].contains("{bg}Nested Bg"))
        assertFalse("Line after nested background should NOT have {bg}", lines[4].contains("{bg}"))
        assertTrue("Line after background should have {agent:v2}", lines[5].contains("{agent:v2}Line 5"))
    }

    @Test
    fun testTtpTimingOnParagraph() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter">
              <body>
                <div>
                  <p ttp:begin="2.5" ttp:end="4.0">
                    <span>Only timing on ttp</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttml)
        assertFalse("Should parse lines with ttp:begin on p", parsedLines.isEmpty())
        val lrc = TTMLParser.toLRC(parsedLines)
        assertTrue(lrc.contains("[00:02.50]"))
        assertTrue(lrc.contains("Only timing on ttp"))
    }

    @Test
    fun testParagraphInheritsBeginFromFirstSpan() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p>
                    <span begin="00:05.000" end="00:05.500">No</span>
                    <span begin="00:05.500" end="00:06.000">begin</span>
                    <span begin="00:06.000" end="00:06.500">on p</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsedLines = TTMLParser.parseTTML(ttml)
        assertFalse("Should infer line time from first span", parsedLines.isEmpty())
        val lrc = TTMLParser.toLRC(parsedLines)
        assertTrue("Line should start at first span time", lrc.trim().lines().first().startsWith("[00:05.00]"))
    }
}
