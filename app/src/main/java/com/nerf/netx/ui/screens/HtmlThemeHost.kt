package com.nerf.netx.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.nerf.netx.domain.AppServices
import com.nerf.netx.ui.theme.ThemeId

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlThemeHost(
  themeId: ThemeId,
  url: String,
  allowedMainUrls: Set<String>,
  services: AppServices,
  modifier: Modifier = Modifier,
  onPageFinished: ((String?) -> Unit)? = null
) {
  key(themeId.id, url) {
    val bridge = remember(themeId, services) { NerfWebBridge(services) }

    AndroidView(
      modifier = modifier,
      factory = { ctx ->
        WebView(ctx).apply {
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          settings.allowFileAccess = true
          settings.allowContentAccess = true
          settings.allowFileAccessFromFileURLs = true
          settings.allowUniversalAccessFromFileURLs = true
          settings.mediaPlaybackRequiresUserGesture = false
          settings.cacheMode = WebSettings.LOAD_NO_CACHE
          settings.loadsImagesAutomatically = true
          webChromeClient = WebChromeClient()
          webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loadedUrl: String?) {
              super.onPageFinished(view, loadedUrl)
              bridge.onPageFinished()
              onPageFinished?.invoke(loadedUrl)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
              if (!request.isForMainFrame) return false
              return request.url.toString() !in allowedMainUrls
            }
          }
          addJavascriptInterface(bridge.attach(this, themeId), "NERF_NATIVE")
          loadUrl(url)
        }
      },
      update = { view ->
        if (view.url != url) {
          view.loadUrl(url)
        }
      },
      onRelease = { view ->
        view.stopLoading()
        view.removeJavascriptInterface("NERF_NATIVE")
        view.destroy()
      }
    )
  }
}
