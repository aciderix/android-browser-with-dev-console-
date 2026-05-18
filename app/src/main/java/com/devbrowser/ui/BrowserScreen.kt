package com.devbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun BrowserScreen(vm: BrowserViewModel) {
    val tabs by vm.tabs.collectAsState()
    val activeId by vm.activeTabId.collectAsState()
    val devOpen by vm.devToolsOpen.collectAsState()
    val active = tabs.firstOrNull { it.id == activeId }

    Column(modifier = Modifier.fillMaxSize()) {
        if (active != null) {
            val state by active.engine.state.collectAsState()
            UrlBar(
                url = state.url,
                isLoading = state.isLoading,
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                onNavigate = vm::navigate,
                onBack = { active.engine.goBack() },
                onForward = { active.engine.goForward() },
                onReload = { active.engine.reload() },
                onToggleDevTools = { vm.toggleDevTools() },
            )
            if (state.isLoading && state.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (active != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> active.engine.createView(ctx).also { active.engine.loadUrl(active.initialUrl) } },
                )
            }
            if (devOpen) {
                DevToolsSheet(
                    controller = vm.devTools,
                    onClose = { vm.toggleDevTools() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun UrlBar(
    url: String,
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onToggleDevTools: () -> Unit,
) {
    var text by remember(url) { mutableStateOf(url) }
    val focus = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(Icons.Default.ArrowForward, contentDescription = "Forward")
        }
        IconButton(onClick = onReload) {
            Icon(Icons.Default.Refresh, contentDescription = "Reload")
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                focus.clearFocus()
                onNavigate(text)
            }),
        )
        IconButton(onClick = onToggleDevTools) {
            Icon(Icons.Default.Code, contentDescription = "DevTools")
        }
    }
    HorizontalDivider()
}
