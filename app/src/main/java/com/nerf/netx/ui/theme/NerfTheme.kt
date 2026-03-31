package com.nerf.netx.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class NativeThemeTokens(
  val primary: Color,
  val accent: Color,
  val highlight: Color,
  val panel: Color,
  val text: Color
)

private val htmlThemeScheme = darkColorScheme(
  primary = Color(0xFFFF6A00),
  secondary = Color(0xFF00D5FF),
  tertiary = Color(0xFFFFD400),
  background = Color(0xFF000000),
  surface = Color(0xFF0A0A0A),
  onBackground = Color(0xFFFFFFFF),
  onSurface = Color(0xFFFFFFFF)
)

private val neonNerfNativeTokens = NativeThemeTokens(
  primary = Color(0xFFFF6A00),
  accent = Color(0xFF00C2FF),
  highlight = Color(0xFFFFD400),
  panel = Color(0xFF10151A),
  text = Color(0xFFEAF2F8)
)

private val neonNerfNativeScheme = darkColorScheme(
  primary = neonNerfNativeTokens.primary,
  secondary = neonNerfNativeTokens.accent,
  tertiary = neonNerfNativeTokens.highlight,
  background = Color(0xFF000000),
  surface = neonNerfNativeTokens.panel,
  onBackground = neonNerfNativeTokens.text,
  onSurface = neonNerfNativeTokens.text
)

fun nativeThemeTokens(themeId: ThemeId): NativeThemeTokens? {
  return when (themeId) {
    ThemeId.NEON_NERF_NATIVE -> neonNerfNativeTokens
    else -> null
  }
}

private fun resolveColorScheme(themeId: ThemeId): ColorScheme {
  return when (themeId) {
    ThemeId.NEON_NERF_NATIVE -> neonNerfNativeScheme
    ThemeId.NERF_MAIN_DASH_HTML,
    ThemeId.NERF_HUD_ALT_HTML -> htmlThemeScheme
  }
}

@Composable
fun NerfTheme(themeId: ThemeId, content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = resolveColorScheme(themeId),
    typography = Typography(),
    content = content
  )
}
