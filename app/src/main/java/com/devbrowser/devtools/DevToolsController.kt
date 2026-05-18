package com.devbrowser.devtools

import com.devbrowser.devtools.cdp.CdpClient
import com.devbrowser.devtools.cdp.jsonParams
import com.devbrowser.engine.BrowserEngine
import com.devbrowser.engine.CdpEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Top-level coordinator for DevTools. Owns one [CdpClient] per attached
 * engine, enables the protocol domains used by each panel, dispatches events
 * to per-panel state flows, and exposes a single status surface to the UI.
 */
class DevToolsController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private var cdp: CdpClient? = null
    private var eventJob: Job? = null

    private val _status = MutableStateFlow(Status.Detached)
    val status: StateFlow<Status> = _status.asStateFlow()

    val console = ConsoleState()
    val network = NetworkState()
    val elements = ElementsState()
    val sources = SourcesState()
    val performance = PerformanceState()
    val memory = MemoryState()
    val application = ApplicationState()

    enum class Status { Detached, Connecting, Connected, Unsupported, Failed }

    suspend fun attach(engine: BrowserEngine) {
        detach()
        val endpoint = engine.cdpEndpoint()
        if (endpoint !is CdpEndpoint.UnixSocket) {
            _status.value = Status.Unsupported
            return
        }
        _status.value = Status.Connecting
        val client = CdpClient(endpoint.name)
        cdp = client
        val ok = client.connectToFirstPage()
        if (!ok) {
            _status.value = Status.Failed
            return
        }
        _status.value = Status.Connected

        eventJob = scope.launch {
            client.events.collect { dispatch(it) }
        }

        runCatching {
            client.fire("Runtime.enable")
            client.fire("Log.enable")
            client.fire("Page.enable")
            client.fire("Network.enable")
            client.fire("DOM.enable")
            client.fire("CSS.enable")
            client.fire("Debugger.enable")
            client.fire("Performance.enable")
            client.fire("Console.enable")
            client.fire("Overlay.enable")
            client.fire("DOMStorage.enable")
            client.fire("HeapProfiler.enable")
        }
        loadDom()
        refreshOriginAndStorage()
    }

    suspend fun detach() {
        eventJob?.cancel()
        eventJob = null
        cdp?.close()
        cdp = null
        _status.value = Status.Detached
    }

    fun clearAll() {
        console.clear()
        network.clear()
    }

    // ─── Console / Eval ───

    suspend fun evaluate(expression: String): String? {
        val client = cdp ?: return null
        val resp = client.send(
            "Runtime.evaluate",
            jsonParams {
                put("expression", expression)
                put("includeCommandLineAPI", true)
                put("returnByValue", false)
                put("generatePreview", true)
                put("userGesture", true)
                put("awaitPromise", true)
                put("replMode", true)
            }
        )
        val result = resp.result ?: return resp.error?.message
        result["exceptionDetails"]?.let { console.appendException(it.jsonObject) }
        return result["result"]?.let { remoteObjectToText(it) }
            ?.also { console.appendEvalResult(it) }
    }

    // ─── Elements ───

    suspend fun loadDom() {
        val client = cdp ?: return
        val resp = runCatching {
            client.send("DOM.getDocument", jsonParams {
                put("depth", 2)
                put("pierce", true)
            })
        }.getOrNull() ?: return
        val rootObj = resp.result?.get("root")?.jsonObject ?: return
        elements.setRoot(parseDomNode(rootObj))
    }

    suspend fun expandNode(node: DomNode) {
        val client = cdp ?: return
        // Already loaded?
        if (node.children.isNotEmpty()) {
            elements.toggleExpanded(node.nodeId)
            return
        }
        if (node.childNodeCount == 0) {
            elements.toggleExpanded(node.nodeId)
            return
        }
        // Request children — they'll arrive via DOM.setChildNodes (handled in dispatch).
        runCatching {
            client.send("DOM.requestChildNodes", jsonParams {
                put("nodeId", node.nodeId)
                put("depth", 1)
            })
        }
        elements.markExpanded(node.nodeId)
    }

    suspend fun selectNode(nodeId: Long) {
        elements.select(nodeId)
        val client = cdp ?: return
        runCatching {
            val resp = client.send("CSS.getComputedStyleForNode", jsonParams {
                put("nodeId", nodeId)
            })
            val arr = resp.result?.get("computedStyle")?.jsonArray ?: return@runCatching
            val styles = arr.map { e ->
                val o = e.jsonObject
                (o["name"]?.jsonPrimitive?.content.orEmpty()) to
                    (o["value"]?.jsonPrimitive?.content.orEmpty())
            }
            elements.setComputedStyle(styles)
        }
        runCatching {
            client.send("Overlay.highlightNode", jsonParams {
                put("nodeId", nodeId)
                put("highlightConfig", buildJsonObject {
                    put("showInfo", true)
                    put("contentColor", colorOf(0x90, 0xCD, 0xF4, 0.6f))
                    put("paddingColor", colorOf(0xB6, 0xFC, 0xCD, 0.6f))
                    put("borderColor", colorOf(0xFC, 0xDD, 0x8E, 0.6f))
                    put("marginColor", colorOf(0xFE, 0xD7, 0xD7, 0.6f))
                })
            })
        }
    }

    suspend fun setAttribute(nodeId: Long, name: String, value: String) {
        val client = cdp ?: return
        runCatching {
            client.send("DOM.setAttributeValue", jsonParams {
                put("nodeId", nodeId)
                put("name", name)
                put("value", value)
            })
        }
    }

    suspend fun setPickMode(enabled: Boolean) {
        val client = cdp ?: return
        elements.setPicking(enabled)
        runCatching {
            client.send("Overlay.setInspectMode", jsonParams {
                put("mode", if (enabled) "searchForNode" else "none")
                put("highlightConfig", buildJsonObject {
                    put("showInfo", true)
                    put("contentColor", colorOf(0x90, 0xCD, 0xF4, 0.6f))
                })
            })
        }
    }

    private fun colorOf(r: Int, g: Int, b: Int, a: Float): JsonObject = buildJsonObject {
        put("r", r); put("g", g); put("b", b); put("a", a)
    }

    // ─── Sources ───

    suspend fun loadScriptSource(scriptId: String) {
        val client = cdp ?: return
        val resp = runCatching {
            client.send("Debugger.getScriptSource", jsonParams {
                put("scriptId", scriptId)
            })
        }.getOrNull() ?: return
        val src = resp.result?.get("scriptSource")?.jsonPrimitive?.content
        sources.selectScript(scriptId, src)
    }

    suspend fun pause() { runCatching { cdp?.fire("Debugger.pause") } }
    suspend fun resume() { runCatching { cdp?.fire("Debugger.resume") } }
    suspend fun stepOver() { runCatching { cdp?.fire("Debugger.stepOver") } }
    suspend fun stepInto() { runCatching { cdp?.fire("Debugger.stepInto") } }
    suspend fun stepOut() { runCatching { cdp?.fire("Debugger.stepOut") } }

    // ─── Performance ───

    suspend fun refreshPerformanceMetrics() {
        val client = cdp ?: return
        val resp = runCatching {
            client.send("Performance.getMetrics")
        }.getOrNull() ?: return
        val arr = resp.result?.get("metrics")?.jsonArray ?: return
        val list = arr.map {
            val o = it.jsonObject
            PerformanceMetric(
                name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                value = o["value"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
            )
        }
        performance.setMetrics(list)
        val used = list.firstOrNull { it.name == "JSHeapUsedSize" }?.value?.toLong()
        val total = list.firstOrNull { it.name == "JSHeapTotalSize" }?.value?.toLong()
        val nodes = list.firstOrNull { it.name == "Nodes" }?.value?.toLong()
        memory.setMetrics(used, total, nodes)
    }

    suspend fun startTracing() {
        val client = cdp ?: return
        performance.reset()
        performance.setRecording(true)
        runCatching {
            client.send("Tracing.start", jsonParams {
                put("transferMode", "ReportEvents")
                put("traceConfig", buildJsonObject {
                    put("recordMode", "recordContinuously")
                    putJsonArray(this, "includedCategories",
                        listOf("devtools.timeline", "v8", "blink.user_timing", "loading", "disabled-by-default-devtools.timeline"))
                })
            })
        }
    }

    suspend fun stopTracing() {
        val client = cdp ?: return
        performance.setRecording(false)
        runCatching { client.fire("Tracing.end") }
    }

    private fun putJsonArray(b: kotlinx.serialization.json.JsonObjectBuilder, key: String, items: List<String>) {
        b.put(key, JsonArray(items.map { JsonPrimitive(it) }))
    }

    // ─── Memory ───

    suspend fun collectGarbage() {
        runCatching { cdp?.fire("HeapProfiler.collectGarbage") }
        refreshPerformanceMetrics()
    }

    suspend fun takeHeapSnapshot() {
        val client = cdp ?: return
        memory.setSampling(true)
        runCatching {
            client.send("HeapProfiler.takeHeapSnapshot", jsonParams {
                put("reportProgress", false)
                put("captureNumericValue", false)
            })
        }
        memory.setSampling(false)
        // The actual snapshot is streamed via HeapProfiler.addHeapSnapshotChunk events;
        // for now we just record the operation and refresh metrics.
        refreshPerformanceMetrics()
        val used = memory.jsHeapUsedBytes.value ?: 0
        memory.appendSnapshot(used)
    }

    // ─── Application / Storage ───

    suspend fun refreshOriginAndStorage() {
        val client = cdp ?: return
        val origin = runCatching {
            client.send("Runtime.evaluate", jsonParams {
                put("expression", "location.origin")
                put("returnByValue", true)
            }).result?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.content
        }.getOrNull()
        application.setOrigin(origin)
        if (origin != null) {
            loadCookies()
            loadDomStorage(origin)
        }
    }

    suspend fun loadCookies() {
        val client = cdp ?: return
        val resp = runCatching { client.send("Network.getCookies") }.getOrNull() ?: return
        val arr = resp.result?.get("cookies")?.jsonArray ?: return
        application.setCookies(arr.map { ce ->
            val o = ce.jsonObject
            Cookie(
                name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                value = o["value"]?.jsonPrimitive?.content.orEmpty(),
                domain = o["domain"]?.jsonPrimitive?.content.orEmpty(),
                path = o["path"]?.jsonPrimitive?.content.orEmpty(),
                expires = o["expires"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                secure = o["secure"]?.jsonPrimitive?.content == "true",
                httpOnly = o["httpOnly"]?.jsonPrimitive?.content == "true",
                sameSite = o["sameSite"]?.jsonPrimitive?.content,
            )
        })
    }

    suspend fun loadDomStorage(origin: String) {
        val client = cdp ?: return
        suspend fun fetch(isLocal: Boolean): List<StorageItem> {
            val resp = runCatching {
                client.send("DOMStorage.getDOMStorageItems", jsonParams {
                    put("storageId", buildJsonObject {
                        put("securityOrigin", origin)
                        put("isLocalStorage", isLocal)
                    })
                })
            }.getOrNull() ?: return emptyList()
            val entries = resp.result?.get("entries")?.jsonArray ?: return emptyList()
            return entries.mapNotNull { kv ->
                val a = kv.jsonArray
                if (a.size < 2) null
                else StorageItem(a[0].jsonPrimitive.content, a[1].jsonPrimitive.content)
            }
        }
        application.setLocalStorage(fetch(true))
        application.setSessionStorage(fetch(false))
    }

    suspend fun deleteCookie(name: String, domain: String) {
        val client = cdp ?: return
        runCatching {
            client.send("Network.deleteCookies", jsonParams {
                put("name", name)
                put("domain", domain)
            })
        }
        loadCookies()
    }

    // ─── Event dispatch ───

    private fun dispatch(ev: CdpClient.CdpEvent) {
        when (ev.method) {
            "Runtime.consoleAPICalled" -> console.appendConsoleApi(ev.params)
            "Runtime.exceptionThrown" -> console.appendException(
                ev.params["exceptionDetails"]?.jsonObject ?: return
            )
            "Log.entryAdded" -> console.appendLogEntry(
                ev.params["entry"]?.jsonObject ?: return
            )
            "Network.requestWillBeSent" -> network.onRequestWillBeSent(ev.params)
            "Network.responseReceived" -> network.onResponseReceived(ev.params)
            "Network.loadingFinished" -> network.onLoadingFinished(ev.params)
            "Network.loadingFailed" -> network.onLoadingFailed(ev.params)
            "DOM.setChildNodes" -> onDomChildNodes(ev.params)
            "DOM.attributeModified" -> onDomAttribute(ev.params, modified = true)
            "DOM.attributeRemoved" -> onDomAttribute(ev.params, modified = false)
            "DOM.documentUpdated" -> scope.launch { loadDom() }
            "Inspector.detached" -> _status.value = Status.Failed
            "Debugger.scriptParsed" -> sources.onScriptParsed(ev.params)
            "Debugger.paused" -> sources.onPaused(ev.params)
            "Debugger.resumed" -> sources.onResumed()
            "Tracing.dataCollected" -> performance.onTraceData(ev.params)
            "Tracing.tracingComplete" -> performance.setRecording(false)
            "Overlay.inspectNodeRequested" -> {
                val backendId = ev.params["backendNodeId"]?.jsonPrimitive?.content?.toLongOrNull()
                if (backendId != null) scope.launch { onInspectRequested(backendId) }
            }
            "Page.frameNavigated" -> scope.launch { refreshOriginAndStorage(); loadDom() }
            else -> Unit
        }
    }

    private suspend fun onInspectRequested(backendNodeId: Long) {
        val client = cdp ?: return
        val resp = runCatching {
            client.send("DOM.pushNodesByBackendIdsToFrontend", jsonParams {
                put("backendNodeIds", JsonArray(listOf(JsonPrimitive(backendNodeId))))
            })
        }.getOrNull() ?: return
        val ids = resp.result?.get("nodeIds")?.jsonArray ?: return
        val id = ids.firstOrNull()?.jsonPrimitive?.content?.toLongOrNull() ?: return
        selectNode(id)
        setPickMode(false)
    }

    private fun onDomChildNodes(params: JsonObject) {
        val parentId = params["parentId"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
        val nodes = params["nodes"]?.jsonArray ?: return
        val children = nodes.map { parseDomNode(it.jsonObject) }
        elements.replaceChildren(parentId, children)
    }

    private fun onDomAttribute(params: JsonObject, modified: Boolean) {
        val nodeId = params["nodeId"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
        // Simplest path: refetch attributes
        scope.launch {
            val client = cdp ?: return@launch
            val resp = runCatching {
                client.send("DOM.getAttributes", jsonParams { put("nodeId", nodeId) })
            }.getOrNull() ?: return@launch
            val arr = resp.result?.get("attributes")?.jsonArray ?: return@launch
            val attrs = buildList<Pair<String, String>> {
                var i = 0
                while (i + 1 < arr.size) {
                    add(arr[i].jsonPrimitive.content to arr[i + 1].jsonPrimitive.content)
                    i += 2
                }
            }
            elements.updateAttributes(nodeId, attrs)
        }
    }
}

private fun remoteObjectToText(remote: JsonElement): String {
    val obj = remote.jsonObject
    val type = obj["type"]?.jsonPrimitive?.content
    val subtype = obj["subtype"]?.jsonPrimitive?.content
    val desc = obj["description"]?.jsonPrimitive?.content
    val value = obj["value"]
    return when {
        type == "string" -> "\"${value?.jsonPrimitive?.content ?: ""}\""
        type == "undefined" -> "undefined"
        subtype == "null" -> "null"
        value != null -> value.toString()
        desc != null -> desc
        else -> obj.toString()
    }
}
