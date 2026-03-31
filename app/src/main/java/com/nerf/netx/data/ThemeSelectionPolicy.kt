package com.nerf.netx.data

import com.nerf.netx.ui.theme.ThemeId

class ThemeSelectionPolicy(
  private val defaultTheme: ThemeId,
  private val availableThemes: List<ThemeId>
) {
  private val forwardCompatibilityMap: Map<String, ThemeId?> = mapOf(
    "NERF_DASH_NEW" to ThemeId.NERF_MAIN_DASH_HTML,
    "nerf_dash_new" to ThemeId.NERF_MAIN_DASH_HTML,
    "nerf_main_hud" to ThemeId.NERF_MAIN_DASH_HTML,
    "nerf_main_dash" to ThemeId.NERF_MAIN_DASH_HTML,
    "NEON_NERF" to ThemeId.NEON_NERF_NATIVE,
    "neon_nerf" to ThemeId.NEON_NERF_NATIVE,
    "speedtest6" to null,
    "nerf_speed2" to null
  )

  fun resolveSavedTheme(savedId: String?): ThemeId {
    if (savedId == null) {
      return fallbackTheme()
    }
    if (forwardCompatibilityMap.containsKey(savedId)) {
      return sanitizeSelection(forwardCompatibilityMap[savedId])
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
