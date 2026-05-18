package com.devbrowser.ui.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devbrowser.devtools.DevToolsController

@Composable fun ElementsPanel(controller: DevToolsController) = Placeholder(
    "Elements",
    "DOM tree, attribute editor and computed styles. Wires onto DOM.* and CSS.* domains — coming in next iteration."
)

@Composable fun SourcesPanel(controller: DevToolsController) = Placeholder(
    "Sources",
    "Script list and inline debugger. Wires onto Debugger.* domain — coming next."
)

@Composable fun PerformancePanel(controller: DevToolsController) = Placeholder(
    "Performance",
    "Tracing recorder (Tracing.start / Tracing.dataCollected) — coming next."
)

@Composable fun MemoryPanel(controller: DevToolsController) = Placeholder(
    "Memory",
    "Heap snapshots via HeapProfiler.takeHeapSnapshot — coming next."
)

@Composable fun ApplicationPanel(controller: DevToolsController) = Placeholder(
    "Application",
    "Storage (Cookies / LocalStorage / IndexedDB / Service Workers) via Storage.* and ServiceWorker.* — coming next."
)

@Composable
private fun Placeholder(title: String, body: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
