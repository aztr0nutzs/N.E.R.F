package com.nerf.netx.networkdoctor.state

import com.nerf.netx.networkdoctor.model.NetworkDoctorAction
import com.nerf.netx.networkdoctor.model.NetworkDoctorEvidenceUi
import com.nerf.netx.networkdoctor.model.NetworkDoctorFindingUi
import com.nerf.netx.networkdoctor.model.NetworkDoctorHealthSummary
import com.nerf.netx.networkdoctor.model.NetworkDoctorRecommendationUi
import com.nerf.netx.networkdoctor.model.NetworkDoctorUnavailableUi

enum class NetworkDoctorLoadState {
  IDLE,
  LOADING,
  READY,
  ERROR
}

data class NetworkDoctorUiState(
  val loadState: NetworkDoctorLoadState = NetworkDoctorLoadState.IDLE,
  val healthSummary: NetworkDoctorHealthSummary? = null,
  val issues: List<NetworkDoctorFindingUi> = emptyList(),
  val recommendations: List<NetworkDoctorRecommendationUi> = emptyList(),
  val currentEvidence: List<NetworkDoctorEvidenceUi> = emptyList(),
  val unavailableItems: List<NetworkDoctorUnavailableUi> = emptyList(),
  val isHealthy: Boolean = false,
  val emptyStateMessage: String? = null,
  val actionMessage: String? = null,
  val runningAction: NetworkDoctorAction? = null,
  val lastUpdatedEpochMs: Long? = null
)
