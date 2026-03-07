package com.nerf.netx.assistant.orchestrator

import com.nerf.netx.assistant.model.AssistantCardType
import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType
import com.nerf.netx.assistant.model.AssistantResponse
import com.nerf.netx.assistant.model.AssistantResponseCard
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.assistant.model.AssistantSuggestedAction
import com.nerf.netx.assistant.model.AssistantToolResult
import com.nerf.netx.assistant.recommendation.AssistantStarterPromptsProvider

class AssistantResponseComposer(
  private val promptsProvider: AssistantStarterPromptsProvider
) {

  fun unsupportedIntent(): AssistantResponse {
    return AssistantResponse(
      title = "I need a clearer command",
      message = "That request is not supported yet. Try one of the starter prompts.",
      severity = AssistantSeverity.WARNING,
      suggestedActions = promptsProvider.followUpActions()
    )
  }

  fun confirmationRequired(intent: AssistantIntent): AssistantResponse {
    val text = when (intent.type) {
      AssistantIntentType.REBOOT_ROUTER -> "Reboot router"
      AssistantIntentType.BLOCK_DEVICE -> "Block device"
      AssistantIntentType.UNBLOCK_DEVICE -> "Unblock device"
      AssistantIntentType.PAUSE_DEVICE -> "Pause device"
      AssistantIntentType.RESUME_DEVICE -> "Resume device"
      AssistantIntentType.SET_GUEST_WIFI -> "Set guest Wi-Fi ${toggleText(intent.toggleEnabled)}"
      AssistantIntentType.SET_DNS_SHIELD -> "Set DNS Shield ${toggleText(intent.toggleEnabled)}"
      else -> "Run this action"
    }
    return AssistantResponse(
      title = "Confirmation required",
      message = "This action is marked risky and needs explicit confirmation.",
      severity = AssistantSeverity.WARNING,
      requiresConfirmation = true,
      confirmationPrompt = "$text? Reply \"yes\" to continue or \"cancel\".",
      pendingIntent = intent,
      suggestedActions = listOf(
        AssistantSuggestedAction("Confirm", "yes"),
        AssistantSuggestedAction("Cancel", "cancel")
      )
    )
  }

  fun noPendingConfirmation(): AssistantResponse {
    return AssistantResponse(
      title = "Nothing pending",
      message = "There is no pending action to confirm.",
      severity = AssistantSeverity.INFO
    )
  }

  fun cancellationAcknowledged(): AssistantResponse {
    return AssistantResponse(
      title = "Action cancelled",
      message = "Pending action has been cancelled.",
      severity = AssistantSeverity.INFO
    )
  }

  fun missingDeviceTarget(intentType: AssistantIntentType): AssistantResponse {
    return AssistantResponse(
      title = "Which device?",
      message = "I need a device target for ${intentType.name.lowercase().replace('_', ' ')}.",
      severity = AssistantSeverity.WARNING,
      suggestedActions = listOf(
        AssistantSuggestedAction("Open Devices", "open devices"),
        AssistantSuggestedAction("Scan Network", "scan network")
      )
    )
  }

  fun ambiguousDeviceTarget(candidates: List<String>): AssistantResponse {
    val card = AssistantResponseCard(
      type = AssistantCardType.DEVICE,
      title = "Matching devices",
      lines = candidates
    )
    return AssistantResponse(
      title = "Multiple devices matched",
      message = "Please be more specific. I found multiple candidates.",
      severity = AssistantSeverity.WARNING,
      cards = listOf(card)
    )
  }

  fun fromToolResult(result: AssistantToolResult): AssistantResponse {
    return AssistantResponse(
      title = result.title,
      message = result.message,
      severity = result.severity,
      cards = result.cards,
      suggestedActions = result.suggestedActions,
      destination = result.destination
    )
  }

  fun networkSummary(context: AssistantContextSnapshot): AssistantResponse {
    val online = context.devices.count { it.online }
    val card = AssistantResponseCard(
      type = AssistantCardType.STATUS,
      title = "Current network",
      lines = listOf(
        "Devices online: $online/${context.devices.size}",
        "Scan phase: ${context.scanState?.phase ?: "N/A"}",
        "Speedtest phase: ${context.speedtestUi?.phaseEnum ?: "N/A"}",
        "Router gateway: ${context.routerInfo?.gatewayIp ?: "Unknown"}"
      )
    )
    return AssistantResponse(
      title = "Network status summary",
      message = "Summary generated from live app state.",
      severity = AssistantSeverity.INFO,
      cards = listOf(card),
      suggestedActions = promptsProvider.followUpActions()
    )
  }

  fun explainMetric(metric: String?): AssistantResponse {
    val value = metric?.trim().orEmpty()
    val normalized = value.lowercase()
    val explanation = when {
      normalized.contains("ping") || normalized.contains("latency") -> "Latency is round-trip delay in milliseconds. Lower is better."
      normalized.contains("jitter") -> "Jitter is latency variation over time. Lower means more stable connections."
      normalized.contains("packet") || normalized.contains("loss") -> "Packet loss is the percentage of dropped packets. Lower is better."
      normalized.contains("download") -> "Download speed measures data received per second in Mbps."
      normalized.contains("upload") -> "Upload speed measures data sent per second in Mbps."
      else -> null
    }

    if (explanation == null) {
      return AssistantResponse(
        title = "Metric not recognized",
        message = "I can explain ping, latency, jitter, packet loss, download, and upload.",
        severity = AssistantSeverity.WARNING
      )
    }

    return AssistantResponse(
      title = "Metric explanation",
      message = explanation,
      severity = AssistantSeverity.INFO
    )
  }

  fun explainFeature(feature: String?): AssistantResponse {
    val normalized = feature?.trim()?.lowercase().orEmpty()
    val explanation = when {
      normalized.contains("speedtest") -> "Speedtest measures ping, download, and upload using configured test servers."
      normalized.contains("diagnostic") -> "Diagnostics summarize scan, speedtest, analytics, and router state for quick triage."
      normalized.contains("topology") || normalized.contains("map") -> "Topology map visualizes discovered devices and inferred link quality."
      normalized.contains("guest wifi") -> "Guest Wi-Fi is controlled through router API support and may be unavailable on unsupported routers."
      normalized.contains("dns shield") -> "DNS Shield maps to router-side security controls when capability exists."
      else -> null
    }

    if (explanation == null) {
      return AssistantResponse(
        title = "Feature not recognized",
        message = "Try asking about speedtest, diagnostics, topology/map, guest wifi, or dns shield.",
        severity = AssistantSeverity.WARNING
      )
    }

    return AssistantResponse(
      title = "Feature explanation",
      message = explanation,
      severity = AssistantSeverity.INFO
    )
  }

  private fun toggleText(toggle: Boolean?): String {
    return when (toggle) {
      true -> "ON"
      false -> "OFF"
      null -> "state"
    }
  }
}
