package com.nerf.netx.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.nerf.netx.data.ThemeRepository
import com.nerf.netx.ui.theme.ThemeId

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(themeId: ThemeId, themeRepository: ThemeRepository) {
  val url = themeRepository.htmlAssetUrl(themeId)

  Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("THEME PREVIEW", style = MaterialTheme.typography.titleLarge)
    Text("Selected: " + themeId.displayName)

    if (url == null) {
      ElevatedCard {
        Column(Modifier.padding(12.dp)) {
          Text("Native theme selected.")
          Text("Switch to an HTML theme in Settings to preview it here.")
        }
      }
    } else {
      ElevatedCard(Modifier.fillMaxWidth().weight(1f)) {
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { ctx ->
            WebView(ctx).apply {
              settings.javaScriptEnabled = true
              settings.domStorageEnabled = true
              webViewClient = WebViewClient()
              loadUrl(url)
            }
          },
          update = { it.loadUrl(url) }
        )
      }
    }
  }
}
