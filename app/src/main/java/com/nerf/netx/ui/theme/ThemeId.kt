package com.nerf.netx.ui.theme

enum class ThemeType { NATIVE, HTML }

enum class ThemeId(val id: String, val displayName: String, val type: ThemeType) {
  NEON_NERF("neon_nerf", "Neon NERF (Native)", ThemeType.NATIVE),
  SPEEDTEST6_HTML("speedtest6", "Speedtest6 (HTML)", ThemeType.HTML),
  NERF_SPEED2_HTML("nerf_speed2", "NERF Speed2 (HTML)", ThemeType.HTML);

  companion object {
    fun fromId(id: String?): ThemeId? {
      return entries.firstOrNull { it.id == id }
    }
  }
}
