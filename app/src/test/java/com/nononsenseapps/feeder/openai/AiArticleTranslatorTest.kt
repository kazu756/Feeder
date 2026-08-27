package com.nononsenseapps.feeder.openai

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.core.RequestOptions
import com.aallam.openai.api.model.Model
import com.aallam.openai.api.model.ModelId
import com.nononsenseapps.feeder.archmodel.OpenAISettings
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiArticleTranslatorTest {
    private val settings = OpenAISettings(key = "key", modelId = "model")

    @Test
    fun `translation replaces only text nodes and preserves protected HTML`() =
        runTest {
            val requests = mutableListOf<ChatCompletionRequest>()
            val translator =
                translator(
                    responses =
                        listOf(
                            response(
                                """{"translations":[{"id":"article-title","text":"題名"},{"id":"s0","text":"見出し"},{"id":"s1","text":"こんにちは"},{"id":"s2","text":"重要"},{"id":"s3","text":"世界"},{"id":"s4","text":"説明"}]}""",
                            ),
                        ),
                    requests = requests,
                )
            val html =
                """
                <h1>Heading</h1>
                <p>Hello <em>important</em> <a href="https://example.com/path">world</a>.</p>
                <figure><img src="image.jpg" srcset="small.jpg 1x" data-sco-src="real.jpg" data-sco-srcset="real.jpg 1x" data-srcset="lazy.jpg 1x" data-original="original.jpg"><figcaption>Caption</figcaption></figure>
                <pre>const value = "do not translate";</pre><code>inlineCode()</code>
                """.trimIndent()

            val result = translator.translate("Title", html, settings)
            val output = Jsoup.parseBodyFragment(result.translatedHtml)

            assertEquals("題名", result.translatedTitle)
            assertEquals("見出し", output.selectFirst("h1")!!.text())
            assertTrue(output.selectFirst("p")!!.text().endsWith("."))
            assertEquals("https://example.com/path", output.selectFirst("a")!!.attr("href"))
            assertEquals("image.jpg", output.selectFirst("img")!!.attr("src"))
            assertEquals("small.jpg 1x", output.selectFirst("img")!!.attr("srcset"))
            assertEquals("real.jpg", output.selectFirst("img")!!.attr("data-sco-src"))
            assertEquals("real.jpg 1x", output.selectFirst("img")!!.attr("data-sco-srcset"))
            assertEquals("lazy.jpg 1x", output.selectFirst("img")!!.attr("data-srcset"))
            assertEquals("original.jpg", output.selectFirst("img")!!.attr("data-original"))
            assertEquals("const value = \"do not translate\";", output.selectFirst("pre")!!.text())
            assertEquals("inlineCode()", output.selectFirst("code")!!.text())
            assertEquals(1, requests.size)
            val userMessage =
                requests
                    .single()
                    .messages
                    .last()
                    .messageContent
                    .toString()
            assertTrue(userMessage.contains("Hello"))
            assertTrue(userMessage.contains("important"))
            assertTrue(userMessage.contains("world"))
            assertTrue(!userMessage.contains("image.jpg"))
        }

    @Test
    fun `Pluralistic style non-translatable nodes stay in DOM and are not sent to AI`() =
        runTest {
            val requests = mutableListOf<ChatCompletionRequest>()
            val translator =
                translator(
                    responses =
                        listOf(
                            response(
                                """{"translations":[{"id":"article-title","text":"題名"},{"id":"s0","text":"節（"},{"id":"s1","text":"固定リンク"},{"id":"s2","text":"これは"},{"id":"s3","text":"重要なリンク"},{"id":"s4","text":"ニュース項目"},{"id":"s5","text":"説明"},{"id":"s6","text":"2026年の選挙"},{"id":"s7","text":"バージョン2が公開されました"},{"id":"s8","text":"1億ドルの投資"},{"id":"s9","text":"42人が負傷しました"},{"id":"s10","text":"詳細はhttps://example.comを参照してください"},{"id":"s11","text":"今日https://example.comを訪問してください"}]}""",
                            ),
                        ),
                    requests = requests,
                )
            val html =
                """
                <h1 id="heading">Section (<a href="#section">permalink</a>)</h1>
                <p id="inline">This is <a href="https://example.com">an important link</a>.</p>
                <p id="https-url">https://example.com/article</p>
                <p id="http-url">http://example.com/</p>
                <p id="www-url">www.example.com</p>
                <p id="keycap">1️⃣2️⃣0️⃣0️⃣</p>
                <p id="emoji">🔥 ✅ →</p>
                <p id="numbers">2026 42 42% ${'$'}100 1,000</p>
                <p id="nbsp">&nbsp;</p>
                <ul><li><a href="https://example.com/news">News item</a> <span>—</span> description</li></ul>
                <p>2026 election</p>
                <p>Version 2 was released</p>
                <p>${'$'}100 million investment</p>
                <p>42 people were injured</p>
                <p>See https://example.com for details</p>
                <p>Visit https://example.com today</p>
                """.trimIndent()

            val result = translator.translate("Title", html, settings)
            val output = Jsoup.parseBodyFragment(result.translatedHtml)
            val userMessage =
                requests
                    .single()
                    .messages
                    .last()
                    .messageContent
                    .toString()

            assertTrue(!userMessage.contains("https://example.com/article"))
            assertTrue(!userMessage.contains("http://example.com/"))
            assertTrue(!userMessage.contains("www.example.com"))
            assertTrue(!userMessage.contains("1️⃣2️⃣0️⃣0️⃣"))
            assertTrue(!userMessage.contains("🔥"))
            assertTrue(!userMessage.contains("2026 42 42%"))
            assertTrue(userMessage.contains("2026 election"))
            assertTrue(userMessage.contains("${'$'}100 million investment"))
            assertTrue(userMessage.contains("See https://example.com for details"))

            assertEquals("節（固定リンク)", output.selectFirst("#heading")!!.text())
            assertEquals("これは 重要なリンク.", output.selectFirst("#inline")!!.text())
            assertEquals("https://example.com/article", output.selectFirst("#https-url")!!.text())
            assertEquals("http://example.com/", output.selectFirst("#http-url")!!.text())
            assertEquals("www.example.com", output.selectFirst("#www-url")!!.text())
            assertEquals("1️⃣2️⃣0️⃣0️⃣", output.selectFirst("#keycap")!!.text())
            assertEquals("🔥 ✅ →", output.selectFirst("#emoji")!!.text())
            assertEquals("2026 42 42% ${'$'}100 1,000", output.selectFirst("#numbers")!!.text())
            assertEquals("https://example.com", output.selectFirst("#inline a")!!.attr("href"))
        }

    @Test
    fun `translatable text detection uses Unicode letters and excludes non-language content`() {
        listOf(
            "",
            " \n\t\u00a0",
            "\u200b\u200c\u200d",
            ".",
            ")",
            ").",
            ":",
            "—",
            "🔥",
            "✅",
            "1️⃣2️⃣0️⃣0️⃣",
            "→",
            "2026",
            "42",
            "42%",
            "${'$'}100",
            "1,000",
            "https://example.com/article",
            "http://example.com/",
            "www.example.com",
        ).forEach { text -> assertFalse(isTranslatableAiText(text), "Expected non-translatable: $text") }

        listOf(
            "2026 election",
            "Version 2 was released",
            "${'$'}100 million investment",
            "42 people were injured",
            "See https://example.com for details",
            "Visit https://example.com today",
            "日本語の記事",
            "Привет, мир",
        ).forEach { text -> assertTrue(isTranslatableAiText(text), "Expected translatable: $text") }
    }

    @Test
    fun `empty AI translation for natural language remains an error`() =
        runTest {
            val translator =
                translator(
                    responses =
                        listOf(
                            response(
                                """{"translations":[{"id":"article-title","text":"題名"},{"id":"s0","text":""}]}""",
                            ),
                        ),
                    requests = mutableListOf(),
                )

            val error =
                assertFailsWith<IllegalArgumentException> {
                    translator.translate("Title", "<p>This government abolished the law.</p>", settings)
                }

            assertEquals("AI translation contained an empty segment", error.message)
        }

    @Test
    fun `large article chunks are translated sequentially in source order`() =
        runTest {
            val requests = mutableListOf<ChatCompletionRequest>()
            val translator =
                translator(
                    responses =
                        listOf(
                            response("""{"translations":[{"id":"article-title","text":"題名"},{"id":"s0","text":"第一"}]}"""),
                            response("""{"translations":[{"id":"s1","text":"第二"}]}"""),
                        ),
                    requests = requests,
                )
            val html = "<p>${"a".repeat(3_000)}</p><p>${"b".repeat(3_000)}</p>"

            val result = translator.translate("Title", html, settings)

            assertEquals(2, requests.size)
            assertTrue(
                requests[0]
                    .messages
                    .last()
                    .messageContent
                    .toString()
                    .contains("s0"),
            )
            assertTrue(
                requests[1]
                    .messages
                    .last()
                    .messageContent
                    .toString()
                    .contains("s1"),
            )
            assertEquals(
                "第一第二",
                Jsoup
                    .parseBodyFragment(result.translatedHtml)
                    .body()
                    .text()
                    .replace(" ", ""),
            )
        }

    @Test
    fun `malformed response fails the complete translation`() =
        runTest {
            val translator = translator(listOf(response("not json")), mutableListOf())

            assertFailsWith<Exception> {
                translator.translate("Title", "<p>Hello</p>", settings)
            }
        }

    private fun translator(
        responses: List<ChatCompletion>,
        requests: MutableList<ChatCompletionRequest>,
    ): AiArticleTranslator {
        val pendingResponses = ArrayDeque(responses)
        val api =
            OpenAIApi("ja") {
                object : OpenAIClient {
                    override suspend fun models(requestOptions: RequestOptions?): List<Model> = emptyList()

                    override suspend fun chatCompletion(
                        request: ChatCompletionRequest,
                        requestOptions: RequestOptions?,
                    ): ChatCompletion {
                        requests += request
                        return pendingResponses.removeFirst()
                    }
                }
            }
        return AiArticleTranslator(api)
    }

    private fun response(content: String) =
        ChatCompletion(
            id = "test",
            model = ModelId("model"),
            created = 0,
            choices =
                listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage(role = ChatRole.Assistant, content = content),
                        finishReason = null,
                        logprobs = null,
                    ),
                ),
            usage = null,
            systemFingerprint = null,
        )
}
