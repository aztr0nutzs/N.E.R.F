package com.nerf.netx.ui.screens

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nerf.netx.data.ThemeRepository
import com.nerf.netx.domain.AppServices
import com.nerf.netx.domain.QosMode
import com.nerf.netx.ui.theme.ThemeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(themeId: ThemeId, themeRepository: ThemeRepository, services: AppServices) {
  val url = themeRepository.htmlAssetUrl(themeId)
  val bridge = remember { NerfWebBridge(services) }

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
              webViewClient = WebViewClient()
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

private class NerfWebBridge(private val services: AppServices) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var webView: WebView? = null

  fun attach(wv: WebView): NerfWebBridge {
    this.webView = wv
    return this
  }

  @JavascriptInterface
  fun request(action: String, payloadJson: String?): String {
    scope.launch {
      val out = when (action) {
        "scan.start" -> {
          services.scan.startDeepScan()
          """{"ok":true,"message":"scan started"}"""
        }
        "scan.stop" -> {
          services.scan.stopScan()
          """{"ok":true,"message":"scan stopped"}"""
        }
        "devices.list" -> {
          val items = services.devices.devices.value.joinToString(",") {
            """{"id":"${it.id}","name":"${it.name}","ip":"${it.ip}","vendor":"${it.vendor}","mac":"${it.mac}","riskScore":${it.riskScore}}"""
          }
          """{"ok":true,"devices":[$items]}"""
        }
        "device.ping" -> {
          val id = extractField(payloadJson, "deviceId")
          val res = services.deviceControl.ping(id)
          """{"ok":${res.ok},"message":"${escape(res.message)}"}"""
        }
        "device.block" -> {
          val id = extractField(payloadJson, "deviceId")
          val res = services.deviceControl.block(id)
          """{"ok":${res.ok},"message":"${escape(res.message)}"}"""
        }
        "device.prioritize" -> {
          val id = extractField(payloadJson, "deviceId")
          val res = services.deviceControl.prioritize(id)
          """{"ok":${res.ok},"message":"${escape(res.message)}"}"""
        }
        "map.refresh" -> {
          services.map.refresh()
          services.topology.refreshTopology()
          """{"ok":true,"nodes":${services.map.nodes.value.size}}"""
        }
        "speedtest.start" -> {
          services.speedtest.start()
          """{"ok":true}"""
        }
        "speedtest.stop" -> {
          services.speedtest.stop()
          """{"ok":true}"""
        }
        "router.toggleGuest" -> {
          val res = services.routerControl.toggleGuest()
          """{"ok":${res.ok},"message":"${escape(res.message)}"}"""
        }
        "router.renewDhcp" -> {
          val res = services.routerControl.renewDhcp()
          """{"ok":${res.ok},"message":"${escape(res.message)}"}"""
        }
        "router.flushDns" -> {
          val res = services.routerControl.flushDns()
          """{"ok":${res.ok},"message":"${escape(res.message)}"}"""
        }
        "router.rebootRouter" -> {
          val res = services.routerControl.rebootRouter()
          """{"ok":${res.ok},"message":"${escape(res.message)}"}"""
        }
        "router.toggleFirewall" -> {
          val res = services.routerControl.toggleFirewall()
          """{"ok":${res.ok},"message":"${escape(res.message)}"}"""
        }
        "router.toggleVpn" -> {
          val res = services.routerControl.toggleVpn()
          """{"ok":${res.ok},"message":"${escape(res.message)}"}"""
        }
        "router.setQos" -> {
          val mode = extractField(payloadJson, "mode").uppercase()
          val qos = runCatching { QosMode.valueOf(mode) }.getOrDefault(QosMode.BALANCED)
          val res = services.routerControl.setQos(qos)
          """{"ok":${res.ok},"message":"${escape(res.message)}"}"""
        }
        "analytics.snapshot" -> {
          services.analytics.refresh()
          val s = services.analytics.snapshot.value
          """{"ok":true,"downMbps":${s.downMbps},"upMbps":${s.upMbps},"latencyMs":${s.latencyMs},"jitterMs":${s.jitterMs},"packetLossPct":${s.packetLossPct},"deviceCount":${s.deviceCount}}"""
        }
        else -> """{"ok":false,"message":"unknown action"}"""
      }
      emit("action.result", out)
    }
    return """{"accepted":true}"""
  }

  private fun emit(eventName: String, payloadJson: String) {
    val js = "window.NERF && window.NERF.onEvent && window.NERF.onEvent('${escape(eventName)}',$payloadJson);"
    webView?.post { webView?.evaluateJavascript(js, null) }
  }

  private fun extractField(payloadJson: String?, field: String): String {
    if (payloadJson.isNullOrBlank()) return ""
    val regex = Regex("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
    return regex.find(payloadJson)?.groupValues?.get(1) ?: ""
  }

  private fun escape(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"")
}
