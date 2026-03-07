package com.nerf.netx.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nerf.netx.data.ThemeRepository
import com.nerf.netx.domain.AppServices
import com.nerf.netx.ui.theme.ThemeId

@Composable
fun PreviewScreen(themeId: ThemeId, themeRepository: ThemeRepository, services: AppServices) {
  val url = themeRepository.htmlAssetUrl(themeId)
  val allowedMainUrls = remember(themeRepository.availableThemes) {
    themeRepository.availableThemes.mapNotNull(themeRepository::htmlAssetUrl).toSet()
  }

  Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("THEME PREVIEW", style = MaterialTheme.typography.titleLarge)
    Text("Selected: " + themeId.displayName)

    if (url == null) {
      ElevatedCard {
        Column(Modifier.padding(12.dp)) {
          Text("Native theme selected.")
          Text("Switch to an HTML dashboard in Settings to preview it here.")
        }
      }
    } else {
      ElevatedCard(Modifier.fillMaxWidth().weight(1f)) {
        HtmlThemeHost(
          modifier = Modifier.fillMaxSize(),
          themeId = themeId,
          url = url,
          allowedMainUrls = allowedMainUrls,
          services = services,
          onPageFinished = { loadedUrl ->
            Log.d("NERF.WebView", "Loaded URL=$loadedUrl, selectedTheme=${themeId.id}")
            if (loadedUrl != null && loadedUrl !in allowedMainUrls) {
              Log.w("NERF.WebView", "Blocked main-frame URL=$loadedUrl")
            }
          }
        )
      }
    }
  }
}
