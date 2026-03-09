package com.nerf.netx.ui.theme

enum class ThemeType { NATIVE, HTML }

enum class ThemeId(val id: String, val displayName: String, val type: ThemeType) {
  NERF_DASH_NEW_HTML("nerf_dash_new", "NERF Dash New (HTML)", ThemeType.HTML),
  NERF_HUD_ALT_HTML("nerf_hud_alt", "NERF HUD Alt (HTML)", ThemeType.HTML),
  NERF_MAIN_HUD_HTML("nerf_main_hud", "NERF Main HUD (HTML)", ThemeType.HTML);

  companion object {
    fun fromId(id: String?): ThemeId? {
      return entries.firstOrNull { it.id == id }
    }
  }
}
