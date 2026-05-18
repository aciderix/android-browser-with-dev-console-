package com.devbrowser.devtools.cdp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * Talks Chrome DevTools Protocol to a single target. Exposes:
 *   - request/response correlation by id (via [send])
 *   - a hot flow of unsolicited events ([events])
 *   - lifecycle ([state])
 *
 * Discovery (listing targets) is done by [discoverTargets] using HTTP over
 * the same UNIX socket — Chromium serves /json/list with the JSON list of
 * pages and workers.
 */
class CdpClient(private val socketName: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1)
    private val pending = mutableMapOf<Long, Channel<CdpResponse>>()
    private val pendingLock = Any()
    private var ws: DevToolsWebSocket? = null
    private var readerJob: Job? = null

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CdpEvent>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val events: SharedFlow<CdpEvent> = _events.asSharedFlow()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    enum class ConnectionState { Disconnected, Connecting, Connected, Failed }

    data class CdpEvent(val method: String, val params: JsonObject, val sessionId: String?)

    /** Lists pages exposed by the WebView devtools server. */
    suspend fun discoverTargets(): List<CdpTarget> = withContext(Dispatchers.IO) {
        val body = LocalSocketTransport(socketName).httpGet("/json/list")
        json.decodeFromString<List<CdpTarget>>(body)
    }

    /**
     * Connects to the first page-like target. Returns true on success.
     * Times out after ~5s if the devtools server is slow.
     */
    suspend fun connectToFirstPage(): Boolean = withContext(Dispatchers.IO) {
        _state.value = ConnectionState.Connecting
        val result = withTimeoutOrNull(5_000) {
            val targets = runCatching { discoverTargets() }.getOrNull().orEmpty()
            val page = targets.firstOrNull { it.type == "page" } ?: return@withTimeoutOrNull false
            val wsUrl = page.webSocketDebuggerUrl ?: return@withTimeoutOrNull false
            // wsUrl looks like ws://localhost/devtools/page/<id> — we only need the path
            val path = extractPath(wsUrl) ?: return@withTimeoutOrNull false
            val socket = DevToolsWebSocket(socketName, path)
            runCatching { socket.connect() }.onFailure {
                _state.value = ConnectionState.Failed
                return@withTimeoutOrNull false
            }
            ws = socket
            readerJob = scope.launch { readLoop(socket) }
            _state.value = ConnectionState.Connected
            true
        }
        if (result != true) _state.value = ConnectionState.Failed
        result == true
    }

    private fun extractPath(wsUrl: String): String? {
        val m = Pattern.compile("ws://[^/]+(/.*)").matcher(wsUrl)
        return if (m.find()) m.group(1) else null
    }

    private suspend fun readLoop(socket: DevToolsWebSocket) {
        while (true) {
            val text = runCatching { socket.receiveText() }.getOrNull() ?: break
            val msg = runCatching { json.decodeFromString<CdpResponse>(text) }.getOrNull() ?: continue
            if (msg.id != null) {
                synchronized(pendingLock) { pending.remove(msg.id) }?.trySend(msg)
            } else if (msg.method != null) {
                _events.tryEmit(CdpEvent(msg.method, msg.params ?: JsonObject(emptyMap()), msg.sessionId))
            }
        }
        _state.value = ConnectionState.Disconnected
    }

    /** Sends a CDP request and awaits the response. */
    suspend fun send(method: String, params: JsonObject? = null, sessionId: String? = null): CdpResponse {
        val socket = ws ?: error("CDP not connected")
        val id = nextId.getAndIncrement()
        val channel = Channel<CdpResponse>(Channel.RENDEZVOUS)
        synchronized(pendingLock) { pending[id] = channel }
        val req = CdpRequest(id = id, method = method, params = params, sessionId = sessionId)
        val text = json.encodeToString(req)
        withContext(Dispatchers.IO) { socket.sendText(text) }
        return channel.receive()
    }

    /** Convenience: send and ignore response. */
    suspend fun fire(method: String, params: JsonObject? = null) {
        send(method, params)
    }

    fun close() {
        readerJob?.cancel()
        runCatching { ws?.close() }
        ws = null
        _state.value = ConnectionState.Disconnected
    }
}

fun jsonParams(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject(builder)

fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
