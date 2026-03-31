package com.nerf.netx.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import com.nerf.netx.ui.theme.ThemeId

interface ThemeVisualAssetRepository {
  fun screenshotPath(themeId: ThemeId): String?
  fun loadScreenshot(themeId: ThemeId): Bitmap?
}

class ThemeVisualAssetRepositoryImpl(context: Context) : ThemeVisualAssetRepository {
  private val appContext = context.applicationContext
  private val screenshotCache = LruCache<String, Bitmap>(4)

  override fun screenshotPath(themeId: ThemeId): String? {
    val path = rawScreenshotPath(themeId) ?: return null
    return path.takeIf(::assetExists)
  }

  override fun loadScreenshot(themeId: ThemeId): Bitmap? {
    val path = screenshotPath(themeId) ?: return null
    screenshotCache[path]?.let { return it }

    return runCatching {
      appContext.assets.open(path).use { stream ->
        BitmapFactory.decodeStream(stream)
      }
    }.getOrNull()?.also { bitmap ->
      screenshotCache.put(path, bitmap)
    }
  }

  private fun rawScreenshotPath(themeId: ThemeId): String? {
    return when (themeId) {
      ThemeId.NERF_MAIN_DASH_HTML -> "themes/nerf_main_dash/screenshot.png"
      ThemeId.NERF_HUD_ALT_HTML -> "themes/nerf_hud_alt/screenshot.png"
      else -> null
    }
  }

  private fun assetExists(path: String): Boolean {
    return runCatching {
      appContext.assets.open(path).use { }
      true
    }.getOrDefault(false)
  }
}
