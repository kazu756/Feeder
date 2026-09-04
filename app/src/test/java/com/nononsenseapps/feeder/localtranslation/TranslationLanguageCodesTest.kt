package com.nononsenseapps.feeder.localtranslation

import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationLanguageCodesTest {
    @Test
    fun normalizesJapaneseNamesAndRegionalLanguageTags() {
        assertEquals("ja", normalizeLanguageCode("Japanese"))
        assertEquals("ja", normalizeLanguageCode("ja-JP"))
        assertEquals("en", normalizeLanguageCode("en-US"))
    }
}
