package com.nerf.netx.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nerf.netx.domain.SpeedtestConfig
import com.nerf.netx.domain.SpeedtestService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class SpeedtestViewModel(
  private val speedtestService: SpeedtestService
) : ViewModel() {

  val uiState = speedtestService.ui
  val servers = speedtestService.servers
  val config = speedtestService.config
  val history = speedtestService.history
  val latestResult = speedtestService.latestResult

  private val _errorEvents = MutableSharedFlow<String>()
  val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

  fun onStartClicked() {
    viewModelScope.launch {
      try {
        speedtestService.start()
      } catch (e: Exception) {
        _errorEvents.emit(e.message ?: "Failed to start speedtest")
      }
    }
  }

  fun onAbortClicked() {
    viewModelScope.launch {
      speedtestService.stop()
    }
  }

  fun onResetClicked() {
    viewModelScope.launch {
      speedtestService.reset()
    }
  }

  fun onUpdateConfig(config: SpeedtestConfig) {
    viewModelScope.launch {
      speedtestService.updateConfig(config)
    }
  }

  fun onClearHistoryClicked() {
    viewModelScope.launch {
      speedtestService.clearHistory()
    }
  }
}

class SpeedtestViewModelFactory(
  private val service: SpeedtestService
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(SpeedtestViewModel::class.java)) {
      return SpeedtestViewModel(service) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}
