package com.nerf.netx.networkdoctor.presentation

import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.assistant.model.AssistantDiagnosisReport
import com.nerf.netx.assistant.model.AssistantDiagnosisType
import com.nerf.netx.assistant.model.AssistantRecommendation
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.domain.ActionSupportCatalog
import com.nerf.netx.domain.AppActionId
import com.nerf.netx.domain.ServiceStatus
import com.nerf.netx.networkdoctor.model.NetworkDoctorAction
import com.nerf.netx.networkdoctor.model.NetworkDoctorActionItem
import com.nerf.netx.networkdoctor.model.NetworkDoctorCategory
import com.nerf.netx.networkdoctor.model.NetworkDoctorEvidenceUi
import com.nerf.netx.networkdoctor.model.NetworkDoctorFindingUi
import com.nerf.netx.networkdoctor.model.NetworkDoctorHealthStatus
import com.nerf.netx.networkdoctor.model.NetworkDoctorHealthSummary
import com.nerf.netx.networkdoctor.model.NetworkDoctorRecommendationUi
import com.nerf.netx.networkdoctor.model.NetworkDoctorUnavailableUi
import com.nerf.netx.networkdoctor.state.NetworkDoctorLoadState
import com.nerf.netx.networkdoctor.state.NetworkDoctorUiState
import com.nerf.netx.ui.nav.Routes

class NetworkDoctorStateMapper {

  fun mapReadyState(
    context: AssistantContextSnapshot,
    report: AssistantDiagnosisReport,
    recommendations: List<AssistantRecommendation>,
    actionMessage: String? = null
  ): NetworkDoctorUiState {
    val issues = report.findings.mapIndexed { index, finding ->
      NetworkDoctorFindingUi(
        key = "${finding.type.name}-$index",
        title = finding.title,
        summary = finding.summary,
        severity = finding.severity,
        category = categoryForFinding(finding.type),
        evidence = finding.evidence,
        inferred = finding.inferred,
        actions = buildFindingActions(finding, context)
      )
    }
    val unavailableItems = buildUnavailableItems(context)
    val evidence = buildEvidence(context, report)
    val recommendationItems = recommendations.mapNotNull { recommendation ->
      val action = commandToAction(recommendation.action.command, recommendation.action.label)
      NetworkDoctorRecommendationUi(
        title = recommendation.title,
        rationale = recommendation.rationale,
        severity = if (recommendation.priority >= 9) AssistantSeverity.ERROR else AssistantSeverity.INFO,
        category = categoryForCommand(recommendation.action.command),
        action = action
      )
    }

    val status = when {
      issues.any { it.severity == AssistantSeverity.ERROR } -> NetworkDoctorHealthStatus.CRITICAL
      issues.any { it.severity == AssistantSeverity.WARNING } -> NetworkDoctorHealthStatus.DEGRADED
      context.devices.isEmpty() && context.analytics?.status == ServiceStatus.NO_DATA -> NetworkDoctorHealthStatus.UNAVAILABLE
      else -> NetworkDoctorHealthStatus.HEALTHY
    }
    val score = when (status) {
      NetworkDoctorHealthStatus.CRITICAL -> 30
      NetworkDoctorHealthStatus.DEGRADED -> 62
      NetworkDoctorHealthStatus.UNAVAILABLE -> 45
      NetworkDoctorHealthStatus.HEALTHY -> 94
    }

    val summary = NetworkDoctorHealthSummary(
      title = when (status) {
        NetworkDoctorHealthStatus.CRITICAL -> "Network health is critical"
        NetworkDoctorHealthStatus.DEGRADED -> "Network health is degraded"
        NetworkDoctorHealthStatus.UNAVAILABLE -> "Network health is partially unavailable"
        NetworkDoctorHealthStatus.HEALTHY -> "Network health looks stable"
      },
      message = when {
        issues.isNotEmpty() -> report.summary
        unavailableItems.isNotEmpty() -> "No major fault is visible, but some diagnostic inputs are unavailable."
        else -> "No significant issues were detected from the latest snapshot."
      },
      status = status,
      severity = when (status) {
        NetworkDoctorHealthStatus.CRITICAL -> AssistantSeverity.ERROR
        NetworkDoctorHealthStatus.DEGRADED -> AssistantSeverity.WARNING
        NetworkDoctorHealthStatus.UNAVAILABLE -> AssistantSeverity.WARNING
        NetworkDoctorHealthStatus.HEALTHY -> AssistantSeverity.SUCCESS
      },
      issueCount = issues.size,
      activeAlertCount = issues.count { it.severity == AssistantSeverity.ERROR || it.severity == AssistantSeverity.WARNING },
      score = score
    )

    return NetworkDoctorUiState(
      loadState = NetworkDoctorLoadState.READY,
      healthSummary = summary,
      issues = issues,
      recommendations = recommendationItems,
      currentEvidence = evidence,
      unavailableItems = unavailableItems,
      isHealthy = issues.isEmpty() && status == NetworkDoctorHealthStatus.HEALTHY,
      emptyStateMessage = if (issues.isEmpty() && status == NetworkDoctorHealthStatus.HEALTHY) {
        "No active issues detected from the current network snapshot."
      } else {
        null
      },
      actionMessage = actionMessage,
      lastUpdatedEpochMs = context.capturedAtEpochMs
    )
  }

