package com.devbrowser.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbrowser.devtools.ConsoleEntry
import com.devbrowser.devtools.DevToolsController
import kotlinx.coroutines.launch

@Composable
fun ConsolePanel(controller: DevToolsController) {
    val entries by controller.console.entries.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.console.clear() }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear")
            }
            Text(
                "${entries.size} entries",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)
        ) {
            items(entries, key = { it.id }) { entry -> ConsoleEntryRow(entry) }
        }
        HorizontalDivider()
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
private fun ConsoleEntryRow(entry: ConsoleEntry) {
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when (entry.level) {
                    ConsoleEntry.Level.Error -> Color(0xFFF8717119)
                    ConsoleEntry.Level.Warn -> Color(0xFFFACC1517)
                    else -> Color.Transparent
                }
            )
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
