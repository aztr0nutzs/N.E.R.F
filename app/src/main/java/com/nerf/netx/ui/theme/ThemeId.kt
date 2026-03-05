package com.nerf.netx.ui.theme

enum class ThemeType { NATIVE, HTML }

enum class ThemeId(val id: String, val displayName: String, val type: ThemeType) {
  NERF_MAIN_DASH_HTML("nerf_main_dash", "NERF Main Dash (HTML)", ThemeType.HTML),
  NERF_HUD_ALT_HTML("nerf_hud_alt", "NERF HUD Alt (HTML)", ThemeType.HTML),
  SPEEDTEST6_HTML("speedtest6", "Speedtest6 (HTML)", ThemeType.HTML),
  NERF_SPEED2_HTML("nerf_speed2", "NERF Speed2 (HTML)", ThemeType.HTML);

  companion object {
    fun fromId(id: String?): ThemeId? {
      return entries.firstOrNull { it.id == id }
    }
  }
}