  fun errorState(message: String): NetworkDoctorUiState {
    return NetworkDoctorUiState(
      loadState = NetworkDoctorLoadState.ERROR,
      actionMessage = message
    )
  }

  private fun buildFindingActions(
    finding: com.nerf.netx.assistant.model.AssistantDiagnosisFinding,
    context: AssistantContextSnapshot
  ): List<NetworkDoctorActionItem> {
    val actions = mutableListOf<NetworkDoctorActionItem>()
    when (finding.type) {
      AssistantDiagnosisType.LOCAL_REACHABILITY_ISSUE -> {
        actions += NetworkDoctorActionItem("Scan Network", NetworkDoctorAction.ScanNetwork, prominent = true)
        actions += NetworkDoctorActionItem("Refresh Topology", NetworkDoctorAction.RefreshTopology)
      }
      AssistantDiagnosisType.ISP_WAN_OUTAGE -> {
        actions += NetworkDoctorActionItem("Run Speedtest", NetworkDoctorAction.RunSpeedtest, prominent = true)
        actions += NetworkDoctorActionItem("Open Analytics", NetworkDoctorAction.OpenRoute(Routes.ANALYTICS))
      }
      AssistantDiagnosisType.HIGH_LATENCY,
      AssistantDiagnosisType.HIGH_JITTER,
      AssistantDiagnosisType.PACKET_LOSS -> {
        actions += NetworkDoctorActionItem("Open Analytics", NetworkDoctorAction.OpenRoute(Routes.ANALYTICS), prominent = true)
      }
      AssistantDiagnosisType.WEAK_WIFI_SIGNAL,
      AssistantDiagnosisType.SAME_CHANNEL_CONGESTION -> {
        actions += NetworkDoctorActionItem("Refresh Topology", NetworkDoctorAction.RefreshTopology, prominent = true)
      }
      AssistantDiagnosisType.UNKNOWN_DEVICE_RISK -> {
        finding.targetDeviceId?.let { deviceId ->
          val device = context.devices.firstOrNull { it.id == deviceId }
          val blockSupport = device?.let {
            ActionSupportCatalog.deviceActionState(AppActionId.DEVICE_BLOCK, it, context.deviceControlStatus)
          }
          actions += NetworkDoctorActionItem(
            label = if (blockSupport?.supported == true) "Block Device" else "Block Device (Unsupported)",
            action = NetworkDoctorAction.BlockDevice(deviceId, "Suspicious device"),
            enabled = blockSupport?.supported == true,
            unavailableReason = blockSupport?.reason
          )
        }
        actions += NetworkDoctorActionItem("Open Devices", NetworkDoctorAction.OpenRoute(Routes.DEVICES), prominent = true)
      }
      AssistantDiagnosisType.HIGH_THROUGHPUT_HOG_DEVICE -> {
        actions += NetworkDoctorActionItem("Open Devices", NetworkDoctorAction.OpenRoute(Routes.DEVICES), prominent = true)
      }
    }
    return actions
  }

