package com.nerf.netx.ui.screens

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.nerf.netx.domain.ActionResult
import com.nerf.netx.domain.AppServices
import com.nerf.netx.domain.QosMode
import com.nerf.netx.domain.RouterInfoResult
import com.nerf.netx.domain.ScanEvent
import com.nerf.netx.domain.ServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class NerfWebBridge(private val services: AppServices) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var webView: WebView? = null
  private var started = false

  fun attach(wv: WebView): NerfWebBridge {
    webView = wv
    if (!started) {
      started = true
      startEventStreams()
    }
    return this
  }

  private fun startEventStreams() {
    scope.launch {
      services.scan.events.collect { event ->
        when (event) {
          is ScanEvent.ScanStarted -> emit("scan.state", """{"phase":"RUNNING","targetsPlanned":${event.targetsPlanned},"startedAt":${event.startedAtEpochMs}}""")
          is ScanEvent.ScanProgress -> emit("scan.progress", """{"phase":"RUNNING","targetsPlanned":${event.targetsPlanned},"probesSent":${event.probesSent},"devicesFound":${event.devicesFound}}""")
          is ScanEvent.ScanDevice -> emit("scan.results", """{"updated":${event.updated},"device":${deviceJson(event.device)}}""")
          is ScanEvent.ScanDone -> emit("scan.state", """{"phase":"COMPLETE","targetsPlanned":${event.targetsPlanned},"probesSent":${event.probesSent},"devicesFound":${event.devicesFound},"completedAt":${event.completedAtEpochMs}}""")
          is ScanEvent.ScanError -> emit("scan.state", """{"phase":"ERROR","message":"${escape(event.message)}"}""")
        }
      }
    }

    scope.launch {
      services.scan.scanState.collectLatest { state ->
        emit("scan.state", """{"phase":"${state.phase}","scannedHosts":${state.scannedHosts},"discoveredHosts":${state.discoveredHosts},"message":${jsonString(state.message)}}""")
      }
    }

    scope.launch {
      services.scan.results.collectLatest { devices ->
        val items = devices.joinToString(",") { deviceJson(it) }
        emit("scan.results", """{"devices":[$items]}""")
      }
    }

    scope.launch {
      services.devices.devices.collectLatest { devices ->
        val items = devices.joinToString(",") { deviceJson(it) }
        emit("devices.update", """{"devices":[$items]}""")
      }
    }

    scope.launch {
      services.speedtest.ui.collectLatest { ui ->
        emit("speedtest.ui", speedtestUiJson(ui.running, ui.phase, ui.progress01, ui.downMbps, ui.upMbps, ui.latencyMs, ui.pingMs, ui.jitterMs, ui.packetLossPct))
      }
    }

    scope.launch {
      services.speedtest.latestResult.collectLatest { result ->
        if (result != null) {
          emit(
            "speedtest.result",
            """{"phase":"${result.phase}","downloadMbps":${result.downloadMbps ?: "null"},"uploadMbps":${result.uploadMbps ?: "null"},"pingMs":${result.pingMs ?: "null"},"jitterMs":${result.jitterMs ?: "null"},"packetLossPct":${result.packetLossPct ?: "null"},"serverName":${jsonString(result.serverName)},"error":${jsonString(result.error)}}"""
          )
        }
      }
    }

    scope.launch {
      services.topology.nodes.collectLatest { emitTopologyState() }
    }
    scope.launch {
      services.topology.links.collectLatest { emitTopologyState() }
    }
    scope.launch {
      services.topology.layoutMode.collectLatest { emitTopologyState() }
    }

    scope.launch {
      services.analytics.snapshot.collectLatest { s ->
        emit("analytics.snapshot", analyticsJson(s.downMbps, s.upMbps, s.latencyMs, s.jitterMs, s.packetLossPct, s.deviceCount, s.reachableCount, s.status.name, s.message))
      }
    }

    scope.launch {
      while (isActive) {
        val info = services.routerControl.info()
        emit("router.status", routerInfoJson(info))
        emit("wifi.environment", """{"status":"NOT_SUPPORTED","message":"Wi-Fi environment data is not available from router backend."}""")
        emit("security.summary", """{"status":"NOT_SUPPORTED","message":"Security summary is not available from router backend."}""")
        delay(5_000)
      }
    }
  }

  private suspend fun emitTopologyState() {
    val nodes = services.topology.nodes.value.joinToString(",") {
      """{"id":"${escape(it.id)}","label":"${escape(it.label)}","strength":${it.strength},"ip":"${escape(it.ip)}","selected":${it.selected}}"""
    }
    val links = services.topology.links.value.joinToString(",") {
      """{"fromId":"${escape(it.fromId)}","toId":"${escape(it.toId)}","quality":${it.quality}}"""
    }
    val mode = services.topology.layoutMode.value.name
    emit("topology.state", """{"mode":"$mode","nodes":[$nodes],"links":[$links]}""")
  }

  @JavascriptInterface
  fun request(action: String, payloadJson: String?): String {
    scope.launch {
      val p = parsePayload(payloadJson)
      val out = when (action) {
        "scan.start" -> { services.scan.startDeepScan(); ok("SCAN_STARTED", "scan started") }
        "scan.stop" -> { services.scan.stopScan(); ok("SCAN_STOPPED", "scan stopped") }
        "scan.state" -> {
          val s = services.scan.scanState.value
          """{"ok":true,"status":"OK","code":"SCAN_STATE","phase":"${s.phase}","scannedHosts":${s.scannedHosts},"discoveredHosts":${s.discoveredHosts},"message":${jsonString(s.message)}}"""
        }
        "devices.list" -> {
          val items = services.devices.devices.value.joinToString(",") { deviceJson(it) }
          """{"ok":true,"status":"OK","code":"DEVICES_LIST","devices":[$items]}"""
        }
        "device.details" -> {
          val id = p.optString("deviceId", "")
          val details = services.deviceControl.deviceDetails(id)
          if (details == null) notSupported("DEVICE_DETAILS_NOT_FOUND", "device details unavailable")
          else """{"ok":true,"status":"OK","code":"DEVICE_DETAILS","details":{"pingMs":${details.pingMs ?: "null"},"notes":${jsonString(details.notes)},"device":${deviceJson(details.device)}}}"""
        }
        "device.ping" -> actionResultJson(services.deviceControl.ping(p.optString("deviceId", "")))
        "device.block", "device.setBlocked" -> {
          val blocked = p.optBoolean("blocked", true)
          if (!blocked) notSupported("NOT_SUPPORTED", "Unblock action is NOT_SUPPORTED")
          else actionResultJson(services.deviceControl.block(p.optString("deviceId", "")))
        }
        "device.setPaused" -> notSupported("NOT_SUPPORTED", "Pause action is NOT_SUPPORTED")
        "device.rename" -> notSupported("NOT_SUPPORTED", "Rename action is NOT_SUPPORTED")
        "device.prioritize" -> actionResultJson(services.deviceControl.prioritize(p.optString("deviceId", "")))
        "map.refresh" -> { services.map.refresh(); services.topology.refreshTopology(); ok("MAP_REFRESHED", "map refreshed") }
        "map.state" -> {
          val nodes = services.topology.nodes.value.joinToString(",") { """{"id":"${escape(it.id)}","label":"${escape(it.label)}","strength":${it.strength},"ip":"${escape(it.ip)}","selected":${it.selected}}""" }
          val links = services.topology.links.value.joinToString(",") { """{"fromId":"${escape(it.fromId)}","toId":"${escape(it.toId)}","quality":${it.quality}}""" }
          """{"ok":true,"status":"OK","code":"MAP_STATE","mode":"${services.topology.layoutMode.value}","nodes":[$nodes],"links":[$links]}"""
        }
        "map.selectNode" -> { services.topology.selectNode(p.optString("nodeId", "").ifBlank { null }); ok("MAP_NODE_SELECTED", "node selected") }
        "speedtest.start" -> { services.speedtest.start(); ok("SPEEDTEST_STARTED", "speedtest started") }
        "speedtest.stop" -> { services.speedtest.stop(); ok("SPEEDTEST_STOPPED", "speedtest stopped") }
        "speedtest.reset" -> { services.speedtest.reset(); ok("SPEEDTEST_RESET", "speedtest reset") }
        "speedtest.state" -> {
          val s = services.speedtest.ui.value
          """{"ok":true,"status":"OK","code":"SPEEDTEST_STATE","state":${speedtestUiJson(s.running, s.phase, s.progress01, s.downMbps, s.upMbps, s.latencyMs, s.pingMs, s.jitterMs, s.packetLossPct)}}"""
        }
        "speedtest.history" -> {
          val history = services.speedtest.history.value.joinToString(",") {
            """{"id":"${escape(it.id)}","timestamp":${it.timestamp},"serverName":${jsonString(it.serverName)},"pingMs":${it.pingMs ?: "null"},"downMbps":${it.downMbps ?: "null"},"upMbps":${it.upMbps ?: "null"},"jitterMs":${it.jitterMs ?: "null"},"lossPct":${it.lossPct ?: "null"}}"""
          }
          """{"ok":true,"status":"OK","code":"SPEEDTEST_HISTORY","history":[$history]}"""
        }
        "router.info", "router.status" -> routerInfoJson(services.routerControl.info(), "ROUTER_STATUS")
        "router.toggleGuest", "router.setGuest" -> actionResultJson(services.routerControl.toggleGuest())
        "router.rebootRouter" -> actionResultJson(services.routerControl.rebootRouter())
        "router.toggleFirewall", "router.setFirewall" -> actionResultJson(services.routerControl.toggleFirewall())
        "router.toggleVpn", "router.setVpn" -> actionResultJson(services.routerControl.toggleVpn())
        "router.setDnsShield" -> notSupported("NOT_SUPPORTED", "DNS Shield control is NOT_SUPPORTED")
        "router.renewDhcp" -> actionResultJson(services.routerControl.renewDhcp())
        "router.flushDns" -> actionResultJson(services.routerControl.flushDns())
        "router.setQos" -> {
          val mode = p.optString("mode", "BALANCED").uppercase()
          val qos = runCatching { QosMode.valueOf(mode) }.getOrDefault(QosMode.BALANCED)
          actionResultJson(services.routerControl.setQos(qos))
        }
        "wifi.environment" -> notSupported("NOT_SUPPORTED", "Wi-Fi environment is NOT_SUPPORTED")
        "security.summary" -> notSupported("NOT_SUPPORTED", "Security summary is NOT_SUPPORTED")
        "analytics.snapshot" -> {
          services.analytics.refresh()
          val s = services.analytics.snapshot.value
          analyticsJson(s.downMbps, s.upMbps, s.latencyMs, s.jitterMs, s.packetLossPct, s.deviceCount, s.reachableCount, s.status.name, s.message, code = "ANALYTICS_SNAPSHOT")
        }
        "analytics.events" -> {
          val events = services.analytics.events.value.joinToString(",") { jsonString(it) }
          """{"ok":true,"status":"OK","code":"ANALYTICS_EVENTS","events":[$events]}"""
        }
        "analytics.exportJson" -> notSupported("NOT_SUPPORTED", "Analytics export is NOT_SUPPORTED")
        "diag.runFull" -> {
          services.scan.startDeepScan()
          services.analytics.refresh()
          val info = services.routerControl.info()
          """{"ok":true,"status":"RUNNING","code":"DIAG_STARTED","message":"Diagnostic run started","routerStatus":${jsonString(info.status.name)}}"""
        }
        "console.execute" -> {
          val cmd = p.optString("command", "").lowercase()
          when {
            cmd.startsWith("scan") -> { services.scan.startDeepScan(); ok("CONSOLE_SCAN_STARTED", "scan started") }
            cmd.startsWith("speedtest") -> { services.speedtest.start(); ok("CONSOLE_SPEEDTEST_STARTED", "speedtest started") }
            cmd.startsWith("router status") -> routerInfoJson(services.routerControl.info(), "ROUTER_STATUS")
            else -> notSupported("NOT_SUPPORTED", "Command not supported: $cmd")
          }
        }
        "assistant.quickAction" -> {
          val actionKey = p.optString("action", "")
          when (actionKey) {
            "speedtest" -> { services.speedtest.start(); ok("ASSISTANT_SPEEDTEST_STARTED", "assistant started speedtest") }
            "scan" -> { services.scan.startDeepScan(); ok("ASSISTANT_SCAN_STARTED", "assistant started scan") }
            else -> notSupported("NOT_SUPPORTED", "Assistant action is NOT_SUPPORTED")
          }
        }
        "assistant.command" -> {
          val command = p.optString("command", "")
          """{"ok":true,"status":"OK","code":"ASSISTANT_COMMAND_ACCEPTED","message":"Command accepted","command":${jsonString(command)}}"""
        }
        else -> """{"ok":false,"status":"ERROR","code":"UNKNOWN_ACTION","message":"unknown action"}"""
      }
      emit("action.result", out)
    }
    return """{"accepted":true}"""
  }

  private fun parsePayload(payloadJson: String?): JSONObject {
    return runCatching {
      if (payloadJson.isNullOrBlank()) JSONObject() else JSONObject(payloadJson)
    }.getOrDefault(JSONObject())
  }

  private fun ok(code: String, message: String): String =
    """{"ok":true,"status":"OK","code":"$code","message":"${escape(message)}"}"""

  private fun notSupported(code: String, message: String): String =
    """{"ok":false,"status":"NOT_SUPPORTED","code":"$code","message":"${escape(message)}"}"""

  private fun actionResultJson(res: ActionResult): String {
    val detailPairs = res.details.entries.joinToString(",") { (k, v) ->
      "\"${escape(k)}\":\"${escape(v)}\""
    }
    val details = if (detailPairs.isBlank()) "{}" else "{$detailPairs}"
    return """{"ok":${res.ok},"status":"${res.status}","code":"${escape(res.code)}","message":"${escape(res.message)}","errorReason":${jsonString(res.errorReason)},"details":$details}"""
  }

  private fun routerInfoJson(info: RouterInfoResult, code: String = "ROUTER_INFO"): String {
    val dns = info.dnsServers.joinToString(",") { "\"${escape(it)}\"" }
    val ok = info.status == ServiceStatus.OK
    return """{"ok":$ok,"status":"${info.status}","code":"$code","message":"${escape(info.message)}","gatewayIp":${jsonString(info.gatewayIp)},"dnsServers":[$dns],"ssid":${jsonString(info.ssid)},"linkSpeedMbps":${info.linkSpeedMbps ?: "null"}}"""
  }

  private fun analyticsJson(
    downMbps: Double?,
    upMbps: Double?,
    latencyMs: Double?,
    jitterMs: Double?,
    packetLossPct: Double?,
    deviceCount: Int,
    reachableCount: Int,
    status: String,
    message: String?,
    code: String = "ANALYTICS_SNAPSHOT"
  ): String {
    return """{"ok":true,"status":"$status","code":"$code","downMbps":${downMbps ?: "null"},"upMbps":${upMbps ?: "null"},"latencyMs":${latencyMs ?: "null"},"jitterMs":${jitterMs ?: "null"},"packetLossPct":${packetLossPct ?: "null"},"deviceCount":$deviceCount,"reachableCount":$reachableCount,"message":${jsonString(message)}}"""
  }

  private fun speedtestUiJson(
    running: Boolean,
    phase: String,
    progress01: Float,
    downMbps: Double?,
    upMbps: Double?,
    latencyMs: Int?,
    pingMs: Double?,
    jitterMs: Double?,
    packetLossPct: Double?
  ): String {
    return """{"running":$running,"phase":"${escape(phase)}","progress01":$progress01,"downMbps":${downMbps ?: "null"},"upMbps":${upMbps ?: "null"},"latencyMs":${latencyMs ?: "null"},"pingMs":${pingMs ?: "null"},"jitterMs":${jitterMs ?: "null"},"packetLossPct":${packetLossPct ?: "null"}}"""
  }

  private fun deviceJson(d: com.nerf.netx.domain.Device): String {
    val quality = d.rssiDbm?.let { (it + 100).coerceIn(5, 99) } ?: latencyToQuality(d.latencyMs)
    return """{"id":"${escape(d.id)}","name":"${escape(d.name)}","hostname":"${escape(d.hostname)}","ip":"${escape(d.ip)}","vendor":"${escape(d.vendor)}","mac":"${escape(d.mac)}","deviceType":"${escape(d.deviceType)}","riskScore":${d.riskScore},"quality":$quality,"latencyMs":${d.latencyMs ?: "null"},"signalStrength":${d.rssiDbm ?: "null"},"reachability":"${escape(d.reachabilityMethod)}","lastSeen":${d.lastSeenEpochMs},"isGateway":${d.isGateway},"online":${d.online},"isBlocked":false,"isPaused":false}"""
  }

  private fun jsonString(value: String?): String = if (value == null) "null" else "\"${escape(value)}\""

  private fun emit(eventName: String, payloadJson: String) {
    val js = "window.NERF && window.NERF.onEvent && window.NERF.onEvent('${escape(eventName)}',$payloadJson);"
    webView?.post { webView?.evaluateJavascript(js, null) }
  }

  private fun latencyToQuality(latencyMs: Int?): Int {
    if (latencyMs == null) return 0
    return (100 - latencyMs).coerceIn(8, 95)
  }

  private fun escape(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"")
}
