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
  private val selectionSchemaVersion = 2
  private val defaultTheme = ThemeId.NERF_MAIN_DASH_HTML
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

  override fun htmlAssetUrl(themeId: ThemeId): String? = themeId.assetFolder?.let(::assetUrl)

  private fun readTheme(): ThemeId {
    migrateSavedSelectionIfNeeded()
    val saved = prefs.getString(key, null)
    val resolved = selectionPolicy.resolveSavedTheme(saved)
    if (saved != resolved.id) {
      prefs.edit().putString(key, resolved.id).apply()
    }
    return resolved
  }

  private fun migrateSavedSelectionIfNeeded() {
    val appliedSchema = prefs.getInt(selectionSchemaKey, 0)
    if (appliedSchema >= selectionSchemaVersion) return
    val migrated = selectionPolicy.resolveSavedTheme(prefs.getString(key, null))
    prefs.edit()
      .putString(key, migrated.id)
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
      ThemeType.HTML -> themeId.assetFolder?.let { assetExists("themes/$it/index.html") } == true
    }
  }

  private fun assetExists(path: String): Boolean {
    return runCatching {
      appContext.assets.open(path).use { }
      true
    }.getOrDefault(false)
  }
}
