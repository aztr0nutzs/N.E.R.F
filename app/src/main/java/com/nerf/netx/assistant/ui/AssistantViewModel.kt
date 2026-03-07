package com.nerf.netx.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nerf.netx.assistant.model.AssistantDestination
import com.nerf.netx.assistant.model.AssistantLoadingState
import com.nerf.netx.assistant.model.AssistantMessage
import com.nerf.netx.assistant.model.AssistantMessageAuthor
import com.nerf.netx.assistant.model.AssistantUiState
import com.nerf.netx.assistant.orchestrator.AssistantOrchestrator
import com.nerf.netx.assistant.recommendation.AssistantStarterPromptsProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class AssistantViewModel(
  private val orchestrator: AssistantOrchestrator,
  promptsProvider: AssistantStarterPromptsProvider
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    AssistantUiState(
      starterPrompts = promptsProvider.prompts()
    )
  )
  val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

  private val _navigationEvents = MutableSharedFlow<AssistantDestination>()
  val navigationEvents: SharedFlow<AssistantDestination> = _navigationEvents.asSharedFlow()

  fun onInputChanged(value: String) {
    _uiState.update { it.copy(inputText = value) }
  }

  fun onStarterPromptSelected(prompt: String) {
    onInputChanged(prompt)
    submitCurrentInput()
  }

  fun submitCurrentInput() {
    val text = uiState.value.inputText.trim()
    if (text.isBlank()) return
    submitMessage(text)
    _uiState.update { it.copy(inputText = "") }
  }

  fun confirmPendingAction() {
    submitMessage("yes")
  }

  fun cancelPendingAction() {
    submitMessage("cancel")
  }

  private fun submitMessage(message: String) {
    appendMessage(AssistantMessage(author = AssistantMessageAuthor.USER, text = message, id = UUID.randomUUID().toString()))
    _uiState.update { it.copy(loadingState = AssistantLoadingState.PROCESSING) }

    viewModelScope.launch {
      val response = orchestrator.handleUserMessage(message)
      appendMessage(
        AssistantMessage(
          id = UUID.randomUUID().toString(),
          author = AssistantMessageAuthor.ASSISTANT,
          text = response.message,
          response = response
        )
      )
      _uiState.update { it.copy(loadingState = AssistantLoadingState.IDLE) }
      response.destination?.let { _navigationEvents.emit(it) }
    }
  }

  private fun appendMessage(message: AssistantMessage) {
    _uiState.update { it.copy(messages = it.messages + message) }
  }
}

class AssistantViewModelFactory(
  private val orchestrator: AssistantOrchestrator,
  private val promptsProvider: AssistantStarterPromptsProvider
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
      return AssistantViewModel(orchestrator, promptsProvider) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}
