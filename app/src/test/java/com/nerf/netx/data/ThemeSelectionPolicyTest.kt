package com.nerf.netx.data

import com.nerf.netx.ui.theme.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSelectionPolicyTest {

  private val policy = ThemeSelectionPolicy(
    defaultTheme = ThemeId.NERF_DASH_NEW_HTML,
    availableThemes = listOf(
      ThemeId.NERF_DASH_NEW_HTML,
      ThemeId.NERF_MAIN_DASH_HTML,
      ThemeId.NERF_HUD_ALT_HTML,
      ThemeId.NERF_SPEED2_HTML,
      ThemeId.SPEEDTEST6_HTML
    )
  )

  @Test
  fun `uses nerf dash new on first launch when nothing is saved`() {
    assertEquals(ThemeId.NERF_DASH_NEW_HTML, policy.resolveSavedTheme(null))
  }

  @Test
  fun `preserves persisted registered theme`() {
    assertEquals(ThemeId.SPEEDTEST6_HTML, policy.resolveSavedTheme("speedtest6"))
  }

  @Test
  fun `falls back to default when saved theme is unknown`() {
    assertEquals(ThemeId.NERF_DASH_NEW_HTML, policy.resolveSavedTheme("missing_theme"))
  }

  @Test
  fun `migrates deprecated neon ids to default`() {
    assertEquals(ThemeId.NERF_DASH_NEW_HTML, policy.resolveSavedTheme("NEON_NERF"))
    assertEquals(ThemeId.NERF_DASH_NEW_HTML, policy.resolveSavedTheme("neon_nerf"))
  }
}