  private fun buildEvidence(
    context: AssistantContextSnapshot,
    report: AssistantDiagnosisReport
  ): List<NetworkDoctorEvidenceUi> {
    val evidence = mutableListOf<NetworkDoctorEvidenceUi>()
    evidence += NetworkDoctorEvidenceUi(
      label = "Active alerts",
      value = report.findings.count { it.severity == AssistantSeverity.ERROR || it.severity == AssistantSeverity.WARNING }.toString(),
      severity = if (report.findings.isEmpty()) AssistantSeverity.SUCCESS else report.severity,
      category = NetworkDoctorCategory.CONNECTIVITY
    )

    val unknownDevices = context.devices.count {
      it.vendor.isBlank() || it.name.isBlank() || it.name.equals("Unknown", ignoreCase = true)
    }
    evidence += NetworkDoctorEvidenceUi(
      label = "Unknown devices",
      value = unknownDevices.toString(),
      severity = if (unknownDevices > 0) AssistantSeverity.WARNING else AssistantSeverity.SUCCESS,
      category = NetworkDoctorCategory.SECURITY
    )

    context.analytics?.packetLossPct?.let {
      evidence += NetworkDoctorEvidenceUi(
        label = "Packet loss",
        value = "${format1(it)}%",
        severity = if (it >= 1.0) AssistantSeverity.WARNING else AssistantSeverity.SUCCESS,
        category = NetworkDoctorCategory.THROUGHPUT
      )
    }

    context.analytics?.jitterMs?.let {
      evidence += NetworkDoctorEvidenceUi(
        label = "Jitter",
        value = "${format1(it)} ms",
        severity = if (it >= 20.0) AssistantSeverity.WARNING else AssistantSeverity.SUCCESS,
        category = NetworkDoctorCategory.LATENCY
      )
    }

    evidence += NetworkDoctorEvidenceUi(
      label = "Congestion",
      value = if (report.findings.any { it.type == AssistantDiagnosisType.SAME_CHANNEL_CONGESTION }) {
        "Possible congestion inferred"
      } else {
        "No strong congestion signal"
      },
      severity = if (report.findings.any { it.type == AssistantDiagnosisType.SAME_CHANNEL_CONGESTION }) {
        AssistantSeverity.WARNING
      } else {
        AssistantSeverity.INFO
      },
      category = NetworkDoctorCategory.WIFI_ENVIRONMENT
    )

    val staleRouterState = context.routerInfo == null ||
      context.routerInfo.status != ServiceStatus.OK ||
      context.routerInfo.message.contains("not configured", ignoreCase = true)
    evidence += NetworkDoctorEvidenceUi(
      label = "Router state",
      value = when {
        context.routerInfo == null -> "Unavailable"
        staleRouterState -> context.routerInfo.message
        else -> context.routerInfo.message
      },
      severity = if (staleRouterState) AssistantSeverity.WARNING else AssistantSeverity.SUCCESS,
      category = NetworkDoctorCategory.ROUTER_HEALTH
    )

    return evidence
  }

  private fun buildUnavailableItems(context: AssistantContextSnapshot): List<NetworkDoctorUnavailableUi> {
    val items = mutableListOf<NetworkDoctorUnavailableUi>()
    items += NetworkDoctorUnavailableUi(
      title = "Per-device traffic unavailable",
      message = "The app does not yet expose per-device throughput telemetry, so heavy-user detection is inference-only.",
      category = NetworkDoctorCategory.THROUGHPUT
    )
    items += NetworkDoctorUnavailableUi(
      title = "Public IP unavailable",
      message = "The current context does not include public IP or WAN address telemetry.",
      category = NetworkDoctorCategory.CONNECTIVITY
    )
    items += NetworkDoctorUnavailableUi(
      title = "Direct Wi-Fi congestion unavailable",
      message = "RF channel and spectrum data are not available; congestion is inferred from symptoms only.",
      category = NetworkDoctorCategory.WIFI_ENVIRONMENT
    )
    val deviceControlStatus = context.deviceControlStatus
    val writableDeviceActions = deviceControlStatus?.deviceCapabilities?.values?.filter { it.writable }.orEmpty()
    val unavailableDeviceActions = deviceControlStatus?.deviceCapabilities?.values?.filterNot { it.writable }.orEmpty()
    if (deviceControlStatus == null || writableDeviceActions.isEmpty() || unavailableDeviceActions.isNotEmpty()) {
      items += NetworkDoctorUnavailableUi(
        title = if (writableDeviceActions.isNotEmpty()) {
          "Some device control actions unavailable"
        } else {
          "Device control actions unavailable"
        },
        message = when {
          deviceControlStatus == null -> "Block, pause/resume, rename, and prioritize controls are unsupported on the current backend/router."
          writableDeviceActions.isNotEmpty() -> buildString {
            append("Available actions are limited to ")
            append(writableDeviceActions.map { it.label }.distinct().joinToString(", "))
            append(". ")
            append(unavailableDeviceActions.firstOrNull()?.reason ?: deviceControlStatus.message)
          }
          else -> deviceControlStatus.message
        },
        category = NetworkDoctorCategory.SECURITY
      )
    }
    val routerCapabilities = context.routerStatus?.routerCapabilities.orEmpty()
    val writableRouterActions = routerCapabilities.values.filter { it.writable }
    val unavailableRouterActions = routerCapabilities.values.filterNot { it.writable }
    val routerControlUnavailable = context.routerInfo == null ||
      context.routerInfo.status != ServiceStatus.OK ||
      routerCapabilities.isEmpty() ||
      writableRouterActions.isEmpty() ||
      unavailableRouterActions.isNotEmpty()
    if (routerControlUnavailable) {
      val unsupportedRouterActions = unavailableRouterActions
        .mapNotNull { it.label }
        .distinct()
      val unsupportedRouterReasons = unavailableRouterActions
        .map { capability ->
          if (capability.status == ServiceStatus.NO_DATA) {
            "${capability.label} unavailable: ${capability.reason}"
          } else {
            "${capability.label} unsupported: ${capability.reason}"
          }
        }
        .filter { it.isNotBlank() }
        .distinct()
      items += NetworkDoctorUnavailableUi(
        title = if (writableRouterActions.isNotEmpty()) {
          "Some router actions unavailable"
        } else {
          "Router control unsupported or unavailable"
        },
        message = when {
          writableRouterActions.isNotEmpty() -> buildString {
            append("Available router actions are limited to ")
            append(writableRouterActions.map { it.label }.distinct().joinToString(", "))
            append(". ")
            append(unavailableRouterReasons.firstOrNull() ?: context.routerStatus?.message.orEmpty())
          }
          unsupportedRouterActions.isNotEmpty() -> buildString {
            append(unsupportedRouterActions.joinToString(", "))
            append(" are unsupported or unavailable on the current backend/router.")
            unsupportedRouterReasons.firstOrNull()?.let {
              append(" ")
              append(it)
            }
          }
          else -> context.routerStatus?.message
            ?: context.routerInfo?.message
            ?: "Router control could not be verified from the current context."
        },
        category = NetworkDoctorCategory.ROUTER_HEALTH
      )
    }
    return items
  }

