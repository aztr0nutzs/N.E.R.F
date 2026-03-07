package com.nerf.netx.assistant.diagnostics

import com.nerf.netx.assistant.model.AssistantCardType
import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.assistant.model.AssistantResponseCard
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.domain.ServiceStatus

data class AssistantDiagnosticsReport(
  val title: String,
  val message: String,
  val severity: AssistantSeverity,
  val cards: List<AssistantResponseCard>
)

class AssistantDiagnosticsEngine {

  fun buildReport(context: AssistantContextSnapshot): AssistantDiagnosticsReport {
    val cards = mutableListOf<AssistantResponseCard>()

    val scanState = context.scanState
    cards += AssistantResponseCard(
      type = AssistantCardType.STATUS,
      title = "Scan",
      lines = listOf(
        "Phase: ${scanState?.phase ?: "N/A"}",
        "Scanned: ${scanState?.scannedHosts ?: 0}",
        "Discovered: ${scanState?.discoveredHosts ?: context.devices.size}"
      )
    )

    val speedtestUi = context.speedtestUi
    cards += AssistantResponseCard(
      type = AssistantCardType.METRIC,
      title = "Speedtest",
      lines = listOf(
        "Phase: ${speedtestUi?.phaseEnum ?: "N/A"}",
        "Down: ${speedtestUi?.downMbps?.let { "%.1f Mbps".format(it) } ?: "N/A"}",
        "Up: ${speedtestUi?.upMbps?.let { "%.1f Mbps".format(it) } ?: "N/A"}",
        "Ping: ${speedtestUi?.pingMs?.let { "%.1f ms".format(it) } ?: "N/A"}"
      )
    )

    val analytics = context.analytics
    cards += AssistantResponseCard(
      type = AssistantCardType.STATUS,
      title = "Analytics",
      lines = listOf(
        "Status: ${analytics?.status ?: "N/A"}",
        "Online devices: ${analytics?.reachableCount ?: 0}/${analytics?.deviceCount ?: context.devices.size}",
        analytics?.message ?: "No analytics summary"
      )
    )

    val router = context.routerInfo
    cards += AssistantResponseCard(
      type = AssistantCardType.STATUS,
      title = "Router",
      lines = listOf(
        "Gateway: ${router?.gatewayIp ?: "Unknown"}",
        "SSID: ${router?.ssid ?: "Unknown"}",
        router?.message ?: "Router info unavailable"
      )
    )

    val severity = when {
      analytics?.status == ServiceStatus.ERROR -> AssistantSeverity.ERROR
      analytics?.status == ServiceStatus.NO_DATA -> AssistantSeverity.WARNING
      else -> AssistantSeverity.INFO
    }

    val message = when (severity) {
      AssistantSeverity.ERROR -> "Diagnostics found service errors."
      AssistantSeverity.WARNING -> "Diagnostics are partial. Run scan and speedtest for complete visibility."
      else -> "Diagnostics completed with currently available data."
    }

    return AssistantDiagnosticsReport(
      title = "Network diagnostics",
      message = message,
      severity = severity,
      cards = cards
    )
  }
}
