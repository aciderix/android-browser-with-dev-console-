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
        /**
         * Pre-page JS shim. Acts as a CDP substitute when the real CDP socket
         * is unreachable (every Samsung WebView build the author has seen).
         * Hooks:
         *   - console.{log,info,warn,error,debug,trace}
         *   - window 'error' + 'unhandledrejection'
         *   - fetch / XMLHttpRequest / WebSocket  → Network mirror
         *   - 3 health checks at +1s/+3s/+8s reporting DOM root state
         *
         * All callbacks post to native via __devbrowser_dbg, which is a
         * top-level public class registered as a JavascriptInterface so
         * Android's reflection always finds the methods.
         */
        private val DBG_SHIM_JS = """
        (function(){
          if (window.__devbrowser_shim_installed) return;
          window.__devbrowser_shim_installed = true;
          var origin = location.href;
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
          function emit(level, text){
            try { var b = bridge(); if (b) b.post(level, text, location.href, -1); } catch(e){}
          }

          // ── console.* hooks ─────────────────────────────────────────────
          ['log','info','warn','error','debug','trace'].forEach(function(name){
            var orig = console[name] ? console[name].bind(console) : function(){};
            console[name] = function(){
              var args = Array.prototype.slice.call(arguments);
              emit(name, args.map(stringify).join(' '));
              return orig.apply(console, args);
            };
          });

          // ── global error / promise hooks ────────────────────────────────
          window.addEventListener('error', function(ev){
            var b = bridge(); if (!b) return;
            var msg;
            if (ev.error && ev.error.stack) msg = ev.error.stack;
            else if (ev.target && (ev.target.tagName === 'SCRIPT' || ev.target.tagName === 'LINK' || ev.target.tagName === 'IMG')) {
              msg = 'Resource load FAILED: <' + ev.target.tagName.toLowerCase() + '> ' + (ev.target.src || ev.target.href || '');
            } else {
              msg = (ev.message||'error') + ' at ' + (ev.filename||'?') + ':' + (ev.lineno||0);
            }
            try { b.post('error', msg, ev.filename || location.href, ev.lineno|0); } catch(e){}
          }, true);
          window.addEventListener('unhandledrejection', function(ev){
            emit('error', 'Unhandled rejection: ' + stringify(ev.reason));
          }, true);

          // Helpers for the per-call bridge.
          var __nextId = 1;
          function nextId(){ return 'c' + (__nextId++); }
          function netStart(id, transport, method, url){
            try { var b = bridge(); if (b) b.netStart(id, transport, method, url); } catch(e){}
          }
          function netEnd(id, transport, method, url, status, dt){
            try { var b = bridge(); if (b) b.netEnd(id, transport, method, url, status|0, dt|0); } catch(e){}
          }
          function netFail(id, transport, method, url, msg){
            try { var b = bridge(); if (b) b.netFail(id, transport, method, url, msg || ''); } catch(e){}
          }

          // ── fetch hook ──────────────────────────────────────────────────
          if (window.fetch) {
            var _fetch = window.fetch.bind(window);
            window.fetch = function(input, init){
              var url = (typeof input === 'string') ? input :
                        (input && input.url) ? input.url : String(input);
              var method = (init && init.method) || (input && input.method) || 'GET';
              var t0 = Date.now();
              var id = nextId();
              netStart(id, 'fetch', method, url);
              emit('debug', '↗ fetch ' + method + ' ' + url);
              return _fetch(input, init).then(function(resp){
                var dt = Date.now()-t0;
                netEnd(id, 'fetch', method, url, resp.status, dt);
                emit(resp.ok ? 'debug' : 'warn',
                  '↙ fetch ' + resp.status + ' ' + url + ' (' + dt + 'ms)');
                return resp;
              }, function(err){
                netFail(id, 'fetch', method, url, (err && err.message) || String(err));
                emit('error', '✗ fetch FAILED ' + url + ': ' + (err && err.message));
                throw err;
              });
            };
          }

          // ── XMLHttpRequest hook ─────────────────────────────────────────
          if (window.XMLHttpRequest) {
            var XHR = window.XMLHttpRequest;
            var _open = XHR.prototype.open;
            var _send = XHR.prototype.send;
            XHR.prototype.open = function(method, url){
              this.__dbg_meta = { method: method, url: url, t0: 0, id: nextId() };
              return _open.apply(this, arguments);
            };
            XHR.prototype.send = function(){
              var m = this.__dbg_meta || {};
              m.t0 = Date.now();
              var self = this;
              netStart(m.id, 'xhr', m.method||'GET', m.url||'');
              emit('debug', '↗ xhr ' + (m.method||'GET') + ' ' + (m.url||''));
              this.addEventListener('load', function(){
                var dt = Date.now()-m.t0;
                netEnd(m.id, 'xhr', m.method||'GET', m.url||'', self.status, dt);
                emit(self.status >= 400 ? 'warn' : 'debug',
                  '↙ xhr ' + self.status + ' ' + (m.url||'') + ' (' + dt + 'ms)');
              });
              this.addEventListener('error', function(){
                netFail(m.id, 'xhr', m.method||'GET', m.url||'', 'network error');
                emit('error', '✗ xhr FAILED ' + (m.url||''));
              });
              this.addEventListener('timeout', function(){
                netFail(m.id, 'xhr', m.method||'GET', m.url||'', 'timeout');
                emit('error', '⏱ xhr TIMEOUT ' + (m.url||''));
              });
              return _send.apply(this, arguments);
            };
          }

          // ── WebSocket hook ──────────────────────────────────────────────
          if (window.WebSocket) {
            var _WS = window.WebSocket;
            var WSWrapper = function(url, protocols){
              var id = nextId();
              netStart(id, 'ws', 'WS', url);
              emit('debug', '↗ ws connect ' + url);
              var t0 = Date.now();
              var ws = protocols !== undefined ? new _WS(url, protocols) : new _WS(url);
              ws.addEventListener('open', function(){
                netEnd(id, 'ws', 'WS', url, 101, Date.now()-t0);
                emit('info', '✓ ws open ' + url);
              });
              ws.addEventListener('close', function(ev){
                netEnd(id, 'ws', 'WS', url, ev.code, Date.now()-t0);
                emit('debug', '✕ ws close ' + url + ' (code=' + ev.code + ' reason="' + (ev.reason||'') + '")');
              });
              ws.addEventListener('error', function(){
                netFail(id, 'ws', 'WS', url, 'ws error');
                emit('error', '✗ ws error ' + url);
              });
              return ws;
            };
            try {
              WSWrapper.prototype = _WS.prototype;
              WSWrapper.CONNECTING = _WS.CONNECTING;
              WSWrapper.OPEN = _WS.OPEN;
              WSWrapper.CLOSING = _WS.CLOSING;
              WSWrapper.CLOSED = _WS.CLOSED;
              Object.defineProperty(window, 'WebSocket',
                { value: WSWrapper, writable: true, configurable: true });
            } catch(e){ emit('warn', 'WebSocket hook install failed: ' + e); }
          }

          // ── Viewport / layout compat patch ──────────────────────────────
          // Tailwind h-screen (height: 100vh) — and 100dvh — sometimes
          // evaluate to 0 inside the Compose AndroidView+WebView stack on
          // Samsung's Chromium build, even though window.innerHeight is the
          // correct value. That collapses #root, and overflow:hidden makes
          // the SPA invisible. Two-step universal fix:
          //   (a) Force html / body to an explicit pixel height (re-applied
          //       on resize, orientationchange, and at T+0/100/500ms to
          //       catch late layout) so percent-based heights chain works.
          //   (b) Inject CSS that overrides common SPA mount points to
          //       min-height: 100% (which is now the explicit body pixel
          //       value, NOT 100vh which is broken).
          try {
            var style = document.createElement('style');
            style.setAttribute('data-devbrowser','viewport-fix');
            style.textContent =
              '#root,#app,#main,#__next,#__nuxt{min-height:100% !important;}' +
              // Tailwind viewport-relative height utilities: rewrite to %
              // because 100vh (and dvh/lvh/svh) currently evaluate to 0 in
              // this WebView. body now has a real pixel height (see below)
              // so 100% chains correctly through any descendant.
              '.h-screen,.h-dvh,.h-lvh,.h-svh{height:100% !important;}' +
              '.min-h-screen,.min-h-dvh,.min-h-lvh,.min-h-svh{min-height:100% !important;}' +
              '.max-h-screen,.max-h-dvh,.max-h-lvh,.max-h-svh{max-height:100% !important;}';
            function attachStyle(){
              if (style.parentNode) return;
              if (document.head) document.head.insertBefore(style, document.head.firstChild);
              else if (document.documentElement) document.documentElement.appendChild(style);
            }
            attachStyle();
            if (!document.head) {
              var obs = new MutationObserver(function(){
                attachStyle();
                if (document.head) obs.disconnect();
              });
              obs.observe(document.documentElement, {childList: true, subtree: true});
            }

            function fixViewport(){
              var h = window.innerHeight || document.documentElement.clientHeight || 0;
              if (!h) return;
              try {
                document.documentElement.style.height = h + 'px';
                document.documentElement.style.minHeight = h + 'px';
              } catch(e){}
              if (document.body) {
                try {
                  document.body.style.height = h + 'px';
                  document.body.style.minHeight = h + 'px';
                } catch(e){}
              }
            }
            fixViewport();
            window.addEventListener('resize', fixViewport);
            window.addEventListener('orientationchange', fixViewport);
            setTimeout(fixViewport, 100);
            setTimeout(fixViewport, 500);
            setTimeout(fixViewport, 1500);
          } catch(e){ emit('warn', 'viewport-fix injection failed: ' + e); }

          // ── DOM health checks ───────────────────────────────────────────
          function clean(s){ return (s||'').replace(/\s+/g, ' ').trim(); }
          function snapshot(label){
            try {
              var roots = ['root','app','main','__next','__nuxt'];
              var found = null;
              for (var i = 0; i < roots.length; i++) {
                var el = document.getElementById(roots[i]);
                if (el) { found = { id: roots[i], el: el }; break; }
              }
              if (!found && document.body && document.body.children.length > 0) {
                found = { id: document.body.children[0].tagName.toLowerCase(), el: document.body.children[0] };
              }
              var body = document.body;
              var htmlH = document.documentElement ? getComputedStyle(document.documentElement).height : '?';
              var bodyH = body ? getComputedStyle(body).height : '?';
              var info = label + ': readyState=' + document.readyState +
                ', title="' + document.title + '"' +
                ', viewport=' + window.innerWidth + 'x' + window.innerHeight +
                ', htmlHeight=' + htmlH + ', bodyHeight=' + bodyH +
                ', bodyChildren=' + (body ? body.children.length : 0) +
                ', bodyTextLen=' + (body ? (body.innerText||'').length : 0) +
                ', buttons=' + document.querySelectorAll('button,[role=button]').length +
                ', inputs=' + document.querySelectorAll('input,select,textarea').length +
                ', forms=' + document.querySelectorAll('form').length;
              if (found) {
                info += '\nmount[#' + found.id + ']{children=' + found.el.children.length +
                  ', innerHTML.length=' + found.el.innerHTML.length + '}';
                var bcr = found.el.getBoundingClientRect();
                info += '\n  bbox=' + Math.round(bcr.width) + 'x' + Math.round(bcr.height) +
                  ' at (' + Math.round(bcr.left) + ',' + Math.round(bcr.top) + ')';
                var cs = window.getComputedStyle(found.el);
                info += '\n  computed{display=' + cs.display + ', visibility=' + cs.visibility +
                  ', opacity=' + cs.opacity + ', color=' + cs.color + ', bg=' + cs.backgroundColor + '}';
                var txt = clean(found.el.innerText);
                if (txt) info += '\n  innerText[0..400]="' + txt.substring(0, 400) + '"';
                else info += '\n  innerText=(empty)';
                var html = clean(found.el.innerHTML);
                info += '\n  innerHTML[0..500]="' + html.substring(0, 500) + (html.length > 500 ? '…' : '') + '"';
                // Walk first 5 visible children and report basics.
                var kids = Array.prototype.slice.call(found.el.querySelectorAll('*')).slice(0, 8);
                kids.forEach(function(k, idx){
                  var r = k.getBoundingClientRect();
                  var sc = window.getComputedStyle(k);
                  info += '\n  [' + idx + '] <' + k.tagName.toLowerCase() +
                    (k.id ? ' id=' + k.id : '') +
                    (k.className && typeof k.className === 'string' ? ' class="' + k.className.substring(0,40) + '"' : '') +
                    '> ' + Math.round(r.width) + 'x' + Math.round(r.height) +
                    ' d=' + sc.display + ' v=' + sc.visibility + ' op=' + sc.opacity;
                });
              } else {
                info += '\nNO MOUNT POINT FOUND';
              }
              emit('info', info);
            } catch(e){ emit('error', 'snapshot failed: ' + e); }
          }
          setTimeout(function(){ snapshot('⏱ T+1s'); }, 1000);
          setTimeout(function(){ snapshot('⏱ T+3s'); }, 3000);
          setTimeout(function(){ snapshot('⏱ T+8s'); }, 8000);
        })();
        """.trimIndent()
    }
}
