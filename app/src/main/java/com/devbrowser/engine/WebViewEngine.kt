package com.devbrowser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume

/**
 * WebView-backed engine.
 *
 * Console capture is layered three times so messages reach DevTools even on
 * tightened Android builds:
 *
 *   1. CDP (Chrome DevTools Protocol) over the WebView's abstract UNIX
 *      socket. Native to Chromium; richest signal (heap, network, debugger).
 *   2. WebChromeClient.onConsoleMessage. Always-on Java callback that fires
 *      for every console.* call. Works without CDP.
 *   3. Pre-page JS shim (DBG_SHIM_JS) injected at navigation start via
 *      WebViewCompat addDocumentStartJavaScript. Wraps console.* and global
 *      error/unhandledrejection, posts strings back through a JS interface.
 *      This catches messages that fire BEFORE either of the other layers
 *      attaches, and serves as the final fallback if both fail.
 *
 * CDP socket discovery uses probe-based connection rather than scanning
 * /proc/net/unix because Android 10+ SELinux policy filters that file for
 * untrusted_app and returns no useful info.
 */
class WebViewEngine : BrowserEngine {

    private var webView: WebView? = null
    private val _state = MutableStateFlow(EngineState())
    override val state = _state.asStateFlow()

    private val _nativeEvents = MutableSharedFlow<NativeEvent>(
        replay = 64,
        extraBufferCapacity = 512,
    )
    override val nativeEvents = _nativeEvents.asSharedFlow()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun createView(context: Context): View {
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val wv = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                // Echo-7 (and many self-hosted dev backends) load via HTTPS but talk
                // to an HTTP backend — we always allow because this is a dev browser.
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Default UA contains "; wv" which some sites use to block WebView.
                userAgentString = userAgentString.replace("; wv", "")
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            // JS bridge for the pre-page shim. Must be a top-level public class
            // for Android's reflection-based JS bridge to find the @JavascriptInterface
            // methods on every WebView build.
            addJavascriptInterface(DbgBridge(_nativeEvents), "__devbrowser_dbg")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, DBG_SHIM_JS, setOf("*"))
            }

            webViewClient = object : WebViewClient() {
                private var pageStartMs: Long = 0L
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    pageStartMs = System.currentTimeMillis()
                    _state.value = _state.value.copy(
                        url = url,
                        isLoading = true,
                        progress = 0,
                        canGoBack = view.canGoBack(),
                        canGoForward = view.canGoForward(),
                    )
                    _nativeEvents.tryEmit(
                        NativeEvent.Console(
                            NativeEvent.Console.Level.Info,
                            "→ Loading: $url",
                            url, null,
                        )
                    )
                    // On older webview builds without DOCUMENT_START_SCRIPT, inject
                    // the shim manually as soon as the document begins parsing.
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        view.evaluateJavascript(DBG_SHIM_JS, null)
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    _state.value = _state.value.copy(
                        url = url,
                        isLoading = false,
                        progress = 100,
                        title = view.title.orEmpty(),
                        canGoBack = view.canGoBack(),
                        canGoForward = view.canGoForward(),
                    )
                    val dt = if (pageStartMs > 0) (System.currentTimeMillis() - pageStartMs) else 0L
                    _nativeEvents.tryEmit(
                        NativeEvent.Console(
                            NativeEvent.Console.Level.Info,
                            "✓ Loaded: $url (${dt} ms)",
                            url, null,
                        )
                    )
                    // Self-test: confirm the JS bridge is actually exposed.
                    view.evaluateJavascript(
                        "(typeof window.__devbrowser_dbg !== 'undefined') ? 'true:' + window.__devbrowser_dbg.ping() : 'false'"
                    ) { result ->
                        val ok = result?.contains("true:pong") == true
                        _nativeEvents.tryEmit(
                            NativeEvent.Console(
                                if (ok) NativeEvent.Console.Level.Debug else NativeEvent.Console.Level.Error,
                                if (ok) "JS shim bridge online (window.__devbrowser_dbg.ping() → pong)"
                                else "JS shim bridge NOT exposed (result=$result). Console capture limited.",
                                null, null,
                            )
                        )
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = false

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    _nativeEvents.tryEmit(
                        NativeEvent.NavigationError(
                            url = request.url.toString(),
                            code = error.errorCode,
                            description = error.description.toString(),
                            isMainFrame = request.isForMainFrame,
                        )
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame) {
                        _nativeEvents.tryEmit(
                            NativeEvent.HttpError(
                                url = request.url.toString(),
                                statusCode = errorResponse.statusCode,
                                description = errorResponse.reasonPhrase.orEmpty(),
                            )
                        )
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    _nativeEvents.tryEmit(
                        NativeEvent.SslError(
                            url = error.url ?: view.url.orEmpty(),
                            description = sslErrorMessage(error),
                        )
                    )
                    handler.cancel()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    _state.value = _state.value.copy(progress = newProgress)
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    _state.value = _state.value.copy(title = title.orEmpty())
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val level = when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.TIP -> NativeEvent.Console.Level.Info
                        ConsoleMessage.MessageLevel.LOG -> NativeEvent.Console.Level.Log
                        ConsoleMessage.MessageLevel.WARNING -> NativeEvent.Console.Level.Warn
                        ConsoleMessage.MessageLevel.ERROR -> NativeEvent.Console.Level.Error
                        ConsoleMessage.MessageLevel.DEBUG -> NativeEvent.Console.Level.Debug
                        else -> NativeEvent.Console.Level.Log
                    }
                    _nativeEvents.tryEmit(
                        NativeEvent.Console(
                            level = level,
                            text = consoleMessage.message().orEmpty(),
                            sourceId = consoleMessage.sourceId(),
                            lineNumber = consoleMessage.lineNumber(),
                        )
                    )
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback,
                ) { callback.invoke(origin, true, false) }

                override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                    _nativeEvents.tryEmit(
                        NativeEvent.Console(
                            NativeEvent.Console.Level.Warn,
                            "alert(): ${message.orEmpty()}",
                            url, null,
                        )
                    )
                    result.confirm()
                    return true
                }

                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message,
                ): Boolean {
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = view
                    resultMsg.sendToTarget()
                    return true
                }
            }
        }
        webView = wv
        return wv
    }

    override fun loadUrl(url: String) { webView?.loadUrl(url) }
    override fun reload() { webView?.reload() }
    override fun goBack(): Boolean =
        webView?.let { if (it.canGoBack()) { it.goBack(); true } else false } ?: false
    override fun goForward(): Boolean =
        webView?.let { if (it.canGoForward()) { it.goForward(); true } else false } ?: false
    override fun stop() { webView?.stopLoading() }
    override fun destroy() {
        webView?.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
        webView = null
    }

    val lastProbeReport = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    override suspend fun cdpEndpoint(): CdpEndpoint? = withContext(Dispatchers.IO) {
        val pid = android.os.Process.myPid()
        val pkg = webView?.context?.packageName.orEmpty()
        val candidates = buildList {
            add("webview_devtools_remote_$pid")
            if (pkg.isNotEmpty()) {
                add("webview_devtools_remote_$pkg")
                add("${pkg}_devtools_remote")
            }
            add("webview_devtools_remote")
            add("chrome_devtools_remote")
        }
        val report = mutableListOf<Pair<String, String>>()
        var hit: String? = null
        for (name in candidates) {
            val r = probeWithReason(name)
            report += name to r
            if (r == "ok") { hit = name; break }
        }
        lastProbeReport.value = report
        if (hit != null) CdpEndpoint.UnixSocket(hit) else null
    }

    /** Like [probeDevToolsSocket] but returns a human-readable reason. */
    private fun probeWithReason(name: String): String = runCatching {
        val socket = LocalSocket()
        socket.use {
            it.soTimeout = 700
            try {
                it.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            } catch (e: Exception) {
                return@use "no-listener (${e.javaClass.simpleName})"
            }
            val out = it.outputStream
            out.write(
                "GET /json/version HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .toByteArray()
            )
            out.flush()
            val reader = BufferedReader(InputStreamReader(it.inputStream))
            val statusLine = reader.readLine().orEmpty()
            if (statusLine.contains("200")) "ok" else "bad-status:$statusLine"
        }
    }.getOrElse { "err:${it.javaClass.simpleName}" }


    override suspend fun evaluateJs(script: String): String? =
        suspendCancellableCoroutine { cont ->
            val wv = webView ?: run { cont.resume(null); return@suspendCancellableCoroutine }
            wv.post {
                wv.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            }
        }

    private fun sslErrorMessage(error: SslError): String = buildString {
        if (error.hasError(SslError.SSL_DATE_INVALID)) append("date invalid; ")
        if (error.hasError(SslError.SSL_EXPIRED)) append("expired; ")
        if (error.hasError(SslError.SSL_IDMISMATCH)) append("hostname mismatch; ")
        if (error.hasError(SslError.SSL_NOTYETVALID)) append("not yet valid; ")
        if (error.hasError(SslError.SSL_UNTRUSTED)) append("untrusted issuer; ")
        if (error.hasError(SslError.SSL_INVALID)) append("invalid; ")
    }.ifEmpty { "ssl error" }

    companion object {
        // Pre-page JS shim that wraps console.* and global error/unhandledrejection,
        // forwarding everything to native via __devbrowser_dbg.post(level,text,src,line).
        // Stays passive if the bridge is missing (e.g. before injection).
        private val DBG_SHIM_JS = """
        (function(){
          if (window.__devbrowser_shim_installed) return;
          window.__devbrowser_shim_installed = true;
          function bridge(){ return window.__devbrowser_dbg; }
          function stringify(arg){
            try {
              if (arg === null) return 'null';
              if (arg === undefined) return 'undefined';
              if (arg instanceof Error) return (arg.stack || (arg.name + ': ' + arg.message));
              if (typeof arg === 'object') {
                try { return JSON.stringify(arg); }
                catch(e){ return Object.prototype.toString.call(arg); }
              }
              return String(arg);
            } catch(e){ return '[unserialisable]'; }
          }
          ['log','info','warn','error','debug','trace'].forEach(function(name){
            var orig = console[name] ? console[name].bind(console) : function(){};
            console[name] = function(){
              var args = Array.prototype.slice.call(arguments);
              var text = args.map(stringify).join(' ');
              try { var b = bridge(); if (b) b.post(name, text, location.href, -1); } catch(e){}
              return orig.apply(console, args);
            };
          });
          window.addEventListener('error', function(ev){
            try {
              var b = bridge();
              if (b) {
                var msg = (ev.error && ev.error.stack) ? ev.error.stack
                    : (ev.message + ' at ' + (ev.filename||'?') + ':' + (ev.lineno||0));
                b.post('error', msg, ev.filename || location.href, ev.lineno|0);
              }
            } catch(e){}
          }, true);
          window.addEventListener('unhandledrejection', function(ev){
            try {
              var b = bridge();
              if (b) b.post('error', 'Unhandled rejection: ' + stringify(ev.reason),
                            location.href, -1);
            } catch(e){}
          }, true);
        })();
        """.trimIndent()
    }
}
