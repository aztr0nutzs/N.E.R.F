package com.nerf.netx.networkdoctor.model

import com.nerf.netx.assistant.model.AssistantSeverity

enum class NetworkDoctorCategory {
  CONNECTIVITY,
  LATENCY,
  THROUGHPUT,
  WIFI_ENVIRONMENT,
  SECURITY,
  ROUTER_HEALTH
}

enum class NetworkDoctorHealthStatus {
  HEALTHY,
  DEGRADED,
  CRITICAL,
  UNAVAILABLE
}

sealed class NetworkDoctorAction {
  data object Refresh : NetworkDoctorAction()
  data object ScanNetwork : NetworkDoctorAction()
  data object RunSpeedtest : NetworkDoctorAction()
  data object RefreshTopology : NetworkDoctorAction()
  data class OpenRoute(val route: String) : NetworkDoctorAction()
  data class BlockDevice(val deviceId: String, val label: String) : NetworkDoctorAction()
}

data class NetworkDoctorActionItem(
  val label: String,
  val action: NetworkDoctorAction,
  val prominent: Boolean = false,
  val enabled: Boolean = true,
  val unavailableReason: String? = null
)

data class NetworkDoctorHealthSummary(
  val title: String,
  val message: String,
  val status: NetworkDoctorHealthStatus,
  val severity: AssistantSeverity,
  val issueCount: Int,
  val activeAlertCount: Int,
  val score: Int
)

data class NetworkDoctorFindingUi(
  val key: String,
  val title: String,
  val summary: String,
  val severity: AssistantSeverity,
  val category: NetworkDoctorCategory,
  val evidence: List<String>,
  val inferred: Boolean,
  val actions: List<NetworkDoctorActionItem> = emptyList()
)

data class NetworkDoctorRecommendationUi(
  val title: String,
  val rationale: String,
  val severity: AssistantSeverity,
  val category: NetworkDoctorCategory,
  val action: NetworkDoctorActionItem? = null
)

data class NetworkDoctorEvidenceUi(
  val label: String,
  val value: String,
  val severity: AssistantSeverity = AssistantSeverity.INFO,
  val category: NetworkDoctorCategory
)

data class NetworkDoctorUnavailableUi(
  val title: String,
  val message: String,
  val category: NetworkDoctorCategory
)
