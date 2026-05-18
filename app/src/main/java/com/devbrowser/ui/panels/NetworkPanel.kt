package com.devbrowser.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbrowser.devtools.DevToolsController
import com.devbrowser.devtools.NetworkRequest

@Composable
fun NetworkPanel(controller: DevToolsController) {
    val requests by controller.network.requests.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.network.clear(); selected = null }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear")
            }
            Text(
                "${requests.size} requests",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()

        val selectedReq = requests.firstOrNull { it.requestId == selected }
        if (selectedReq != null) {
            RequestDetail(selectedReq, onClose = { selected = null })
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(requests, key = { it.requestId }) { req ->
                    RequestRow(req, onClick = { selected = req.requestId })
                }
            }
        }
    }
}

@Composable
private fun RequestRow(req: NetworkRequest, onClick: () -> Unit) {
    val statusColor = when {
        req.failureText != null -> Color(0xFFF87171)
        req.status == null -> MaterialTheme.colorScheme.outline
        req.status in 200..299 -> Color(0xFF4ADE80)
        req.status in 300..399 -> Color(0xFF60A5FA)
        req.status in 400..599 -> Color(0xFFFACC15)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val duration = req.endMs?.let { (it - req.startMs).toLong() }
    val path = runCatching { java.net.URI(req.url).path.ifEmpty { "/" } }.getOrNull() ?: req.url
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = req.status?.toString() ?: req.failureText?.take(5) ?: "…",
                color = statusColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = req.method,
                color = MaterialTheme.colorScheme.outline,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                text = path,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (duration != null) {
                Text(
                    text = "${duration}ms",
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Text(
            text = req.url,
            color = MaterialTheme.colorScheme.outline,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

@Composable
private fun RequestDetail(req: NetworkRequest, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Back") }
            Text("Request", style = MaterialTheme.typography.titleSmall)
        }
        SectionLabel("General")
        KeyValue("URL", req.url)
        KeyValue("Method", req.method)
        KeyValue("Status", "${req.status ?: "-"} ${req.statusText.orEmpty()}")
        KeyValue("Type", req.resourceType ?: "-")
        KeyValue("Protocol", req.protocol ?: "-")
        KeyValue("Remote", req.remoteIp ?: "-")
        KeyValue("Size", req.responseSize?.let { "$it B" } ?: "-")
        KeyValue("Duration", req.endMs?.let { "${(it - req.startMs).toLong()} ms" } ?: "-")
        if (req.failureText != null) KeyValue("Error", req.failureText, Color(0xFFF87171))

        SectionLabel("Request headers")
        req.requestHeaders.forEach { (k, v) -> KeyValue(k, v) }

        SectionLabel("Response headers")
        req.responseHeaders.forEach { (k, v) -> KeyValue(k, v) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp, horizontal = 6.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun KeyValue(key: String, value: String, color: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 6.dp)) {
        Text(
            text = key,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}
