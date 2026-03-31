package com.nerf.netx.ui.theme

enum class ThemeType { NATIVE, HTML }

enum class ThemeId(
  val id: String,
  val displayName: String,
  val type: ThemeType,
  val assetFolder: String? = null
) {
  NERF_MAIN_DASH_HTML("nerf_main_dash", "NERF Main Dash (HTML)", ThemeType.HTML, assetFolder = "nerf_main_dash"),
  NERF_HUD_ALT_HTML("nerf_hud_alt", "NERF HUD Alt (HTML)", ThemeType.HTML, assetFolder = "nerf_hud_alt"),
  NERF_DASH_NEW_HTML("nerf_dash_new", "NERF Dash New (HTML)", ThemeType.HTML, assetFolder = "nerf_dash_new"),
  NERF_MAIN_HUD_HTML("nerf_main_hud", "NERF Main HUD (HTML)", ThemeType.HTML, assetFolder = "nerf_main_hud"),
  NEON_NERF_NATIVE("neon_nerf", "Neon NERF (Native)", ThemeType.NATIVE);

  companion object {
    private val legacyIdMap: Map<String, ThemeId> = mapOf(
      "NERF_DASH_NEW" to NERF_DASH_NEW_HTML,
      "speedtest6" to NERF_DASH_NEW_HTML,
      "nerf_speed2" to NERF_DASH_NEW_HTML,
      "NEON_NERF" to NEON_NERF_NATIVE
    )

    fun fromId(id: String?): ThemeId? {
      if (id == null) return null
      return entries.firstOrNull { it.id == id } ?: legacyIdMap[id]
    }
  }
}
