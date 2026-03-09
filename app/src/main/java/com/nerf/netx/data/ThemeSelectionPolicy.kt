package com.nerf.netx.data

import com.nerf.netx.ui.theme.ThemeId

class ThemeSelectionPolicy(
  private val defaultTheme: ThemeId,
  private val availableThemes: List<ThemeId>
) {
  fun resolveSavedTheme(savedId: String?): ThemeId {
    if (savedId == "NEON_NERF" || savedId == "neon_nerf") {
      return fallbackTheme()
    }
    if (savedId == "nerf_main_dash") {
      return sanitizeSelection(ThemeId.NERF_MAIN_HUD_HTML)
    }
    if (savedId == "nerf_speed2" || savedId == "speedtest6") {
      return fallbackTheme()
    }
    return sanitizeSelection(ThemeId.fromId(savedId))
  }

  fun sanitizeSelection(themeId: ThemeId?): ThemeId {
    return themeId?.takeIf { it in availableThemes } ?: fallbackTheme()
  }

  private fun fallbackTheme(): ThemeId {
    return defaultTheme.takeIf { it in availableThemes }
      ?: availableThemes.firstOrNull()
      ?: defaultTheme
  }
}
