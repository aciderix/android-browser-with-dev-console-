package com.devbrowser.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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
fun PerformancePanel(controller: DevToolsController) {
    val isRecording by controller.performance.isRecording.collectAsState()
    val eventCount by controller.performance.traceEventCount.collectAsState()
    val cats by controller.performance.traceCategoryCounts.collectAsState()
    val metrics by controller.performance.metrics.collectAsState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                scope.launch { if (isRecording) controller.stopTracing() else controller.startTracing() }
            }) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = if (isRecording) "Stop" else "Record",
                    tint = if (isRecording) Color(0xFFF87171) else Color(0xFFEF4444),
                )
            }
            IconButton(onClick = { scope.launch { controller.refreshPerformanceMetrics() } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh metrics")
            }
            Text(
                if (isRecording) "Recording — $eventCount events captured"
                else if (eventCount > 0) "$eventCount events"
                else "Tap ● to start a trace",
                style = MaterialTheme.typography.labelMedium,
                color = if (isRecording) Color(0xFFF87171) else MaterialTheme.colorScheme.outline,
            )
        }
        HorizontalDivider()
        if (cats.isNotEmpty()) {
            Text(
                "Events by category",
                modifier = Modifier.padding(8.dp),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
            )
            val max = cats.values.maxOrNull() ?: 1
            cats.entries.sortedByDescending { it.value }.forEach { (cat, count) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        cat,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(180.dp),
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .height(10.dp)
                            .weight(count.toFloat() / max)
                            .background(Color(0xFF60A5FA))
                    )
                    Box(modifier = Modifier.weight((max - count).toFloat() / max))
                    Text(
                        count.toString(),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        Text(
            "Live metrics (Performance.getMetrics)",
            modifier = Modifier.padding(8.dp),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        if (metrics.isEmpty()) {
            Text(
                "Tap ↻ to fetch metrics",
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            metrics.forEach { m ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)) {
                    Text(
                        m.name,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFA5B4FC),
                        modifier = Modifier.width(180.dp),
                    )
                    Text(
                        formatMetric(m.name, m.value),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun formatMetric(name: String, value: Double): String = when {
    name.endsWith("Size") -> bytes(value.toLong())
    name == "Timestamp" -> "%.3f s".format(value)
    name == "Duration" -> "%.3f ms".format(value * 1000)
    else -> if (value == value.toLong().toDouble()) value.toLong().toString() else "%.3f".format(value)
}

internal fun bytes(b: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$b B" else "%.2f %s".format(v, units[i])
}
