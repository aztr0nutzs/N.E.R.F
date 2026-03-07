package com.nerf.netx.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nerf.netx.assistant.context.BuildAssistantContextUseCase
import com.nerf.netx.assistant.diagnostics.AssistantDiagnosticsEngine
import com.nerf.netx.assistant.model.AssistantDestination
import com.nerf.netx.assistant.model.AssistantLoadingState
import com.nerf.netx.assistant.model.AssistantMessage
import com.nerf.netx.assistant.model.AssistantMessageAuthor
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.assistant.orchestrator.AssistantActionPolicy
import com.nerf.netx.assistant.orchestrator.AssistantOrchestrator
import com.nerf.netx.assistant.orchestrator.AssistantResponseComposer
import com.nerf.netx.assistant.parser.AssistantIntentParser
import com.nerf.netx.assistant.recommendation.AssistantStarterPromptsProvider
import com.nerf.netx.assistant.state.AssistantSessionMemory
import com.nerf.netx.assistant.tools.AssistantToolRegistry
import com.nerf.netx.assistant.tools.DeviceTool
import com.nerf.netx.assistant.tools.NavigationTool
import com.nerf.netx.assistant.tools.RouterTool
import com.nerf.netx.assistant.tools.ScanTool
import com.nerf.netx.assistant.tools.SpeedtestTool
import com.nerf.netx.domain.AppServices
import kotlinx.coroutines.flow.collect

interface AssistantPanelActions {
  fun onNavigate(destination: AssistantDestination)
}

@Composable
fun AssistantPanelHost(
  services: AppServices,
  actions: AssistantPanelActions
) {
  val prompts = remember { AssistantStarterPromptsProvider() }
  val parser = remember { AssistantIntentParser() }
  val actionPolicy = remember { AssistantActionPolicy() }
  val memory = remember { AssistantSessionMemory() }
  val contextUseCase = remember(services) { BuildAssistantContextUseCase(services) }
  val toolRegistry = remember(services) {
    AssistantToolRegistry(
      scanTool = ScanTool(services),
      speedtestTool = SpeedtestTool(services),
      deviceTool = DeviceTool(services),
      routerTool = RouterTool(services),
      navigationTool = NavigationTool()
    )
  }
  val orchestrator = remember(contextUseCase, parser, actionPolicy, memory, toolRegistry, prompts) {
    AssistantOrchestrator(
      contextUseCase = contextUseCase,
      parser = parser,
      actionPolicy = actionPolicy,
      sessionMemory = memory,
      toolRegistry = toolRegistry,
      diagnosticsEngine = AssistantDiagnosticsEngine(),
      responseComposer = AssistantResponseComposer(prompts)
    )
  }

  val vm: AssistantViewModel = viewModel(
    factory = AssistantViewModelFactory(orchestrator, prompts)
  )
  val state by vm.uiState.collectAsState()

  LaunchedEffect(Unit) {
    vm.navigationEvents.collect { destination ->
      actions.onNavigate(destination)
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .statusBarsPadding()
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Text("NERF ASSISTANT", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      state.starterPrompts.forEach { prompt ->
        FilterChip(
          selected = false,
          onClick = { vm.onStarterPromptSelected(prompt) },
          label = { Text(prompt) }
        )
      }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        if (state.messages.isEmpty()) {
          item {
            Text(
              "Ask for network status, diagnostics, scans, speedtest control, or device actions.",
              style = MaterialTheme.typography.bodyMedium
            )
          }
        }

        items(state.messages, key = { it.id }) { message ->
          AssistantMessageItem(
            item = message,
            onSuggestedAction = { command ->
              vm.onInputChanged(command)
              vm.submitCurrentInput()
            },
            onConfirm = { vm.confirmPendingAction() },
            onCancel = { vm.cancelPendingAction() }
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedTextField(
        value = state.inputText,
        onValueChange = vm::onInputChanged,
        modifier = Modifier.weight(1f),
        label = { Text("Message") },
        placeholder = { Text("Type an assistant command") },
        singleLine = true
      )
      Button(
        onClick = { vm.submitCurrentInput() },
        enabled = state.loadingState != AssistantLoadingState.PROCESSING
      ) {
        if (state.loadingState == AssistantLoadingState.PROCESSING) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
          Text("Send")
        }
      }
    }

    if (state.loadingState == AssistantLoadingState.PROCESSING) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text("Processing request...", style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun AssistantMessageItem(
  item: AssistantMessage,
  onSuggestedAction: (String) -> Unit,
  onConfirm: () -> Unit,
  onCancel: () -> Unit
) {
  val alignment = if (item.author == AssistantMessageAuthor.USER) Alignment.CenterEnd else Alignment.CenterStart
  val bgColor = if (item.author == AssistantMessageAuthor.USER) Color(0xFF1E3A5F) else Color(0xFF2F2F3A)

  Column(horizontalAlignment = alignment, modifier = Modifier.fillMaxWidth()) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = bgColor,
      modifier = Modifier
        .fillMaxWidth(0.95f)
        .padding(vertical = 2.dp)
    ) {
      Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.author == AssistantMessageAuthor.ASSISTANT) {
          val response = item.response
          if (response != null) {
            Text(response.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(response.message, style = MaterialTheme.typography.bodyMedium)
            SeverityPill(response.severity)
            response.cards.forEach { card ->
              ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Text(card.title, style = MaterialTheme.typography.labelLarge)
                  card.lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                  }
                }
              }
            }
            if (response.requiresConfirmation) {
              Text(response.confirmationPrompt.orEmpty(), style = MaterialTheme.typography.bodySmall)
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text("Confirm") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
              }
            }
            if (response.suggestedActions.isNotEmpty()) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                response.suggestedActions.forEach { action ->
                  OutlinedButton(onClick = { onSuggestedAction(action.command) }) {
                    Text(action.label)
                  }
                }
              }
            }
          } else {
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
          }
        } else {
          Text(item.text, style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

@Composable
private fun SeverityPill(severity: AssistantSeverity) {
  val color = when (severity) {
    AssistantSeverity.INFO -> Color(0xFF4AA3FF)
    AssistantSeverity.SUCCESS -> Color(0xFF4DD387)
    AssistantSeverity.WARNING -> Color(0xFFFFB347)
    AssistantSeverity.ERROR -> Color(0xFFFF6B6B)
  }
  Box(
    modifier = Modifier
      .background(color.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
      .padding(horizontal = 8.dp, vertical = 2.dp)
  ) {
    Text(severity.name, style = MaterialTheme.typography.labelSmall, color = color)
  }
}
