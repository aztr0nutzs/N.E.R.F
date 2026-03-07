package com.nerf.netx.assistant.diagnostics

import com.nerf.netx.assistant.model.AssistantDiagnosisFinding
import com.nerf.netx.assistant.model.AssistantDiagnosisFocus
import com.nerf.netx.assistant.model.AssistantDiagnosisReport
import com.nerf.netx.assistant.model.AssistantDiagnosisType
import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.ServiceStatus

class NetworkDiagnosisEngine {

  fun diagnose(
    context: AssistantContextSnapshot,
    focus: AssistantDiagnosisFocus = AssistantDiagnosisFocus.GENERAL,
    targetDevice: Device? = null
  ): AssistantDiagnosisReport {
    val findings = mutableListOf<AssistantDiagnosisFinding>()

    maybeAddWanOutage(context)?.let(findings::add)
    maybeAddLocalReachability(context, targetDevice)?.let(findings::add)
    maybeAddHighLatency(context, targetDevice)?.let(findings::add)
    maybeAddHighJitter(context)?.let(findings::add)
    maybeAddPacketLoss(context)?.let(findings::add)
    maybeAddWeakWifiSignal(context, targetDevice)?.let(findings::add)
    maybeAddSameChannelCongestion(context)?.let(findings::add)
    maybeAddThroughputHog(context, focus)?.let(findings::add)
    maybeAddUnknownDeviceRisk(context, targetDevice)?.let(findings::add)

    val ordered = findings.sortedWith(
      compareByDescending<AssistantDiagnosisFinding> { severityWeight(it.severity) }
        .thenBy { diagnosisPriority(it.type) }
    )

    if (ordered.isEmpty()) {
      return AssistantDiagnosisReport(
        title = diagnosisTitle(focus, targetDevice),
        summary = if (targetDevice != null) {
          "No clear issue stands out for ${targetDevice.name.ifBlank { targetDevice.ip }} from current app telemetry."
        } else {
          "No clear network fault stands out from the current app telemetry."
        },
        severity = AssistantSeverity.INFO,
        findings = emptyList()
      )
    }

    return AssistantDiagnosisReport(
      title = diagnosisTitle(focus, targetDevice),
      summary = ordered.first().summary,
      severity = ordered.maxBy { severityWeight(it.severity) }.severity,
      findings = ordered
    )
  }

  private fun maybeAddWanOutage(context: AssistantContextSnapshot): AssistantDiagnosisFinding? {
    val routerReachable = context.routerInfo?.gatewayIp != null ||
      context.routerInfo?.ssid != null ||
      context.devices.any { it.isGateway && (it.online || it.reachable) }
    val latest = context.latestSpeedtest
    val ui = context.speedtestUi
    val totalThroughput = listOf(
      context.analytics?.downMbps,
      latest?.downloadMbps,
      ui?.downMbps
    ).firstOrNull { it != null } ?: 0.0
    val speedtestErrored = latest?.error?.isNotBlank() == true || ui?.status == ServiceStatus.ERROR

    if (!routerReachable || !speedtestErrored || totalThroughput > 1.0) {
      return null
    }

    return AssistantDiagnosisFinding(
      type = AssistantDiagnosisType.ISP_WAN_OUTAGE,
      title = "Possible ISP/WAN outage",
      summary = "Local network appears reachable, but internet-facing checks are failing.",
      severity = AssistantSeverity.ERROR,
      evidence = listOfNotNull(
        context.routerInfo?.gatewayIp?.let { "Router gateway is present at $it." },
        latest?.error?.takeIf { it.isNotBlank() }?.let { "Last speedtest failed: $it" },
        ui?.message?.takeIf { ui.status == ServiceStatus.ERROR && it.isNotBlank() }?.let { "Current speedtest status: $it" }
      ).ifEmpty {
        listOf("Local router is visible, but the last internet test did not succeed.")
      },
      inferred = true
    )
  }

