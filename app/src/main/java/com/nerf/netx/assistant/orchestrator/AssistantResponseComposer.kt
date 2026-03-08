package com.nerf.netx.assistant.orchestrator

import com.nerf.netx.assistant.model.AssistantCardType
import com.nerf.netx.assistant.model.AssistantDeviceCandidate
import com.nerf.netx.assistant.model.AssistantDiagnosisReport
import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.assistant.model.AssistantConfirmationUi
import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType
import com.nerf.netx.assistant.model.AssistantRecommendation
import com.nerf.netx.assistant.model.AssistantResponse
import com.nerf.netx.assistant.model.AssistantResponseCard
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.assistant.model.AssistantSuggestedAction
import com.nerf.netx.assistant.model.AssistantToolResult
import com.nerf.netx.assistant.recommendation.AssistantStarterPromptsProvider
import com.nerf.netx.domain.ActionSupportState

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

  fun confirmationRequired(
    intent: AssistantIntent,
    resolvedTarget: String? = null,
    detailLines: List<String> = emptyList()
  ): AssistantResponse {
    val text = confirmationActionLabel(intent, resolvedTarget)
    val title = "Confirm action"
    val summary = when (intent.type) {
      AssistantIntentType.REBOOT_ROUTER -> "This will reboot the router and interrupt active sessions."
      AssistantIntentType.BLOCK_DEVICE,
      AssistantIntentType.UNBLOCK_DEVICE,
      AssistantIntentType.PAUSE_DEVICE,
      AssistantIntentType.RESUME_DEVICE -> "This changes device access on the router backend for the resolved target."
      AssistantIntentType.SET_GUEST_WIFI,
      AssistantIntentType.SET_DNS_SHIELD -> "This changes a live router setting and may affect connected clients."
      else -> "This action changes live network behavior."
    }
    val confirmLabel = when (intent.type) {
      AssistantIntentType.REBOOT_ROUTER -> "Reboot router"
      AssistantIntentType.BLOCK_DEVICE -> "Block device"
      AssistantIntentType.UNBLOCK_DEVICE -> "Unblock device"
      AssistantIntentType.PAUSE_DEVICE -> "Pause device"
      AssistantIntentType.RESUME_DEVICE -> "Resume device"
      AssistantIntentType.SET_GUEST_WIFI -> "Apply guest Wi-Fi change"
      AssistantIntentType.SET_DNS_SHIELD -> "Apply DNS Shield change"
      else -> "Confirm"
    }
    return AssistantResponse(
      title = title,
      message = summary,
      severity = AssistantSeverity.WARNING,
      requiresConfirmation = true,
      confirmationPrompt = "$text. Use the confirmation buttons below, or reply \"yes\" / \"cancel\" if you prefer typing.",
      confirmationUi = AssistantConfirmationUi(
        title = text,
        summary = summary,
        details = detailLines,
        confirmLabel = confirmLabel,
        cancelLabel = "Cancel"
      ),
      pendingIntent = intent,
      suggestedActions = listOf(
        AssistantSuggestedAction(
          label = "Run diagnostics instead",
          command = "run diagnostics",
          description = "Check current network state before making the change."
        ),
        AssistantSuggestedAction(
          label = "Open devices",
          command = "open devices",
          description = "Review the device list or router context before acting."
        )
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
      message = "Pending action has been cancelled. No router or device change was sent.",
      severity = AssistantSeverity.INFO,
      suggestedActions = listOf(
        AssistantSuggestedAction(
          label = "Run diagnostics",
          command = "run diagnostics",
          description = "Review the current network state instead."
        )
      )
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

  fun deviceNotFound(query: String): AssistantResponse {
    return AssistantResponse(
      title = "Device not found",
      message = "I couldn't resolve \"$query\" to a discovered device.",
      severity = AssistantSeverity.WARNING,
      suggestedActions = listOf(
        AssistantSuggestedAction("Open Devices", "open devices"),
        AssistantSuggestedAction("Scan Network", "scan network")
      )
    )
  }

  fun unsupportedAction(label: String, support: ActionSupportState): AssistantResponse {
    val reason = support.reason?.takeIf { it.isNotBlank() } ?: "$label is unavailable."
    val alternatives = unsupportedAlternatives(label)
    return AssistantResponse(
      title = "$label unavailable",
      message = buildString {
        append("I couldn't complete $label.")
        append(" ")
        append(reason)
        append(" This is a backend/router capability limit, not a temporary assistant UI error.")
      },
      severity = AssistantSeverity.WARNING,
      cards = listOf(
        AssistantResponseCard(
          type = AssistantCardType.ACTION,
          title = "Why this is unavailable",
          lines = listOf(
            "$label could not be sent.",
            reason,
            "Current limitation: router/backend support for this action is missing or unavailable."
          ),
          bullets = alternatives.map { it.description ?: it.label }
        )
      ),
      suggestedActions = alternatives
    )
  }

  fun ambiguousDeviceTarget(
    intent: AssistantIntent,
    candidates: List<AssistantDeviceCandidate>
  ): AssistantResponse {
    val card = AssistantResponseCard(
      type = AssistantCardType.CLARIFICATION,
      title = "Matching devices",
      lines = candidates.map { candidate ->
        buildString {
          append(candidate.name)
          append(" - ")
          append(candidate.ip)
          candidate.vendor?.takeIf { it.isNotBlank() }?.let {
            append(" - ")
            append(it)
          }
        }
      }
    )
    return AssistantResponse(
      title = "Multiple devices matched",
      message = "Please choose one device instead of guessing.",
      severity = AssistantSeverity.WARNING,
      cards = listOf(card),
      suggestedActions = candidates.take(5).map { candidate ->
        AssistantSuggestedAction(
          label = candidate.name.take(18),
          command = clarificationCommand(intent, candidate)
        )
      }
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

  fun diagnosisResponse(
    report: AssistantDiagnosisReport,
    recommendations: List<AssistantRecommendation>
  ): AssistantResponse {
    val findingCards = report.findings.map { finding ->
      AssistantResponseCard(
        type = AssistantCardType.DIAGNOSIS,
        title = finding.title,
        lines = listOf(finding.summary),
        severity = finding.severity,
        bullets = finding.evidence + listOfNotNull(
          if (finding.inferred) "Inference only: this finding is based on symptoms, not direct backend telemetry." else null
        )
      )
    }
    val recommendationCard = if (recommendations.isEmpty()) {
      emptyList()
    } else {
      listOf(
        AssistantResponseCard(
          type = AssistantCardType.RECOMMENDATION,
          title = "Recommended next steps",
          lines = recommendations.map { it.title },
          bullets = recommendations.map { it.rationale }
        )
      )
    }

    return AssistantResponse(
      title = report.title,
      message = report.summary,
      severity = report.severity,
      cards = findingCards + recommendationCard,
      suggestedActions = recommendations.map { it.action }
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

  private fun confirmationActionLabel(intent: AssistantIntent, resolvedTarget: String?): String {
    return when (intent.type) {
      AssistantIntentType.REBOOT_ROUTER -> "Reboot router"
      AssistantIntentType.BLOCK_DEVICE -> "Block ${resolvedTarget ?: "device"}"
      AssistantIntentType.UNBLOCK_DEVICE -> "Unblock ${resolvedTarget ?: "device"}"
      AssistantIntentType.PAUSE_DEVICE -> "Pause ${resolvedTarget ?: "device"}"
      AssistantIntentType.RESUME_DEVICE -> "Resume ${resolvedTarget ?: "device"}"
      AssistantIntentType.SET_GUEST_WIFI -> "Turn guest Wi-Fi ${toggleText(intent.toggleEnabled)}"
      AssistantIntentType.SET_DNS_SHIELD -> "Turn DNS Shield ${toggleText(intent.toggleEnabled)}"
      else -> "Run this action"
    }
  }

  private fun unsupportedAlternatives(label: String): List<AssistantSuggestedAction> {
    val normalized = label.lowercase()
    val safeAction = when {
      normalized.contains("device") -> AssistantSuggestedAction(
        label = "Open devices",
        command = "open devices",
        description = "Review the device list and current state."
      )
      normalized.contains("guest") || normalized.contains("dns") || normalized.contains("router") -> AssistantSuggestedAction(
        label = "Run diagnostics",
        command = "run diagnostics",
        description = "Check router status, backend capability, and scan health."
      )
      else -> AssistantSuggestedAction(
        label = "Run diagnostics",
        command = "run diagnostics",
        description = "Inspect what is currently available."
      )
    }
    return listOf(
      safeAction,
      AssistantSuggestedAction(
        label = "Scan network",
        command = "scan network",
        description = "Refresh devices, topology, and assistant context."
      )
    )
  }

  private fun clarificationCommand(intent: AssistantIntent, candidate: AssistantDeviceCandidate): String {
    return when (intent.type) {
      AssistantIntentType.PING_DEVICE -> "ping device ${candidate.ip}"
      AssistantIntentType.BLOCK_DEVICE -> "block device ${candidate.ip}"
      AssistantIntentType.UNBLOCK_DEVICE -> "unblock device ${candidate.ip}"
      AssistantIntentType.PAUSE_DEVICE -> "pause device ${candidate.ip}"
      AssistantIntentType.RESUME_DEVICE -> "resume device ${candidate.ip}"
      AssistantIntentType.DIAGNOSE_NETWORK_ISSUES -> "what's wrong with ${candidate.ip}"
      AssistantIntentType.DIAGNOSE_SLOW_INTERNET -> "why is ${candidate.ip} slow"
      AssistantIntentType.DIAGNOSE_HIGH_LATENCY -> "why is ${candidate.ip} latency high"
      AssistantIntentType.RECOMMEND_NEXT_STEPS -> "what should i do next for ${candidate.ip}"
      else -> candidate.ip
    }
  }
}
