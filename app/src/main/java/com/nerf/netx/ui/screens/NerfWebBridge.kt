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
import com.nerf.netx.domain.ActionSupportCatalog
import com.nerf.netx.domain.AnalyticsSnapshot
import com.nerf.netx.domain.ActionSupportState
import com.nerf.netx.domain.AppActionId
import com.nerf.netx.domain.AppServices
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.MapLink
import com.nerf.netx.domain.MapNode
import com.nerf.netx.domain.QosMode
import com.nerf.netx.domain.RouterInfoResult
import com.nerf.netx.domain.RouterWriteAction
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
        runCatching { services.deviceControl.refreshStatus() }
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
        runCatching { services.deviceControl.refreshStatus() }
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
              .put("actionSupport", actionSupportJson(details.actionSupport))
              .put("backend", deviceBackendJson(details.backend))
              .put("deviceCapabilities", deviceCapabilitiesJson(details.deviceCapabilities))
              .put("device", deviceJson(details.device))
          )
          .toString()
      }
      "device.ping" -> actionResultJson(services.deviceControl.ping(payload.optString("deviceId", "")))
      "device.block" -> guardedDeviceActionResult(
        actionId = AppActionId.DEVICE_BLOCK,
        deviceId = payload.optString("deviceId", ""),
        action = { services.deviceControl.block(payload.optString("deviceId", "")) }
      )
      "device.setBlocked" -> {
        val blocked = if (payload.has("blocked")) payload.optBoolean("blocked") else true
        guardedDeviceActionResult(
          actionId = if (blocked) AppActionId.DEVICE_BLOCK else AppActionId.DEVICE_UNBLOCK,
          deviceId = payload.optString("deviceId", ""),
          action = { services.deviceControl.setBlocked(payload.optString("deviceId", ""), blocked) }
        )
      }
      "device.setPaused" -> {
        val paused = if (payload.has("paused")) payload.optBoolean("paused") else true
        guardedDeviceActionResult(
          actionId = if (paused) AppActionId.DEVICE_PAUSE else AppActionId.DEVICE_RESUME,
          deviceId = payload.optString("deviceId", ""),
          action = { services.deviceControl.setPaused(payload.optString("deviceId", ""), paused) }
        )
      }
      "device.rename" -> guardedDeviceActionResult(
        actionId = AppActionId.DEVICE_RENAME,
        deviceId = payload.optString("deviceId", ""),
        action = {
          services.deviceControl.rename(
            payload.optString("deviceId", ""),
            payload.optString("name", "")
          )
        }
      )
      "device.prioritize" -> guardedDeviceActionResult(
        actionId = AppActionId.DEVICE_PRIORITIZE,
        deviceId = payload.optString("deviceId", ""),
        action = { services.deviceControl.prioritize(payload.optString("deviceId", "")) }
      )
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
      "router.toggleGuest" -> guardedRouterActionResult(
        actionId = AppActionId.ROUTER_GUEST_WIFI,
        routerAction = RouterWriteAction.GUEST_WIFI,
        action = { services.routerControl.toggleGuest() }
      )
      "router.setGuest" -> guardedRouterActionResult(
        actionId = AppActionId.ROUTER_GUEST_WIFI,
        routerAction = RouterWriteAction.GUEST_WIFI,
        action = {
          services.routerControl.setGuestWifiEnabled(
            if (payload.has("enabled")) payload.optBoolean("enabled") else true
          )
        }
      )
      "router.setDnsShield" -> guardedRouterActionResult(
        actionId = AppActionId.ROUTER_DNS_SHIELD,
        routerAction = RouterWriteAction.DNS_SHIELD,
        action = {
          services.routerControl.setDnsShieldEnabled(
            if (payload.has("enabled")) payload.optBoolean("enabled") else true
          )
        }
      )
      "router.rebootRouter" -> guardedRouterActionResult(
        actionId = AppActionId.ROUTER_REBOOT,
        routerAction = RouterWriteAction.REBOOT,
        action = { services.routerControl.rebootRouter() }
      )
      "router.toggleFirewall" -> guardedRouterActionResult(
        actionId = AppActionId.ROUTER_FIREWALL,
        routerAction = RouterWriteAction.FIREWALL,
        action = { services.routerControl.toggleFirewall() }
      )
      "router.setFirewall" -> guardedRouterActionResult(
        actionId = AppActionId.ROUTER_FIREWALL,
        routerAction = RouterWriteAction.FIREWALL,
        action = {
          services.routerControl.setFirewallEnabled(
            if (payload.has("enabled")) payload.optBoolean("enabled") else true
          )
        }
      )
      "router.toggleVpn" -> guardedRouterActionResult(
        actionId = AppActionId.ROUTER_VPN,
        routerAction = RouterWriteAction.VPN,
        action = { services.routerControl.toggleVpn() }
      )
      "router.setVpn" -> guardedRouterActionResult(
        actionId = AppActionId.ROUTER_VPN,
        routerAction = RouterWriteAction.VPN,
        action = {
          services.routerControl.setVpnEnabled(
            if (payload.has("enabled")) payload.optBoolean("enabled") else true
          )
        }
      )
      "router.renewDhcp" -> guardedRouterActionResult(
        actionId = AppActionId.ROUTER_RENEW_DHCP,
        routerAction = RouterWriteAction.RENEW_DHCP,
        action = { services.routerControl.renewDhcp() }
      )
      "router.flushDns" -> guardedRouterActionResult(
        actionId = AppActionId.ROUTER_FLUSH_DNS,
        routerAction = RouterWriteAction.FLUSH_DNS,
        action = { services.routerControl.flushDns() }
      )
      "router.setQos" -> {
        val mode = runCatching {
          QosMode.valueOf(payload.optString("mode", "BALANCED").uppercase())
        }.getOrDefault(QosMode.BALANCED)
        guardedRouterActionResult(
          actionId = AppActionId.ROUTER_QOS,
          routerAction = RouterWriteAction.QOS,
          action = { services.routerControl.setQos(mode) }
        )
      }
      "wifi.environment" -> wifiEnvironmentJson(
        devices = services.devices.devices.value,
        analytics = services.analytics.snapshot.value,
        routerStatus = services.routerControl.status.value
      ).toString()
      "security.summary" -> {
        runCatching { services.deviceControl.refreshStatus() }
        securitySummaryJson(services.devices.devices.value).toString()
      }
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

  private suspend fun guardedDeviceActionResult(
    actionId: String,
    deviceId: String,
    action: suspend () -> ActionResult
  ): String {
    val details = services.deviceControl.deviceDetails(deviceId)
    val support = details?.actionSupport?.get(actionId)
      ?: details?.support?.let { ActionSupportCatalog.deviceActionState(actionId, it) }
      ?: ActionSupportCatalog.deviceActionState(actionId)
    if (support != null && !support.supported) {
      return actionResultJson(
        ActionResult(
          ok = false,
          status = if (details?.backend?.detected == true && details.backend.authenticated.not()) {
            ServiceStatus.NO_DATA
          } else {
            ServiceStatus.NOT_SUPPORTED
          },
          code = if (details?.backend?.detected == true && details.backend.authenticated.not()) {
            "DEVICE_CONTROL_UNAVAILABLE"
          } else {
            "NOT_SUPPORTED"
          },
          message = if (details?.backend?.detected == true && details.backend.authenticated.not()) {
            "${support.label ?: "Device action"} is unavailable."
          } else {
            "${support.label ?: "Device action"} is unsupported on the current backend/router."
          },
          errorReason = support.reason
        )
      )
    }
    return actionResultJson(action())
  }

  private suspend fun guardedRouterActionResult(
    actionId: String,
    routerAction: RouterWriteAction,
    action: suspend () -> ActionResult
  ): String {
    val snapshot = services.routerControl.refreshStatus()
    val support = snapshot.actionSupport[actionId] ?: ActionSupportCatalog.routerActionState(actionId, snapshot)
    if (support != null && !support.supported) {
      val result = if (snapshot.status == ServiceStatus.OK) {
        routerAction.unsupportedResult(support.reason)
      } else {
        routerAction.unavailableResult(support.reason)
      }
      return actionResultJson(result)
    }
    return actionResultJson(action())
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
      .put("backend", routerBackendJson(snapshot))
      .put("guestWifi", featureStateJson(snapshot.guestWifi))
      .put("dnsShield", featureStateJson(snapshot.dnsShield))
      .put("firewall", featureStateJson(snapshot.firewall))
      .put("vpn", featureStateJson(snapshot.vpn))
      .put("qosMode", snapshot.qosMode)
      .put("routerCapabilities", routerCapabilitiesJson(snapshot))
      .put("actionSupport", actionSupportJson(snapshot.actionSupport))
      .put("lastUpdatedEpochMs", snapshot.lastUpdatedEpochMs)
  }

  private fun featureStateJson(feature: com.nerf.netx.domain.RouterFeatureState): JSONObject {
    return JSONObject()
      .put("supported", feature.supported)
      .put("readable", feature.readable)
      .put("writable", feature.writable)
      .put("enabled", feature.enabled)
      .put("status", feature.status.name)
      .put("message", feature.message)
  }

  private fun routerBackendJson(snapshot: RouterStatusSnapshot): JSONObject {
    return JSONObject()
      .put("detected", snapshot.backend.detected)
      .put("authenticated", snapshot.backend.authenticated)
      .put("readable", snapshot.backend.readable)
      .put("writable", snapshot.backend.writable)
      .put("vendorName", snapshot.backend.vendorName)
      .put("modelName", snapshot.backend.modelName)
      .put("firmwareVersion", snapshot.backend.firmwareVersion)
      .put("adapterId", snapshot.backend.adapterId)
      .put("message", snapshot.backend.message)
  }

  private fun routerCapabilitiesJson(snapshot: RouterStatusSnapshot): JSONObject {
    val json = JSONObject()
    snapshot.routerCapabilities.forEach { (key, value) ->
      json.put(
        key,
        JSONObject()
          .put("label", value.label)
          .put("supported", value.supported)
          .put("detected", value.detected)
          .put("authenticated", value.authenticated)
          .put("readable", value.readable)
          .put("writable", value.writable)
          .put("status", value.status.name)
          .put("reason", value.reason)
          .put("source", value.source)
      )
    }
    return json
  }

  private fun actionSupportJson(states: Map<String, ActionSupportState>): JSONObject {
    val json = JSONObject()
    states.forEach { (key, value) ->
      json.put(
        key,
        JSONObject()
          .put("supported", value.supported)
          .put("label", value.label)
          .put("reason", value.reason)
      )
    }
    return json
  }

  private fun deviceBackendJson(backend: com.nerf.netx.domain.DeviceControlBackendState): JSONObject {
    return JSONObject()
      .put("detected", backend.detected)
      .put("authenticated", backend.authenticated)
      .put("readable", backend.readable)
      .put("writable", backend.writable)
      .put("vendorName", backend.vendorName)
      .put("modelName", backend.modelName)
      .put("firmwareVersion", backend.firmwareVersion)
      .put("adapterId", backend.adapterId)
      .put("message", backend.message)
  }

  private fun deviceCapabilitiesJson(states: Map<String, com.nerf.netx.domain.DeviceCapabilityState>): JSONObject {
    val json = JSONObject()
    states.forEach { (key, value) ->
      json.put(
        key,
        JSONObject()
          .put("label", value.label)
          .put("supported", value.supported)
          .put("detected", value.detected)
          .put("authenticated", value.authenticated)
          .put("readable", value.readable)
          .put("writable", value.writable)
          .put("status", value.status.name)
          .put("reason", value.reason)
          .put("source", value.source)
      )
    }
    return json
  }

  private fun globalDeviceActionSupport(): Map<String, ActionSupportState> {
    val capabilities = services.deviceControl.status.value.deviceCapabilities
    return linkedMapOf(
      AppActionId.DEVICE_BLOCK to capabilitySupport(capabilities, AppActionId.DEVICE_BLOCK, "Block device"),
      AppActionId.DEVICE_UNBLOCK to capabilitySupport(capabilities, AppActionId.DEVICE_BLOCK, "Unblock device"),
      AppActionId.DEVICE_PAUSE to capabilitySupport(capabilities, AppActionId.DEVICE_PAUSE, "Pause device"),
      AppActionId.DEVICE_RESUME to capabilitySupport(capabilities, AppActionId.DEVICE_PAUSE, "Resume device"),
      AppActionId.DEVICE_RENAME to capabilitySupport(capabilities, AppActionId.DEVICE_RENAME, "Rename device"),
      AppActionId.DEVICE_PRIORITIZE to capabilitySupport(capabilities, AppActionId.DEVICE_PRIORITIZE, "Prioritize device")
    )
  }

  private fun capabilitySupport(
    capabilities: Map<String, com.nerf.netx.domain.DeviceCapabilityState>,
    actionId: String,
    label: String
  ): ActionSupportState {
    val capability = capabilities[actionId]
    return if (capability == null) {
      ActionSupportState(false, "$label is unsupported on the current backend/router.", label)
    } else {
      ActionSupportState(
        supported = capability.writable,
        reason = if (capability.writable) "$label is available." else capability.reason,
        label = label
      )
    }
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

  private fun securitySummaryJson(devices: List<Device>): JSONObject {
    val deviceSupport = services.deviceControl.status.value.deviceCapabilities
    val blockedSupported = deviceSupport[AppActionId.DEVICE_BLOCK]?.writable == true
    val pausedSupported = deviceSupport[AppActionId.DEVICE_PAUSE]?.writable == true
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
      .put("blockedSupported", blockedSupported)
      .put("pausedSupported", pausedSupported)
      .put("blockedStateAuthoritative", blockedSupported)
      .put("pausedStateAuthoritative", false)
      .put("actionSupport", actionSupportJson(globalDeviceActionSupport()))
  }

  private fun devicesJson(devices: List<Device>): JSONArray {
    val array = JSONArray()
    devices.forEach { array.put(deviceJson(it)) }
    return array
  }

  private fun deviceJson(device: Device): JSONObject {
    val quality = device.rssiDbm?.let { (it + 100).coerceIn(5, 99) } ?: latencyToQuality(device.latencyMs)
    val actionSupport = ActionSupportCatalog.deviceActionSupport(device, services.deviceControl.status.value)
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
      .put("actionSupport", actionSupportJson(actionSupport))
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
      .put(
        "confirmationUi",
        response.confirmationUi?.let { confirmation ->
          JSONObject()
            .put("title", confirmation.title)
            .put("summary", confirmation.summary)
            .put("details", JSONArray(confirmation.details))
            .put("confirmLabel", confirmation.confirmLabel)
            .put("cancelLabel", confirmation.cancelLabel)
        }
      )
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
