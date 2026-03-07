package com.nerf.netx.networkdoctor.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nerf.netx.assistant.context.BuildAssistantContextUseCase
import com.nerf.netx.assistant.diagnostics.NetworkDiagnosisEngine
import com.nerf.netx.assistant.model.AssistantDiagnosisFocus
import com.nerf.netx.assistant.recommendation.RecommendationEngine
import com.nerf.netx.domain.AppServices
import com.nerf.netx.networkdoctor.model.NetworkDoctorAction
import com.nerf.netx.networkdoctor.state.NetworkDoctorLoadState
import com.nerf.netx.networkdoctor.state.NetworkDoctorUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NetworkDoctorViewModel(
  private val services: AppServices,
  private val contextUseCase: BuildAssistantContextUseCase,
  private val diagnosisEngine: NetworkDiagnosisEngine,
  private val recommendationEngine: RecommendationEngine,
  private val stateMapper: NetworkDoctorStateMapper,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

  private val _uiState = MutableStateFlow(NetworkDoctorUiState(loadState = NetworkDoctorLoadState.LOADING))
  val uiState: StateFlow<NetworkDoctorUiState> = _uiState.asStateFlow()

  private val _navigationEvents = MutableSharedFlow<String>()
  val navigationEvents: SharedFlow<String> = _navigationEvents.asSharedFlow()

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch(dispatcher) {
      _uiState.update { it.copy(loadState = NetworkDoctorLoadState.LOADING, actionMessage = null, runningAction = null) }
      runCatching {
        val context = contextUseCase()
        val diagnosis = diagnosisEngine.diagnose(context, AssistantDiagnosisFocus.GENERAL)
        val recommendations = recommendationEngine.buildRecommendations(diagnosis, context)
        stateMapper.mapReadyState(context, diagnosis, recommendations)
      }.onSuccess { mapped ->
        _uiState.value = mapped
      }.onFailure { error ->
        _uiState.value = stateMapper.errorState(error.message ?: "Network Doctor failed to load.")
      }
    }
  }

  fun onAction(action: NetworkDoctorAction) {
    viewModelScope.launch(dispatcher) {
      _uiState.update { it.copy(runningAction = action, actionMessage = null) }
      when (action) {
        NetworkDoctorAction.Refresh -> refresh()
        NetworkDoctorAction.ScanNetwork -> runServiceAction(action) {
          services.scan.startDeepScan()
          "Network scan requested."
        }
        NetworkDoctorAction.RunSpeedtest -> runServiceAction(action) {
          services.speedtest.start()
          "Speedtest requested."
        }
        NetworkDoctorAction.RefreshTopology -> runServiceAction(action) {
          services.topology.refreshTopology()
          "Topology refresh requested."
        }
        is NetworkDoctorAction.OpenRoute -> {
          _navigationEvents.emit(action.route)
          _uiState.update { it.copy(runningAction = null) }
        }
        is NetworkDoctorAction.BlockDevice -> {
          runServiceAction(action) {
            val result = services.deviceControl.block(action.deviceId)
            buildString {
              append(result.message)
              result.errorReason?.takeIf { it.isNotBlank() }?.let {
                append(" ")
                append(it)
              }
            }
          }
        }
      }
    }
  }

  private suspend fun runServiceAction(
    action: NetworkDoctorAction,
    operation: suspend () -> String
  ) {
    runCatching {
      val message = operation()
      val context = contextUseCase()
      val diagnosis = diagnosisEngine.diagnose(context, AssistantDiagnosisFocus.GENERAL)
      val recommendations = recommendationEngine.buildRecommendations(diagnosis, context)
      stateMapper.mapReadyState(context, diagnosis, recommendations, actionMessage = message)
    }.onSuccess { mapped ->
      _uiState.value = mapped.copy(runningAction = null)
    }.onFailure { error ->
      _uiState.update {
        it.copy(
          runningAction = null,
          actionMessage = error.message ?: "Action failed."
        )
      }
    }
  }
}

class NetworkDoctorViewModelFactory(
  private val services: AppServices,
  private val contextUseCase: BuildAssistantContextUseCase,
  private val diagnosisEngine: NetworkDiagnosisEngine,
  private val recommendationEngine: RecommendationEngine,
  private val stateMapper: NetworkDoctorStateMapper,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(NetworkDoctorViewModel::class.java)) {
      return NetworkDoctorViewModel(
        services = services,
        contextUseCase = contextUseCase,
        diagnosisEngine = diagnosisEngine,
        recommendationEngine = recommendationEngine,
        stateMapper = stateMapper,
        dispatcher = dispatcher
      ) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}
