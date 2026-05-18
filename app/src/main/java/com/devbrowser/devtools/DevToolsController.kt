package com.devbrowser.devtools

import com.devbrowser.devtools.cdp.CdpClient
import com.devbrowser.devtools.cdp.jsonParams
import com.devbrowser.devtools.cdp.stringOrNull
import com.devbrowser.engine.BrowserEngine
import com.devbrowser.engine.CdpEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

        // Enable all the protocol domains we listen to.
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
        }
    }

    suspend fun detach() {
        eventJob?.cancel()
        eventJob = null
        cdp?.close()
        cdp = null
        _status.value = Status.Detached
    }

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
        val exception = result["exceptionDetails"]
        if (exception != null) {
            // surface to console as error
            console.appendException(exception.jsonObject)
        }
        return result["result"]?.let { remoteObjectToText(it) }
            ?.also { console.appendEvalResult(it) }
    }

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
            else -> Unit
        }
    }

    fun clearAll() {
        console.clear()
        network.clear()
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
