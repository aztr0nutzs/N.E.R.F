package com.nerf.netx.data

import com.nerf.netx.ui.theme.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSelectionPolicyTest {

  private val policy = ThemeSelectionPolicy(
    defaultTheme = ThemeId.NERF_DASH_NEW_HTML,
    availableThemes = listOf(
      ThemeId.NERF_DASH_NEW_HTML,
      ThemeId.NERF_HUD_ALT_HTML,
      ThemeId.NERF_MAIN_HUD_HTML
    )
  )

  @Test
  fun `uses nerf dash new on first launch when nothing is saved`() {
    assertEquals(ThemeId.NERF_DASH_NEW_HTML, policy.resolveSavedTheme(null))
  }

  @Test
  fun `preserves persisted registered theme`() {
    assertEquals(ThemeId.NERF_MAIN_HUD_HTML, policy.resolveSavedTheme("nerf_main_hud"))
  }

  @Test
  fun `falls back to default when saved theme is unknown`() {
    assertEquals(ThemeId.NERF_DASH_NEW_HTML, policy.resolveSavedTheme("missing_theme"))
  }

  @Test
  fun `migrates deprecated neon ids to neon native`() {
    assertEquals(ThemeId.NEON_NERF_NATIVE, policy.resolveSavedTheme("NEON_NERF"))
    assertEquals(ThemeId.NEON_NERF_NATIVE, policy.resolveSavedTheme("neon_nerf"))
  }

  @Test
  fun `falls back when legacy main dash theme is unavailable`() {
    assertEquals(ThemeId.NERF_DASH_NEW_HTML, policy.resolveSavedTheme("nerf_main_dash"))
  }

  @Test
  fun `falls back to default for removed themes`() {
    assertEquals(ThemeId.NERF_DASH_NEW_HTML, policy.resolveSavedTheme("nerf_speed2"))
    assertEquals(ThemeId.NERF_DASH_NEW_HTML, policy.resolveSavedTheme("speedtest6"))
  }
}
