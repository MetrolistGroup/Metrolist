package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AudienceCountTest {
    @Test
    fun preservesUnicodeDigitsAndNumberSeparators() {
        for (number in listOf("١٢٣٬٤٥٦", "۱۲۳٫۴", "১২,৩৪,৫৬৭", "१२३", "๑๒๓", "１２３", "1\u202f234", "1'234", "1’234", "1.234,56")) {
            val count = assertNotNull(parseAudienceCount(number))
            assertEquals(number, count.text)
            assertEquals("$number audience", count.withLabel { "$it audience" })
        }
    }

    @Test
    fun extractsUnitsBeforeOrAfterTheNumber() {
        for ((raw, expected) in listOf(
            "Audience M 2,4" to "M 2,4",
            "Audience: 2,4 M" to "2,4 M",
            "月間視聴者数: 2.4億 人" to "2.4億",
            "جمهور ١٢٫٣ مليون شهريًا" to "١٢٫٣ مليون",
        )) {
            assertEquals("$expected audience", monthlyAudienceText(raw, "audience"))
        }
        assertEquals("42 audience", monthlyAudienceText("42", "audience"))
        assertEquals("0 audience", monthlyAudienceText("0", "audience"))
    }

    @Test
    fun keepsUncertainLocalizedTextWithoutAddingALabel() {
        for (raw in listOf(
            "1.2 unfamiliar-unit listeners",
            "1.2 millions d'auditeurs",
            "२ परार्ध श्रोते",
            "10–20 monthly listeners",
            "M 2.4 B listeners",
            "‫لا تتوفر بيانات",
            "42 monthly audience",
        )) {
            val count = assertNotNull(parseAudienceCount(raw))
            assertEquals(false, count.isCountOnly)
            assertEquals(raw, count.text)
            assertEquals(raw, count.withLabel { error("Do not label the original text") })
            assertEquals(raw, monthlyAudienceText(monthlyListenersCount(raw), "audience"))
        }
        assertNull(parseAudienceCount(null))
        assertNull(parseAudienceCount(" \u00a0"))
    }
}
