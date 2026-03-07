package com.nerf.netx.assistant.model

import com.nerf.netx.domain.AnalyticsSnapshot
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.MapLink
import com.nerf.netx.domain.MapNode
import com.nerf.netx.domain.RouterInfoResult
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
  ACTION
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
  val style: AssistantActionStyle = AssistantActionStyle.SECONDARY
)

data class AssistantResponseCard(
  val type: AssistantCardType,
  val title: String,
  val lines: List<String>
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
  val routerInfo: RouterInfoResult?,
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
