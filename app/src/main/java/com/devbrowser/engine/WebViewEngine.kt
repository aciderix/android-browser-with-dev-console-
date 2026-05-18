package com.devbrowser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * WebView-backed engine. Debugging is enabled application-wide; the Chromium
 * runtime exposes a CDP endpoint over an abstract UNIX socket named
 * `webview_devtools_remote_<pid>`. We dial that socket from inside the app.
 */
class WebViewEngine : BrowserEngine {

    private var webView: WebView? = null
    private val _state = MutableStateFlow(EngineState())
    override val state = _state.asStateFlow()

    @SuppressLint("SetJavaScriptEnabled")
    override fun createView(context: Context): View {
        val wv = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = userAgentString.replace("; wv", "")
            }
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
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    _state.value = _state.value.copy(progress = newProgress)
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    _state.value = _state.value.copy(title = title.orEmpty())
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

    override suspend fun cdpEndpoint(): CdpEndpoint {
        // Chromium WebView exposes its devtools on an abstract UNIX socket.
        // The name is webview_devtools_remote_<pid> for the embedding process.
        val pid = Process.myPid()
        return CdpEndpoint.UnixSocket("webview_devtools_remote_$pid")
    }

    override suspend fun evaluateJs(script: String): String? =
        suspendCancellableCoroutine { cont ->
            val wv = webView ?: run { cont.resume(null); return@suspendCancellableCoroutine }
            wv.post {
                wv.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            }
        }
}
