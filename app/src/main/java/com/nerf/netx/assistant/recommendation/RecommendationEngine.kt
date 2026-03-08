package com.nerf.netx.assistant.recommendation

import com.nerf.netx.assistant.model.AssistantActionStyle
import com.nerf.netx.assistant.model.AssistantDiagnosisReport
import com.nerf.netx.assistant.model.AssistantDiagnosisType
import com.nerf.netx.assistant.model.AssistantRecommendation
import com.nerf.netx.assistant.model.AssistantSuggestedAction
import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.domain.Device

class RecommendationEngine {

  fun buildRecommendations(
    report: AssistantDiagnosisReport,
    context: AssistantContextSnapshot,
    targetDevice: Device? = null
  ): List<AssistantRecommendation> {
    val out = mutableListOf<AssistantRecommendation>()

    report.findings.forEach { finding ->
      when (finding.type) {
        AssistantDiagnosisType.ISP_WAN_OUTAGE -> {
          out += recommendation(
            title = "Verify internet path with a fresh speedtest",
            rationale = "The latest internet-facing check failed while the router still appears reachable.",
            action = AssistantSuggestedAction("Run Current Speedtest Mode", "start speedtest", style = AssistantActionStyle.PRIMARY),
            priority = 10
          )
          out += recommendation(
            title = "Review the latest network analytics",
            rationale = "Analytics helps separate a WAN outage from a transient speedtest/server issue.",
            action = AssistantSuggestedAction("Open Analytics", "open analytics"),
            priority = 9
          )
        }
        AssistantDiagnosisType.LOCAL_REACHABILITY_ISSUE -> {
          out += recommendation(
            title = "Run a fresh LAN scan",
            rationale = "A new scan will confirm whether devices are still reachable and refresh topology state.",
            action = AssistantSuggestedAction("Scan Network", "scan network", style = AssistantActionStyle.PRIMARY),
            priority = 9
          )
          out += recommendation(
            title = "Inspect the map for broken local paths",
            rationale = "Topology view is the fastest way to see whether the gateway path looks degraded.",
            action = AssistantSuggestedAction("Open Map", "open map"),
            priority = 8
          )
        }
        AssistantDiagnosisType.HIGH_LATENCY,
        AssistantDiagnosisType.HIGH_JITTER,
        AssistantDiagnosisType.PACKET_LOSS -> {
          out += recommendation(
            title = "Capture a fresh analytics snapshot",
            rationale = "Updated measurements help confirm whether degradation is persistent or transient.",
            action = AssistantSuggestedAction("Open Analytics", "open analytics", style = AssistantActionStyle.PRIMARY),
            priority = 8
          )
          if (targetDevice != null) {
            out += recommendation(
              title = "Ping the affected device",
              rationale = "A direct device probe helps separate one-host issues from network-wide latency.",
              action = AssistantSuggestedAction(
                "Ping Device",
                "ping device ${targetDevice.ip}"
              ),
              priority = 7
            )
          }
        }
        AssistantDiagnosisType.WEAK_WIFI_SIGNAL -> {
          out += recommendation(
            title = "Inspect wireless placement and path quality",
            rationale = "Weak signal is the clearest local cause of retries and slowdown.",
            action = AssistantSuggestedAction("Open Map", "open map", style = AssistantActionStyle.PRIMARY),
            priority = 8
          )
        }
        AssistantDiagnosisType.SAME_CHANNEL_CONGESTION -> {
          out += recommendation(
            title = "Check how many devices are active",
            rationale = "The current finding is inferred from contention symptoms and active device count.",
            action = AssistantSuggestedAction("Open Devices", "open devices", style = AssistantActionStyle.PRIMARY),
            priority = 7
          )
        }
        AssistantDiagnosisType.HIGH_THROUGHPUT_HOG_DEVICE -> {
          out += recommendation(
            title = "Review likely heavy-use devices",
            rationale = "This app does not yet have per-device throughput telemetry, so the best next step is device inspection.",
            action = AssistantSuggestedAction("Open Devices", "open devices", style = AssistantActionStyle.PRIMARY),
            priority = 6
          )
        }
        AssistantDiagnosisType.UNKNOWN_DEVICE_RISK -> {
          out += recommendation(
            title = "Inspect the unknown device",
            rationale = "An unverified device should be reviewed before taking a risky control action.",
            action = AssistantSuggestedAction("Open Devices", "open devices", style = AssistantActionStyle.PRIMARY),
            priority = 7
          )
          if (targetDevice != null) {
            out += recommendation(
              title = "Ping the unknown device",
              rationale = "Reachability helps confirm whether the device is active before any containment step.",
              action = AssistantSuggestedAction("Ping Device", "ping device ${targetDevice.ip}"),
              priority = 6
            )
          }
        }
      }
    }

    if (out.none { it.action.command == "run diagnostics" } && report.findings.isNotEmpty()) {
      out += recommendation(
        title = "Refresh the diagnostic snapshot",
        rationale = "A general diagnostics refresh gives the assistant the latest scan, speedtest, and router context.",
        action = AssistantSuggestedAction("Run Diagnostics", "run diagnostics"),
        priority = 5
      )
    }

    if (context.devices.isEmpty()) {
      out += recommendation(
        title = "Populate device context first",
        rationale = "Entity resolution and local diagnostics are limited until at least one scan has completed.",
        action = AssistantSuggestedAction("Scan Network", "scan network", style = AssistantActionStyle.PRIMARY),
        priority = 10
      )
    }

    return out
      .sortedByDescending { it.priority }
      .distinctBy { it.action.command }
      .take(5)
  }

  private fun recommendation(
    title: String,
    rationale: String,
    action: AssistantSuggestedAction,
    priority: Int
  ): AssistantRecommendation {
    return AssistantRecommendation(
      title = title,
      rationale = rationale,
      action = action,
      priority = priority
    )
  }
}
