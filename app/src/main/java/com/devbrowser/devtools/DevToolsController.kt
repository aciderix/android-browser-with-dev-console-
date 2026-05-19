package com.devbrowser.devtools

import com.devbrowser.devtools.cdp.CdpClient
import com.devbrowser.devtools.cdp.jsonParams
import com.devbrowser.engine.BrowserEngine
import com.devbrowser.engine.CdpEndpoint
import com.devbrowser.engine.NativeEvent
import com.devbrowser.engine.WebViewEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
    private var attachedEngine: BrowserEngine? = null
    private var eventJob: Job? = null
    private var nativeEventJob: Job? = null
    private var stateJob: Job? = null

    private val _status = MutableStateFlow(Status.Detached)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _diagnostics = MutableStateFlow<String?>(null)
    val diagnostics: StateFlow<String?> = _diagnostics.asStateFlow()

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
        attachedEngine = engine
        // Always wire the native console fallback first so messages flow even
        // if CDP fails to connect.
        nativeEventJob = scope.launch {
            engine.nativeEvents.collect { onNativeEvent(it) }
        }

        // Discovery may race with WebView socket creation — retry a few times.
        _status.value = Status.Connecting
        _diagnostics.value = "Looking for WebView CDP socket…"
        val endpoint = withTimeoutOrNull(4_000) {
            var ep: CdpEndpoint?
            while (true) {
                ep = engine.cdpEndpoint()
                if (ep is CdpEndpoint.UnixSocket) return@withTimeoutOrNull ep
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") null
        } as? CdpEndpoint.UnixSocket
        if (endpoint == null) {
            _status.value = Status.Unsupported
            val report = (engine as? WebViewEngine)?.lastProbeReport?.value.orEmpty()
            _diagnostics.value = "CDP unreachable (${report.size} probes). Shim active."
            // Re-populate panels every time the page finishes loading a new URL.
            stateJob = scope.launch {
                var lastUrl = ""
                var lastLoading = true
                engine.state.collect { s ->
                    val justFinished = lastLoading && !s.isLoading
                    val urlChanged = s.url != lastUrl
                    if (justFinished || (urlChanged && !s.isLoading)) {
                        shimInitialRefresh()
                    }
                    lastUrl = s.url
                    lastLoading = s.isLoading
                }
            }
            shimInitialRefresh()
            return
        }
        _diagnostics.value = "Connecting to ${endpoint.name}…"
        val client = CdpClient(endpoint.name)
        cdp = client
        val ok = client.connectToFirstPage()
        if (!ok) {
            _status.value = Status.Failed
            _diagnostics.value = "CDP handshake failed on ${endpoint.name}. Native console fallback is active."
            return
        }
        _status.value = Status.Connected
        _diagnostics.value = "CDP connected via ${endpoint.name}"

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
            // Some WebView builds defer console events until this is called.
            client.fire("Runtime.runIfWaitingForDebugger")
        }
        loadDom()
        refreshOriginAndStorage()
    }

    suspend fun detach() {
        eventJob?.cancel()
        eventJob = null
        nativeEventJob?.cancel()
        nativeEventJob = null
        stateJob?.cancel()
        stateJob = null
        cdp?.close()
        cdp = null
        attachedEngine = null
        _status.value = Status.Detached
        _diagnostics.value = null
    }

    private fun onNativeEvent(event: NativeEvent) {
        when (event) {
            is NativeEvent.NetworkCall -> {
                // When CDP's Network domain is delivering events, don't
                // double-count the shim view. Without CDP, the shim is the
                // sole source for the Network panel.
                if (_status.value == Status.Connected) return
                when (event.phase) {
                    NativeEvent.NetworkCall.Phase.Started ->
                        network.onShimStart(event.callId, event.transport, event.method, event.url)
                    NativeEvent.NetworkCall.Phase.Completed ->
                        network.onShimEnd(event.callId, event.status, event.durationMs)
                    NativeEvent.NetworkCall.Phase.Failed ->
                        network.onShimFail(event.callId, event.errorText)
                }
            }
            is NativeEvent.Console -> {
                // Picker callback message — handle then suppress from console.
                if (event.text.startsWith("__pick__:")) {
                    val parts = event.text.removePrefix("__pick__:").split(":", limit = 2)
                    val nodeId = parts.getOrNull(0)?.toLongOrNull()
                    if (nodeId != null && nodeId > 0) {
                        scope.launch {
                            selectNode(nodeId)
                            elements.setPicking(false)
                        }
                    } else {
                        scope.launch { elements.setPicking(false) }
                    }
                    return
                }
                // Avoid duplicates: when CDP is delivering Runtime.consoleAPICalled
                // already, skip the WebChromeClient mirror.
                if (_status.value == Status.Connected) return
                val level = when (event.level) {
                    NativeEvent.Console.Level.Error -> ConsoleEntry.Level.Error
                    NativeEvent.Console.Level.Warn -> ConsoleEntry.Level.Warn
                    NativeEvent.Console.Level.Info -> ConsoleEntry.Level.Info
                    NativeEvent.Console.Level.Debug -> ConsoleEntry.Level.Debug
                    NativeEvent.Console.Level.Verbose -> ConsoleEntry.Level.Debug
                    NativeEvent.Console.Level.Log -> ConsoleEntry.Level.Log
                }
                console.appendNative(level, event.text, event.sourceId, event.lineNumber)
            }
            is NativeEvent.NavigationError -> {
                console.appendNative(
                    ConsoleEntry.Level.Error,
                    "navigation failed (${event.code}): ${event.description} — ${event.url}",
                    null, null,
                )
            }
            is NativeEvent.HttpError -> {
                console.appendNative(
                    ConsoleEntry.Level.Warn,
                    "HTTP ${event.statusCode} ${event.description} — ${event.url}",
                    event.url, null,
                )
            }
            is NativeEvent.SslError -> {
                console.appendNative(
                    ConsoleEntry.Level.Error,
                    "SSL error: ${event.description} — ${event.url}",
                    event.url, null,
                )
            }
        }
    }

    fun clearAll() {
        console.clear()
        network.clear()
    }

    // ─── Console / Eval ───

    suspend fun evaluate(expression: String): String? {
        // CDP path: rich result (preview, exception details). Preferred when available.
        val client = cdp
        if (client != null) {
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
        // Native fallback: wrap the expression so we capture both value and errors.
        // The wrap: try { JSON.stringify(<expr>) } catch(e) { '__err__:' + e.toString() }
        val engine = attachedEngine ?: return "(no engine attached)".also { console.appendEvalResult(it) }
        val wrapped = "(function(){try{var v=eval(${jsStringLiteral(expression)});" +
            "if (v===undefined) return 'undefined';" +
            "if (v===null) return 'null';" +
            "if (typeof v==='function') return v.toString();" +
            "try { return JSON.stringify(v); } catch(e) { return String(v); }" +
            "}catch(e){return '__err__:'+(e.stack||e.toString());}})()"
        val raw = engine.evaluateJs(wrapped) ?: "(no result)"
        val text = try {
            // evaluateJavascript returns the result JSON-encoded. Strip outer quotes if string.
            val s = raw
            if (s.startsWith("\"__err__:")) {
                val err = jsonCodec.decodeFromString<String>(s).removePrefix("__err__:")
                console.appendNative(ConsoleEntry.Level.Error, err, null, null)
                err
            } else {
                // evaluateJavascript wraps strings in quotes; un-quote for nicer display
                if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
                    jsonCodec.decodeFromString<String>(s)
                } else s
            }
        } catch (e: Exception) { raw }
        console.appendEvalResult(text)
        return text
    }

    /** Run all shim-side panel population in parallel. */
    private suspend fun shimInitialRefresh() {
        runCatching { loadDom() }
        runCatching { refreshScripts() }
        runCatching { refreshOriginAndStorage() }
        runCatching { refreshPerformanceMetrics() }
    }

    /** Trigger an on-demand DOM health snapshot via the JS shim. */
    suspend fun snapshotPage() {
        val engine = attachedEngine ?: return
        engine.evaluateJs(
            "(typeof __devbrowser_snapshot === 'function') ? __devbrowser_snapshot('manual') : 'shim-missing'"
        )
    }

    private fun jsStringLiteral(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
        return "'$escaped'"
    }

    private val jsonCodec = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── Elements ───

    suspend fun loadDom() {
        val client = cdp
        if (client != null) {
            val resp = runCatching {
                client.send("DOM.getDocument", jsonParams {
                    put("depth", 2)
                    put("pierce", true)
                })
            }.getOrNull() ?: return
            val rootObj = resp.result?.get("root")?.jsonObject ?: return
            elements.setRoot(parseDomNode(rootObj))
            return
        }
        // Shim fallback
        val engine = attachedEngine ?: return
        val raw = engine.evaluateJs("JSON.stringify(__devbrowser_dom_get(8))") ?: return
        val unquoted = if (raw.startsWith("\"")) {
            runCatching { jsonCodec.decodeFromString<String>(raw) }.getOrDefault(raw)
        } else raw
        val obj = runCatching { jsonCodec.parseToJsonElement(unquoted).jsonObject }.getOrNull() ?: return
        elements.setRoot(parseDomNode(obj))
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
        val client = cdp
        if (client != null) {
            runCatching {
                val resp = client.send("CSS.getComputedStyleForNode", jsonParams { put("nodeId", nodeId) })
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
            return
        }
        // Shim fallback
        val engine = attachedEngine ?: return
        engine.evaluateJs("__devbrowser_highlight($nodeId)")
        val raw = engine.evaluateJs("JSON.stringify(__devbrowser_computed_style($nodeId))") ?: return
        val unquoted = if (raw.startsWith("\"")) {
            runCatching { jsonCodec.decodeFromString<String>(raw) }.getOrDefault(raw)
        } else raw
        val arr = runCatching { jsonCodec.parseToJsonElement(unquoted).jsonArray }.getOrNull() ?: return
        val styles = arr.map { e ->
            val o = e.jsonObject
            (o["name"]?.jsonPrimitive?.content.orEmpty()) to
                (o["value"]?.jsonPrimitive?.content.orEmpty())
        }
        elements.setComputedStyle(styles)
    }

    suspend fun setAttribute(nodeId: Long, name: String, value: String) {
        val client = cdp
        if (client != null) {
            runCatching {
                client.send("DOM.setAttributeValue", jsonParams {
                    put("nodeId", nodeId)
                    put("name", name)
                    put("value", value)
                })
            }
            return
        }
        attachedEngine?.evaluateJs(
            "__devbrowser_set_attr($nodeId, ${jsStringLiteral(name)}, ${jsStringLiteral(value)})"
        )
        loadDom()
    }

    suspend fun setPickMode(enabled: Boolean) {
        val client = cdp
        elements.setPicking(enabled)
        if (client != null) {
            runCatching {
                client.send("Overlay.setInspectMode", jsonParams {
                    put("mode", if (enabled) "searchForNode" else "none")
                    put("highlightConfig", buildJsonObject {
                        put("showInfo", true)
                        put("contentColor", colorOf(0x90, 0xCD, 0xF4, 0.6f))
                    })
                })
            }
            return
        }
        // Shim fallback — install an in-page touch picker.
        val engine = attachedEngine ?: return
        engine.evaluateJs(if (enabled) "__devbrowser_pick_start()" else "__devbrowser_pick_stop()")
    }

    private fun colorOf(r: Int, g: Int, b: Int, a: Float): JsonObject = buildJsonObject {
        put("r", r); put("g", g); put("b", b); put("a", a)
    }

    // ─── Sources ───

    suspend fun loadScriptSource(scriptId: String) {
        val client = cdp
        if (client != null) {
            val resp = runCatching {
                client.send("Debugger.getScriptSource", jsonParams { put("scriptId", scriptId) })
            }.getOrNull() ?: return
            val src = resp.result?.get("scriptSource")?.jsonPrimitive?.content
            sources.selectScript(scriptId, src)
            return
        }
        // Shim fallback: inline script content via the bridge, external via sync XHR.
        val engine = attachedEngine ?: return
        val inlineRaw = engine.evaluateJs(
            "__devbrowser_get_script_source(${jsStringLiteral(scriptId)})"
        )
        if (inlineRaw != null && inlineRaw != "null") {
            val unquoted = if (inlineRaw.startsWith("\"")) {
                runCatching { jsonCodec.decodeFromString<String>(inlineRaw) }.getOrDefault(inlineRaw)
            } else inlineRaw
            sources.selectScript(scriptId, unquoted)
            return
        }
        val script = sources.scripts.value.firstOrNull { it.scriptId == scriptId }
        val url = script?.url
        if (url == null || url.startsWith("inline-")) {
            sources.selectScript(scriptId, "(no source available)")
            return
        }
        // Sync XHR. Deprecated for production but fine for a debug capture.
        val js = "(function(){try{var x=new XMLHttpRequest();" +
            "x.open('GET',${jsStringLiteral(url)},false);x.send();return x.responseText;" +
            "}catch(e){return '__err__:'+e;}})()"
        val raw = engine.evaluateJs(js) ?: "(fetch failed)"
        val text = if (raw.startsWith("\"")) {
            runCatching { jsonCodec.decodeFromString<String>(raw) }.getOrDefault(raw)
        } else raw
        sources.selectScript(scriptId, text)
    }

    suspend fun pause() { runCatching { cdp?.fire("Debugger.pause") } }
    suspend fun resume() { runCatching { cdp?.fire("Debugger.resume") } }
    suspend fun stepOver() { runCatching { cdp?.fire("Debugger.stepOver") } }
    suspend fun stepInto() { runCatching { cdp?.fire("Debugger.stepInto") } }
    suspend fun stepOut() { runCatching { cdp?.fire("Debugger.stepOut") } }

    // ─── Performance ───

    suspend fun refreshPerformanceMetrics() {
        val client = cdp
        val list: List<PerformanceMetric>
        if (client != null) {
            val resp = runCatching { client.send("Performance.getMetrics") }.getOrNull() ?: return
            val arr = resp.result?.get("metrics")?.jsonArray ?: return
            list = arr.map {
                val o = it.jsonObject
                PerformanceMetric(
                    name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                    value = o["value"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            }
        } else {
            val engine = attachedEngine ?: return
            val raw = engine.evaluateJs("JSON.stringify(__devbrowser_get_perf())") ?: return
            val unquoted = if (raw.startsWith("\"")) {
                runCatching { jsonCodec.decodeFromString<String>(raw) }.getOrDefault(raw)
            } else raw
            val arr = runCatching { jsonCodec.parseToJsonElement(unquoted).jsonArray }.getOrNull() ?: return
            list = arr.map {
                val o = it.jsonObject
                PerformanceMetric(
                    name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                    value = o["value"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            }
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
        val client = cdp
        if (client != null) {
            runCatching { client.fire("HeapProfiler.collectGarbage") }
        } else {
            // Best-effort: window.gc is exposed if Chromium is started with
            // --js-flags="--expose-gc". Usually not the case in WebView, but
            // we can at least try to drop references.
            attachedEngine?.evaluateJs("(typeof gc === 'function') ? (gc(), 'ok') : 'unavailable'")
        }
        refreshPerformanceMetrics()
    }

    suspend fun takeHeapSnapshot() {
        memory.setSampling(true)
        val client = cdp
        if (client != null) {
            runCatching {
                client.send("HeapProfiler.takeHeapSnapshot", jsonParams {
                    put("reportProgress", false)
                    put("captureNumericValue", false)
                })
            }
        }
        memory.setSampling(false)
        refreshPerformanceMetrics()
        // Without CDP we can't get a true heap snapshot — record the
        // current heap-used value as a sampled checkpoint instead.
        val used = memory.jsHeapUsedBytes.value ?: 0
        memory.appendSnapshot(used)
    }

    // ─── Application / Storage ───

    suspend fun refreshOriginAndStorage() {
        val client = cdp
        if (client != null) {
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
            return
        }
        // Shim fallback — one call returns origin + cookies + both storages.
        val engine = attachedEngine ?: return
        val raw = engine.evaluateJs("JSON.stringify(__devbrowser_get_storage())") ?: return
        val unquoted = if (raw.startsWith("\"")) {
            runCatching { jsonCodec.decodeFromString<String>(raw) }.getOrDefault(raw)
        } else raw
        val obj = runCatching { jsonCodec.parseToJsonElement(unquoted).jsonObject }.getOrNull() ?: return
        application.setOrigin(obj["origin"]?.jsonPrimitive?.content)
        application.setCookies(
            (obj["cookies"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())).map { c ->
                val o = c.jsonObject
                Cookie(
                    name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                    value = o["value"]?.jsonPrimitive?.content.orEmpty(),
                    domain = o["domain"]?.jsonPrimitive?.content.orEmpty(),
                    path = o["path"]?.jsonPrimitive?.content.orEmpty(),
                    expires = null,
                    secure = o["secure"]?.jsonPrimitive?.content == "true",
                    httpOnly = false,
                    sameSite = o["sameSite"]?.jsonPrimitive?.content,
                )
            }
        )
        fun parseItems(arr: kotlinx.serialization.json.JsonArray?): List<StorageItem> =
            arr.orEmpty().map { i ->
                val o = i.jsonObject
                StorageItem(
                    key = o["key"]?.jsonPrimitive?.content.orEmpty(),
                    value = o["value"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
        application.setLocalStorage(parseItems(obj["localStorage"]?.jsonArray))
        application.setSessionStorage(parseItems(obj["sessionStorage"]?.jsonArray))
    }

    suspend fun loadCookies() = refreshOriginAndStorage()

    suspend fun loadDomStorage(origin: String) = refreshOriginAndStorage()

    suspend fun deleteCookie(name: String, domain: String) {
        val client = cdp
        if (client != null) {
            runCatching {
                client.send("Network.deleteCookies", jsonParams {
                    put("name", name)
                    put("domain", domain)
                })
            }
        } else {
            attachedEngine?.evaluateJs("__devbrowser_delete_cookie(${jsStringLiteral(name)})")
        }
        refreshOriginAndStorage()
    }

    // ─── Sources ───

    /** Populate the script list from the page via the shim when CDP is off. */
    suspend fun refreshScripts() {
        if (cdp != null) return // CDP fires Debugger.scriptParsed events
        val engine = attachedEngine ?: return
        val raw = engine.evaluateJs("JSON.stringify(__devbrowser_get_scripts())") ?: return
        val unquoted = if (raw.startsWith("\"")) {
            runCatching { jsonCodec.decodeFromString<String>(raw) }.getOrDefault(raw)
        } else raw
        val arr = runCatching { jsonCodec.parseToJsonElement(unquoted).jsonArray }.getOrNull() ?: return
        val list = arr.map {
            val o = it.jsonObject
            ParsedScript(
                scriptId = o["scriptId"]?.jsonPrimitive?.content.orEmpty(),
                url = o["url"]?.jsonPrimitive?.content.orEmpty(),
                isModule = o["isModule"]?.jsonPrimitive?.content == "true",
                length = o["length"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
        sources.replaceScripts(list)
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
