package com.nerf.netx.networkdoctor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nerf.netx.assistant.context.BuildAssistantContextUseCase
import com.nerf.netx.assistant.diagnostics.NetworkDiagnosisEngine
import com.nerf.netx.assistant.recommendation.RecommendationEngine
import com.nerf.netx.domain.AppServices
import com.nerf.netx.networkdoctor.model.NetworkDoctorAction
import com.nerf.netx.networkdoctor.presentation.NetworkDoctorStateMapper
import com.nerf.netx.networkdoctor.presentation.NetworkDoctorViewModel
import com.nerf.netx.networkdoctor.presentation.NetworkDoctorViewModelFactory
import com.nerf.netx.networkdoctor.state.NetworkDoctorLoadState
import com.nerf.netx.networkdoctor.state.NetworkDoctorUiState

interface NetworkDoctorActions {
  fun onNavigate(route: String)
}

@Composable
fun NetworkDoctorHost(
  services: AppServices,
  actions: NetworkDoctorActions
) {
  val vm: NetworkDoctorViewModel = viewModel(
    factory = NetworkDoctorViewModelFactory(
      services = services,
      contextUseCase = BuildAssistantContextUseCase(services),
      diagnosisEngine = NetworkDiagnosisEngine(),
      recommendationEngine = RecommendationEngine(),
      stateMapper = NetworkDoctorStateMapper()
    )
  )
  val state by vm.uiState.collectAsState()
  val snackbarHost = remember { SnackbarHostState() }

  LaunchedEffect(Unit) {
    vm.navigationEvents.collect { route ->
      actions.onNavigate(route)
    }
  }

  LaunchedEffect(state.actionMessage) {
    state.actionMessage?.takeIf { it.isNotBlank() }?.let { snackbarHost.showSnackbar(it) }
  }

  NetworkDoctorScreen(
    state = state,
    snackbarHost = snackbarHost,
    onRefresh = vm::refresh,
    onAction = vm::onAction
  )
}

@Composable
fun NetworkDoctorScreen(
  state: NetworkDoctorUiState,
  snackbarHost: SnackbarHostState,
  onRefresh: () -> Unit,
  onAction: (NetworkDoctorAction) -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Text("NETWORK DOCTOR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    SnackbarHost(hostState = snackbarHost)

    when (state.loadState) {
      NetworkDoctorLoadState.LOADING -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }
      NetworkDoctorLoadState.ERROR -> {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
          Text(state.actionMessage ?: "Network Doctor failed to load.", style = MaterialTheme.typography.bodyMedium)
        }
      }
      else -> {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          state.healthSummary?.let { summary ->
            item { HealthSummaryCard(summary = summary, onRefresh = onRefresh) }
          }

          item { SectionHeader("Current Evidence") }
          if (state.currentEvidence.isEmpty()) {
            item { Text("No current evidence available.", style = MaterialTheme.typography.bodySmall) }
          } else {
            items(state.currentEvidence, key = { "${it.label}-${it.category}" }) { item ->
              CurrentEvidenceCard(item)
            }
          }

          item { SectionHeader("Ranked Issues") }
          if (state.issues.isEmpty()) {
            item {
              Text(
                state.emptyStateMessage ?: state.healthSummary?.message ?: "No active issues detected.",
                style = MaterialTheme.typography.bodyMedium
              )
            }
          } else {
            items(state.issues, key = { it.key }) { item ->
              DiagnosisFindingCard(item = item, onAction = onAction)
            }
          }

          item { SectionHeader("Recommended Next Actions") }
          if (state.recommendations.isEmpty()) {
            item { Text("No additional actions suggested right now.", style = MaterialTheme.typography.bodySmall) }
          } else {
            items(state.recommendations, key = { it.title }) { item ->
              RecommendationCard(item = item, onAction = onAction)
            }
          }

          item { SectionHeader("Unavailable Data") }
          if (state.unavailableItems.isEmpty()) {
            item { Text("All expected diagnostic inputs are currently available.", style = MaterialTheme.typography.bodySmall) }
          } else {
            items(state.unavailableItems, key = { it.title }) { item ->
              UnavailableDataCard(item)
            }
          }
        }
      }
    }
  }
}
