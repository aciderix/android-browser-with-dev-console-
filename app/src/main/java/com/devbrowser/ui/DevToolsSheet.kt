package com.devbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbrowser.devtools.DevToolsController
import com.devbrowser.ui.panels.ApplicationPanel
import com.devbrowser.ui.panels.ConsolePanel
import com.devbrowser.ui.panels.ElementsPanel
import com.devbrowser.ui.panels.MemoryPanel
import com.devbrowser.ui.panels.NetworkPanel
import com.devbrowser.ui.panels.PerformancePanel
import com.devbrowser.ui.panels.SourcesPanel
import kotlin.math.roundToInt

enum class Panel(val label: String) {
    Console("Console"),
    Network("Network"),
    Elements("Elements"),
    Sources("Sources"),
    Performance("Perf"),
    Memory("Memory"),
    Application("App"),
}

@Composable
fun DevToolsSheet(
    controller: DevToolsController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }
    val minHeight = screenHeightPx * 0.25f
    val maxHeight = screenHeightPx * 0.92f
    var heightPx by remember { mutableFloatStateOf(screenHeightPx * 0.5f) }
    val heightDp = with(LocalDensity.current) { heightPx.toDp() }

    var selected by remember { mutableStateOf(Panel.Console) }
    val status by controller.status.collectAsState()
    val diagnostics by controller.diagnostics.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        heightPx = (heightPx - dragAmount).coerceIn(minHeight, maxHeight)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            )
        }

        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DevTools",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = when (status) {
                    DevToolsController.Status.Connected -> "●"
                    DevToolsController.Status.Connecting -> "◌"
                    DevToolsController.Status.Failed -> "✕"
                    DevToolsController.Status.Unsupported -> "?"
                    DevToolsController.Status.Detached -> "○"
                },
                color = when (status) {
                    DevToolsController.Status.Connected -> Color(0xFF4ADE80)
                    DevToolsController.Status.Connecting -> Color(0xFFFACC15)
                    DevToolsController.Status.Failed -> Color(0xFFF87171)
                    else -> MaterialTheme.colorScheme.outline
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Box(modifier = Modifier.weight(1f))
            IconButton(onClick = {
                heightPx = if (heightPx < maxHeight * 0.95f) maxHeight else minHeight
            }) {
                Icon(
                    if (heightPx >= maxHeight * 0.95f) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                    contentDescription = "Expand/Collapse",
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        diagnostics?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        ScrollableTabRow(
            selectedTabIndex = selected.ordinal,
            edgePadding = 0.dp,
            divider = {},
        ) {
            Panel.entries.forEach { p ->
                Tab(
                    selected = p == selected,
                    onClick = { selected = p },
                    text = { Text(p.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        HorizontalDivider()

        Box(modifier = Modifier.fillMaxSize()) {
            when (selected) {
                Panel.Console -> ConsolePanel(controller)
                Panel.Network -> NetworkPanel(controller)
                Panel.Elements -> ElementsPanel(controller)
                Panel.Sources -> SourcesPanel(controller)
                Panel.Performance -> PerformancePanel(controller)
                Panel.Memory -> MemoryPanel(controller)
                Panel.Application -> ApplicationPanel(controller)
            }
        }
    }
}
