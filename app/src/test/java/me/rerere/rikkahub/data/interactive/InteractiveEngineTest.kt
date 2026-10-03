package me.rerere.rikkahub.data.interactive

import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.model.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.ui.components.interactive.interactiveCatalog
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InteractiveEngineTest {
    /** Android 的 JSON Reader 在设备测试验证；此处把同一描述交给真实引擎和目录校验。 */
    private suspend fun CoroutineScope.withSurface(code: String, check: suspend (A2uiCoreSurfaceModel, MutableList<A2uiClientToServerMessage>) -> Unit) {
        val processor = A2uiMessageProcessor(listOf(interactiveCatalog {}))
        val events = mutableListOf<A2uiClientToServerMessage>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { processor.outboundEvents.collect { events += it } }
        val engine = launch { processor.collectMessages() }
        try {
            code.lines().filter { it.isNotBlank() }.forEach { line ->
                val envelope = JsonInstant.parseToJsonElement(line) as JsonObject
                when {
                    "createSurface" in envelope -> {
                        val body = envelope["createSurface"] as JsonObject
                        processor.processMessage(A2uiCreateSurfaceMessage(body.getValue("surfaceId").jsonPrimitive.content, A2uiBasicCatalogV1.CatalogId))
                    }
                    "updateComponents" in envelope -> {
                        val body = envelope["updateComponents"] as JsonObject
                        val components = (body["components"] as JsonArray).map { element ->
                            val component = element as JsonObject
                            A2uiComponentPayload(component.getValue("id").jsonPrimitive.content,
                                component.getValue("component").jsonPrimitive.content,
                                component.filterKeys { it != "id" && it != "component" }.mapValues { it.value.toInteractiveValue() })
                        }
                        processor.processMessage(A2uiUpdateComponentsMessage(body.getValue("surfaceId").jsonPrimitive.content, components))
                    }
                    "updateDataModel" in envelope -> {
                        val body = envelope["updateDataModel"] as JsonObject
                        processor.processMessage(A2uiUpdateDataModelMessage(body.getValue("surfaceId").jsonPrimitive.content,
                            body["path"]?.jsonPrimitive?.content ?: "/", body["value"]?.toInteractiveValue()))
                    }
                }
            }
            val surface = withTimeout(5_000) { processor.activeSurfaces.first { it.isNotEmpty() }.first() as A2uiCoreSurfaceModel }
            processor.processError(A2uiClientErrorMessage("TEST_READY", surface.id, "Ready"))
            withTimeout(5_000) { while (events.none { it is A2uiClientErrorMessage && it.code == "TEST_READY" }) delay(1) }
            events.removeAll { it is A2uiClientErrorMessage && it.code == "TEST_READY" }
            check(surface, events)
        } finally { observer.cancelAndJoin(); engine.cancelAndJoin() }
    }

    @Test fun `packaged examples pass the official catalog schema and resolve edited submission values`() = runBlocking {
        val examples = interactiveFences(File("src/main/assets/builtin-skills/interactive-components/references/examples.md").readText())
        examples.forEach { fence ->
            withSurface(fence.code) { surface, events ->
                assertTrue("Official catalog errors: $events", events.none { it is A2uiClientErrorMessage })
                val initialBudget = surface.dataModel[A2uiDataPath("/budget")]
                if (initialBudget != null) {
                    surface.dataModel.update(A2uiDataPath("/budget"), 1500)
                    surface.dispatchAction("submit", mapOf("event" to mapOf("name" to "submit_preferences", "context" to mapOf("Budget" to mapOf("path" to "/budget")))))
                    withTimeout(5_000) { while (events.none { it is A2uiClientEventMessage }) delay(1) }
                    val event = events.filterIsInstance<A2uiClientEventMessage>().single()
                    assertEquals(1500.0, (event.context["Budget"] as Number).toDouble(), 0.0)
                }
            }
        }
    }

    @Test fun `official checks evaluate against current input rather than initial description`() = runBlocking {
        val code = """
            {"createSurface":{"surfaceId":"form"}}
            {"updateComponents":{"surfaceId":"form","components":[{"id":"root","component":"TextField","label":"Name","value":{"path":"/name"},"checks":[{"condition":{"call":"required","args":{"value":{"path":"/name"}}},"message":"Enter a name"}]}]}}
            {"updateDataModel":{"surfaceId":"form","value":{"name":""}}}
        """.trimIndent()
        val component = (JsonInstant.parseToJsonElement(code.lines()[1]) as JsonObject).getValue("updateComponents").jsonObject.getValue("components").jsonArray.first().jsonObject
        withSurface(code) { surface, _ ->
            assertEquals("Enter a name", interactiveValidationError(surface, mapOf("root" to component)))
            surface.dataModel.update(A2uiDataPath("/name"), "Alice")
            assertNull(interactiveValidationError(surface, mapOf("root" to component)))
        }
    }

    @Test fun `streamed component previews remain valid official protocol messages`() = runBlocking {
        val examples = interactiveFences(File("src/main/assets/builtin-skills/interactive-components/references/examples.md").readText())
        examples.forEach { fence ->
            val decoder = InteractiveDocument("<supported catalogId>")
            val messages = mutableListOf<String>()
            for (length in 1..fence.code.length step 17) messages += decoder.consume(fence.code.take(length), false)
            messages += decoder.consume(fence.code, true)
            withSurface(messages.joinToString("\n")) { surface, events ->
                assertTrue("Official catalog errors: $events", events.none { it is A2uiClientErrorMessage })
                assertEquals("preferences", surface.id)
            }
        }
    }
    @Test fun `readiness barrier preserves object models without adding host fields`() = runBlocking {
        val code = """
            {"createSurface":{"surfaceId":"root-model"}}
            {"updateComponents":{"surfaceId":"root-model","components":[{"id":"root","component":"TextField","label":"Name","value":{"path":"/name"}}]}}
            {"updateDataModel":{"surfaceId":"root-model","value":{"name":"Initial"}}}
        """.trimIndent()
        withSurface(code) { surface, events ->
            assertEquals(JsonInstant.parseToJsonElement("""{"name":"Initial"}"""), interactiveJson(surface.dataModel[A2uiDataPath("/")]))
            surface.dataModel.update(A2uiDataPath("/name"), "Draft")
            assertEquals(JsonInstant.parseToJsonElement("""{"name":"Draft"}"""), interactiveJson(surface.dataModel[A2uiDataPath("/")]))
            assertTrue(events.isEmpty())
        }
    }

}
