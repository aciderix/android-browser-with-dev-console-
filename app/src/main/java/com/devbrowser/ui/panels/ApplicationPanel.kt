package com.devbrowser.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbrowser.devtools.DevToolsController
import kotlinx.coroutines.launch

private enum class StorageTab { Cookies, Local, Session }

@Composable
fun ApplicationPanel(controller: DevToolsController) {
    val origin by controller.application.origin.collectAsState()
    val cookies by controller.application.cookies.collectAsState()
    val local by controller.application.localStorage.collectAsState()
    val session by controller.application.sessionStorage.collectAsState()
    var tab by remember { mutableStateOf(StorageTab.Cookies) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { controller.refreshOriginAndStorage() } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
            Text(origin ?: "(no origin)", style = MaterialTheme.typography.labelMedium)
        }
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            TabChip("Cookies (${cookies.size})", tab == StorageTab.Cookies) { tab = StorageTab.Cookies }
            TabChip("localStorage (${local.size})", tab == StorageTab.Local) { tab = StorageTab.Local }
            TabChip("sessionStorage (${session.size})", tab == StorageTab.Session) { tab = StorageTab.Session }
        }
        HorizontalDivider()
        when (tab) {
            StorageTab.Cookies -> CookiesList(controller)
            StorageTab.Local -> KvList(local.map { it.key to it.value })
            StorageTab.Session -> KvList(session.map { it.key to it.value })
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CookiesList(controller: DevToolsController) {
    val cookies by controller.application.cookies.collectAsState()
    val scope = rememberCoroutineScope()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(cookies, key = { it.name + "@" + it.domain }) { c ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        c.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        c.value,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                    )
                    Text(
                        "${c.domain}${c.path}" +
                            (if (c.secure) " · secure" else "") +
                            (if (c.httpOnly) " · httpOnly" else "") +
                            (c.sameSite?.let { " · $it" } ?: ""),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = { scope.launch { controller.deleteCookie(c.name, c.domain) } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete cookie")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
        }
    }
}

@Composable
private fun KvList(items: List<Pair<String, String>>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items) { (k, v) ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    k,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    modifier = Modifier.width(140.dp),
                )
                Text(
                    v,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
        }
    }
}
