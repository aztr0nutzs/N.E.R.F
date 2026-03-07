package com.nerf.netx.ui.screens

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nerf.netx.data.ThemeRepository
import com.nerf.netx.domain.AppServices
import com.nerf.netx.ui.theme.ThemeId

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(themeId: ThemeId, themeRepository: ThemeRepository, services: AppServices) {
  val url = themeRepository.htmlAssetUrl(themeId)
  val bridge = remember { NerfWebBridge(services) }
  val allowedMainUrls = remember {
    setOf(
      "file:///android_asset/themes/nerf_main_dash/index.html",
      "file:///android_asset/themes/nerf_hud_alt/index.html",
      "file:///android_asset/themes/nerf_dash_new/index.html"
    )
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
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { ctx ->
            WebView(ctx).apply {
              settings.javaScriptEnabled = true
              settings.domStorageEnabled = true
              settings.allowFileAccess = true
              webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String?) {
                  super.onPageFinished(view, loadedUrl)
                  Log.d("NERF.WebView", "Loaded URL=$loadedUrl, selectedTheme=${themeId.id}")
                }

                override fun shouldOverrideUrlLoading(
                  view: WebView,
                  request: WebResourceRequest
                ): Boolean {
                  if (!request.isForMainFrame) return false
                  val next = request.url.toString()
                  val allowed = next in allowedMainUrls
                  if (!allowed) {
                    Log.w("NERF.WebView", "Blocked main-frame URL=$next")
                  }
                  return !allowed
                }
              }
              addJavascriptInterface(bridge.attach(this), "NERF_NATIVE")
              loadUrl(url)
            }
          },
          update = { it.loadUrl(url) }
        )
      }
    }
  }
}
