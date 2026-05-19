package com.devbrowser.ui.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbrowser.devtools.ConsoleEntry
import com.devbrowser.devtools.DevToolsController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class LevelFilter(
    val level: ConsoleEntry.Level,
    val label: String,
    val color: Color,
)

private val FilterChips = listOf(
    LevelFilter(ConsoleEntry.Level.Error, "Errors", Color(0xFFF87171)),
    LevelFilter(ConsoleEntry.Level.Warn, "Warnings", Color(0xFFFACC15)),
    LevelFilter(ConsoleEntry.Level.Info, "Info", Color(0xFF60A5FA)),
    LevelFilter(ConsoleEntry.Level.Log, "Logs", Color(0xFFE5E7EB)),
    LevelFilter(ConsoleEntry.Level.Debug, "Debug", Color(0xFF94A3B8)),
)

@Composable
fun ConsolePanel(controller: DevToolsController) {
    val entries by controller.console.entries.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selected = remember { mutableStateListOf<Long>() }

    // Filter state: every level visible by default, plus eval entries.
    val visibleLevels = remember {
        mutableStateListOf<ConsoleEntry.Level>().apply { addAll(ConsoleEntry.Level.entries) }
    }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }

    val visible by remember(entries, query) {
        derivedStateOf {
            val q = query.trim().lowercase()
            entries.filter { e ->
                (e.level in visibleLevels ||
                    e.level == ConsoleEntry.Level.EvalInput ||
                    e.level == ConsoleEntry.Level.EvalResult) &&
                    (q.isEmpty() || e.text.lowercase().contains(q))
            }
        }
    }

    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty() && selected.isEmpty()) {
            listState.animateScrollToItem(visible.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar row 1: actions
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.console.clear(); selected.clear() }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear")
            }
            IconButton(onClick = {
                val source = if (selected.isNotEmpty()) entries.filter { it.id in selected } else visible
                copy(context, formatEntries(source), "${source.size} entries copied")
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
            }
            IconButton(onClick = {
                if (selected.size == visible.size) selected.clear()
                else { selected.clear(); visible.forEach { selected.add(it.id) } }
            }) {
                Icon(Icons.Default.SelectAll, contentDescription = "Select all")
            }
            IconButton(onClick = { searchOpen = !searchOpen }) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (searchOpen || query.isNotEmpty()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { scope.launch { controller.snapshotPage() } }) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "DOM snapshot")
            }
            Text(
                text = if (selected.isEmpty()) "${visible.size}/${entries.size}"
                else "${selected.size} selected",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }

        // Search field (collapsible)
        if (searchOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp).padding(end = 6.dp),
                )
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                if (query.isNotEmpty()) {
                    Text(
                        "✕",
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .pointerInput(Unit) { detectTapGestures { query = "" } },
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // Level filter chips
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(FilterChips) { chip ->
                val active = chip.level in visibleLevels
                FilterChip(
                    label = chip.label,
                    active = active,
                    color = chip.color,
                    onToggle = {
                        if (active) visibleLevels.remove(chip.level)
                        else visibleLevels.add(chip.level)
                    },
                )
            }
        }
        HorizontalDivider()

        // Entries
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background),
        ) {
            items(visible, key = { it.id }) { entry ->
                ConsoleEntryRow(
                    entry = entry,
                    selected = entry.id in selected,
                    onTap = {
                        if (selected.isNotEmpty()) {
                            if (entry.id in selected) selected.remove(entry.id)
                            else selected.add(entry.id)
                        }
                    },
                    onLongPress = {
                        if (entry.id in selected) selected.remove(entry.id)
                        else selected.add(entry.id)
                    },
                    onDoubleTap = {
                        copy(context, entry.text, "Entry copied")
                    },
                )
            }
        }
        HorizontalDivider()

        // REPL input
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(">", color = Color(0xFF60A5FA), modifier = Modifier.padding(end = 6.dp))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val expr = input.text
                    if (expr.isNotBlank()) {
                        controller.console.appendInput(expr)
                        scope.launch { controller.evaluate(expr) }
                        input = TextFieldValue("")
                    }
                }),
            )
            IconButton(onClick = {
                val expr = input.text
                if (expr.isNotBlank()) {
                    controller.console.appendInput(expr)
                    scope.launch { controller.evaluate(expr) }
                    input = TextFieldValue("")
                }
            }) { Icon(Icons.Default.PlayArrow, contentDescription = "Run") }
        }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, color: Color, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) color.copy(alpha = 0.18f) else Color.Transparent
            )
            .pointerInput(label) { detectTapGestures { onToggle() } }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (active) color else color.copy(alpha = 0.3f))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontSize = 11.sp,
                color = if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ConsoleEntryRow(
    entry: ConsoleEntry,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val color = when (entry.level) {
        ConsoleEntry.Level.Error -> Color(0xFFF87171)
        ConsoleEntry.Level.Warn -> Color(0xFFFACC15)
        ConsoleEntry.Level.Info -> Color(0xFF60A5FA)
        ConsoleEntry.Level.Debug -> Color(0xFF94A3B8)
        ConsoleEntry.Level.EvalInput -> Color(0xFF93C5FD)
        ConsoleEntry.Level.EvalResult -> Color(0xFFCBD5E1)
        ConsoleEntry.Level.Log -> Color(0xFFE5E7EB)
    }
    val prefix = when (entry.level) {
        ConsoleEntry.Level.Error -> "✕ "
        ConsoleEntry.Level.Warn -> "⚠ "
        ConsoleEntry.Level.EvalInput -> "› "
        ConsoleEntry.Level.EvalResult -> "‹ "
        else -> ""
    }
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        entry.level == ConsoleEntry.Level.Error -> Color(0xFFF8717119)
        entry.level == ConsoleEntry.Level.Warn -> Color(0xFFFACC1517)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .pointerInput(entry.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                    onDoubleTap = { onDoubleTap() },
                )
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            Text(
                text = prefix + entry.text,
                color = color,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (entry.level == ConsoleEntry.Level.EvalInput) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (entry.url != null) {
                Text(
                    text = "${entry.url}${entry.line?.let { ":$it" } ?: ""}",
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun formatEntries(list: List<ConsoleEntry>): String = buildString {
    list.forEachIndexed { idx, e ->
        if (idx > 0) append("\n")
        append('[').append(timestampFormat.format(Date(e.timestamp))).append("] ")
        append(e.level.name.uppercase()).append(' ')
        append(e.text)
        e.url?.let { append(" @ ").append(it) }
        e.line?.let { append(':').append(it) }
    }
}

private fun copy(context: Context, text: String, toastMessage: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("DevBrowser console", text))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}
