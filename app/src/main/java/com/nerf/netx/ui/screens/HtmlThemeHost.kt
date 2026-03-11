package com.nerf.netx.ui.screens

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebResourceResponse
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
import java.io.ByteArrayInputStream

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
          webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
              Log.d(
                "NERF.WebConsole",
                "${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}"
              )
              return super.onConsoleMessage(consoleMessage)
            }
          }
          webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loadedUrl: String?) {
              super.onPageFinished(view, loadedUrl)
              bridge.onPageFinished()
              view.evaluateJavascript(
                """
                  (function() {
                    var overview = document.getElementById('s-ov');
                    return JSON.stringify({
                      readyState: document.readyState,
                      overviewChildren: overview ? overview.children.length : -1,
                      hasBoot: typeof boot,
                      hasBuildOv: typeof buildOv,
                      hasSwitch: typeof sw
                    });
                  })();
                """.trimIndent()
              ) { snapshot ->
                Log.d("NERF.WebDiag", "onPageFinished url=$loadedUrl snapshot=$snapshot")
              }
              onPageFinished?.invoke(loadedUrl)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
              if (themeId != ThemeId.NERF_DASH_NEW_HTML) return null
              val requestedUrl = request.url.toString()
              val deferredHeaders = listOf(
                "nerf_header1.mp4",
                "nerf_header2.mp4",
                "nerf_header3.mp4",
                "nerf_header4.mp4"
              )
              val shouldDefer = deferredHeaders.any { requestedUrl.contains(it, ignoreCase = true) } &&
                !requestedUrl.contains("live=1", ignoreCase = true)
              if (!shouldDefer) return null
              return WebResourceResponse(
                "video/mp4",
                null,
                204,
                "No Content",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
              )
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
