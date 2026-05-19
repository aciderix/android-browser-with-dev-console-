package com.devbrowser.engine

import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Top-level JavascriptInterface bridge. Must be a public top-level class for
 * Android's WebView reflection to reliably discover the @JavascriptInterface
 * methods — Kotlin inner private classes have inconsistent reflection
 * behaviour on some OEM WebView builds.
 *
 * The JS shim in WebViewEngine calls __devbrowser_dbg.post(level,text,src,line)
 * once per intercepted console.* call or window error.
 */
class DbgBridge(private val sink: MutableSharedFlow<NativeEvent>) {

    @JavascriptInterface
    fun post(level: String, text: String, source: String?, line: Int) {
        val lvl = when (level) {
            "error" -> NativeEvent.Console.Level.Error
            "warn" -> NativeEvent.Console.Level.Warn
            "info" -> NativeEvent.Console.Level.Info
            "debug" -> NativeEvent.Console.Level.Debug
            "verbose", "trace" -> NativeEvent.Console.Level.Verbose
            else -> NativeEvent.Console.Level.Log
        }
        sink.tryEmit(
            NativeEvent.Console(
                level = lvl,
                text = text,
                sourceId = source,
                lineNumber = if (line >= 0) line else null,
            )
        )
    }

    @JavascriptInterface
    fun ping(): String = "pong"

    @JavascriptInterface
    fun netStart(callId: String, transport: String, method: String, url: String) {
        sink.tryEmit(
            NativeEvent.NetworkCall(
                callId = callId, transport = transport, method = method, url = url,
                phase = NativeEvent.NetworkCall.Phase.Started,
            )
        )
    }

    @JavascriptInterface
    fun netEnd(callId: String, transport: String, method: String, url: String, status: Int, durationMs: Int) {
        sink.tryEmit(
            NativeEvent.NetworkCall(
                callId = callId, transport = transport, method = method, url = url,
                phase = NativeEvent.NetworkCall.Phase.Completed,
                status = status, durationMs = durationMs.toLong(),
            )
        )
    }

    @JavascriptInterface
    fun netFail(callId: String, transport: String, method: String, url: String, errorText: String?) {
        sink.tryEmit(
            NativeEvent.NetworkCall(
                callId = callId, transport = transport, method = method, url = url,
                phase = NativeEvent.NetworkCall.Phase.Failed,
                errorText = errorText,
            )
        )
    }
}
