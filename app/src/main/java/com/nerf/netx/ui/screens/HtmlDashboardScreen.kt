package com.nerf.netx.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nerf.netx.data.ThemeRepository
import com.nerf.netx.domain.AppServices

@Composable
fun HtmlDashboardScreen(themeRepository: ThemeRepository, services: AppServices) {
  val selectedTheme by themeRepository.selected.collectAsState()
  val resolvedTheme = selectedTheme.takeIf { themeRepository.htmlAssetUrl(it) != null }
    ?: themeRepository.availableThemes.firstOrNull()
    ?: return
  val url = themeRepository.htmlAssetUrl(resolvedTheme) ?: return
  val allowedMainUrls = remember(themeRepository.availableThemes) {
    themeRepository.availableThemes.mapNotNull(themeRepository::htmlAssetUrl).toSet()
  }

  HtmlThemeHost(
    themeId = resolvedTheme,
    url = url,
    allowedMainUrls = allowedMainUrls,
    services = services,
    modifier = Modifier.fillMaxSize()
  )
}
