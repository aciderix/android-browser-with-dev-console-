package com.devbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbrowser.engine.EngineKind

@Composable
fun TabBar(vm: BrowserViewModel) {
    val tabs by vm.tabs.collectAsState()
    val activeId by vm.activeTabId.collectAsState()
    var newTabMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                val isActive = tab.id == activeId
                val state by tab.engine.state.collectAsState()
                val label = state.title.ifEmpty {
                    runCatching { java.net.URI(state.url).host ?: state.url }.getOrDefault(state.url)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isActive) MaterialTheme.colorScheme.surface
                            else Color.Transparent
                        )
                        .border(
                            1.dp,
                            if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { vm.selectTab(tab.id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .widthIn(min = 80.dp, max = 200.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when (tab.engineKind) {
                                    EngineKind.WebView -> Color(0xFF60A5FA)
                                    EngineKind.Gecko -> Color(0xFFFF7E1D)
                                }
                            )
                    )
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        maxLines = 1,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    )
                    if (tabs.size > 1) {
                        IconButton(
                            onClick = { vm.closeTab(tab.id) },
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close tab",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        Box {
            IconButton(onClick = { newTabMenuExpanded = true }) {
                Icon(Icons.Default.Add, contentDescription = "New tab")
            }
            DropdownMenu(
                expanded = newTabMenuExpanded,
                onDismissRequest = { newTabMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("New tab — WebView (Chromium)") },
                    onClick = {
                        newTabMenuExpanded = false
                        vm.newTab(EngineKind.WebView, "about:blank")
                    },
                )
                DropdownMenuItem(
                    text = { Text("New tab — GeckoView (Firefox)") },
                    onClick = {
                        newTabMenuExpanded = false
                        vm.newTab(EngineKind.Gecko, "about:blank")
                    },
                )
            }
        }
    }
}