  private fun maybeAddLocalReachability(
    context: AssistantContextSnapshot,
    targetDevice: Device?
  ): AssistantDiagnosisFinding? {
    if (targetDevice != null && !targetDevice.reachable) {
      return AssistantDiagnosisFinding(
        type = AssistantDiagnosisType.LOCAL_REACHABILITY_ISSUE,
        title = "Device reachability issue",
        summary = "${targetDevice.name.ifBlank { targetDevice.ip }} is not currently reachable on the local network.",
        severity = AssistantSeverity.ERROR,
        evidence = listOf(
          "Device is marked offline=${targetDevice.online} reachable=${targetDevice.reachable}.",
          targetDevice.latencyReason ?: "No successful reachability probe is recorded."
        ),
        targetDeviceId = targetDevice.id
      )
    }

    val devices = context.devices
    if (devices.isEmpty()) return null

    val reachable = devices.count { it.reachable }
    val reachabilityRatio = reachable.toDouble() / devices.size.toDouble()
    if (reachabilityRatio <= 0.45) {
      return AssistantDiagnosisFinding(
        type = AssistantDiagnosisType.LOCAL_REACHABILITY_ISSUE,
        title = "Local reachability problems",
        summary = "A large share of discovered devices is not reachable.",
        severity = AssistantSeverity.ERROR,
        evidence = listOf(
          "$reachable of ${devices.size} discovered devices are currently reachable.",
          "Scan phase: ${context.scanState?.phase ?: "N/A"}."
        )
      )
    }

    val routerMessage = context.routerInfo?.message.orEmpty().lowercase()
    if (context.routerInfo?.status == ServiceStatus.ERROR || routerMessage.contains("no active network")) {
      return AssistantDiagnosisFinding(
        type = AssistantDiagnosisType.LOCAL_REACHABILITY_ISSUE,
        title = "Local network connectivity issue",
        summary = "The app cannot confirm a healthy local network path to the router.",
        severity = AssistantSeverity.ERROR,
        evidence = listOf(context.routerInfo?.message ?: "Router info is unavailable.")
      )
    }

    return null
  }

  private fun maybeAddHighLatency(
    context: AssistantContextSnapshot,
    targetDevice: Device?
  ): AssistantDiagnosisFinding? {
    val targetLatency = targetDevice?.latencyMs?.toDouble()
    val latency = targetLatency
      ?: context.analytics?.latencyMs
      ?: context.latestSpeedtest?.pingMs
      ?: context.speedtestUi?.pingMs
    if (latency == null || latency < 80.0) return null

    return AssistantDiagnosisFinding(
      type = AssistantDiagnosisType.HIGH_LATENCY,
      title = "High latency",
      summary = if (targetDevice != null) {
        "${targetDevice.name.ifBlank { targetDevice.ip }} is seeing elevated round-trip latency."
      } else {
        "Round-trip latency is elevated."
      },
      severity = if (latency >= 150.0) AssistantSeverity.ERROR else AssistantSeverity.WARNING,
      evidence = listOf(
        "Observed latency: ${format1(latency)} ms.",
        if (targetDevice != null) {
          targetDevice.latencyReason ?: "Device latency was measured during local scan/probe."
        } else {
          "Latency comes from the current analytics/speedtest snapshot."
        }
      ),
      targetDeviceId = targetDevice?.id
    )
  }

  private fun maybeAddHighJitter(context: AssistantContextSnapshot): AssistantDiagnosisFinding? {
    val jitter = context.analytics?.jitterMs ?: context.latestSpeedtest?.jitterMs ?: context.speedtestUi?.jitterMs
    if (jitter == null || jitter < 20.0) return null

    return AssistantDiagnosisFinding(
      type = AssistantDiagnosisType.HIGH_JITTER,
      title = "High jitter",
      summary = "Latency is fluctuating more than expected.",
      severity = if (jitter >= 40.0) AssistantSeverity.ERROR else AssistantSeverity.WARNING,
      evidence = listOf(
        "Observed jitter: ${format1(jitter)} ms.",
        "Jitter is derived from the latest speedtest/analytics snapshot."
      )
    )
  }

