package com.aerogrid.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerogrid.app.ui.theme.LocalSoft
import kotlin.math.roundToInt

/**
 * Neumorphic double shadow: a light highlight up-left and a soft dark shadow down-right,
 * drawn behind the element with framework shadow layers (GPU accelerated).
 */
fun Modifier.neu(radius: Dp, elevation: Dp = 8.dp, fill: Color? = null, inset: Boolean = false): Modifier = composed {
    val s = LocalSoft.current
    drawBehind {
    val r = radius.toPx(); val e = elevation.toPx()
    drawIntoCanvas { c ->
        val p = Paint().asFrameworkPaint()
        p.isAntiAlias = true
        p.color = (fill ?: Color.White).toArgb()
        val soft = s.shadowDark
        if (!inset) {
            p.setShadowLayer(e * 1.4f, -e * 0.6f, -e * 0.6f, s.shadowLight.copy(alpha = 0.9f).toArgb())
            c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
            p.setShadowLayer(e * 1.6f, e * 0.7f, e * 0.8f, soft.copy(alpha = 0.55f).toArgb())
            c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
        } else {
            c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
        }
    }
    }
}

/** Soft white card with large radius and neumorphic shadow. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, radius: Dp = 28.dp, elevation: Dp = 8.dp, padding: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    val s = LocalSoft.current
    Column(modifier.neu(radius, elevation, s.card).clip(RoundedCornerShape(radius)).background(s.card).padding(padding), content = content)
}

/** Paints its content (e.g. an icon) with the accent gradient. */
fun Modifier.gradientTint(brush: Brush): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithCache { onDrawWithContent { drawContent(); drawRect(brush, blendMode = BlendMode.SrcAtop) } }

@Composable
fun GradientIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier) =
    Icon(icon, contentDescription, modifier.gradientTint(LocalSoft.current.gradient), tint = Color.Black)

/** Pill button filled with the blue→indigo gradient and a coloured glow (kit "Sign Up"). */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, height: Dp = 52.dp) {
    val s = LocalSoft.current
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(height)
            .then(if (enabled) Modifier.shadow(12.dp, shape, ambientColor = s.gradEnd, spotColor = s.gradEnd) else Modifier)
            .clip(shape)
            .background(if (enabled) s.gradient else Brush.horizontalGradient(listOf(s.track, s.track)))
            .clickable(enabled = enabled, role = Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) Color.White else s.muted
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** White pill with icon + label (kit "Share" / "Download"); [accent] gives the icon a gradient square (kit "Add Friend"). */
@Composable
fun SoftPillButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false, enabled: Boolean = true) {
    val s = LocalSoft.current
    Row(modifier.height(60.dp).neu(30.dp, 7.dp, s.card).clip(RoundedCornerShape(30.dp)).background(s.card)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(start = 8.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        if (accent) Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(s.gradientV), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) }
        else Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = s.muted) }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = if (enabled) MaterialTheme.colorScheme.onSurface else s.muted, maxLines = 1)
    }
}

/**
 * Rounded-square icon tile (kit Home/Calendar tiles). Selected = raised white tile with gradient icon;
 * [filled] = gradient tile with white icon; otherwise translucent card so it stays readable over the map.
 */
@Composable
fun SoftIconTile(icon: ImageVector?, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 52.dp,
                 selected: Boolean = false, filled: Boolean = false, label: String? = null, enabled: Boolean = true, text: String? = null) {
    val s = LocalSoft.current
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(size * 0.32f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            Modifier.size(size)
                .then(if (filled) Modifier.shadow(10.dp, shape, ambientColor = s.gradEnd, spotColor = s.gradEnd) else Modifier.neu(size * 0.32f, 6.dp, s.card))
                .clip(shape)
                .then(if (filled) Modifier.background(s.gradient) else Modifier.background(s.card))
                .clickable(enabled = enabled, role = Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
            contentAlignment = Alignment.Center,
        ) {
            when {
                text != null -> Text(text, fontWeight = FontWeight.ExtraBold, color = if (filled) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                icon != null && filled -> Icon(icon, contentDescription, tint = Color.White)
                icon != null && (selected) -> GradientIcon(icon, contentDescription)
                icon != null -> Icon(icon, contentDescription, tint = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f) else s.muted.copy(alpha = 0.5f))
            }
        }
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = s.muted, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Gradient-track slider with a white glowing knob and a gradient centre dot (kit 71% slider). */
@Composable
fun SoftSlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, onValueChangeFinished: (() -> Unit)? = null, enabled: Boolean = true,
) {
    val s = LocalSoft.current
    val onChange by rememberUpdatedState(onValueChange)
    val onDone by rememberUpdatedState(onValueChangeFinished)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val knob = 26.dp
    val knobPx = with(LocalDensity.current) { knob.toPx() }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(40.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                setProgress { v -> onChange(v.coerceIn(valueRange)); onDone?.invoke(); true }
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                fun at(x: Float) = valueRange.start + ((x - knobPx / 2) / (size.width - knobPx)).coerceIn(0f, 1f) * span
                detectTapGestures { o -> onChange(at(o.x)); onDone?.invoke() }
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                fun at(x: Float) = valueRange.start + ((x - knobPx / 2) / (size.width - knobPx)).coerceIn(0f, 1f) * span
                detectDragGestures(onDragEnd = { onDone?.invoke() }, onDragCancel = { onDone?.invoke() }) { change, _ -> change.consume(); onChange(at(change.position.x)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = constraints.maxWidth.toFloat()
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(s.track, cornerRadius = r)
            val activeW = knobPx / 2 + (size.width - knobPx) * frac
            if (activeW > 0) drawRoundRect(if (enabled) s.gradient else Brush.horizontalGradient(listOf(s.muted, s.muted)), size = Size(activeW, size.height), cornerRadius = r)
        }
        val x = with(LocalDensity.current) { ((w - knobPx) * frac).toDp() }
        Box(Modifier.offset(x = x).size(knob).shadow(8.dp, CircleShape, ambientColor = s.gradEnd, spotColor = s.gradEnd).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (enabled) s.gradient else Brush.horizontalGradient(listOf(s.muted, s.muted))))
        }
    }
}

