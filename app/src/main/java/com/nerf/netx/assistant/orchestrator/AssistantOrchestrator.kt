package com.nerf.netx.assistant.orchestrator

import com.nerf.netx.assistant.context.BuildAssistantContextUseCase
import com.nerf.netx.assistant.diagnostics.AssistantDiagnosticsEngine
import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType
import com.nerf.netx.assistant.model.AssistantResponse
import com.nerf.netx.assistant.state.AssistantSessionMemory
import com.nerf.netx.assistant.tools.AssistantToolRegistry
import com.nerf.netx.domain.Device

class AssistantOrchestrator(
  private val contextUseCase: BuildAssistantContextUseCase,
  private val parser: com.nerf.netx.assistant.parser.AssistantIntentParser,
  private val actionPolicy: AssistantActionPolicy,
  private val sessionMemory: AssistantSessionMemory,
  private val toolRegistry: AssistantToolRegistry,
  private val diagnosticsEngine: AssistantDiagnosticsEngine,
  private val responseComposer: AssistantResponseComposer
) {

  suspend fun handleUserMessage(message: String): AssistantResponse {
    val intent = parser.parse(message)

    if (intent.type == AssistantIntentType.CONFIRM_PENDING_ACTION) {
      val pending = sessionMemory.lastPendingConfirmationIntent
        ?: return responseComposer.noPendingConfirmation()
      sessionMemory.clearPendingConfirmation()
      return executeIntent(pending)
    }

    if (intent.type == AssistantIntentType.CANCEL_PENDING_ACTION) {
      sessionMemory.clearPendingConfirmation()
      return responseComposer.cancellationAcknowledged()
    }

    if (actionPolicy.requiresConfirmation(intent)) {
      sessionMemory.setPendingConfirmation(intent)
      return responseComposer.confirmationRequired(intent)
    }

    return executeIntent(intent)
  }

  private suspend fun executeIntent(intent: AssistantIntent): AssistantResponse {
    val context = contextUseCase()

    return when (intent.type) {
      AssistantIntentType.UNKNOWN -> responseComposer.unsupportedIntent()
      AssistantIntentType.NETWORK_STATUS_SUMMARY -> responseComposer.networkSummary(context)
      AssistantIntentType.RUN_DIAGNOSTICS -> {
        val report = diagnosticsEngine.buildReport(context)
        AssistantResponse(
          title = report.title,
          message = report.message,
          severity = report.severity,
          cards = report.cards
        )
      }
      AssistantIntentType.REFRESH_TOPOLOGY -> {
        val result = toolRegistry.execute(intent)
        responseComposer.fromToolResult(result)
      }
      AssistantIntentType.EXPLAIN_METRIC -> responseComposer.explainMetric(intent.metricKey)
      AssistantIntentType.EXPLAIN_FEATURE -> responseComposer.explainFeature(intent.featureKey)
      AssistantIntentType.PING_DEVICE,
      AssistantIntentType.BLOCK_DEVICE,
      AssistantIntentType.UNBLOCK_DEVICE,
      AssistantIntentType.PAUSE_DEVICE,
      AssistantIntentType.RESUME_DEVICE -> {
        val resolved = resolveDeviceIntent(intent, context)
        when {
          resolved == null -> responseComposer.missingDeviceTarget(intent.type)
          resolved.first == null -> responseComposer.ambiguousDeviceTarget(resolved.second)
          else -> {
            val resolvedIntent = intent.copy(targetDeviceId = resolved.first.id)
            sessionMemory.updateLastDiscussedDevice(resolved.first.id)
            val result = toolRegistry.execute(resolvedIntent)
            sessionMemory.recordAction(intent.type)
            responseComposer.fromToolResult(result)
          }
        }
      }
      else -> {
        val result = toolRegistry.execute(intent)
        sessionMemory.recordAction(intent.type)
        responseComposer.fromToolResult(result)
      }
    }
  }

  private fun resolveDeviceIntent(
    intent: AssistantIntent,
    context: AssistantContextSnapshot
  ): Pair<Device?, List<String>>? {
    if (context.devices.isEmpty()) return null

    val query = intent.targetDeviceQuery?.trim()
    if (query.isNullOrBlank()) {
      val remembered = sessionMemory.lastDiscussedDeviceId
      if (remembered.isNullOrBlank()) return null
      val byId = context.devices.firstOrNull { it.id == remembered }
      return if (byId == null) null else (byId to emptyList())
    }

    val lowered = query.lowercase()
    val exact = context.devices.filter {
      it.id.equals(query, ignoreCase = true) ||
        it.ip.equals(query, ignoreCase = true) ||
        it.mac.equals(query, ignoreCase = true) ||
        it.name.equals(query, ignoreCase = true)
    }
    if (exact.size == 1) return exact.first() to emptyList()
    if (exact.size > 1) return null to exact.map { formatDevice(it) }

    val fuzzy = context.devices.filter {
      it.name.lowercase().contains(lowered) ||
        it.ip.lowercase().contains(lowered) ||
        it.mac.lowercase().contains(lowered) ||
        it.hostname.lowercase().contains(lowered)
    }

    return when {
      fuzzy.isEmpty() -> null
      fuzzy.size == 1 -> fuzzy.first() to emptyList()
      else -> null to fuzzy.take(6).map { formatDevice(it) }
    }
  }

  private fun formatDevice(device: Device): String {
    return "${device.name.ifBlank { "Unknown" }} (${device.ip})"
  }
}
