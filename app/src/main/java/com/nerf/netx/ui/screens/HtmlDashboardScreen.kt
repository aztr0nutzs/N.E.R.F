package com.nerf.netx.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.nerf.netx.data.ThemeRepository
import com.nerf.netx.domain.AppServices
import com.nerf.netx.ui.theme.ThemeId

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlDashboardScreen(themeRepository: ThemeRepository, services: AppServices) {
  val bridge = remember { NerfWebBridge(services) }
  val url = remember { themeRepository.htmlAssetUrl(ThemeId.NERF_DASH_NEW_HTML).orEmpty() }
  val allowedMainUrls = remember { setOf(url) }

  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { ctx ->
      WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        webViewClient = object : WebViewClient() {
          override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return request.url.toString() !in allowedMainUrls
          }
        }
        addJavascriptInterface(bridge.attach(this), "NERF_NATIVE")
        loadUrl(url)
      }
    },
    update = { it.loadUrl(url) }
  )
}