/** Kit-style ON switch: gradient pill with a white ribbed knob. */
@Composable
fun SoftSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalSoft.current
    val pos by animateFloatAsState(if (checked) 1f else 0f, spring(stiffness = 600f), label = "switch")
    Box(modifier.size(62.dp, 34.dp).neu(17.dp, 4.dp, s.card).clip(RoundedCornerShape(17.dp))
        .background(if (checked) s.gradient else Brush.horizontalGradient(listOf(s.track, s.track)))
        .clickable(role = Role.Switch) { onCheckedChange(!checked) }
        .semantics { stateDescription = if (checked) "On" else "Off" }, contentAlignment = Alignment.CenterStart) {
        Text(if (checked) "ON" else "OFF", color = if (checked) Color.White else s.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(if (checked) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 8.dp))
        Box(Modifier.offset(x = (3 + 28 * pos).dp).size(28.dp).shadow(4.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Color.White),
            contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(3) { Box(Modifier.size(2.dp, 12.dp).background(s.track)) } }
        }
    }
}

/** Pill track with a sliding gradient selection. */
@Composable
fun <T> SoftSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, height: Dp = 46.dp) {
    val s = LocalSoft.current
    val haptic = LocalHapticFeedback.current
    val idx = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(modifier.height(height).neu(height / 2, 6.dp, s.card).clip(RoundedCornerShape(50)).background(s.card).padding(4.dp)) {
        val segW = maxWidth / options.size
        val x by animateDpAsState(segW * idx, spring(stiffness = 500f), label = "seg")
        Box(Modifier.offset(x = x).width(segW).fillMaxHeight().shadow(8.dp, RoundedCornerShape(50), ambientColor = s.gradEnd, spotColor = s.gradEnd)
            .clip(RoundedCornerShape(50)).background(s.gradient))
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, o ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onSelect(o) },
                    contentAlignment = Alignment.Center) {
                    Text(label(o), color = if (i == idx) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** Kit "75%" ring gauge: gradient arc over a grey arc around a raised white centre. */
@Composable
fun RingGauge(fraction: Float, centre: String, modifier: Modifier = Modifier, size: Dp = 120.dp, color: Color? = null) {
    val s = LocalSoft.current
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = size.toPx() * 0.07f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(s.track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val brush = if (color != null) Brush.linearGradient(listOf(color, color)) else Brush.sweepGradient(listOf(s.gradStart, s.gradEnd, s.gradStart))
            drawArc(brush, 135f, 270f * f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Box(Modifier.size(size * 0.62f).neu(size * 0.31f, 6.dp, s.card).clip(CircleShape).background(s.card), contentAlignment = Alignment.Center) {
            Text(centre, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** Thin gradient progress bar. */
@Composable
fun SoftProgress(fraction: Float, modifier: Modifier = Modifier) {
    val s = LocalSoft.current
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(s.track, cornerRadius = r)
        drawRoundRect(s.gradient, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = r)
    }
}

@Composable
fun SoftChip(text: String, onClick: () -> Unit, selected: Boolean = false, modifier: Modifier = Modifier, leading: (@Composable RowScope.() -> Unit)? = null) {
    val s = LocalSoft.current
    Row(modifier.height(38.dp)
        .then(if (selected) Modifier.shadow(8.dp, RoundedCornerShape(50), ambientColor = s.gradEnd, spotColor = s.gradEnd) else Modifier.neu(19.dp, 4.dp, s.card))
        .clip(RoundedCornerShape(50)).background(if (selected) s.gradient else Brush.horizontalGradient(listOf(s.card, s.card)))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides if (selected) Color.White else MaterialTheme.colorScheme.onSurface) {
            leading?.invoke(this); if (leading != null) Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

@Suppress("unused") private fun Float.pct() = (this * 100).roundToInt()
@Suppress("unused") private val unusedBorder = Modifier.border(0.dp, Color.Transparent)
