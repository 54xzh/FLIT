package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SerperSearchServiceTest {
    @Test
    fun mapsOrganicResultsAndKnowledgeGraph() {
        val payload = """
            {
              "knowledgeGraph": {
                "title": "Apple",
                "type": "Technology company",
                "website": "https://www.apple.com/",
                "description": "Apple Inc. is an American multinational technology company.",
                "attributes": {
                  "CEO": "Tim Cook"
                }
              },
              "organic": [
                {
                  "title": "Apple Inc. - Wikipedia",
                  "link": "https://en.wikipedia.org/wiki/Apple_Inc.",
                  "snippet": "Apple Inc. is an American technology company.",
                  "position": 2
                },
                {
                  "title": "Apple",
                  "link": "https://www.apple.com/",
                  "snippet": "Discover the world of Apple.",
                  "position": 1
                }
              ]
            }
        """.trimIndent()

        val response = SearchService.json.decodeFromString<SerperSearchService.SerperSearchResponse>(payload)
        val result = response.toSearchResult(limit = 1)

        assertEquals("Apple Inc. is an American multinational technology company.", result.answer)
        assertEquals(1, result.items.size)
        assertEquals("Apple", result.items.first().title)
        assertEquals("https://www.apple.com/", result.items.first().url)
        assertEquals("Discover the world of Apple.", result.items.first().text)
    }

    @Test
    fun fallsBackToKnowledgeGraphWhenOrganicIsEmpty() {
        val payload = """
            {
              "knowledgeGraph": {
                "title": "Apple",
                "type": "Technology company",
                "website": "https://www.apple.com/",
                "description": "Apple Inc. is an American multinational technology company."
              }
            }
        """.trimIndent()

        val response = SearchService.json.decodeFromString<SerperSearchService.SerperSearchResponse>(payload)
        val result = response.toSearchResult(limit = 5)

        assertEquals(1, result.items.size)
        assertEquals("Apple", result.items.first().title)
        assertEquals("Technology company\nApple Inc. is an American multinational technology company.", result.items.first().text)
    }
}