  private fun maybeAddPacketLoss(context: AssistantContextSnapshot): AssistantDiagnosisFinding? {
    val loss = context.analytics?.packetLossPct ?: context.latestSpeedtest?.packetLossPct ?: context.speedtestUi?.packetLossPct
    if (loss == null || loss < 1.0) return null

    return AssistantDiagnosisFinding(
      type = AssistantDiagnosisType.PACKET_LOSS,
      title = "Packet loss detected",
      summary = "Traffic loss is present and can degrade throughput and latency-sensitive apps.",
      severity = if (loss >= 5.0) AssistantSeverity.ERROR else AssistantSeverity.WARNING,
      evidence = listOf(
        "Observed packet loss: ${format1(loss)}%.",
        "Loss is coming from the current speedtest/analytics snapshot."
      )
    )
  }

  private fun maybeAddWeakWifiSignal(
    context: AssistantContextSnapshot,
    targetDevice: Device?
  ): AssistantDiagnosisFinding? {
    val candidate = targetDevice?.takeIf { it.rssiDbm != null }
      ?: context.devices.filter { it.rssiDbm != null }.minByOrNull { it.rssiDbm ?: Int.MAX_VALUE }
    val rssi = candidate?.rssiDbm ?: return null
    if (rssi > -67) return null

    return AssistantDiagnosisFinding(
      type = AssistantDiagnosisType.WEAK_WIFI_SIGNAL,
      title = "Weak Wi-Fi signal",
      summary = "${candidate.name.ifBlank { candidate.ip }} has a weak Wi-Fi signal.",
      severity = if (rssi <= -80) AssistantSeverity.ERROR else AssistantSeverity.WARNING,
      evidence = listOf(
        "Observed RSSI: $rssi dBm.",
        "Weak signal can increase retries, latency, and packet loss."
      ),
      targetDeviceId = candidate.id
    )
  }

  private fun maybeAddSameChannelCongestion(context: AssistantContextSnapshot): AssistantDiagnosisFinding? {
    val onlineCount = context.devices.count { it.online || it.reachable }
    val jitter = context.analytics?.jitterMs ?: context.latestSpeedtest?.jitterMs ?: context.speedtestUi?.jitterMs ?: 0.0
    val loss = context.analytics?.packetLossPct ?: context.latestSpeedtest?.packetLossPct ?: context.speedtestUi?.packetLossPct ?: 0.0
    if (onlineCount < 8 || (jitter < 18.0 && loss < 2.0)) return null

    return AssistantDiagnosisFinding(
      type = AssistantDiagnosisType.SAME_CHANNEL_CONGESTION,
      title = "Possible same-channel congestion",
      summary = "Wireless contention is a plausible cause of the current instability.",
      severity = AssistantSeverity.WARNING,
      evidence = listOf(
        "$onlineCount devices are active on the local network.",
        "Jitter is ${format1(jitter)} ms and packet loss is ${format1(loss)}%.",
        "This is inferred from wireless symptoms; the app does not yet have direct RF channel telemetry."
      ),
      inferred = true
    )
  }

  private fun maybeAddThroughputHog(
    context: AssistantContextSnapshot,
    focus: AssistantDiagnosisFocus
  ): AssistantDiagnosisFinding? {
    if (focus != AssistantDiagnosisFocus.SPEED) return null

    val onlineDevices = context.devices.filter { it.online || it.reachable }
    if (onlineDevices.size < 4) return null

    val candidates = onlineDevices.filter { device ->
      val type = device.deviceType.uppercase()
      val name = "${device.name} ${device.hostname}".lowercase()
      type in setOf("MEDIA", "COMPUTER") ||
        name.contains("roku") ||
        name.contains("chromecast") ||
        name.contains("tv") ||
        name.contains("xbox") ||
        name.contains("playstation") ||
        name.contains("desktop")
    }
    if (candidates.size != 1) return null

    val suspect = candidates.first()
    return AssistantDiagnosisFinding(
      type = AssistantDiagnosisType.HIGH_THROUGHPUT_HOG_DEVICE,
      title = "Possible bandwidth-heavy device",
      summary = "${suspect.name.ifBlank { suspect.ip }} may be a heavy bandwidth consumer.",
      severity = AssistantSeverity.WARNING,
      evidence = listOf(
        "The device is online while the current request is focused on slow throughput.",
        "Device type/name suggests media or large-download usage.",
        "This is inferred from device role. The app does not yet have per-device throughput telemetry."
      ),
      targetDeviceId = suspect.id,
      inferred = true
    )
  }

