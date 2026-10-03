package me.rerere.rikkahub.ui.components.interactive

import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.model.processor.A2uiActionInterceptor
import androidx.a2ui.model.protocol.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.InteractiveComponentStateEntity
import me.rerere.rikkahub.data.interactive.*
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject
import java.util.UUID

/** 已闭合的组件逐步显示，未完成的组件继续缓冲；同一前缀不重复应用。 */
@Composable
fun InteractiveBlock(code: String, closed: Boolean, offset: Int, modifier: Modifier = Modifier, export: Boolean = false) {
    val origin = LocalInteractiveContentContext.current
    key(origin?.conversationId, origin?.messageId, origin?.partIndex, offset, export) {
        InteractiveBlockContent(code, closed, offset, modifier, export)
    }
}

@Composable
private fun InteractiveBlockContent(code: String, closed: Boolean, offset: Int, modifier: Modifier, export: Boolean) {
    val origin = LocalInteractiveContentContext.current
    val context = LocalContext.current
    val currentAndroidContext by rememberUpdatedState(context)
    val repository = koinInject<InteractiveStateRepository>()
    val currentOrigin by rememberUpdatedState(origin)
    var epoch by remember { mutableIntStateOf(0) }
    val decoder = remember(epoch) { InteractiveDocument(A2uiBasicCatalogV1.CatalogId) }
    var componentSnapshot by remember(epoch) { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    if (decoder.hasChangedPrefix(code)) {
        LaunchedEffect(code) { epoch++ }
        return
    }
    val catalog = remember { interactiveCatalog(localeProvider = androidx.a2ui.model.catalog.functions.A2uiLocaleProvider {
        currentAndroidContext.resources.configuration.locales[0]
    }) { url ->
        // 与正文链接使用相同的打开方式，只有用户点击才执行。
        if (android.net.Uri.parse(url).scheme in setOf("https", "http")) currentAndroidContext.openUrl(url)
    } }
    var runtimeSurface by remember(epoch) { mutableStateOf<A2uiCoreSurfaceModel?>(null) }
    var restorationQueued by remember(epoch) { mutableStateOf(false) }
    var ready by remember(epoch) { mutableStateOf(false) }
    var error by remember(epoch) { mutableStateOf<String?>(null) }
    var notice by remember(epoch) { mutableStateOf<String?>(null) }
    var pending by remember(epoch) { mutableStateOf(false) }
    var persisted by remember(epoch) { mutableStateOf<InteractiveComponentStateEntity?>(null) }
    var renderedCode by remember(epoch) { mutableStateOf(code) }
    val fingerprint = remember(code) { interactiveFingerprint(code) }
    if ((ready || error != null) && renderedCode != code) {
        LaunchedEffect(code) { epoch++ }
        return
    }
    SideEffect { renderedCode = code }
    val readOnly = export || origin == null || pending || persisted?.submitted == true ||
        decoder.surfaceId?.let { origin.isReadOnly(it, offset, fingerprint) } == true
    val controls = InteractiveControls(readOnly = readOnly || !ready || error != null, canSubmit = closed && ready && !readOnly && origin?.generating == false)
    val currentControls by rememberUpdatedState(controls)
    val processor = remember(catalog, epoch) {
        A2uiMessageProcessor(listOf(catalog), listOf(A2uiActionInterceptor { action ->
            val flags = currentControls
            if (pending || flags.readOnly || (action is A2uiEventAction && !flags.canSubmit)) null
            else if (action is A2uiEventAction) {
                val validation = runtimeSurface?.let { interactiveValidationError(it, decoder.componentSnapshot) }
                if (validation != null) { notice = validation; null } else action
            } else action
        }))
    }
    val surfaces by processor.activeSurfaces.collectAsState()
    val surface = surfaces.firstOrNull() as? A2uiCoreSurfaceModel
    SideEffect { runtimeSurface = surface }
    val parser = remember { A2uiMessageParser() }
    val marker = remember(epoch) { "FLIT_READY_${UUID.randomUUID()}" }
    val fingerprintState by rememberUpdatedState(fingerprint)

    LaunchedEffect(processor) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.collect { event ->
                when (event) {
                    is A2uiClientErrorMessage -> {
                        if (event.code == marker) {
                            val active = processor.activeSurfaces.value.firstOrNull() as? A2uiCoreSurfaceModel
                            if (active != null && error == null) {
                                val restored = persisted?.dataModel?.takeIf { it.isNotBlank() }?.let {
                                    runCatching { JsonInstant.parseToJsonElement(it) }.getOrNull()
                                }
                                if (restored != null) active.dataModel.update(A2uiDataPath("/"), restored.toInteractiveValue())
                                ready = true
                            }
                        } else if (event.code == "VALIDATION_FAILED") notice = event.message else error = event.message.take(240)
                    }
                    is A2uiClientEventMessage -> {
                        val host = currentOrigin ?: return@collect
                        if (pending || !currentControls.canSubmit) return@collect
                        val active = processor.activeSurfaces.value.firstOrNull() as? A2uiCoreSurfaceModel ?: return@collect
                        val data = interactiveJson(active.dataModel[A2uiDataPath("/")])
                        val button = decoder.componentSnapshot[event.componentId]
                        val childId = button?.get("child")?.jsonPrimitiveOrNull?.contentOrNull
                        val label = decoder.componentSnapshot[childId]?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                            ?: event.type
                        val request = InteractiveSubmission(host, offset, fingerprintState, event.surfaceId,
                            event.componentId, event.type, event.timestamp, label,
                            interactiveJson(event.context) as? JsonObject ?: JsonObject(emptyMap()), data)
                        notice = null
                        pending = host.onSubmit(request)
                        if (!pending) notice = context.getString(R.string.interactive_components_submit_unavailable)
                    }
                }
            }
        }
        try { processor.collectMessages() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message?.take(240) ?: context.getString(R.string.interactive_components_error) }
    }

    LaunchedEffect(code, closed, origin?.generating, processor) {
        if (error != null) return@LaunchedEffect
        try {
            val complete = closed || currentOrigin?.generating != true
            val lines = decoder.consume(code, complete)
            componentSnapshot = decoder.componentSnapshot
            lines.forEach { processor.processMessage(parser.parse(it)) }
            if (complete && !decoder.deleted && !restorationQueued) {
                val host = currentOrigin
                val initial = host?.let { InteractiveComponentStateEntity(it.conversationId.toString(), it.messageId.toString(),
                    it.partIndex, offset, fingerprint, decoder.surfaceId.orEmpty(), "", null, false) }
                persisted = initial?.let { repository.load(it, host.temporary) }
                // 先确认 surface 已创建，再把本地屏障送入同一串行队列。
                // 屏障只在宿主消费，不修改用户数据，也不会发送给模型。
                withTimeout(5_000) { processor.activeSurfaces.first { it.any { surface -> surface.id == decoder.surfaceId } } }
                processor.processError(A2uiClientErrorMessage(marker, decoder.surfaceId.orEmpty(), "Ready"))
                restorationQueued = true
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message?.take(240) ?: context.getString(R.string.interactive_components_error) }
    }

    LaunchedEffect(surface, marker) {
        if (surface == null) return@LaunchedEffect
        snapshotFlow { componentSnapshot to interactiveJson(surface.dataModel[A2uiDataPath("/")]) }.collect { (components, model) ->
            try { interactiveInstances(components, model) }
            catch (e: IllegalArgumentException) { error = e.message; return@collect }
            if (ready) {
                val state = persisted ?: return@collect
                if (!export && !state.submitted && !currentControls.readOnly) {
                    repository.update(state.copy(dataModel = model.toString()), currentOrigin?.temporary == true)
                }
            }
        }
    }
    LaunchedEffect(persisted?.fingerprint) {
        val initial = persisted ?: return@LaunchedEffect
        repository.observe(initial).filterNotNull().collect { if (it.fingerprint == initial.fingerprint) persisted = it }
    }
    LaunchedEffect(origin?.generating, readOnly) {
        if (pending && origin?.generationActive?.invoke() == false && persisted?.submitted != true &&
            decoder.surfaceId?.let { origin.isReadOnly(it, offset, fingerprint) } != true) pending = false
    }
    DisposableEffect(processor) {
        onDispose { if (!export) persisted?.let { repository.flush(it, currentOrigin?.temporary == true) } }
    }

    val expansionError = surface?.let {
        runCatching { interactiveInstances(componentSnapshot, interactiveJson(it.dataModel[A2uiDataPath("/")])) }.exceptionOrNull()?.message
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (error != null || expansionError != null) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(stringResource(R.string.interactive_components_error), color = MaterialTheme.colorScheme.error)
                Text((error ?: expansionError).orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
        } else if (surface != null && !decoder.deleted) {
            CompositionLocalProvider(LocalInteractiveControls provides controls) {
                MaterialTheme(motionScheme = InteractiveMotionScheme) {
                A2uiSurface(surfaceModel = surface, modifier = Modifier.fillMaxWidth(), transitionSpec = null,
                    loadingContent = { Text(stringResource(R.string.interactive_components_loading)) },
                    errorContent = { Text(stringResource(R.string.interactive_components_error), color = MaterialTheme.colorScheme.error) })
                }
            }
        } else if (!decoder.deleted) Text(stringResource(R.string.interactive_components_loading))
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
