package me.rerere.rikkahub.interactive

import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import me.rerere.rikkahub.data.interactive.interactiveFences
import me.rerere.rikkahub.ui.components.interactive.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

class InteractiveComponentTest {
    @get:Rule val compose = createComposeRule()
    private fun description(initial: String = "") = """
        {"version":"v0.9.1","createSurface":{"surfaceId":"form","catalogId":"${A2uiBasicCatalogV1.CatalogId}"}}
        {"version":"v0.9.1","updateComponents":{"surfaceId":"form","components":[{"id":"root","component":"Card","child":"body"},{"id":"body","component":"Column","children":["name","send"]},{"id":"name","component":"TextField","label":"Name","value":{"path":"/name"},"checks":[{"condition":{"call":"required","args":{"value":{"path":"/name"}}},"message":"Enter a name"}]},{"id":"label","component":"Text","text":"Send"},{"id":"send","component":"Button","child":"label","action":{"event":{"name":"submit","context":{"Name":{"path":"/name"}}}}}]}}
        {"version":"v0.9.1","updateDataModel":{"surfaceId":"form","value":{"name":"$initial"}}}
    """.trimIndent()

    @Test fun localEditingValidationGenerationGuardAndSubmissionSnapshot() {
        val code = description()
        val submits = AtomicInteger()
        val last = AtomicReference<InteractiveSubmission>()
        var generating by mutableStateOf(true)
        val base = InteractiveContentContext(Uuid.random(), Uuid.random(), 0, null, null, true, true,
            interactiveFences("```a2ui\n$code\n```"), isReadOnly = { _, _, _ -> submits.get() > 0 }, onSubmit = {
                last.set(it); submits.incrementAndGet(); true
            })
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalInteractiveContentContext provides base.copy(generating = generating)) {
                    InteractiveBlock(code, true, 0)
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Send").assertIsNotEnabled()
        assertEquals(0, submits.get())
        compose.runOnIdle { generating = false }
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        assertEquals(0, submits.get())
        compose.onNode(hasSetTextAction()).performTextInput("Alice")
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        assertEquals(1, submits.get())
        assertEquals("\"Alice\"", last.get().values["Name"].toString())
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertIsNotEnabled()
    }

