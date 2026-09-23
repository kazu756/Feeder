package com.nononsenseapps.feeder.model

import com.nononsenseapps.feeder.archmodel.OpenAISettings
import com.nononsenseapps.feeder.openai.LOCAL_TRANSLATION_PROVIDER_URL
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TranslationCacheProviderTest {
    @Test
    fun blockTranslationUsesNewLocalCacheVersion() {
        val provider = translationCacheProvider(OpenAISettings(baseUrl = LOCAL_TRANSLATION_PROVIDER_URL))
        assertEquals("local-v3", provider)
        assertNotEquals("local-v2", provider)
    }

    @Test
    fun otherProvidersAndExplicitAiOverrideAreUnchanged() {
        assertEquals("deepl", translationCacheProvider(OpenAISettings(baseUrl = "https://api.deepl.com")))
        assertEquals("openai", translationCacheProvider(OpenAISettings()))
        assertEquals("ai", translationCacheProvider(OpenAISettings(baseUrl = LOCAL_TRANSLATION_PROVIDER_URL), "ai"))
    }
}
