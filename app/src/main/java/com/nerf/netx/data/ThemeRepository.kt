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
  private val _selected = MutableStateFlow(readTheme())
  override val selected: StateFlow<ThemeId> = _selected

  override fun set(themeId: ThemeId) {
    prefs.edit().putString(key, themeId.id).apply()
    _selected.value = themeId
  }

  override fun htmlAssetUrl(themeId: ThemeId): String? = when (themeId) {
    ThemeId.NEON_NERF -> null
    ThemeId.SPEEDTEST6_HTML -> "file:///android_asset/themes/speedtest6/index.html"
    ThemeId.NERF_SPEED2_HTML -> "file:///android_asset/themes/nerf_speed2/index.html"
  }

  private fun readTheme(): ThemeId {
    val saved = prefs.getString(key, null)
    return ThemeId.fromId(saved) ?: ThemeId.NEON_NERF
  }
}