    @Test fun draftsSurviveUnmountAndEditedDescriptionDoesNotReuseThem() {
        var code by mutableStateOf(description("Initial"))
        var mounted by mutableStateOf(true)
        val base = InteractiveContentContext(Uuid.random(), Uuid.random(), 0, null, null, false, true,
            emptyList(), isReadOnly = { _, _, _ -> false }, onSubmit = { false })
        compose.setContent {
            MaterialTheme {
                if (mounted) CompositionLocalProvider(LocalInteractiveContentContext provides base) { InteractiveBlock(code, true, 0) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("Draft")
        compose.runOnIdle { mounted = false }
        compose.runOnIdle { mounted = true }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Draft")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { code = description("Edited") }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Edited")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Draft").assertDoesNotExist()
    }
    @Test fun correctedDescriptionRecoversAfterAnInvalidComponent() {
        var code by mutableStateOf(description().replace("\"component\":\"TextField\"", "\"component\":\"Unknown\""))
        val origin = InteractiveContentContext(Uuid.random(), Uuid.random(), 0, null, null, false, true,
            emptyList(), isReadOnly = { _, _, _ -> false }, onSubmit = { false })
        val errorText = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getString(me.rerere.rikkahub.R.string.interactive_components_error)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalInteractiveContentContext provides origin) { InteractiveBlock(code, true, 0) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(errorText)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { code = description() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).assertIsEnabled()
    }

    @Test fun completeComponentsRenderBeforeTheBatchAndBlockFinish() {
        val lines = description("Initial").lines()
        val prelude = "${lines[0]}\n${lines[2]}\n"
        val batch = lines[1]
        var code by mutableStateOf(prelude + batch.substring(0, batch.indexOf("{\"id\":\"label\"")))
        var closed by mutableStateOf(false)
        var generating by mutableStateOf(true)
        val origin = InteractiveContentContext(Uuid.random(), Uuid.random(), 0, null, null, true, true,
            emptyList(), isReadOnly = { _, _, _ -> false }, onSubmit = { fail("Streaming must not submit"); false })
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalInteractiveContentContext provides origin.copy(generating = generating)) {
                    InteractiveBlock(code, closed, 0)
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Initial").assertExists()
        compose.onNode(hasSetTextAction()).assertIsNotEnabled()
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.runOnIdle { code = prelude + batch }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Send")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.runOnIdle { closed = true; generating = false }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasSetTextAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Initial").assertExists()
    }

    @Test fun scriptToolContinuesLocallyAfterSubmittingAndExportUsesTheSubmissionSnapshot() {
        val code = """
            {"version":"v0.9.1","createSurface":{"surfaceId":"calculator","catalogId":"flit:interactive/v1","flitRuntime":{"version":1,"code":"({calculate({model}){return [{path:'/result',value:String(Number(model.amount)*2)}]}})","watch":[{"paths":["/amount"],"handler":"calculate"}]}}}
            {"version":"v0.9.1","updateComponents":{"surfaceId":"calculator","components":[{"id":"root","component":"Column","children":["amount","result","send"]},{"id":"amount","component":"TextField","label":"Amount","value":{"path":"/amount"}},{"id":"result","component":"Text","text":{"path":"/result"}},{"id":"label","component":"Text","text":"Analyze"},{"id":"send","component":"Button","child":"label","action":{"event":{"name":"analyze","context":{"Result":{"path":"/result"}}}}}]}}
            {"version":"v0.9.1","updateDataModel":{"surfaceId":"calculator","value":{"amount":"2","result":"0"}}}
        """.trimIndent()
        val submits = AtomicInteger()
        val last = AtomicReference<InteractiveSubmission>()
        var submitted by mutableStateOf(false)
        var export by mutableStateOf(false)
        val origin = InteractiveContentContext(Uuid.random(), Uuid.random(), 0, null, null, false, true,
            emptyList(), isReadOnly = { _, _, _ -> submitted }, hasSubmitted = { _, _ -> submitted }, onSubmit = {
                last.set(it)
                submits.incrementAndGet()
                val repo = org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.interactive.InteractiveStateRepository>()
                repo.update(me.rerere.rikkahub.data.db.entity.InteractiveComponentStateEntity(
                    it.origin.conversationId.toString(), it.origin.messageId.toString(), 0, 0, it.fingerprint,
                    it.surfaceId, it.dataModel.toString(), "one", true, it.dataModel.toString()), true, true)
                submitted = true
                true
            })
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalInteractiveContentContext provides origin) { InteractiveBlock(code, true, 0, export = export) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("4")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Analyze").performClick()
        compose.waitForIdle()
        assertEquals(1, submits.get())
        compose.onNode(hasSetTextAction()).assertIsEnabled().performTextReplacement("3")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("6")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Analyze").assertIsNotEnabled()
        assertEquals("\"4\"", last.get().values["Result"].toString())
        compose.runOnIdle { export = true }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("4")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("6").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsNotEnabled()
        assertEquals(1, submits.get())
    }

    @Test fun explicitScriptButtonsUpdateLocallyWithoutSubmitting() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val examples = context.assets.open("builtin-skills/interactive-components/references/script-examples.md")
            .bufferedReader().use { it.readText() }
        val code = interactiveFences(examples).last().code.replace("\"answer\":[]", "\"answer\":[\"4\"]")
        val origin = InteractiveContentContext(Uuid.random(), Uuid.random(), 0, null, null, false, true,
            emptyList(), isReadOnly = { _, _, _ -> false }, onSubmit = { fail("A local action must not submit"); false })
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalInteractiveContentContext provides origin) { InteractiveBlock(code, true, 0) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Check answer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Check answer").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Correct: 1 point")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Check answer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Check answer").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Check answer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Correct: 1 point").assertExists()
    }

}
