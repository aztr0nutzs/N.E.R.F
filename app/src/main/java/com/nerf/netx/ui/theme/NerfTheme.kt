package com.nerf.netx.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.nerf.netx.assistant.model.AssistantMessageAuthor
import com.nerf.netx.assistant.model.AssistantSeverity

data class NativeThemeTokens(
  val primary: Color,
  val accent: Color,
  val highlight: Color,
  val panel: Color,
  val text: Color
)

data class StatusPalette(
  val info: Color,
  val success: Color,
  val warning: Color,
  val error: Color
)

data class NativeUiTokens(
  val messageUserBubble: Color,
  val messageAssistantBubble: Color,
  val severity: StatusPalette,
  val metricPillBackground: Color
)

data class NerfThemeTokens(
  val palette: NativeThemeTokens,
  val ui: NativeUiTokens
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

private val nerfMainDashHtmlTokens = NativeThemeTokens(
  primary = Color(0xFF02FEFF),
  accent = Color(0xFF00D4E8),
  highlight = Color(0xFFF2C14E),
  panel = Color(0xFF000E16),
  text = Color(0xFFE9FCFF)
)

private val nerfHudAltHtmlTokens = NativeThemeTokens(
  primary = Color(0xFFFFE600),
  accent = Color(0xFF00A3FF),
  highlight = Color(0xFFFF8F00),
  panel = Color(0xFF10151A),
  text = Color(0xFFEAF2F8)
)

private val defaultStatusPalette = StatusPalette(
  info = Color(0xFF4AA3FF),
  success = Color(0xFF4DD387),
  warning = Color(0xFFFFB347),
  error = Color(0xFFFF6B6B)
)

private val defaultUiTokens = NativeUiTokens(
  messageUserBubble = Color(0xFF1E3A5F),
  messageAssistantBubble = Color(0xFF2F2F3A),
  severity = defaultStatusPalette,
  metricPillBackground = Color(0x1F7CA4C9)
)

private val fallbackThemeTokens = NerfThemeTokens(
  palette = neonNerfNativeTokens,
  ui = defaultUiTokens
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

fun themePaletteTokens(themeId: ThemeId): NativeThemeTokens {
  return when (themeId) {
    ThemeId.NERF_MAIN_DASH_HTML -> nerfMainDashHtmlTokens
    ThemeId.NERF_HUD_ALT_HTML -> nerfHudAltHtmlTokens
    ThemeId.NEON_NERF_NATIVE -> neonNerfNativeTokens
  }
}

fun severityColor(tokens: NerfThemeTokens, severity: AssistantSeverity): Color {
  return when (severity) {
    AssistantSeverity.INFO -> tokens.ui.severity.info
    AssistantSeverity.SUCCESS -> tokens.ui.severity.success
    AssistantSeverity.WARNING -> tokens.ui.severity.warning
    AssistantSeverity.ERROR -> tokens.ui.severity.error
  }
}

fun messageBubbleColor(tokens: NerfThemeTokens, author: AssistantMessageAuthor): Color {
  return when (author) {
    AssistantMessageAuthor.USER -> tokens.ui.messageUserBubble
    AssistantMessageAuthor.ASSISTANT -> tokens.ui.messageAssistantBubble
  }
}

private val LocalNerfThemeTokens = staticCompositionLocalOf { fallbackThemeTokens }

@Composable
fun rememberNerfThemeTokens(): NerfThemeTokens = LocalNerfThemeTokens.current

private fun resolveColorScheme(themeId: ThemeId): ColorScheme {
  return when (themeId) {
    ThemeId.NEON_NERF_NATIVE -> neonNerfNativeScheme
    ThemeId.NERF_MAIN_DASH_HTML,
    ThemeId.NERF_HUD_ALT_HTML -> htmlThemeScheme
  }
}

@Composable
fun NerfTheme(themeId: ThemeId, content: @Composable () -> Unit) {
  val themeTokens = NerfThemeTokens(
    palette = themePaletteTokens(themeId),
    ui = defaultUiTokens
  )
  MaterialTheme(
    colorScheme = resolveColorScheme(themeId),
    typography = Typography(),
  ) {
    CompositionLocalProvider(LocalNerfThemeTokens provides themeTokens) {
      content()
    }
  }
}
