package com.nerf.netx.assistant.orchestrator

import com.nerf.netx.assistant.context.BuildAssistantContextUseCase
import com.nerf.netx.assistant.diagnostics.AssistantDiagnosticsEngine
import com.nerf.netx.assistant.diagnostics.NetworkDiagnosisEngine
import com.nerf.netx.assistant.model.AssistantDiagnosisFocus
import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.assistant.model.AssistantEntityResolution
import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType
import com.nerf.netx.assistant.model.AssistantResponse
import com.nerf.netx.assistant.recommendation.RecommendationEngine
import com.nerf.netx.assistant.state.AssistantSessionMemory
import com.nerf.netx.assistant.tools.AssistantToolRegistry
import com.nerf.netx.domain.ActionSupportCatalog
import com.nerf.netx.domain.AppActionId
import com.nerf.netx.domain.Device

class AssistantOrchestrator(
  private val contextUseCase: BuildAssistantContextUseCase,
  private val parser: com.nerf.netx.assistant.parser.AssistantIntentParser,
  private val actionPolicy: AssistantActionPolicy,
  private val sessionMemory: AssistantSessionMemory,
  private val toolRegistry: AssistantToolRegistry,
  private val entityResolver: AssistantEntityResolver,
  private val diagnosticsEngine: AssistantDiagnosticsEngine,
  private val networkDiagnosisEngine: NetworkDiagnosisEngine,
  private val recommendationEngine: RecommendationEngine,
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

    unsupportedBeforeConfirmation(intent)?.let { return it }

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
        val summary = diagnosticsEngine.buildReport(context)
        val diagnosis = networkDiagnosisEngine.diagnose(context)
        val recommendations = recommendationEngine.buildRecommendations(diagnosis, context)
        val response = responseComposer.diagnosisResponse(diagnosis, recommendations)
        response.copy(
          message = if (diagnosis.findings.isEmpty()) summary.message else diagnosis.summary,
          cards = summary.cards + response.cards
        )
      }
      AssistantIntentType.REFRESH_TOPOLOGY -> {
        val result = toolRegistry.execute(intent)
        responseComposer.fromToolResult(result)
      }
      AssistantIntentType.DIAGNOSE_NETWORK_ISSUES,
      AssistantIntentType.DIAGNOSE_SLOW_INTERNET,
      AssistantIntentType.DIAGNOSE_HIGH_LATENCY,
      AssistantIntentType.RECOMMEND_NEXT_STEPS -> handleDiagnosisIntent(intent, context)
      AssistantIntentType.EXPLAIN_METRIC -> responseComposer.explainMetric(intent.metricKey)
      AssistantIntentType.EXPLAIN_FEATURE -> responseComposer.explainFeature(intent.featureKey)
      AssistantIntentType.PING_DEVICE,
      AssistantIntentType.BLOCK_DEVICE,
      AssistantIntentType.UNBLOCK_DEVICE,
      AssistantIntentType.PAUSE_DEVICE,
      AssistantIntentType.RESUME_DEVICE -> handleDeviceIntent(intent, context)
      else -> {
        val result = toolRegistry.execute(intent)
        sessionMemory.recordAction(intent.type)
        responseComposer.fromToolResult(result)
      }
    }
  }

  private suspend fun handleDeviceIntent(
    intent: AssistantIntent,
    context: AssistantContextSnapshot
  ): AssistantResponse {
    return when (val resolution = entityResolver.resolveDevice(
      query = intent.targetDeviceQuery,
      devices = context.devices,
      lastDiscussedDeviceId = sessionMemory.lastDiscussedDeviceId,
      allowContextFallback = true
    )) {
      is AssistantEntityResolution.Missing -> {
        if (resolution.query.isNullOrBlank()) {
          responseComposer.missingDeviceTarget(intent.type)
        } else {
          responseComposer.deviceNotFound(resolution.query)
        }
      }
      is AssistantEntityResolution.Ambiguous -> responseComposer.ambiguousDeviceTarget(intent, resolution.candidates)
      is AssistantEntityResolution.Resolved -> {
        sessionMemory.updateLastDiscussedDevice(resolution.candidate.id)
        val result = toolRegistry.execute(intent.copy(targetDeviceId = resolution.candidate.id))
        sessionMemory.recordAction(intent.type)
        responseComposer.fromToolResult(result)
      }
    }
  }

  private fun handleDiagnosisIntent(
    intent: AssistantIntent,
    context: AssistantContextSnapshot
  ): AssistantResponse {
    val targetDevice = resolveDiagnosisTarget(intent, context)
    if (targetDevice is DiagnosisTargetResponse) {
      return targetDevice.response
    }

    val device = (targetDevice as DiagnosisTargetDevice).device
    device?.let { sessionMemory.updateLastDiscussedDevice(it.id) }

    val focus = intent.diagnosisFocus ?: when (intent.type) {
      AssistantIntentType.DIAGNOSE_SLOW_INTERNET -> AssistantDiagnosisFocus.SPEED
      AssistantIntentType.DIAGNOSE_HIGH_LATENCY -> AssistantDiagnosisFocus.LATENCY
      AssistantIntentType.RECOMMEND_NEXT_STEPS -> AssistantDiagnosisFocus.NEXT_STEPS
      else -> AssistantDiagnosisFocus.GENERAL
    }
    val diagnosis = networkDiagnosisEngine.diagnose(context, focus = focus, targetDevice = device)
    val recommendations = recommendationEngine.buildRecommendations(diagnosis, context, device)
    sessionMemory.recordAction(intent.type)
    return responseComposer.diagnosisResponse(diagnosis, recommendations)
  }

  private fun resolveDiagnosisTarget(
    intent: AssistantIntent,
    context: AssistantContextSnapshot
  ): DiagnosisTarget {
    val query = intent.targetDeviceQuery?.trim()
    if (query.isNullOrBlank()) {
      return DiagnosisTargetDevice(null)
    }

    return when (val resolution = entityResolver.resolveDevice(
      query = query,
      devices = context.devices,
      lastDiscussedDeviceId = sessionMemory.lastDiscussedDeviceId,
      allowContextFallback = false
    )) {
      is AssistantEntityResolution.Missing -> DiagnosisTargetResponse(responseComposer.deviceNotFound(query))
      is AssistantEntityResolution.Ambiguous -> DiagnosisTargetResponse(
        responseComposer.ambiguousDeviceTarget(intent, resolution.candidates)
      )
      is AssistantEntityResolution.Resolved -> {
        DiagnosisTargetDevice(context.devices.firstOrNull { it.id == resolution.candidate.id })
      }
    }
  }

  private sealed interface DiagnosisTarget
  private data class DiagnosisTargetDevice(val device: Device?) : DiagnosisTarget
  private data class DiagnosisTargetResponse(val response: AssistantResponse) : DiagnosisTarget

  private val DiagnosisTarget.response: AssistantResponse
    get() = (this as DiagnosisTargetResponse).response

  private suspend fun unsupportedBeforeConfirmation(intent: AssistantIntent): AssistantResponse? {
    val deviceAction = when (intent.type) {
      AssistantIntentType.BLOCK_DEVICE -> "Block device" to AppActionId.DEVICE_BLOCK
      AssistantIntentType.UNBLOCK_DEVICE -> "Unblock device" to AppActionId.DEVICE_UNBLOCK
      AssistantIntentType.PAUSE_DEVICE -> "Pause device" to AppActionId.DEVICE_PAUSE
      AssistantIntentType.RESUME_DEVICE -> "Resume device" to AppActionId.DEVICE_RESUME
      else -> null
    }
    if (deviceAction != null) {
      val context = contextUseCase()
      val resolvedDevice = entityResolver.resolveDevice(
        query = intent.targetDeviceQuery,
        devices = context.devices,
        lastDiscussedDeviceId = sessionMemory.lastDiscussedDeviceId,
        allowContextFallback = true
      )
      val support = (resolvedDevice as? AssistantEntityResolution.Resolved)
        ?.candidate
        ?.id
        ?.let { deviceId -> context.devices.firstOrNull { it.id == deviceId } }
        ?.let { device -> ActionSupportCatalog.deviceActionState(deviceAction.second, device, context.deviceControlStatus) }
      if (support != null && !support.supported) {
        return responseComposer.unsupportedAction(deviceAction.first, support)
      }
    }

    val routerAction = when (intent.type) {
      AssistantIntentType.SET_GUEST_WIFI -> "Guest Wi-Fi" to AppActionId.ROUTER_GUEST_WIFI
      AssistantIntentType.SET_DNS_SHIELD -> "DNS Shield" to AppActionId.ROUTER_DNS_SHIELD
      AssistantIntentType.REBOOT_ROUTER -> "Reboot router" to AppActionId.ROUTER_REBOOT
      AssistantIntentType.FLUSH_DNS -> "Flush DNS" to AppActionId.ROUTER_FLUSH_DNS
      else -> null
    } ?: return null

    val context = contextUseCase()
    val support = context.routerStatus?.actionSupport?.get(routerAction.second)
    return if (support != null && !support.supported) {
      responseComposer.unsupportedAction(routerAction.first, support)
    } else {
      null
    }
  }
}
