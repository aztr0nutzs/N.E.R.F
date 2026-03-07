package com.nerf.netx.ui.screens

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.nerf.netx.assistant.context.BuildAssistantContextUseCase
import com.nerf.netx.assistant.diagnostics.AssistantDiagnosticsEngine
import com.nerf.netx.assistant.diagnostics.NetworkDiagnosisEngine
import com.nerf.netx.assistant.model.AssistantResponse
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.assistant.orchestrator.AssistantActionPolicy
import com.nerf.netx.assistant.orchestrator.AssistantEntityResolver
import com.nerf.netx.assistant.orchestrator.AssistantOrchestrator
import com.nerf.netx.assistant.orchestrator.AssistantResponseComposer
import com.nerf.netx.assistant.parser.AssistantIntentParser
import com.nerf.netx.assistant.recommendation.AssistantStarterPromptsProvider
import com.nerf.netx.assistant.recommendation.RecommendationEngine
import com.nerf.netx.assistant.state.AssistantSessionMemory
import com.nerf.netx.assistant.tools.AssistantToolRegistry
import com.nerf.netx.assistant.tools.DeviceTool
import com.nerf.netx.assistant.tools.NavigationTool
import com.nerf.netx.assistant.tools.RouterTool
import com.nerf.netx.assistant.tools.ScanTool
import com.nerf.netx.assistant.tools.SpeedtestTool
import com.nerf.netx.domain.ActionResult
import com.nerf.netx.domain.AnalyticsSnapshot
import com.nerf.netx.domain.AppServices
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.MapLink
import com.nerf.netx.domain.MapNode
import com.nerf.netx.domain.QosMode
import com.nerf.netx.domain.RouterInfoResult
import com.nerf.netx.domain.RouterStatusSnapshot
import com.nerf.netx.domain.ScanEvent
import com.nerf.netx.domain.ServiceStatus
import com.nerf.netx.domain.SpeedtestResult
import com.nerf.netx.domain.SpeedtestUiState
import com.nerf.netx.ui.theme.ThemeBridgeContract
import com.nerf.netx.ui.theme.ThemeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class NerfWebBridge(private val services: AppServices) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var webView: WebView? = null
  private var currentThemeId: ThemeId? = null
  private var started = false

  private val assistantOrchestrator by lazy {
    val prompts = AssistantStarterPromptsProvider()
    AssistantOrchestrator(
      contextUseCase = BuildAssistantContextUseCase(services),
      parser = AssistantIntentParser(),
      actionPolicy = AssistantActionPolicy(),
      sessionMemory = AssistantSessionMemory(),
      toolRegistry = AssistantToolRegistry(
        scanTool = ScanTool(services),
        speedtestTool = SpeedtestTool(services),
        deviceTool = DeviceTool(services),
        routerTool = RouterTool(services),
        navigationTool = NavigationTool()
      ),
      entityResolver = AssistantEntityResolver(),
      diagnosticsEngine = AssistantDiagnosticsEngine(),
      networkDiagnosisEngine = NetworkDiagnosisEngine(),
      recommendationEngine = RecommendationEngine(),
      responseComposer = AssistantResponseComposer(prompts)
    )
  }

  fun attach(wv: WebView, themeId: ThemeId? = null): NerfWebBridge {
    webView = wv
    currentThemeId = themeId
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
          is ScanEvent.ScanStarted -> {
            val payload = JSONObject()
              .put("phase", "RUNNING")
              .put("targetsPlanned", event.targetsPlanned)
              .put("startedAt", event.startedAtEpochMs)
            emit("scan.state", payload.toString())
            emit("scan_progress", payload.toString())
          }
          is ScanEvent.ScanProgress -> {
            val payload = JSONObject()
              .put("phase", "RUNNING")
              .put("targetsPlanned", event.targetsPlanned)
              .put("probesSent", event.probesSent)
              .put("devicesFound", event.devicesFound)
            emit("scan.progress", payload.toString())
            emit("scan_progress", payload.toString())
          }
          is ScanEvent.ScanDevice -> {
            val payload = JSONObject()
              .put("updated", event.updated)
              .put("device", deviceJson(event.device))
            emit("scan.results", payload.toString())
          }
          is ScanEvent.ScanDone -> {
            val payload = JSONObject()
              .put("phase", "COMPLETE")
              .put("targetsPlanned", event.targetsPlanned)
              .put("probesSent", event.probesSent)
              .put("devicesFound", event.devicesFound)
              .put("completedAt", event.completedAtEpochMs)
            emit("scan.state", payload.toString())
            emit("scan_done", payload.toString())
          }
          is ScanEvent.ScanError -> {
            val payload = JSONObject()
              .put("phase", "ERROR")
              .put("message", event.message)
            emit("scan.state", payload.toString())
            emit("scan_error", payload.toString())
          }
        }
      }
    }

    scope.launch {
      services.scan.scanState.collectLatest { state ->
        emit(
          "scan.state",
          JSONObject()
            .put("phase", state.phase.name)
            .put("scannedHosts", state.scannedHosts)
            .put("discoveredHosts", state.discoveredHosts)
            .put("message", state.message)
            .toString()
        )
      }
    }

    scope.launch {
      services.scan.results.collectLatest { devices ->
        emit(
          "scan.results",
          JSONObject().put("devices", devicesJson(devices)).toString()
        )
      }
    }

    scope.launch {
      services.devices.devices.collectLatest { devices ->
        emit(
          "devices.update",
          JSONObject().put("devices", devicesJson(devices)).toString()
        )
        emitDerivedState()
      }
    }

    scope.launch {
      services.speedtest.ui.collectLatest { ui ->
        emit("speedtest.ui", speedtestUiJson(ui).toString())
      }
    }

    scope.launch {
      services.speedtest.latestResult.collectLatest { result ->
        result ?: return@collectLatest
        emit("speedtest.result", speedtestResultJson(result).toString())
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
      services.analytics.snapshot.collectLatest { snapshot ->
        emit("analytics.snapshot", analyticsJson(snapshot, code = "ANALYTICS_SNAPSHOT").toString())
        emitDerivedState()
      }
    }

    scope.launch {
      services.analytics.events.collectLatest { events ->
        emit(
          "analytics.events",
          JSONObject().put("events", JSONArray(events)).toString()
        )
      }
    }

    scope.launch {
      services.routerControl.status.collectLatest { snapshot ->
        emit("router.status", routerStatusJson(snapshot, "ROUTER_STATUS").toString())
        emitDerivedState(snapshot)
      }
    }

    scope.launch {
      while (isActive) {
        services.routerControl.refreshStatus()
        emitDerivedState()
        delay(5_000)
      }
    }
  }

  private suspend fun emitTopologyState() {
    emit(
      "topology.state",
      topologyStateJson(
        services.topology.nodes.value,
        services.topology.links.value,
        services.topology.layoutMode.value.name
      ).toString()
    )
  }

  private suspend fun emitDerivedState(routerStatus: RouterStatusSnapshot = services.routerControl.status.value) {
    emit(
      "wifi.environment",
      wifiEnvironmentJson(
        devices = services.devices.devices.value,
        analytics = services.analytics.snapshot.value,
        routerStatus = routerStatus
      ).toString()
    )
    emit(
      "security.summary",
      securitySummaryJson(services.devices.devices.value).toString()
    )
  }

  @JavascriptInterface
  fun request(action: String, payloadJson: String?): String {
    if (!ThemeBridgeContract.supportsAction(action)) {
      scope.launch {
        emit(
          ThemeBridgeContract.Events.ACTION_RESULT,
          error("UNKNOWN_ACTION", "Unknown action: $action")
        )
      }
      return """{"accepted":false,"error":"UNKNOWN_ACTION"}"""
    }
    scope.launch {
      val payload = parsePayload(payloadJson)
      val result = handleRequest(action, payload)
      emit(ThemeBridgeContract.Events.ACTION_RESULT, result)
    }
    return """{"accepted":true}"""
  }

  private suspend fun handleRequest(action: String, payload: JSONObject): String {
    return when (action) {
      "scan.start" -> {
        services.scan.startDeepScan()
        ok("SCAN_STARTED", "Scan started.")
      }
      "scan.stop" -> {
        services.scan.stopScan()
        ok("SCAN_STOPPED", "Scan stopped.")
      }
      "scan.state" -> {
        val state = services.scan.scanState.value
        JSONObject()
          .put("ok", true)
          .put("status", ServiceStatus.OK.name)
          .put("code", "SCAN_STATE")
          .put("phase", state.phase.name)
          .put("scannedHosts", state.scannedHosts)
          .put("discoveredHosts", state.discoveredHosts)
          .put("message", state.message)
          .toString()
      }
      "devices.list" -> {
        JSONObject()
          .put("ok", true)
          .put("status", ServiceStatus.OK.name)
          .put("code", "DEVICES_LIST")
          .put("devices", devicesJson(services.devices.devices.value))
          .toString()
      }
      "device.details" -> {
        val details = services.deviceControl.deviceDetails(payload.optString("deviceId", ""))
          ?: return notSupported("DEVICE_DETAILS_UNAVAILABLE", "Device details are unavailable.")
        JSONObject()
          .put("ok", true)
          .put("status", ServiceStatus.OK.name)
          .put("code", "DEVICE_DETAILS")
          .put(
            "details",
            JSONObject()
              .put("pingMs", details.pingMs)
              .put("notes", details.notes)
              .put("trafficMessage", details.trafficMessage)
              .put("device", deviceJson(details.device))
          )
          .toString()
      }
      "device.ping" -> actionResultJson(services.deviceControl.ping(payload.optString("deviceId", "")))
      "device.block" -> actionResultJson(services.deviceControl.block(payload.optString("deviceId", "")))
      "device.setBlocked" -> {
        val blocked = if (payload.has("blocked")) payload.optBoolean("blocked") else true
        actionResultJson(services.deviceControl.setBlocked(payload.optString("deviceId", ""), blocked))
      }
      "device.setPaused" -> {
        val paused = if (payload.has("paused")) payload.optBoolean("paused") else true
        actionResultJson(services.deviceControl.setPaused(payload.optString("deviceId", ""), paused))
      }
      "device.rename" -> actionResultJson(
        services.deviceControl.rename(
          payload.optString("deviceId", ""),
          payload.optString("name", "")
        )
      )
      "device.prioritize" -> actionResultJson(services.deviceControl.prioritize(payload.optString("deviceId", "")))
      "map.refresh" -> {
        services.map.refresh()
        services.topology.refreshTopology()
        ok("MAP_REFRESHED", "Topology refreshed.")
      }
      "map.state" -> topologyStateJson(
        services.topology.nodes.value,
        services.topology.links.value,
        services.topology.layoutMode.value.name,
        code = "MAP_STATE"
      ).put("ok", true).put("status", ServiceStatus.OK.name).toString()
      "map.selectNode" -> {
        services.topology.selectNode(payload.optString("nodeId", "").ifBlank { null })
        ok("MAP_NODE_SELECTED", "Map node selected.")
      }
      "speedtest.start" -> {
        services.speedtest.start()
        ok("SPEEDTEST_STARTED", "Speedtest started.")
      }
      "speedtest.stop" -> {
        services.speedtest.stop()
        ok("SPEEDTEST_STOPPED", "Speedtest stopped.")
      }
      "speedtest.reset" -> {
        services.speedtest.reset()
        ok("SPEEDTEST_RESET", "Speedtest reset.")
      }
      "speedtest.state" -> {
        JSONObject()
          .put("ok", true)
          .put("status", ServiceStatus.OK.name)
          .put("code", "SPEEDTEST_STATE")
          .put("state", speedtestUiJson(services.speedtest.ui.value))
          .toString()
      }
      "speedtest.history" -> {
        val history = JSONArray()
        services.speedtest.history.value.forEach { item ->
          history.put(
            JSONObject()
              .put("id", item.id)
              .put("timestamp", item.timestamp)
              .put("serverName", item.serverName)
              .put("pingMs", item.pingMs)
              .put("downMbps", item.downMbps)
              .put("upMbps", item.upMbps)
              .put("jitterMs", item.jitterMs)
              .put("lossPct", item.lossPct)
          )
        }
        JSONObject()
          .put("ok", true)
          .put("status", ServiceStatus.OK.name)
          .put("code", "SPEEDTEST_HISTORY")
          .put("history", history)
          .toString()
      }
      "router.info", "router.status" -> routerStatusJson(services.routerControl.refreshStatus(), "ROUTER_STATUS").toString()
      "router.toggleGuest" -> actionResultJson(services.routerControl.toggleGuest())
      "router.setGuest" -> actionResultJson(
        services.routerControl.setGuestWifiEnabled(if (payload.has("enabled")) payload.optBoolean("enabled") else true)
      )
      "router.setDnsShield" -> actionResultJson(
        services.routerControl.setDnsShieldEnabled(if (payload.has("enabled")) payload.optBoolean("enabled") else true)
      )
      "router.rebootRouter" -> actionResultJson(services.routerControl.rebootRouter())
      "router.toggleFirewall" -> actionResultJson(services.routerControl.toggleFirewall())
      "router.setFirewall" -> actionResultJson(
        services.routerControl.setFirewallEnabled(if (payload.has("enabled")) payload.optBoolean("enabled") else true)
      )
      "router.toggleVpn" -> actionResultJson(services.routerControl.toggleVpn())
      "router.setVpn" -> actionResultJson(
        services.routerControl.setVpnEnabled(if (payload.has("enabled")) payload.optBoolean("enabled") else true)
      )
      "router.renewDhcp" -> actionResultJson(services.routerControl.renewDhcp())
      "router.flushDns" -> actionResultJson(services.routerControl.flushDns())
      "router.setQos" -> {
        val mode = runCatching {
          QosMode.valueOf(payload.optString("mode", "BALANCED").uppercase())
        }.getOrDefault(QosMode.BALANCED)
        actionResultJson(services.routerControl.setQos(mode))
      }
      "wifi.environment" -> wifiEnvironmentJson(
        devices = services.devices.devices.value,
        analytics = services.analytics.snapshot.value,
        routerStatus = services.routerControl.status.value
      ).toString()
      "security.summary" -> securitySummaryJson(services.devices.devices.value).toString()
      "analytics.snapshot" -> {
        services.analytics.refresh()
        analyticsJson(services.analytics.snapshot.value, code = "ANALYTICS_SNAPSHOT").toString()
      }
      "analytics.events" -> {
        JSONObject()
          .put("ok", true)
          .put("status", ServiceStatus.OK.name)
          .put("code", "ANALYTICS_EVENTS")
          .put("events", JSONArray(services.analytics.events.value))
          .toString()
      }
      "analytics.exportJson" -> actionResultJson(services.analytics.exportJson())
      "diag.runFull" -> {
        services.scan.startDeepScan()
        services.analytics.refresh()
        services.routerControl.refreshStatus()
        JSONObject()
          .put("ok", true)
          .put("status", ServiceStatus.RUNNING.name)
          .put("code", "DIAG_STARTED")
          .put("message", "Diagnostic run started.")
          .toString()
      }
      "console.execute" -> handleConsoleCommand(payload.optString("command", ""))
      "assistant.quickAction" -> {
        val command = when (payload.optString("action", "").lowercase()) {
          "scan" -> "scan network"
          "speedtest" -> "start speedtest"
          "diagnostics" -> "run diagnostics"
          "latency" -> "why is latency high"
          else -> ""
        }
        if (command.isBlank()) {
          notSupported("ASSISTANT_ACTION_UNSUPPORTED", "Assistant quick action is not supported.")
        } else {
          handleAssistantCommand(command)
        }
      }
      "assistant.command" -> handleAssistantCommand(payload.optString("command", ""))
      else -> error("UNKNOWN_ACTION", "Unknown action: $action")
    }
  }

  private suspend fun handleConsoleCommand(command: String): String {
    val normalized = command.trim().lowercase()
    val pingTarget = normalized.removePrefix("ping").trim()
    return when {
      normalized.startsWith("scan") -> {
        services.scan.startDeepScan()
        ok("CONSOLE_SCAN_STARTED", "Scan started from console.")
      }
      normalized.startsWith("speedtest") -> {
        services.speedtest.start()
        ok("CONSOLE_SPEEDTEST_STARTED", "Speedtest started from console.")
      }
      normalized.startsWith("router status") -> routerStatusJson(services.routerControl.refreshStatus(), "ROUTER_STATUS").toString()
      normalized.startsWith("flush") -> actionResultJson(services.routerControl.flushDns())
      normalized.startsWith("export") -> actionResultJson(services.analytics.exportJson())
      normalized.startsWith("diagnostics") -> {
        services.scan.startDeepScan()
        services.analytics.refresh()
        ok("CONSOLE_DIAGNOSTICS_STARTED", "Diagnostics started from console.")
      }
      normalized.startsWith("diag") -> {
        services.scan.startDeepScan()
        services.analytics.refresh()
        ok("CONSOLE_DIAGNOSTICS_STARTED", "Diagnostics started from console.")
      }
      normalized.startsWith("ping ") -> {
        val device = services.devices.devices.value.firstOrNull { device ->
          device.id.equals(pingTarget, ignoreCase = true) ||
            device.ip.equals(pingTarget, ignoreCase = true) ||
            device.name.equals(pingTarget, ignoreCase = true) ||
            device.hostname.equals(pingTarget, ignoreCase = true)
        } ?: return notSupported("CONSOLE_COMMAND_UNSUPPORTED", "No known device matches: $pingTarget")
        actionResultJson(services.deviceControl.ping(device.id))
      }
      else -> notSupported("CONSOLE_COMMAND_UNSUPPORTED", "Command not supported: $normalized")
    }
  }

  private suspend fun handleAssistantCommand(command: String): String {
    if (command.isBlank()) {
      return error("ASSISTANT_EMPTY", "Assistant command is empty.")
    }
    val response = assistantOrchestrator.handleUserMessage(command)
    emit("assistant.response", assistantResponseJson(response).toString())
    return JSONObject()
      .put("ok", true)
      .put("status", ServiceStatus.OK.name)
      .put("code", "ASSISTANT_RESPONSE")
      .put("title", response.title)
      .put("message", response.message)
      .put("severity", response.severity.name)
      .toString()
  }

  private fun parsePayload(payloadJson: String?): JSONObject {
    return runCatching {
      if (payloadJson.isNullOrBlank()) JSONObject() else JSONObject(payloadJson)
    }.getOrDefault(JSONObject())
  }

  private fun ok(code: String, message: String): String {
    return JSONObject()
      .put("ok", true)
      .put("status", ServiceStatus.OK.name)
      .put("code", code)
      .put("message", message)
      .toString()
  }

  private fun error(code: String, message: String): String {
    return JSONObject()
      .put("ok", false)
      .put("status", ServiceStatus.ERROR.name)
      .put("code", code)
      .put("message", message)
      .toString()
  }

  private fun notSupported(code: String, message: String): String {
    return JSONObject()
      .put("ok", false)
      .put("status", ServiceStatus.NOT_SUPPORTED.name)
      .put("code", code)
      .put("message", message)
      .toString()
  }

  private fun actionResultJson(result: ActionResult): String {
    return JSONObject()
      .put("ok", result.ok)
      .put("status", result.status.name)
      .put("code", result.code)
      .put("message", result.message)
      .put("errorReason", result.errorReason)
      .put("details", JSONObject(result.details))
      .toString()
  }

  private fun analyticsJson(snapshot: AnalyticsSnapshot, code: String): JSONObject {
    return JSONObject()
      .put("ok", true)
      .put("status", snapshot.status.name)
      .put("code", code)
      .put("downMbps", snapshot.downMbps)
      .put("upMbps", snapshot.upMbps)
      .put("latencyMs", snapshot.latencyMs)
      .put("jitterMs", snapshot.jitterMs)
      .put("packetLossPct", snapshot.packetLossPct)
      .put("deviceCount", snapshot.deviceCount)
      .put("reachableCount", snapshot.reachableCount)
      .put("avgRttMs", snapshot.avgRttMs)
      .put("medianRttMs", snapshot.medianRttMs)
      .put("scanDurationMs", snapshot.scanDurationMs)
      .put("lastScanEpochMs", snapshot.lastScanEpochMs)
      .put("message", snapshot.message)
  }

  private fun speedtestUiJson(ui: SpeedtestUiState): JSONObject {
    return JSONObject()
      .put("running", ui.running)
      .put("phase", ui.phase)
      .put("progress01", ui.progress01)
      .put("downMbps", ui.downMbps)
      .put("upMbps", ui.upMbps)
      .put("latencyMs", ui.latencyMs)
      .put("pingMs", ui.pingMs)
      .put("jitterMs", ui.jitterMs)
      .put("packetLossPct", ui.packetLossPct)
      .put("activeServerId", ui.activeServerId)
      .put("activeServerName", ui.activeServerName)
      .put("status", ui.status.name)
      .put("message", ui.message)
      .put("reason", ui.reason)
  }

  private fun speedtestResultJson(result: SpeedtestResult): JSONObject {
    return JSONObject()
      .put("phase", result.phase.name)
      .put("downloadMbps", result.downloadMbps)
      .put("uploadMbps", result.uploadMbps)
      .put("pingMs", result.pingMs)
      .put("jitterMs", result.jitterMs)
      .put("packetLossPct", result.packetLossPct)
      .put("serverName", result.serverName)
      .put("serverId", result.serverId)
      .put("error", result.error)
      .put("startedAt", result.startedAt)
      .put("finishedAt", result.finishedAt)
  }

  private fun topologyStateJson(
    nodes: List<MapNode>,
    links: List<MapLink>,
    mode: String,
    code: String = "TOPOLOGY_STATE"
  ): JSONObject {
    val nodeArray = JSONArray()
    nodes.forEach { node ->
      nodeArray.put(
        JSONObject()
          .put("id", node.id)
          .put("label", node.label)
          .put("strength", node.strength)
          .put("ip", node.ip)
          .put("selected", node.selected)
      )
    }
    val linkArray = JSONArray()
    links.forEach { link ->
      linkArray.put(
        JSONObject()
          .put("fromId", link.fromId)
          .put("toId", link.toId)
          .put("quality", link.quality)
      )
    }
    return JSONObject()
      .put("code", code)
      .put("mode", mode)
      .put("nodes", nodeArray)
      .put("links", linkArray)
  }

  private fun routerStatusJson(snapshot: RouterStatusSnapshot, code: String): JSONObject {
    return JSONObject()
      .put("ok", snapshot.status == ServiceStatus.OK)
      .put("status", snapshot.status.name)
      .put("code", code)
      .put("message", snapshot.message)
      .put("gatewayIp", snapshot.gatewayIp)
      .put("publicIp", snapshot.publicIp)
      .put("dnsServers", JSONArray(snapshot.dnsServers))
      .put("ssid", snapshot.ssid)
      .put("linkSpeedMbps", snapshot.linkSpeedMbps)
      .put("accessMode", snapshot.accessMode)
      .put("capabilities", JSONArray(snapshot.capabilities))
      .put("guestWifi", featureStateJson(snapshot.guestWifi))
      .put("dnsShield", featureStateJson(snapshot.dnsShield))
      .put("firewall", featureStateJson(snapshot.firewall))
      .put("vpn", featureStateJson(snapshot.vpn))
      .put("qosMode", snapshot.qosMode)
      .put("lastUpdatedEpochMs", snapshot.lastUpdatedEpochMs)
  }

  private fun featureStateJson(feature: com.nerf.netx.domain.RouterFeatureState): JSONObject {
    return JSONObject()
      .put("supported", feature.supported)
      .put("enabled", feature.enabled)
      .put("status", feature.status.name)
      .put("message", feature.message)
  }

  private fun wifiEnvironmentJson(
    devices: List<Device>,
    analytics: AnalyticsSnapshot,
    routerStatus: RouterStatusSnapshot
  ): JSONObject {
    val weakSignalCount = devices.count { (it.rssiDbm ?: Int.MAX_VALUE) <= -70 }
    val activeDevices = devices.count { it.online || it.reachable }
    val congestionScore = when {
      activeDevices >= 10 || (analytics.jitterMs ?: 0.0) >= 20.0 || (analytics.packetLossPct ?: 0.0) >= 1.0 -> 72
      activeDevices >= 6 -> 48
      activeDevices > 0 -> 24
      else -> 0
    }
    val status = when {
      devices.isEmpty() -> ServiceStatus.NO_DATA
      congestionScore >= 65 || weakSignalCount >= 2 -> ServiceStatus.OK
      else -> ServiceStatus.OK
    }
    val label = when {
      congestionScore >= 65 -> "HIGH"
      congestionScore >= 35 -> "MODERATE"
      congestionScore > 0 -> "LOW"
      else -> "UNKNOWN"
    }
    val message = when {
      devices.isEmpty() -> "Run a scan to populate Wi-Fi environment telemetry."
      congestionScore >= 65 -> "Possible congestion is inferred from active device count and current latency variability."
      weakSignalCount > 0 -> "Some devices show weak Wi-Fi signal."
      else -> "No strong Wi-Fi congestion signal detected from current telemetry."
    }

    return JSONObject()
      .put("ok", status != ServiceStatus.ERROR)
      .put("status", status.name)
      .put("code", "WIFI_ENVIRONMENT")
      .put("message", message)
      .put("congestionScore", congestionScore)
      .put("congestionLabel", label)
      .put("connectedDeviceCount", activeDevices)
      .put("weakSignalCount", weakSignalCount)
      .put("ssid", routerStatus.ssid)
      .put("linkSpeedMbps", routerStatus.linkSpeedMbps)
      .put("inferred", true)
    }
  }

  private fun securitySummaryJson(devices: List<Device>): JSONObject {
    val unknownCount = devices.count {
      it.vendor.isBlank() || it.name.isBlank() || it.name.equals("Unknown", ignoreCase = true)
    }
    val riskyCount = devices.count { it.riskScore >= 40 }
    val message = when {
      devices.isEmpty() -> "Run a scan to populate security summary."
      riskyCount > 0 -> "Review unverified or high-risk devices."
      unknownCount > 0 -> "Unknown devices are present but no high-risk flags are active."
      else -> "No high-risk devices detected from current scan data."
    }
    return JSONObject()
      .put("ok", true)
      .put("status", if (devices.isEmpty()) ServiceStatus.NO_DATA.name else ServiceStatus.OK.name)
      .put("code", "SECURITY_SUMMARY")
      .put("message", message)
      .put("riskDeviceCount", riskyCount)
      .put("unknownDeviceCount", unknownCount)
      .put("blockedSupported", false)
      .put("pausedSupported", false)
      .put("blockedStateAuthoritative", false)
      .put("pausedStateAuthoritative", false)
    }
  }

  private fun devicesJson(devices: List<Device>): JSONArray {
    val array = JSONArray()
    devices.forEach { array.put(deviceJson(it)) }
    return array
  }

  private fun deviceJson(device: Device): JSONObject {
    val quality = device.rssiDbm?.let { (it + 100).coerceIn(5, 99) } ?: latencyToQuality(device.latencyMs)
    return JSONObject()
      .put("id", device.id)
      .put("name", device.name)
      .put("nickname", device.nickname)
      .put("hostname", device.hostname)
      .put("ip", device.ip)
      .put("vendor", device.vendor)
      .put("mac", device.mac)
      .put("deviceType", device.deviceType)
      .put("riskScore", device.riskScore)
      .put("quality", quality)
      .put("latencyMs", device.latencyMs)
      .put("signalStrength", device.rssiDbm)
      .put("reachability", device.reachabilityMethod)
      .put("lastSeen", device.lastSeenEpochMs)
      .put("isGateway", device.isGateway)
      .put("online", device.online)
      .put("reachable", device.reachable)
      .put("isBlocked", device.isBlocked)
      .put("isPaused", device.isPaused)
      .put("downMbps", device.downMbps)
      .put("upMbps", device.upMbps)
      .put("trafficStatus", device.trafficStatus.name)
  }

  private fun assistantResponseJson(response: AssistantResponse): JSONObject {
    val suggestedActions = JSONArray()
    response.suggestedActions.forEach { action ->
      suggestedActions.put(
        JSONObject()
          .put("label", action.label)
          .put("command", action.command)
          .put("style", action.style.name)
          .put("description", action.description)
      )
    }
    val cards = JSONArray()
    response.cards.forEach { card ->
      cards.put(
        JSONObject()
          .put("type", card.type.name)
          .put("title", card.title)
          .put("severity", card.severity?.name)
          .put("lines", JSONArray(card.lines))
          .put("bullets", JSONArray(card.bullets))
      )
    }
    return JSONObject()
      .put("title", response.title)
      .put("message", response.message)
      .put("severity", response.severity.name)
      .put("requiresConfirmation", response.requiresConfirmation)
      .put("confirmationPrompt", response.confirmationPrompt)
      .put("suggestedActions", suggestedActions)
      .put("cards", cards)
  }

  private fun emit(eventName: String, payloadJson: String) {
    val js = "window.NERF && window.NERF.onEvent && window.NERF.onEvent('${escapeJs(eventName)}', $payloadJson);"
    webView?.post { webView?.evaluateJavascript(js, null) }
  }

  private fun latencyToQuality(latencyMs: Int?): Int {
    if (latencyMs == null) return 0
    return (100 - latencyMs).coerceIn(8, 95)
  }

  private fun escapeJs(value: String): String {
    return value.replace("\\", "\\\\").replace("'", "\\'")
  }
}
