package com.nerf.netx.ui.screens

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
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
import com.nerf.netx.domain.ActionResult
import com.nerf.netx.domain.AppServices
import com.nerf.netx.domain.QosMode
import com.nerf.netx.domain.RouterInfoResult
import com.nerf.netx.domain.ScanEvent
import com.nerf.netx.ui.theme.ThemeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(themeId: ThemeId, themeRepository: ThemeRepository, services: AppServices) {
  val url = themeRepository.htmlAssetUrl(themeId)
  val bridge = remember { NerfWebBridge(services) }
  val allowedMainUrls = remember {
    setOf(
      "file:///android_asset/themes/nerf_main_dash/index.html",
      "file:///android_asset/themes/nerf_hud_alt/index.html"
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

private class NerfWebBridge(private val services: AppServices) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var webView: WebView? = null
  private var scanEventsJob: Job? = null

  fun attach(wv: WebView): NerfWebBridge {
    this.webView = wv
    if (scanEventsJob == null) {
      scanEventsJob = scope.launch {
        services.scan.events.collect { event ->
          when (event) {
            is ScanEvent.ScanStarted -> {
              emit(
                "scan_started",
                """{"targetsPlanned":${event.targetsPlanned},"startedAt":${event.startedAtEpochMs}}"""
              )
            }
            is ScanEvent.ScanProgress -> {
              emit(
                "scan_progress",
                """{"targetsPlanned":${event.targetsPlanned},"probesSent":${event.probesSent},"devicesFound":${event.devicesFound}}"""
              )
            }
            is ScanEvent.ScanDevice -> {
              val d = event.device
              val quality = d.rssiDbm?.let { (it + 100).coerceIn(5, 99) } ?: latencyToQuality(d.latencyMs)
              emit(
                "scan_device",
                """{"updated":${event.updated},"device":{"id":"${escape(d.id)}","name":"${escape(d.name)}","hostname":"${escape(d.hostname)}","ip":"${escape(d.ip)}","vendor":"${escape(d.vendor)}","mac":"${escape(d.mac)}","deviceType":"${escape(d.deviceType)}","riskScore":${d.riskScore},"quality":$quality,"latencyMs":${d.latencyMs ?: "null"},"signalStrength":${d.rssiDbm ?: "null"},"reachability":"${escape(d.reachabilityMethod)}","lastSeen":${d.lastSeenEpochMs},"isGateway":${d.isGateway}}}"""
              )
            }
            is ScanEvent.ScanDone -> {
              emit(
                "scan_done",
                """{"targetsPlanned":${event.targetsPlanned},"probesSent":${event.probesSent},"devicesFound":${event.devicesFound},"completedAt":${event.completedAtEpochMs}}"""
              )
            }
            is ScanEvent.ScanError -> {
              emit("scan_error", """{"message":"${escape(event.message)}"}""")
            }
          }
        }
      }
    }
    return this
  }

  @JavascriptInterface
  fun request(action: String, payloadJson: String?): String {
    scope.launch {
      val out = when (action) {
        "scan.start" -> {
          services.scan.startDeepScan()
          """{"ok":true,"status":"OK","code":"SCAN_STARTED","message":"scan started"}"""
        }
        "scan.stop" -> {
          services.scan.stopScan()
          """{"ok":true,"status":"OK","code":"SCAN_STOPPED","message":"scan stopped"}"""
        }
        "devices.list" -> {
          val items = services.devices.devices.value.joinToString(",") {
            val quality = it.rssiDbm?.let { rssi -> (rssi + 100).coerceIn(5, 99) } ?: latencyToQuality(it.latencyMs)
            """{"id":"${escape(it.id)}","name":"${escape(it.name)}","hostname":"${escape(it.hostname)}","ip":"${escape(it.ip)}","vendor":"${escape(it.vendor)}","mac":"${escape(it.mac)}","deviceType":"${escape(it.deviceType)}","riskScore":${it.riskScore},"quality":$quality,"latencyMs":${it.latencyMs ?: "null"},"signalStrength":${it.rssiDbm ?: "null"},"reachability":"${escape(it.reachabilityMethod)}","lastSeen":${it.lastSeenEpochMs},"isGateway":${it.isGateway}}"""
          }
          """{"ok":true,"status":"OK","code":"DEVICES_LIST","devices":[$items]}"""
        }
        "device.ping" -> {
          val id = extractField(payloadJson, "deviceId")
          actionResultJson(services.deviceControl.ping(id))
        }
        "device.block" -> {
          val id = extractField(payloadJson, "deviceId")
          actionResultJson(services.deviceControl.block(id))
        }
        "device.prioritize" -> {
          val id = extractField(payloadJson, "deviceId")
          actionResultJson(services.deviceControl.prioritize(id))
        }
        "map.refresh" -> {
          services.map.refresh()
          services.topology.refreshTopology()
          """{"ok":true,"status":"OK","code":"MAP_REFRESHED","nodes":${services.map.nodes.value.size}}"""
        }
        "speedtest.start" -> {
          services.speedtest.start()
          """{"ok":true,"status":"OK","code":"SPEEDTEST_STARTED"}"""
        }
        "speedtest.stop" -> {
          services.speedtest.stop()
          """{"ok":true,"status":"OK","code":"SPEEDTEST_STOPPED"}"""
        }
        "router.info" -> routerInfoJson(services.routerControl.info())
        "router.toggleGuest" -> actionResultJson(services.routerControl.toggleGuest())
        "router.renewDhcp" -> actionResultJson(services.routerControl.renewDhcp())
        "router.flushDns" -> actionResultJson(services.routerControl.flushDns())
        "router.rebootRouter" -> actionResultJson(services.routerControl.rebootRouter())
        "router.toggleFirewall" -> actionResultJson(services.routerControl.toggleFirewall())
        "router.toggleVpn" -> actionResultJson(services.routerControl.toggleVpn())
        "router.setQos" -> {
          val mode = extractField(payloadJson, "mode").uppercase()
          val qos = runCatching { QosMode.valueOf(mode) }.getOrDefault(QosMode.BALANCED)
          actionResultJson(services.routerControl.setQos(qos))
        }
        "analytics.snapshot" -> {
          services.analytics.refresh()
          val s = services.analytics.snapshot.value
          """{"ok":true,"status":"${s.status}","downMbps":${s.downMbps ?: "null"},"upMbps":${s.upMbps ?: "null"},"latencyMs":${s.latencyMs ?: "null"},"jitterMs":${s.jitterMs ?: "null"},"packetLossPct":${s.packetLossPct ?: "null"},"deviceCount":${s.deviceCount},"reachableCount":${s.reachableCount},"avgRttMs":${s.avgRttMs ?: "null"},"medianRttMs":${s.medianRttMs ?: "null"},"scanDurationMs":${s.scanDurationMs ?: "null"},"lastScanEpochMs":${s.lastScanEpochMs ?: "null"},"message":"${escape(s.message ?: "")}"}"""
        }
        else -> """{"ok":false,"status":"ERROR","code":"UNKNOWN_ACTION","message":"unknown action"}"""
      }
      emit("action.result", out)
    }
    return """{"accepted":true}"""
  }

  private fun actionResultJson(res: ActionResult): String {
    val detailPairs = res.details.entries.joinToString(",") { (k, v) ->
      "\"${escape(k)}\":\"${escape(v)}\""
    }
    val details = if (detailPairs.isBlank()) "{}" else "{$detailPairs}"
    return """{"ok":${res.ok},"status":"${res.status}","code":"${escape(res.code)}","message":"${escape(res.message)}","errorReason":"${escape(res.errorReason ?: "")}","details":$details}"""
  }

  private fun routerInfoJson(info: RouterInfoResult): String {
    val dns = info.dnsServers.joinToString(",") { "\"${escape(it)}\"" }
    return """{"ok":${info.status == com.nerf.netx.domain.ServiceStatus.OK},"status":"${info.status}","code":"ROUTER_INFO","message":"${escape(info.message)}","gatewayIp":${jsonString(info.gatewayIp)},"dnsServers":[$dns],"ssid":${jsonString(info.ssid)},"linkSpeedMbps":${info.linkSpeedMbps ?: "null"}}"""
  }

  private fun jsonString(value: String?): String = if (value == null) "null" else "\"${escape(value)}\""

  private fun emit(eventName: String, payloadJson: String) {
    val js = "window.NERF && window.NERF.onEvent && window.NERF.onEvent('${escape(eventName)}',$payloadJson);"
    webView?.post { webView?.evaluateJavascript(js, null) }
  }

  private fun extractField(payloadJson: String?, field: String): String {
    if (payloadJson.isNullOrBlank()) return ""
    val regex = Regex("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
    return regex.find(payloadJson)?.groupValues?.get(1) ?: ""
  }

  private fun latencyToQuality(latencyMs: Int?): Int {
    if (latencyMs == null) return 0
    return (100 - latencyMs).coerceIn(8, 95)
  }

  private fun escape(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"")
}
