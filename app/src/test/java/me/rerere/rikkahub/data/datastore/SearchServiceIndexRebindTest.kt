package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantSearchMode
import me.rerere.rikkahub.data.model.withBuiltInSearchDisabled
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class SearchServiceIndexRebindTest {
    @Test
    fun disablingBuiltInSearch_restoresSelectedExternalProvider_orTurnsSearchOff() {
        assertEquals(
            AssistantSearchMode.Provider(1),
            AssistantSearchMode.BuiltIn.withBuiltInSearchDisabled(
                searchServiceCount = 3,
                selectedSearchServiceIndex = 1,
            ),
        )
        assertEquals(
            AssistantSearchMode.Off,
            AssistantSearchMode.BuiltIn.withBuiltInSearchDisabled(
                searchServiceCount = 0,
                selectedSearchServiceIndex = 0,
            ),
        )
    }

    @Test
    fun rebindSearchServiceIndices_remapsAssistantSearchModes_andGlobalSelectedIndex_onReorder() {
        val a = SearchServiceOptions.TavilyOptions(id = Uuid.parse("00000000-0000-0000-0000-000000000001"))
        val b = SearchServiceOptions.ExaOptions(id = Uuid.parse("00000000-0000-0000-0000-000000000002"))
        val c = SearchServiceOptions.BraveOptions(id = Uuid.parse("00000000-0000-0000-0000-000000000003"))

        val oldServices = listOf(a, b, c)
        val newServices = listOf(c, a, b)

        val assistant = Assistant(
            id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
            searchMode = AssistantSearchMode.MultiProvider(listOf(1, 2)),
        )

        val rebounded = Settings(
            assistants = listOf(assistant),
            searchServices = newServices,
            searchServiceSelected = 1,
        ).rebindSearchServiceIndices(oldSearchServices = oldServices)

        assertEquals(2, rebounded.searchServiceSelected)
        assertEquals(
            AssistantSearchMode.MultiProvider(listOf(0, 2)),
            rebounded.assistants.single().searchMode,
        )
    }

    @Test
    fun rebindSearchServiceIndices_dropsMissingProvider_andCanonicalizes_toProvider() {
        val a = SearchServiceOptions.TavilyOptions(id = Uuid.parse("00000000-0000-0000-0000-000000000001"))
        val b = SearchServiceOptions.ExaOptions(id = Uuid.parse("00000000-0000-0000-0000-000000000002"))
        val c = SearchServiceOptions.BraveOptions(id = Uuid.parse("00000000-0000-0000-0000-000000000003"))

        val oldServices = listOf(a, b, c)
        val newServices = listOf(a, c)

        val assistant = Assistant(
            id = Uuid.parse("10000000-0000-0000-0000-000000000002"),
            searchMode = AssistantSearchMode.MultiProvider(listOf(1, 2)),
        )

        val rebounded = Settings(
            assistants = listOf(assistant),
            searchServices = newServices,
        ).rebindSearchServiceIndices(oldSearchServices = oldServices)

        assertEquals(
            AssistantSearchMode.Provider(1),
            rebounded.assistants.single().searchMode,
        )
    }

    @Test
    fun rebindSearchServiceIndices_shiftsIndices_onInsertion() {
        val a = SearchServiceOptions.TavilyOptions(id = Uuid.parse("00000000-0000-0000-0000-000000000001"))
        val b = SearchServiceOptions.ExaOptions(id = Uuid.parse("00000000-0000-0000-0000-000000000002"))
        val d = SearchServiceOptions.BraveOptions(id = Uuid.parse("00000000-0000-0000-0000-000000000004"))

        val oldServices = listOf(a, b)
        val newServices = listOf(d, a, b)

        val assistant = Assistant(
            id = Uuid.parse("10000000-0000-0000-0000-000000000003"),
            searchMode = AssistantSearchMode.Provider(0),
        )

        val rebounded = Settings(
            assistants = listOf(assistant),
            searchServices = newServices,
        ).rebindSearchServiceIndices(oldSearchServices = oldServices)

        assertEquals(
            AssistantSearchMode.Provider(1),
            rebounded.assistants.single().searchMode,
        )
    }
}
