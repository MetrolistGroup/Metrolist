package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformCryptoTest {
    @Test
    fun md5MatchesKnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
        assertEquals(
            "9e107d9d372bb6826bd81d3542a419d6",
            md5Hex("The quick brown fox jumps over the lazy dog"),
        )
        assertEquals(
            "e4d909c290d0fb1ca068ffaddf22cbd0",
            md5Hex("The quick brown fox jumps over the lazy dog."),
        )
        assertEquals("66ddcd97cfdeabb2f6fb8a999b4bc76f", md5Hex("é"))
        assertEquals("ef1772b6dff9a122358552954ad0df65", md5Hex("a".repeat(55)))
        assertEquals("3b0c8ac703f828b04c6c197006d17218", md5Hex("a".repeat(56)))
        assertEquals("b06521f39153d618550606be297466d5", md5Hex("a".repeat(63)))
        assertEquals("014842d480b571495a4a0363793f7367", md5Hex("a".repeat(64)))
        assertEquals("c743a45e0d2e6a95cb859adae0248435", md5Hex("a".repeat(65)))
        assertEquals("b15af9cdabbaea0516866a33d8fd0f98", md5Hex("a".repeat(80)))
    }
}
