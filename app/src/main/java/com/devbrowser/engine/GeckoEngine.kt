package com.devbrowser.engine

import android.content.Context
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Placeholder GeckoView engine. The full implementation lives at the
 * commented-out import sites and uses org.mozilla.geckoview.* — the dependency
 * has been removed from app/build.gradle.kts to keep the APK under GitHub's
 * 100 MB file size limit. To re-enable Gecko:
 *
 *   1. Uncomment the geckoview implementation line in app/build.gradle.kts.
 *   2. Replace this file with the version under git history that constructs
 *      GeckoRuntime + GeckoSession + GeckoView.
 *   3. Finish FirefoxRdpBridge so DevTools can attach.
 */
class GeckoEngine(@Suppress("UNUSED_PARAMETER") appContext: Context) : BrowserEngine {

    private val _state = MutableStateFlow(EngineState(url = "gecko://disabled"))
    override val state = _state.asStateFlow()

    override val nativeEvents = MutableSharedFlow<NativeEvent>().asSharedFlow()

    override fun createView(context: Context): View = TextView(context).apply {
        text = "GeckoView engine is disabled in this build.\n\n" +
            "Enable the geckoview dependency in app/build.gradle.kts to use it.\n" +
            "The DevTools RDP→CDP bridge is also not yet implemented."
        textSize = 14f
        setPadding(48, 48, 48, 48)
    }

    override fun loadUrl(url: String) = Unit
    override fun reload() = Unit
    override fun goBack(): Boolean = false
    override fun goForward(): Boolean = false
    override fun stop() = Unit
    override fun destroy() = Unit
    override suspend fun cdpEndpoint(): CdpEndpoint? = null
    override suspend fun evaluateJs(script: String): String? = null
}
