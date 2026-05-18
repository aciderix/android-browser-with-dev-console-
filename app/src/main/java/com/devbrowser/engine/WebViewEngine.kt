package com.devbrowser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * WebView-backed engine. Debugging is enabled application-wide via
 * WebView.setWebContentsDebuggingEnabled. The Chromium runtime exposes a CDP
 * endpoint over an abstract UNIX socket; we discover its real name by scanning
 * /proc/net/unix because the historical "webview_devtools_remote_<pid>" pattern
 * isn't guaranteed by modern WebView releases.
 */
class WebViewEngine : BrowserEngine {

    private var webView: WebView? = null
    private val _state = MutableStateFlow(EngineState())
    override val state = _state.asStateFlow()

    private val _nativeEvents = MutableSharedFlow<NativeEvent>(
        replay = 32,
        extraBufferCapacity = 256,
    )
    override val nativeEvents = _nativeEvents.asSharedFlow()

    @SuppressLint("SetJavaScriptEnabled")
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
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // Default UA contains "; wv" which some sites use to block WebView.
                userAgentString = userAgentString.replace("; wv", "")
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    _state.value = _state.value.copy(
                        url = url,
                        isLoading = true,
                        progress = 0,
                        canGoBack = view.canGoBack(),
                        canGoForward = view.canGoForward(),
                    )
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
                    // Block by default — same as Chrome's default behaviour.
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
                    // Auto-grant by default for DevTools usefulness on dev hosts.
                    request.grant(request.resources)
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback,
                ) {
                    callback.invoke(origin, true, false)
                }

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
                    // Many sites trigger a popup that we redirect into the same WebView so
                    // navigation isn't lost. Production code would open a new tab here.
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

    override suspend fun cdpEndpoint(): CdpEndpoint? {
        // Scan the kernel's UNIX socket table for the WebView devtools socket.
        // The historical naming pattern is webview_devtools_remote_<pid> but
        // modern releases sometimes append the package name or a salt.
        val candidates = findWebViewSocketNames()
        val pidSuffix = "_${android.os.Process.myPid()}"
        val preferred = candidates.firstOrNull { it.endsWith(pidSuffix) }
            ?: candidates.firstOrNull()
            ?: return null
        return CdpEndpoint.UnixSocket(preferred)
    }

    private fun findWebViewSocketNames(): List<String> = runCatching {
        File("/proc/net/unix").useLines { lines ->
            lines.mapNotNull { line ->
                val name = line.trim().split(Regex("\\s+")).lastOrNull() ?: return@mapNotNull null
                // Abstract namespace sockets are prefixed with '@' in /proc/net/unix.
                if (name.startsWith("@webview_devtools_remote")) name.substring(1) else null
            }.toList().distinct()
        }
    }.getOrDefault(emptyList())

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
}
