package com.devbrowser.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbrowser.devtools.DevToolsController
import kotlinx.coroutines.launch

@Composable
fun SourcesPanel(controller: DevToolsController) {
    val scripts by controller.sources.scripts.collectAsState()
    val selectedId by controller.sources.selectedScriptId.collectAsState()
    val source by controller.sources.selectedSource.collectAsState()
    val isPaused by controller.sources.isPaused.collectAsState()
    val pauseReason by controller.sources.pauseReason.collectAsState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                scope.launch { if (isPaused) controller.resume() else controller.pause() }
            }) {
                Icon(
                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (isPaused) "Resume" else "Pause",
                    tint = if (isPaused) Color(0xFFFACC15) else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(enabled = isPaused, onClick = { scope.launch { controller.stepOver() } }) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Step over")
            }
            IconButton(enabled = isPaused, onClick = { scope.launch { controller.stepInto() } }) {
                Icon(Icons.Default.ArrowOutward, contentDescription = "Step into")
            }
            IconButton(enabled = isPaused, onClick = { scope.launch { controller.stepOut() } }) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Step out")
            }
            Text(
                if (isPaused) "paused${pauseReason?.let { " ($it)" }.orEmpty()}"
                else "${scripts.size} scripts",
                style = MaterialTheme.typography.labelMedium,
                color = if (isPaused) Color(0xFFFACC15) else MaterialTheme.colorScheme.outline,
            )
        }
        HorizontalDivider()
        Row(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxSize().background(MaterialTheme.colorScheme.background)
            ) {
                items(scripts, key = { it.scriptId }) { script ->
                    val short = runCatching { java.net.URI(script.url).path.takeIf { it.isNotEmpty() } ?: script.url }
                        .getOrDefault(script.url)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (script.scriptId == selectedId)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .clickable { scope.launch { controller.loadScriptSource(script.scriptId) } }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text(
                            short,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            fontWeight = if (script.scriptId == selectedId) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                }
            }
            Box(
                modifier = Modifier
                    .weight(1.6f)
                    .fillMaxSize()
                    .background(Color(0xFF0B1020))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = source ?: "(select a script)",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE2E8F0),
                )
            }
        }
    }
}
