package com.nerf.netx.data

import android.content.Context
import android.content.SharedPreferences
import com.nerf.netx.ui.theme.ThemeId
import com.nerf.netx.ui.theme.ThemeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ThemeRepository {
  val availableThemes: List<ThemeId>
  val selected: StateFlow<ThemeId>
  fun set(themeId: ThemeId)
  fun htmlAssetUrl(themeId: ThemeId): String?
}

class ThemeRepositoryImpl(context: Context) : ThemeRepository {
  private val appContext = context.applicationContext
  private val prefs: SharedPreferences = context.getSharedPreferences("nerf_prefs", Context.MODE_PRIVATE)
  private val key = "theme_id"
  private val selectionSchemaKey = "theme_selection_schema"
  private val selectionSchemaVersion = 1
  private val defaultTheme = ThemeId.NERF_DASH_NEW_HTML
  override val availableThemes: List<ThemeId> = ThemeId.entries.filter(::isThemeAvailable)
  private val selectionPolicy = ThemeSelectionPolicy(defaultTheme, availableThemes)
  private val _selected = MutableStateFlow(readTheme())
  override val selected: StateFlow<ThemeId> = _selected

  override fun set(themeId: ThemeId) {
    val safeTheme = selectionPolicy.sanitizeSelection(themeId)
    prefs.edit()
      .putString(key, safeTheme.id)
      .putInt(selectionSchemaKey, selectionSchemaVersion)
      .apply()
    _selected.value = safeTheme
  }

  override fun htmlAssetUrl(themeId: ThemeId): String? = when (themeId) {
    ThemeId.NERF_DASH_NEW_HTML -> assetUrl("nerf_dash_new")
    ThemeId.NERF_HUD_ALT_HTML -> assetUrl("nerf_hud_alt")
    ThemeId.NERF_MAIN_HUD_HTML -> assetUrl("nerf_main_hud")
  }

  private fun readTheme(): ThemeId {
    migrateSavedSelectionIfNeeded()
    val saved = prefs.getString(key, null)
    if (saved == "NEON_NERF" || saved == "neon_nerf") {
      prefs.edit().putString(key, defaultTheme.id).apply()
      return defaultTheme
    }
    return selectionPolicy.resolveSavedTheme(saved)
  }

  private fun migrateSavedSelectionIfNeeded() {
    val appliedSchema = prefs.getInt(selectionSchemaKey, 0)
    if (appliedSchema >= selectionSchemaVersion) return
    prefs.edit()
      .putString(key, defaultTheme.id)
      .putInt(selectionSchemaKey, selectionSchemaVersion)
      .apply()
  }

  private fun assetUrl(folder: String): String? {
    return if (assetExists("themes/$folder/index.html")) {
      "file:///android_asset/themes/$folder/index.html"
    } else {
      null
    }
  }

  private fun isThemeAvailable(themeId: ThemeId): Boolean {
    return when (themeId.type) {
      ThemeType.NATIVE -> true
      ThemeType.HTML -> assetExists("themes/${themeId.id}/index.html")
    }
  }

  private fun assetExists(path: String): Boolean {
    return runCatching {
      appContext.assets.open(path).use { }
      true
    }.getOrDefault(false)
  }
}
