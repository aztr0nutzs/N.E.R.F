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
