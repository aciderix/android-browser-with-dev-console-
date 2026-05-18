package com.devbrowser.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbrowser.devtools.DevToolsController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryPanel(controller: DevToolsController) {
    val used by controller.memory.jsHeapUsedBytes.collectAsState()
    val total by controller.memory.jsHeapTotalBytes.collectAsState()
    val nodes by controller.memory.domNodeCount.collectAsState()
    val snapshots by controller.memory.snapshots.collectAsState()
    val isSampling by controller.memory.isSampling.collectAsState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { controller.refreshPerformanceMetrics() } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = { scope.launch { controller.collectGarbage() } }) {
                Icon(Icons.Default.CleaningServices, contentDescription = "Collect garbage")
            }
            IconButton(onClick = { scope.launch { controller.takeHeapSnapshot() } }, enabled = !isSampling) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Heap snapshot")
            }
            Text(
                "JS heap",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        HorizontalDivider()
        Column(modifier = Modifier.padding(12.dp)) {
            val u = used ?: 0
            val t = total ?: 0
            val ratio = if (t > 0) (u.toFloat() / t).coerceIn(0f, 1f) else 0f
            Text("Used: ${bytes(u)} / Allocated: ${bytes(t)}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 6.dp),
            )
            Text(
                "DOM nodes: ${nodes ?: 0}",
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        HorizontalDivider()
        Text(
            "Snapshots",
            modifier = Modifier.padding(12.dp),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        if (snapshots.isEmpty()) {
            Text(
                "Tap 📷 to take a heap snapshot",
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            val df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(snapshots) { snap ->
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(df.format(Date(snap.takenAt)), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Box(modifier = Modifier.padding(horizontal = 12.dp))
                        Text(bytes(snap.sizeBytes), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
