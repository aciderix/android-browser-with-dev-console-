package com.devbrowser.engine

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over WebView / GeckoView so DevTools can talk to either.
 * Each engine exposes the same observable state and a CDP endpoint that the
 * devtools client can connect to.
 */
interface BrowserEngine {
    val state: StateFlow<EngineState>

    /**
     * Native-side console/navigation events. Available regardless of whether
     * the CDP bridge is connected — for WebView this is fed by
     * WebChromeClient.onConsoleMessage and WebViewClient errors.
     */
    val nativeEvents: SharedFlow<NativeEvent>

    fun createView(context: Context): View
    fun loadUrl(url: String)
    fun reload()
    fun goBack(): Boolean
    fun goForward(): Boolean
    fun stop()
    fun destroy()

    /** Returns the local CDP endpoint (UNIX socket name or ws://host:port). */
    suspend fun cdpEndpoint(): CdpEndpoint?

    /** Executes JS in the page (best-effort, returns serialized result). */
    suspend fun evaluateJs(script: String): String?
}

sealed class NativeEvent {
    data class Console(
        val level: Level,
        val text: String,
        val sourceId: String?,
        val lineNumber: Int?,
    ) : NativeEvent() {
        enum class Level { Verbose, Debug, Info, Log, Warn, Error }
    }

    data class NavigationError(
        val url: String,
        val code: Int,
        val description: String,
        val isMainFrame: Boolean,
    ) : NativeEvent()

    data class SslError(
        val url: String,
        val description: String,
    ) : NativeEvent()

    data class HttpError(
        val url: String,
        val statusCode: Int,
        val description: String,
    ) : NativeEvent()
}

data class EngineState(
    val url: String = "about:blank",
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
)

sealed class CdpEndpoint {
    /** Local UNIX abstract socket — used by WebView devtools. */
    data class UnixSocket(val name: String) : CdpEndpoint()

    /** Standard WebSocket endpoint — used by GeckoView / generic. */
    data class WebSocketUrl(val url: String) : CdpEndpoint()
}

enum class EngineKind { WebView, Gecko }
