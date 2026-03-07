package com.nerf.netx.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeOperationalAuditTest {

  @Test
  fun `all registered html themes have audit coverage`() {
    ThemeId.entries.forEach { themeId ->
      val entries = ThemeOperationalAudit.entriesFor(themeId)
      assertFalse("Theme audit missing for ${themeId.id}", entries.isEmpty())
    }
  }

  @Test
  fun `dash new covers assistant and console surfaces`() {
    val dashNewEntries = ThemeOperationalAudit.entriesFor(ThemeId.NERF_DASH_NEW_HTML)
    assertTrue(dashNewEntries.any { it.screen == "assistant" && it.status == ThemeAuditStatus.WIRED })
    assertTrue(dashNewEntries.any { it.screen == "intel-console" && it.status == ThemeAuditStatus.WIRED })
  }

  @Test
  fun `shared dashboard themes mark unsupported device controls honestly`() {
    val sharedThemes = listOf(
      ThemeId.NERF_MAIN_DASH_HTML,
      ThemeId.NERF_HUD_ALT_HTML,
      ThemeId.NERF_SPEED2_HTML,
      ThemeId.SPEEDTEST6_HTML
    )

    sharedThemes.forEach { themeId ->
      assertTrue(
        "Theme ${themeId.id} should mark unsupported device actions explicitly",
        ThemeOperationalAudit.entriesFor(themeId).any { entry ->
          entry.screen == "devices" && entry.status == ThemeAuditStatus.EXPLICIT_UNSUPPORTED
        }
      )
    }
  }
}
