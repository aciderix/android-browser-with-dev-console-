package com.devbrowser.engine

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * GeckoView-backed engine. Uses Firefox's Remote Debugging Protocol over a
 * TCP socket. The DevTools client adapts the protocol; for now we surface the
 * endpoint and basic navigation. Remote debugging server is enabled via
 * GeckoRuntimeSettings.aboutConfigEnabled + devtools.debugger.remote-enabled.
 */
class GeckoEngine(appContext: Context) : BrowserEngine {

    private val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(true)
            .remoteDebuggingEnabled(true)
            .consoleOutput(true)
            .build()
        GeckoRuntime.create(appContext.applicationContext, settings)
    }

    private val session: GeckoSession = GeckoSession()
    private var view: GeckoView? = null

    private val _state = MutableStateFlow(EngineState())
    override val state = _state.asStateFlow()

    init {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                _state.value = _state.value.copy(url = url.orEmpty())
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                _state.value = _state.value.copy(canGoBack = canGoBack)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                _state.value = _state.value.copy(canGoForward = canGoForward)
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                _state.value = _state.value.copy(url = url, isLoading = true, progress = 0)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _state.value = _state.value.copy(isLoading = false, progress = 100)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                _state.value = _state.value.copy(progress = progress)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                _state.value = _state.value.copy(title = title.orEmpty())
            }
        }
    }

    override fun createView(context: Context): View {
        val v = GeckoView(context)
        if (!session.isOpen) session.open(runtime)
        v.setSession(session)
        view = v
        return v
    }

    override fun loadUrl(url: String) { session.loadUri(url) }
    override fun reload() { session.reload() }
    override fun goBack(): Boolean { session.goBack(); return true }
    override fun goForward(): Boolean { session.goForward(); return true }
    override fun stop() { session.stop() }
    override fun destroy() {
        if (session.isOpen) session.close()
    }

    override suspend fun cdpEndpoint(): CdpEndpoint? {
        // Gecko's remote debugger listens on a TCP port (default 6000). We
        // would translate Firefox RDP <-> CDP in a separate adapter layer.
        // Returning null until that adapter is wired up.
        return null
    }

    override suspend fun evaluateJs(script: String): String? = null
}
