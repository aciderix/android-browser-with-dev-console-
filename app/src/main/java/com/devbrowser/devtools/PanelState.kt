package com.devbrowser.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ─────────── Console ───────────

data class ConsoleEntry(
    val id: Long,
    val level: Level,
    val text: String,
    val source: String? = null,
    val url: String? = null,
    val line: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Level { Log, Info, Warn, Error, Debug, EvalInput, EvalResult }
}

class ConsoleState {
    private var idGen = 0L
    private val _entries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val entries: StateFlow<List<ConsoleEntry>> = _entries.asStateFlow()

    fun clear() = _entries.update { emptyList() }

    fun appendInput(text: String) = add(ConsoleEntry(nextId(), ConsoleEntry.Level.EvalInput, text))
    fun appendEvalResult(text: String) =
        add(ConsoleEntry(nextId(), ConsoleEntry.Level.EvalResult, text))

    fun appendConsoleApi(params: JsonObject) {
        val type = params["type"]?.jsonPrimitive?.content ?: "log"
        val args = params["args"]?.jsonArray ?: JsonArray(emptyList())
        val text = args.joinToString(" ") { remoteObjectPreview(it.jsonObject) }
        val level = when (type) {
            "warning" -> ConsoleEntry.Level.Warn
            "error" -> ConsoleEntry.Level.Error
            "info" -> ConsoleEntry.Level.Info
            "debug" -> ConsoleEntry.Level.Debug
            else -> ConsoleEntry.Level.Log
        }
        add(ConsoleEntry(nextId(), level, text, source = "console"))
    }

    fun appendException(details: JsonObject) {
        val text = details["text"]?.jsonPrimitive?.content
            ?: details["exception"]?.jsonObject?.let { remoteObjectPreview(it) }
            ?: "Exception"
        val url = details["url"]?.jsonPrimitive?.content
        val line = details["lineNumber"]?.jsonPrimitive?.content?.toIntOrNull()
        add(ConsoleEntry(nextId(), ConsoleEntry.Level.Error, text, source = "exception", url = url, line = line))
    }

    fun appendLogEntry(entry: JsonObject) {
        val level = when (entry["level"]?.jsonPrimitive?.content) {
            "warning" -> ConsoleEntry.Level.Warn
            "error" -> ConsoleEntry.Level.Error
            "info" -> ConsoleEntry.Level.Info
            "verbose" -> ConsoleEntry.Level.Debug
            else -> ConsoleEntry.Level.Log
        }
        val text = entry["text"]?.jsonPrimitive?.content.orEmpty()
        val source = entry["source"]?.jsonPrimitive?.content
        val url = entry["url"]?.jsonPrimitive?.content
        val line = entry["lineNumber"]?.jsonPrimitive?.content?.toIntOrNull()
        add(ConsoleEntry(nextId(), level, text, source = source, url = url, line = line))
    }

    private fun add(entry: ConsoleEntry) {
        _entries.update { (it + entry).takeLast(MAX) }
    }

    private fun nextId(): Long = ++idGen

    companion object { private const val MAX = 5_000 }
}

private fun remoteObjectPreview(remote: JsonObject): String {
    val type = remote["type"]?.jsonPrimitive?.content
    val subtype = remote["subtype"]?.jsonPrimitive?.content
    val value = remote["value"]
    val description = remote["description"]?.jsonPrimitive?.content
    return when {
        type == "string" -> value?.jsonPrimitive?.content.orEmpty()
        subtype == "null" -> "null"
        type == "undefined" -> "undefined"
        value != null -> value.toString()
        description != null -> description
        else -> remote.toString()
    }
}

// ─────────── Network ───────────

data class NetworkRequest(
    val requestId: String,
    val url: String,
    val method: String,
    val resourceType: String? = null,
    val startMs: Double,
    val endMs: Double? = null,
    val status: Int? = null,
    val statusText: String? = null,
    val mimeType: String? = null,
    val responseSize: Long? = null,
    val failureText: String? = null,
    val fromCache: Boolean = false,
    val protocol: String? = null,
    val remoteIp: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
)

class NetworkState {
    private val _requests = MutableStateFlow<List<NetworkRequest>>(emptyList())
    val requests: StateFlow<List<NetworkRequest>> = _requests.asStateFlow()

    fun clear() = _requests.update { emptyList() }

    fun onRequestWillBeSent(params: JsonObject) {
        val id = params["requestId"]?.jsonPrimitive?.content ?: return
        val req = params["request"]?.jsonObject ?: return
        val ts = params["timestamp"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val entry = NetworkRequest(
            requestId = id,
            url = req["url"]?.jsonPrimitive?.content.orEmpty(),
            method = req["method"]?.jsonPrimitive?.content.orEmpty(),
            resourceType = params["type"]?.jsonPrimitive?.content,
            startMs = ts * 1000,
            requestHeaders = headersOf(req["headers"]),
        )
        _requests.update { it + entry }
    }

    fun onResponseReceived(params: JsonObject) {
        val id = params["requestId"]?.jsonPrimitive?.content ?: return
        val resp = params["response"]?.jsonObject ?: return
        update(id) { current ->
            current.copy(
                status = resp["status"]?.jsonPrimitive?.content?.toIntOrNull(),
                statusText = resp["statusText"]?.jsonPrimitive?.content,
                mimeType = resp["mimeType"]?.jsonPrimitive?.content,
                fromCache = resp["fromDiskCache"]?.jsonPrimitive?.content == "true",
                protocol = resp["protocol"]?.jsonPrimitive?.content,
                remoteIp = resp["remoteIPAddress"]?.jsonPrimitive?.content,
                responseHeaders = headersOf(resp["headers"]),
            )
        }
    }

    fun onLoadingFinished(params: JsonObject) {
        val id = params["requestId"]?.jsonPrimitive?.content ?: return
        val ts = params["timestamp"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val size = params["encodedDataLength"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
        update(id) { it.copy(endMs = ts * 1000, responseSize = size) }
    }

    fun onLoadingFailed(params: JsonObject) {
        val id = params["requestId"]?.jsonPrimitive?.content ?: return
        val ts = params["timestamp"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        update(id) { it.copy(endMs = ts * 1000, failureText = params["errorText"]?.jsonPrimitive?.content) }
    }

    private fun update(id: String, transform: (NetworkRequest) -> NetworkRequest) {
        _requests.update { list ->
            list.map { if (it.requestId == id) transform(it) else it }
        }
    }

    private fun headersOf(element: kotlinx.serialization.json.JsonElement?): Map<String, String> {
        val obj = element?.jsonObject ?: return emptyMap()
        return obj.mapValues { it.value.jsonPrimitive.content }
    }
}

// ─────────── Stubs for further panels ───────────

class ElementsState {
    private val _rootNodeId = MutableStateFlow<Long?>(null)
    val rootNodeId: StateFlow<Long?> = _rootNodeId.asStateFlow()
}

class SourcesState {
    private val _scripts = MutableStateFlow<List<String>>(emptyList())
    val scripts: StateFlow<List<String>> = _scripts.asStateFlow()
}

class PerformanceState {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
}

class MemoryState {
    private val _heapUsedBytes = MutableStateFlow<Long?>(null)
    val heapUsedBytes: StateFlow<Long?> = _heapUsedBytes.asStateFlow()
}

class ApplicationState {
    private val _cookies = MutableStateFlow<List<String>>(emptyList())
    val cookies: StateFlow<List<String>> = _cookies.asStateFlow()
}
