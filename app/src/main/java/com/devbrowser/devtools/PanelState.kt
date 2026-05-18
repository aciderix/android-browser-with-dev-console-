package com.devbrowser.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    private fun headersOf(element: JsonElement?): Map<String, String> {
        val obj = element?.jsonObject ?: return emptyMap()
        return obj.mapValues { it.value.jsonPrimitive.content }
    }
}

// ─────────── Elements ───────────

data class DomNode(
    val nodeId: Long,
    val nodeType: Int,
    val nodeName: String,
    val localName: String? = null,
    val nodeValue: String? = null,
    val attributes: List<Pair<String, String>> = emptyList(),
    val children: List<DomNode> = emptyList(),
    val childNodeCount: Int = 0,
)

class ElementsState {
    private val _root = MutableStateFlow<DomNode?>(null)
    val root: StateFlow<DomNode?> = _root.asStateFlow()

    private val _selectedNodeId = MutableStateFlow<Long?>(null)
    val selectedNodeId: StateFlow<Long?> = _selectedNodeId.asStateFlow()

    private val _expanded = MutableStateFlow<Set<Long>>(emptySet())
    val expanded: StateFlow<Set<Long>> = _expanded.asStateFlow()

    private val _computedStyle = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val computedStyle: StateFlow<List<Pair<String, String>>> = _computedStyle.asStateFlow()

    private val _isPicking = MutableStateFlow(false)
    val isPicking: StateFlow<Boolean> = _isPicking.asStateFlow()

    fun setRoot(node: DomNode?) { _root.value = node }
    fun select(id: Long?) { _selectedNodeId.value = id }
    fun toggleExpanded(id: Long) {
        _expanded.update { if (id in it) it - id else it + id }
    }
    fun markExpanded(id: Long) { _expanded.update { it + id } }
    fun setComputedStyle(styles: List<Pair<String, String>>) { _computedStyle.value = styles }
    fun setPicking(value: Boolean) { _isPicking.value = value }

    fun replaceChildren(parentId: Long, children: List<DomNode>) {
        _root.update { it?.let { r -> r.withChildren(parentId, children) } }
    }

    fun updateAttributes(nodeId: Long, attrs: List<Pair<String, String>>) {
        _root.update { it?.let { r -> r.withAttributes(nodeId, attrs) } }
    }
}

private fun DomNode.withChildren(targetId: Long, newChildren: List<DomNode>): DomNode {
    if (nodeId == targetId) return copy(children = newChildren, childNodeCount = newChildren.size)
    if (children.isEmpty()) return this
    return copy(children = children.map { it.withChildren(targetId, newChildren) })
}

private fun DomNode.withAttributes(targetId: Long, attrs: List<Pair<String, String>>): DomNode {
    if (nodeId == targetId) return copy(attributes = attrs)
    if (children.isEmpty()) return this
    return copy(children = children.map { it.withAttributes(targetId, attrs) })
}