  private fun maybeAddUnknownDeviceRisk(
    context: AssistantContextSnapshot,
    targetDevice: Device?
  ): AssistantDiagnosisFinding? {
    val candidate = targetDevice?.takeIf { isUnknownRisk(it) }
      ?: context.devices.firstOrNull { isUnknownRisk(it) }
      ?: return null

    return AssistantDiagnosisFinding(
      type = AssistantDiagnosisType.UNKNOWN_DEVICE_RISK,
      title = "Unknown device risk",
      summary = "${candidate.name.ifBlank { candidate.ip }} should be reviewed because its identity is weak or unverified.",
      severity = if (candidate.riskScore >= 60) AssistantSeverity.ERROR else AssistantSeverity.WARNING,
      evidence = buildList {
        if (candidate.vendor.isBlank()) add("Vendor is unknown.")
        if (candidate.name.isBlank() || candidate.name.equals("Unknown", ignoreCase = true)) add("Device name is not descriptive.")
        if (candidate.riskScore > 0) add("Risk score: ${candidate.riskScore}.")
        if (candidate.openPortsSummary.isNotBlank()) add("Open ports summary: ${candidate.openPortsSummary}.")
      }.ifEmpty {
        listOf("Device identity could not be confirmed from current scan data.")
      },
      targetDeviceId = candidate.id
    )
  }

  private fun isUnknownRisk(device: Device): Boolean {
    val vendorUnknown = device.vendor.isBlank() || device.vendor.equals("unknown", ignoreCase = true)
    val nameUnknown = device.name.isBlank() || device.name.equals("unknown", ignoreCase = true)
    return (vendorUnknown && (device.online || device.reachable)) ||
      device.riskScore >= 40 ||
      (nameUnknown && device.openPortsSummary.isNotBlank())
  }

  private fun diagnosisTitle(focus: AssistantDiagnosisFocus, targetDevice: Device?): String {
    return when {
      targetDevice != null -> "Device diagnosis"
      focus == AssistantDiagnosisFocus.SPEED -> "Slow internet diagnosis"
      focus == AssistantDiagnosisFocus.LATENCY -> "Latency diagnosis"
      else -> "Network diagnosis"
    }
  }

  private fun severityWeight(severity: AssistantSeverity): Int {
    return when (severity) {
      AssistantSeverity.ERROR -> 4
      AssistantSeverity.WARNING -> 3
      AssistantSeverity.SUCCESS -> 2
      AssistantSeverity.INFO -> 1
    }
  }

  private fun diagnosisPriority(type: AssistantDiagnosisType): Int {
    return when (type) {
      AssistantDiagnosisType.ISP_WAN_OUTAGE -> 1
      AssistantDiagnosisType.LOCAL_REACHABILITY_ISSUE -> 2
      AssistantDiagnosisType.HIGH_LATENCY -> 3
      AssistantDiagnosisType.HIGH_JITTER -> 4
      AssistantDiagnosisType.PACKET_LOSS -> 5
      AssistantDiagnosisType.WEAK_WIFI_SIGNAL -> 6
      AssistantDiagnosisType.SAME_CHANNEL_CONGESTION -> 7
      AssistantDiagnosisType.HIGH_THROUGHPUT_HOG_DEVICE -> 8
      AssistantDiagnosisType.UNKNOWN_DEVICE_RISK -> 9
    }
  }

  private fun format1(value: Double): String = String.format("%.1f", value)
}
