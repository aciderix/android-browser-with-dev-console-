package com.devbrowser.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.devbrowser.devtools.DomNode
import kotlinx.coroutines.launch

@Composable
fun ElementsPanel(controller: DevToolsController) {
    val root by controller.elements.root.collectAsState()
    val selectedId by controller.elements.selectedNodeId.collectAsState()
    val expanded by controller.elements.expanded.collectAsState()
    val isPicking by controller.elements.isPicking.collectAsState()
    val computed by controller.elements.computedStyle.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Triple<Long, String, String>?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { controller.setPickMode(!isPicking) } }) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = "Inspect element",
                    tint = if (isPicking) Color(0xFF60A5FA) else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { scope.launch { controller.loadDom() } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Reload DOM")
            }
            Text(
                if (isPicking) "Tap an element on the page" else (root?.nodeName?.lowercase() ?: "no document"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        Row(modifier = Modifier.weight(1f)) {
            // Tree column
            LazyColumn(
                modifier = Modifier
                    .weight(1.4f)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                val visible = mutableListOf<Pair<DomNode, Int>>()
                root?.let { flatten(it, 0, expanded, visible) }
                items(visible, key = { it.first.nodeId }) { (node, depth) ->
                    DomRow(
                        node = node,
                        depth = depth,
                        expanded = node.nodeId in expanded,
                        selected = node.nodeId == selectedId,
                        onClick = {
                            scope.launch { controller.selectNode(node.nodeId) }
                        },
                        onToggle = {
                            scope.launch { controller.expandNode(node) }
                        },
                        onEditAttribute = { attr -> editing = Triple(node.nodeId, attr.first, attr.second) },
                    )
                }
            }
            // Computed styles column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(6.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Computed",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                if (computed.isEmpty()) {
                    Text(
                        "(select an element)",
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 11.sp,
                    )
                } else {
                    computed.forEach { (name, value) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(
                                name,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFA5B4FC),
                                modifier = Modifier.width(110.dp),
                            )
                            Text(
                                value,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }

    val edit = editing
    if (edit != null) {
        var value by remember { mutableStateOf(edit.third) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit attribute ${edit.second}") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = false,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = edit
                    scope.launch { controller.setAttribute(target.first, target.second, value) }
                    editing = null
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            },
        )
    }
}

private fun flatten(
    node: DomNode,
    depth: Int,
    expanded: Set<Long>,
    out: MutableList<Pair<DomNode, Int>>,
) {
    // Skip document & doctype noise — start at <html>
    val isDoc = node.nodeType == 9
    if (!isDoc) out.add(node to depth)
    if (isDoc || node.nodeId in expanded) {
        node.children.forEach { flatten(it, depth + (if (isDoc) 0 else 1), expanded, out) }
    }
}

@Composable
private fun DomRow(
    node: DomNode,
    depth: Int,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onEditAttribute: (Pair<String, String>) -> Unit,
) {
    val tagColor = Color(0xFFE879F9)
    val attrNameColor = Color(0xFFFCD34D)
    val attrValueColor = Color(0xFF6EE7B7)
    val textColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(start = (8 + depth * 12).dp, top = 1.dp, bottom = 1.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.childNodeCount > 0) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp).clickable(onClick = onToggle),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(2.dp))
        } else {
            Spacer(Modifier.width(16.dp))
        }
        when (node.nodeType) {
            1 -> { // Element
                Text("<", color = textColor.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(
                    node.localName ?: node.nodeName.lowercase(),
                    color = tagColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                node.attributes.forEach { attr ->
                    Spacer(Modifier.width(4.dp))
                    Text(
                        attr.first,
                        color = attrNameColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { onEditAttribute(attr) },
                    )
                    Text("=", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "\"${attr.second}\"",
                        color = attrValueColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier.clickable { onEditAttribute(attr) },
                    )
                }
                Text(">", color = textColor.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            3 -> { // Text
                val txt = node.nodeValue?.trim().orEmpty()
                if (txt.isNotEmpty()) {
                    Text(
                        if (txt.length > 80) txt.take(80) + "…" else txt,
                        color = textColor.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
            8 -> { // Comment
                Text(
                    "<!-- ${node.nodeValue?.trim().orEmpty()} -->",
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            10 -> { // DocumentType
                Text(
                    "<!doctype ${node.nodeName.lowercase()}>",
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            else -> {
                Text(
                    node.nodeName,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
