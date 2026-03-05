package com.nerf.netx.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
  primary = Color(0xFFFF6A00),
  secondary = Color(0xFF00D5FF),
  tertiary = Color(0xFFFFD400),
  background = Color(0xFF000000),
  surface = Color(0xFF0A0A0A),
  onBackground = Color(0xFFFFFFFF),
  onSurface = Color(0xFFFFFFFF)
)

@Composable
fun NerfTheme(themeId: ThemeId, content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
