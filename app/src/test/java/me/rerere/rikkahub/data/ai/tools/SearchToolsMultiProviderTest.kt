package me.rerere.rikkahub.data.ai.tools

import me.rerere.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchToolsMultiProviderTest {
    @Test
    fun mergeProviderSearchOutcomes_dedupByUrl_andKeepsOrder() {
        val a = SearchTools.ProviderSearchOutcome(
            providerName = "A",
            result = Result.success(
                SearchResult(
                    answer = "answer-a",
                    items = listOf(
                        SearchResult.SearchResultItem(
                            title = "t1",
                            url = "https://EXAMPLE.com/a/",
                            text = "x",
                        ),
                        SearchResult.SearchResultItem(
                            title = "t2",
                            url = "https://site.com/x",
                            text = "y",
                        ),
                    ),
                )
            ),
        )

        val b = SearchTools.ProviderSearchOutcome(
            providerName = "B",
            result = Result.success(
                SearchResult(
                    answer = "answer-b",
                    items = listOf(
                        SearchResult.SearchResultItem(
                            title = "t3",
                            url = "http://example.com/a",
                            text = "dup",
                        ),
                        SearchResult.SearchResultItem(
                            title = "t4",
                            url = "https://site.com/y/",
                            text = "z",
                        ),
                    ),
                )
            ),
        )

        val merged = SearchTools.mergeProviderSearchOutcomes(listOf(a, b))

        assertEquals("answer-a", merged.answer)
        assertEquals(
            listOf(
                "https://EXAMPLE.com/a/",
                "https://site.com/x",
                "https://site.com/y/",
            ),
            merged.items.map { it.url },
        )
        assertTrue(merged.errors.isEmpty())
    }

    @Test
    fun mergeProviderSearchOutcomes_collectsErrors_andContinues() {
        val a = SearchTools.ProviderSearchOutcome(
            providerName = "A",
            result = Result.success(
                SearchResult(
                    answer = null,
                    items = listOf(
                        SearchResult.SearchResultItem(
                            title = "t",
                            url = "https://a.com",
                            text = "x",
                        ),
                    ),
                )
            ),
        )
        val b = SearchTools.ProviderSearchOutcome(
            providerName = "B",
            result = Result.failure(IllegalStateException("boom")),
        )

        val merged = SearchTools.mergeProviderSearchOutcomes(listOf(a, b))

        assertEquals(1, merged.items.size)
        assertEquals("https://a.com", merged.items.first().url)
        assertEquals(1, merged.errors.size)
        assertEquals("B", merged.errors.first().providerName)
        assertEquals("boom", merged.errors.first().message)
    }

    @Test
    fun urlDedupKey_normalizesHost_andTrimsSlash() {
        assertEquals("example.com/path", SearchTools.urlDedupKey("https://Example.COM/path/"))
    }

    @Test
    fun toolSystemPromptRegistry_containsSearchAgentPrompt() {
        val definition = ToolSystemPromptRegistry.get("search_agent")

        assertEquals(ToolSystemPromptGroup.Search, definition?.group)
        assertTrue(SEARCH_AGENT_MAIN_TOOL_PROMPT_TEMPLATE.contains("latest/current information"))
    }
}
