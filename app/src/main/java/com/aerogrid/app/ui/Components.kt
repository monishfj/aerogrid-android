package com.aerogrid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LabeledSlider(
    label: String, value: Double, range: ClosedFloatingPointRange<Double>, format: (Double) -> String,
    steps: Int = 0, modifier: Modifier = Modifier, onLive: ((Double) -> Unit)? = null, onChange: (Double) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(format(local.toDouble()), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        SoftSlider(value = local, onValueChange = { local = it; onLive?.invoke(it.toDouble()) }, valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            onValueChangeFinished = { onChange(local.toDouble()) })
    }
}

@Composable
fun NumberField(label: String, value: Double?, modifier: Modifier = Modifier, decimals: Int = 1, suffix: String = "", enabled: Boolean = true, onValue: (Double) -> Unit) {
    val fmt = { v: Double? -> v?.let { if (decimals == 0) it.toLong().toString() else "%.${decimals}f".format(it) } ?: "" }
    var text by remember(value) { mutableStateOf(fmt(value)) }
    val focus = LocalFocusManager.current
    fun commit() { text.replace(',', '.').toDoubleOrNull()?.let { if (it != value) onValue(it) } ?: run { text = fmt(value) } }
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true, enabled = enabled,
        suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() })
}

@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T, labelOf: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Box0 {
            TextButton(onClick = { open = true }) { Text(labelOf(selected)); Icon(Icons.Default.ArrowDropDown, null) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(labelOf(o)) }, onClick = { open = false; onSelect(o) }) }
            }
        }
    }
}

@Composable
private fun Box0(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Box { content() }

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        SoftSwitch(checked = checked, onCheckedChange = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange(it) })
    }
}

@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Column(modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (warn) Color(0xFFFF9100) else MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

@Composable
fun RowGap(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }

@Composable
fun HSpace(w: Int = 8) = Spacer(Modifier.width(w.dp))