  private fun categoryForFinding(type: AssistantDiagnosisType): NetworkDoctorCategory {
    return when (type) {
      AssistantDiagnosisType.ISP_WAN_OUTAGE,
      AssistantDiagnosisType.LOCAL_REACHABILITY_ISSUE -> NetworkDoctorCategory.CONNECTIVITY
      AssistantDiagnosisType.HIGH_LATENCY,
      AssistantDiagnosisType.HIGH_JITTER -> NetworkDoctorCategory.LATENCY
      AssistantDiagnosisType.PACKET_LOSS,
      AssistantDiagnosisType.HIGH_THROUGHPUT_HOG_DEVICE -> NetworkDoctorCategory.THROUGHPUT
      AssistantDiagnosisType.WEAK_WIFI_SIGNAL,
      AssistantDiagnosisType.SAME_CHANNEL_CONGESTION -> NetworkDoctorCategory.WIFI_ENVIRONMENT
      AssistantDiagnosisType.UNKNOWN_DEVICE_RISK -> NetworkDoctorCategory.SECURITY
    }
  }

  private fun categoryForCommand(command: String): NetworkDoctorCategory {
    val normalized = command.lowercase()
    return when {
      normalized.contains("speedtest") -> NetworkDoctorCategory.THROUGHPUT
      normalized.contains("analytics") -> NetworkDoctorCategory.LATENCY
      normalized.contains("map") || normalized.contains("topology") -> NetworkDoctorCategory.WIFI_ENVIRONMENT
      normalized.contains("devices") || normalized.contains("block") -> NetworkDoctorCategory.SECURITY
      else -> NetworkDoctorCategory.CONNECTIVITY
    }
  }

  private fun commandToAction(command: String, label: String): NetworkDoctorActionItem? {
    val action = when (command.lowercase()) {
      "scan network" -> NetworkDoctorAction.ScanNetwork
      "start speedtest", "run speedtest" -> NetworkDoctorAction.RunSpeedtest
      "refresh topology" -> NetworkDoctorAction.RefreshTopology
      "open devices" -> NetworkDoctorAction.OpenRoute(Routes.DEVICES)
      "open analytics" -> NetworkDoctorAction.OpenRoute(Routes.ANALYTICS)
      "open map" -> NetworkDoctorAction.OpenRoute(Routes.MAP)
      "run diagnostics" -> NetworkDoctorAction.Refresh
      else -> null
    } ?: return null

    return NetworkDoctorActionItem(label = label, action = action, prominent = true)
  }

  private fun format1(value: Double): String = String.format("%.1f", value)
}
