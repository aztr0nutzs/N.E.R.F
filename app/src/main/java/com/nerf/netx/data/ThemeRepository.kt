package com.nerf.netx.data

import android.content.Context
import android.content.SharedPreferences
import com.nerf.netx.ui.theme.ThemeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ThemeRepository {
  val selected: StateFlow<ThemeId>
  fun set(themeId: ThemeId)
  fun htmlAssetUrl(themeId: ThemeId): String?
}

class ThemeRepositoryImpl(context: Context) : ThemeRepository {
  private val prefs: SharedPreferences = context.getSharedPreferences("nerf_prefs", Context.MODE_PRIVATE)
  private val key = "theme_id"
  private val defaultTheme = ThemeId.NERF_MAIN_DASH_HTML
  private val _selected = MutableStateFlow(readTheme())
  override val selected: StateFlow<ThemeId> = _selected

  override fun set(themeId: ThemeId) {
    val safeTheme = if (themeId == ThemeId.NERF_MAIN_DASH_HTML || themeId == ThemeId.NERF_HUD_ALT_HTML || themeId == ThemeId.NERF_DASH_NEW_HTML) {
      themeId
    } else {
      defaultTheme
    }
    prefs.edit().putString(key, safeTheme.id).apply()
    _selected.value = safeTheme
  }

  override fun htmlAssetUrl(themeId: ThemeId): String? = when (themeId) {
    ThemeId.NERF_MAIN_DASH_HTML -> "file:///android_asset/themes/nerf_main_dash/index.html"
    ThemeId.NERF_HUD_ALT_HTML -> "file:///android_asset/themes/nerf_hud_alt/index.html"
    ThemeId.NERF_DASH_NEW_HTML -> "file:///android_asset/themes/nerf_dash_new/index.html"
  }

  private fun readTheme(): ThemeId {
    val saved = prefs.getString(key, null)
    if (
      saved == "NEON_NERF" ||
      saved == "neon_nerf" ||
      saved == "speedtest6" ||
      saved == "nerf_speed2"
    ) {
      prefs.edit().putString(key, defaultTheme.id).apply()
      return defaultTheme
    }
    return ThemeId.fromId(saved) ?: defaultTheme
  }
}
