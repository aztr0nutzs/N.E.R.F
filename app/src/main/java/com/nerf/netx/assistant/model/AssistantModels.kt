package com.nerf.netx.assistant.model

import com.nerf.netx.domain.AnalyticsSnapshot
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.DeviceControlStatusSnapshot
import com.nerf.netx.domain.MapLink
import com.nerf.netx.domain.MapNode
import com.nerf.netx.domain.RouterInfoResult
import com.nerf.netx.domain.RouterStatusSnapshot
import com.nerf.netx.domain.ScanState
import com.nerf.netx.domain.SpeedtestResult
import com.nerf.netx.domain.SpeedtestUiState

enum class AssistantSeverity {
  INFO,
  SUCCESS,
  WARNING,
  ERROR
}

enum class AssistantCardType {
  STATUS,
  METRIC,
  DEVICE,
  ACTION,
  DIAGNOSIS,
  RECOMMENDATION,
  CLARIFICATION
}

enum class AssistantLoadingState {
  IDLE,
  PROCESSING
}

enum class AssistantMessageAuthor {
  USER,
  ASSISTANT
}

enum class AssistantActionStyle {
  PRIMARY,
  SECONDARY,
  DANGER
}

data class AssistantSuggestedAction(
  val label: String,
  val command: String,
  val destination: AssistantDestination? = null,
  val style: AssistantActionStyle = AssistantActionStyle.SECONDARY,
  val description: String? = null
)

data class AssistantConfirmationUi(
  val title: String,
  val summary: String,
  val details: List<String> = emptyList(),
  val confirmLabel: String = "Confirm",
  val cancelLabel: String = "Cancel"
)

data class AssistantResponseCard(
  val type: AssistantCardType,
  val title: String,
  val lines: List<String>,
  val severity: AssistantSeverity? = null,
  val bullets: List<String> = emptyList()
)

data class AssistantToolResult(
  val handled: Boolean,
  val success: Boolean,
  val supported: Boolean,
  val severity: AssistantSeverity,
  val title: String,
  val message: String,
  val destination: AssistantDestination? = null,
  val cards: List<AssistantResponseCard> = emptyList(),
  val suggestedActions: List<AssistantSuggestedAction> = emptyList(),
  val details: Map<String, String> = emptyMap()
)

data class AssistantResponse(
  val title: String,
  val message: String,
  val severity: AssistantSeverity,
  val suggestedActions: List<AssistantSuggestedAction> = emptyList(),
  val cards: List<AssistantResponseCard> = emptyList(),
  val requiresConfirmation: Boolean = false,
  val confirmationPrompt: String? = null,
  val confirmationUi: AssistantConfirmationUi? = null,
  val pendingIntent: AssistantIntent? = null,
  val destination: AssistantDestination? = null
)

data class AssistantContextSnapshot(
  val speedtestUi: SpeedtestUiState?,
  val latestSpeedtest: SpeedtestResult?,
  val scanState: ScanState?,
  val devices: List<Device>,
  val topologyNodes: List<MapNode>,
  val topologyLinks: List<MapLink>,
  val analytics: AnalyticsSnapshot?,
  val deviceControlStatus: DeviceControlStatusSnapshot?,
  val routerInfo: RouterInfoResult?,
  val routerStatus: RouterStatusSnapshot?,
  val capturedAtEpochMs: Long = System.currentTimeMillis()
)

data class AssistantMessage(
  val id: String,
  val author: AssistantMessageAuthor,
  val text: String,
  val response: AssistantResponse? = null,
  val timestampMs: Long = System.currentTimeMillis()
)

data class AssistantUiState(
  val loadingState: AssistantLoadingState = AssistantLoadingState.IDLE,
  val inputText: String = "",
  val starterPrompts: List<String> = emptyList(),
  val messages: List<AssistantMessage> = emptyList()
)

enum class AssistantEntityMatchType {
  CONTEXT_LAST_DISCUSSION,
  EXACT_IP,
  EXACT_HOSTNAME,
  EXACT_NICKNAME,
  EXACT_NAME,
  EXACT_VENDOR,
  FUZZY_NAME
}

data class AssistantDeviceCandidate(
  val id: String,
  val name: String,
  val ip: String,
  val vendor: String? = null,
  val hostname: String? = null,
  val matchType: AssistantEntityMatchType
)

sealed class AssistantEntityResolution {
  data class Resolved(
    val candidate: AssistantDeviceCandidate
  ) : AssistantEntityResolution()

  data class Ambiguous(
    val query: String,
    val candidates: List<AssistantDeviceCandidate>
  ) : AssistantEntityResolution()

  data class Missing(
    val query: String?
  ) : AssistantEntityResolution()
}

enum class AssistantDiagnosisType {
  ISP_WAN_OUTAGE,
  LOCAL_REACHABILITY_ISSUE,
  HIGH_LATENCY,
  HIGH_JITTER,
  PACKET_LOSS,
  WEAK_WIFI_SIGNAL,
  SAME_CHANNEL_CONGESTION,
  HIGH_THROUGHPUT_HOG_DEVICE,
  UNKNOWN_DEVICE_RISK
}

data class AssistantDiagnosisFinding(
  val type: AssistantDiagnosisType,
  val title: String,
  val summary: String,
  val severity: AssistantSeverity,
  val evidence: List<String>,
  val targetDeviceId: String? = null,
  val inferred: Boolean = false
)

data class AssistantRecommendation(
  val title: String,
  val rationale: String,
  val action: AssistantSuggestedAction,
  val priority: Int
)

data class AssistantDiagnosisReport(
  val title: String,
  val summary: String,
  val severity: AssistantSeverity,
  val findings: List<AssistantDiagnosisFinding>
)
