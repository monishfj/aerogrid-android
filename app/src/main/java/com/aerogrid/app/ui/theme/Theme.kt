package com.aerogrid.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Dark = darkColorScheme(primary = Color(0xFF6CC6FF), secondary = Color(0xFFFFC94D), tertiary = Color(0xFF7BE0A3),
    background = Color(0xFF0F1419), surface = Color(0xFF151B21), surfaceContainer = Color(0xFF1B232B), error = Color(0xFFFF6B6B))
private val Light = lightColorScheme(primary = Color(0xFF0B5CAD), secondary = Color(0xFF8A5A00), tertiary = Color(0xFF1E7A46))

@Composable
fun AeroGridTheme(theme: Int, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
