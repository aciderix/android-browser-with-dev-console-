package com.devbrowser.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devbrowser.devtools.DevToolsController
import com.devbrowser.engine.BrowserEngine
import com.devbrowser.engine.EngineKind
import com.devbrowser.engine.GeckoEngine
import com.devbrowser.engine.WebViewEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    val devTools = DevToolsController(viewModelScope.coroutineContext.let {
        kotlinx.coroutines.CoroutineScope(it)
    })

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _devToolsOpen = MutableStateFlow(false)
    val devToolsOpen: StateFlow<Boolean> = _devToolsOpen.asStateFlow()

    init {
        newTab(EngineKind.WebView, "https://m.wikipedia.org/")
        // Re-attach DevTools when the active tab changes (and DevTools is open).
        viewModelScope.launch {
            combine(_activeTabId, _devToolsOpen) { id, open -> id to open }
                .distinctUntilChanged()
                .collect { (id, open) ->
                    val engine = _tabs.value.firstOrNull { it.id == id }?.engine
                    if (open && engine != null) {
                        devTools.attach(engine)
                    } else if (!open) {
                        devTools.detach()
                    }
                }
        }
    }

    fun newTab(kind: EngineKind, initialUrl: String) {
        val engine: BrowserEngine = when (kind) {
            EngineKind.WebView -> WebViewEngine()
            EngineKind.Gecko -> GeckoEngine(getApplication())
        }
        val tab = BrowserTab(
            id = java.util.UUID.randomUUID().toString(),
            engineKind = kind,
            engine = engine,
            initialUrl = initialUrl,
        )
        _tabs.update { it + tab }
        _activeTabId.value = tab.id
    }

    fun selectTab(id: String) { _activeTabId.value = id }

    fun closeTab(id: String) {
        val list = _tabs.value
        list.firstOrNull { it.id == id }?.engine?.destroy()
        _tabs.update { it.filterNot { t -> t.id == id } }
        if (_activeTabId.value == id) {
            _activeTabId.value = _tabs.value.firstOrNull()?.id
        }
    }

    fun activeTab(): BrowserTab? = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun navigate(url: String) {
        val target = normalizeUrl(url)
        activeTab()?.engine?.loadUrl(target)
    }

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "about:blank"
        if (trimmed.contains("://")) return trimmed
        if (trimmed.contains(".") && !trimmed.contains(" ")) return "https://$trimmed"
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }

    fun toggleDevTools() {
        _devToolsOpen.value = !_devToolsOpen.value
    }

    fun onBackPressed(): Boolean {
        if (_devToolsOpen.value) { _devToolsOpen.value = false; return true }
        val engine = activeTab()?.engine ?: return false
        return engine.goBack()
    }

    override fun onCleared() {
        super.onCleared()
        _tabs.value.forEach { it.engine.destroy() }
    }
}

data class BrowserTab(
    val id: String,
    val engineKind: EngineKind,
    val engine: BrowserEngine,
    val initialUrl: String,
)
