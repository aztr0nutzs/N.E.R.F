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
  val lastCommittedBeforeCurrent: StateFlow<ThemeId?>
  fun set(themeId: ThemeId)
  fun revertToLastCommitted()
  fun htmlAssetUrl(themeId: ThemeId): String?
}

class ThemeRepositoryImpl(context: Context) : ThemeRepository {
  private val appContext = context.applicationContext
  private val prefs: SharedPreferences = context.getSharedPreferences("nerf_prefs", Context.MODE_PRIVATE)
  private val key = "theme_id"
  private val previousKey = "theme_previous_id"
  private val selectionSchemaKey = "theme_selection_schema"
  private val selectionSchemaVersion = 3
  private val defaultTheme = ThemeId.NERF_MAIN_DASH_HTML
  override val availableThemes: List<ThemeId> = ThemeId.entries.filter(::isThemeAvailable)
  private val selectionPolicy = ThemeSelectionPolicy(defaultTheme, availableThemes)
  private val _selected = MutableStateFlow(readTheme())
  override val selected: StateFlow<ThemeId> = _selected
  private val _lastCommittedBeforeCurrent = MutableStateFlow(readLastCommittedBeforeCurrent(_selected.value))
  override val lastCommittedBeforeCurrent: StateFlow<ThemeId?> = _lastCommittedBeforeCurrent

  override fun set(themeId: ThemeId) {
    val safeTheme = selectionPolicy.sanitizeSelection(themeId)
    val currentlyCommitted = _selected.value
    if (safeTheme == currentlyCommitted) return
    prefs.edit()
      .putString(key, safeTheme.id)
      .putString(previousKey, currentlyCommitted.id)
      .putInt(selectionSchemaKey, selectionSchemaVersion)
      .apply()
    _selected.value = safeTheme
    _lastCommittedBeforeCurrent.value = currentlyCommitted
  }

  override fun revertToLastCommitted() {
    val rollbackTheme = _lastCommittedBeforeCurrent.value ?: return
    val currentTheme = _selected.value
    if (rollbackTheme == currentTheme) return
    prefs.edit()
      .putString(key, rollbackTheme.id)
      .putString(previousKey, currentTheme.id)
      .putInt(selectionSchemaKey, selectionSchemaVersion)
      .apply()
    _selected.value = rollbackTheme
    _lastCommittedBeforeCurrent.value = currentTheme
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
    val migratedPrevious = parseSavedThemeOrNull(prefs.getString(previousKey, null))
      ?.takeIf { it != migrated }
    prefs.edit()
      .putString(key, migrated.id)
      .putString(previousKey, migratedPrevious?.id)
      .putInt(selectionSchemaKey, selectionSchemaVersion)
      .apply()
  }

  private fun readLastCommittedBeforeCurrent(currentTheme: ThemeId): ThemeId? {
    return parseSavedThemeOrNull(prefs.getString(previousKey, null))
      ?.takeIf { it != currentTheme }
  }

  private fun parseSavedThemeOrNull(rawThemeId: String?): ThemeId? {
    val parsed = ThemeId.fromId(rawThemeId) ?: return null
    return selectionPolicy.sanitizeSelection(parsed)
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