fun parseDomNode(obj: JsonObject): DomNode {
    val attrsList = obj["attributes"]?.jsonArray.orEmpty()
    val attrPairs = buildList<Pair<String, String>> {
        var i = 0
        while (i + 1 < attrsList.size) {
            add(attrsList[i].jsonPrimitive.content to attrsList[i + 1].jsonPrimitive.content)
            i += 2
        }
    }
    val childrenArr = obj["children"]?.jsonArray
    val children = childrenArr?.map { parseDomNode(it.jsonObject) }.orEmpty()
    return DomNode(
        nodeId = obj["nodeId"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1,
        nodeType = obj["nodeType"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
        nodeName = obj["nodeName"]?.jsonPrimitive?.content.orEmpty(),
        localName = obj["localName"]?.jsonPrimitive?.content,
        nodeValue = obj["nodeValue"]?.jsonPrimitive?.content,
        attributes = attrPairs,
        children = children,
        childNodeCount = obj["childNodeCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: children.size,
    )
}

private val JsonArray?.orEmpty: JsonArray get() = this ?: JsonArray(emptyList())

// ─────────── Sources ───────────

data class ParsedScript(
    val scriptId: String,
    val url: String,
    val isModule: Boolean = false,
    val length: Int = 0,
)

class SourcesState {
    private val _scripts = MutableStateFlow<List<ParsedScript>>(emptyList())
    val scripts: StateFlow<List<ParsedScript>> = _scripts.asStateFlow()

    private val _selectedScriptId = MutableStateFlow<String?>(null)
    val selectedScriptId: StateFlow<String?> = _selectedScriptId.asStateFlow()

    private val _selectedSource = MutableStateFlow<String?>(null)
    val selectedSource: StateFlow<String?> = _selectedSource.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _pauseReason = MutableStateFlow<String?>(null)
    val pauseReason: StateFlow<String?> = _pauseReason.asStateFlow()

    fun onScriptParsed(params: JsonObject) {
        val id = params["scriptId"]?.jsonPrimitive?.content ?: return
        val url = params["url"]?.jsonPrimitive?.content.orEmpty()
        if (url.isEmpty()) return // skip anonymous evals for the list
        val len = params["length"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val isModule = params["isModule"]?.jsonPrimitive?.content == "true"
        _scripts.update { list ->
            if (list.any { it.scriptId == id }) list
            else list + ParsedScript(id, url, isModule, len)
        }
    }

    fun selectScript(id: String?, source: String? = null) {
        _selectedScriptId.value = id
        _selectedSource.value = source
    }

    fun onPaused(params: JsonObject) {
        _isPaused.value = true
        _pauseReason.value = params["reason"]?.jsonPrimitive?.content
    }
    fun onResumed() {
        _isPaused.value = false
        _pauseReason.value = null
    }

    fun clear() {
        _scripts.value = emptyList()
        _selectedScriptId.value = null
        _selectedSource.value = null
    }
}

// ─────────── Performance ───────────

data class PerformanceMetric(val name: String, val value: Double)

class PerformanceState {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _metrics = MutableStateFlow<List<PerformanceMetric>>(emptyList())
    val metrics: StateFlow<List<PerformanceMetric>> = _metrics.asStateFlow()

    private val _traceEventCount = MutableStateFlow(0)
    val traceEventCount: StateFlow<Int> = _traceEventCount.asStateFlow()

    private val _traceCategoryCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val traceCategoryCounts: StateFlow<Map<String, Int>> = _traceCategoryCounts.asStateFlow()

    fun setRecording(value: Boolean) { _isRecording.value = value }

    fun setMetrics(values: List<PerformanceMetric>) { _metrics.value = values }

    fun onTraceData(params: JsonObject) {
        val events = params["value"]?.jsonArray ?: return
        _traceEventCount.update { it + events.size }
        val incCounts = mutableMapOf<String, Int>()
        events.forEach { ev ->
            val cat = ev.jsonObject["cat"]?.jsonPrimitive?.content ?: "other"
            incCounts[cat] = (incCounts[cat] ?: 0) + 1
        }
        _traceCategoryCounts.update { current ->
            val merged = current.toMutableMap()
            incCounts.forEach { (k, v) -> merged[k] = (merged[k] ?: 0) + v }
            merged
        }
    }

    fun reset() {
        _traceEventCount.value = 0
        _traceCategoryCounts.value = emptyMap()
    }
}

// ─────────── Memory ───────────

data class HeapSnapshot(
    val takenAt: Long,
    val sizeBytes: Long,
)

class MemoryState {
    private val _jsHeapUsedBytes = MutableStateFlow<Long?>(null)
    val jsHeapUsedBytes: StateFlow<Long?> = _jsHeapUsedBytes.asStateFlow()

    private val _jsHeapTotalBytes = MutableStateFlow<Long?>(null)
    val jsHeapTotalBytes: StateFlow<Long?> = _jsHeapTotalBytes.asStateFlow()

    private val _domNodeCount = MutableStateFlow<Long?>(null)
    val domNodeCount: StateFlow<Long?> = _domNodeCount.asStateFlow()

    private val _snapshots = MutableStateFlow<List<HeapSnapshot>>(emptyList())
    val snapshots: StateFlow<List<HeapSnapshot>> = _snapshots.asStateFlow()

    private val _isSampling = MutableStateFlow(false)
    val isSampling: StateFlow<Boolean> = _isSampling.asStateFlow()

    fun setMetrics(used: Long?, total: Long?, nodes: Long?) {
        if (used != null) _jsHeapUsedBytes.value = used
        if (total != null) _jsHeapTotalBytes.value = total
        if (nodes != null) _domNodeCount.value = nodes
    }

    fun appendSnapshot(sizeBytes: Long) {
        _snapshots.update { it + HeapSnapshot(System.currentTimeMillis(), sizeBytes) }
    }

    fun setSampling(value: Boolean) { _isSampling.value = value }
}

// ─────────── Application ───────────

data class Cookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expires: Double? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val sameSite: String? = null,
)

data class StorageItem(val key: String, val value: String)

data class StorageOrigin(
    val origin: String,
    val local: List<StorageItem> = emptyList(),
    val session: List<StorageItem> = emptyList(),
)

class ApplicationState {
    private val _cookies = MutableStateFlow<List<Cookie>>(emptyList())
    val cookies: StateFlow<List<Cookie>> = _cookies.asStateFlow()

    private val _localStorage = MutableStateFlow<List<StorageItem>>(emptyList())
    val localStorage: StateFlow<List<StorageItem>> = _localStorage.asStateFlow()

    private val _sessionStorage = MutableStateFlow<List<StorageItem>>(emptyList())
    val sessionStorage: StateFlow<List<StorageItem>> = _sessionStorage.asStateFlow()

    private val _origin = MutableStateFlow<String?>(null)
    val origin: StateFlow<String?> = _origin.asStateFlow()

    fun setOrigin(origin: String?) { _origin.value = origin }

    fun setCookies(list: List<Cookie>) { _cookies.value = list }
    fun setLocalStorage(items: List<StorageItem>) { _localStorage.value = items }
    fun setSessionStorage(items: List<StorageItem>) { _sessionStorage.value = items }
}
