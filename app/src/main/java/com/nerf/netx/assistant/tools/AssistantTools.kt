package com.nerf.netx.assistant.tools

import com.nerf.netx.assistant.model.AssistantCardType
import com.nerf.netx.assistant.model.AssistantDestination
import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType
import com.nerf.netx.assistant.model.AssistantResponseCard
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.assistant.model.AssistantToolResult
import com.nerf.netx.domain.ActionResult
import com.nerf.netx.domain.ActionSupportCatalog
import com.nerf.netx.domain.ActionSupportState
import com.nerf.netx.domain.AppServices
import com.nerf.netx.domain.ServiceStatus

class ScanTool(
  private val services: AppServices
) {
  suspend fun run(intent: AssistantIntent): AssistantToolResult {
    return when (intent.type) {
      AssistantIntentType.SCAN_NETWORK -> {
        services.scan.startDeepScan()
        AssistantToolResult(
          handled = true,
          success = true,
          supported = true,
          severity = AssistantSeverity.INFO,
          title = "Network scan started",
          message = "Deep scan has been requested. Results will populate as devices are discovered."
        )
      }
      AssistantIntentType.REFRESH_TOPOLOGY -> {
        services.topology.refreshTopology()
        AssistantToolResult(
          handled = true,
          success = true,
          supported = true,
          severity = AssistantSeverity.INFO,
          title = "Topology refreshed",
          message = "Topology refresh has been requested."
        )
      }
      else -> AssistantToolResult(false, false, false, AssistantSeverity.WARNING, "Unsupported", "Intent not handled by ScanTool")
    }
  }
}

class SpeedtestTool(
  private val services: AppServices
) {
  suspend fun run(intent: AssistantIntent): AssistantToolResult {
    return when (intent.type) {
      AssistantIntentType.START_SPEEDTEST -> {
        services.speedtest.start()
        AssistantToolResult(true, true, true, AssistantSeverity.INFO, "Speedtest started", "Speedtest is now running.")
      }
      AssistantIntentType.STOP_SPEEDTEST -> {
        services.speedtest.stop()
        AssistantToolResult(true, true, true, AssistantSeverity.INFO, "Speedtest stopped", "Speedtest stop was requested.")
      }
      AssistantIntentType.RESET_SPEEDTEST -> {
        services.speedtest.reset()
        AssistantToolResult(true, true, true, AssistantSeverity.INFO, "Speedtest reset", "Speedtest state has been reset.")
      }
      else -> AssistantToolResult(false, false, false, AssistantSeverity.WARNING, "Unsupported", "Intent not handled by SpeedtestTool")
    }
  }
}

class DeviceTool(
  private val services: AppServices
) {
  suspend fun run(intent: AssistantIntent): AssistantToolResult {
    val deviceId = intent.targetDeviceId
      ?: return AssistantToolResult(true, false, false, AssistantSeverity.WARNING, "Missing device", "A concrete device target is required.")

    return when (intent.type) {
      AssistantIntentType.PING_DEVICE -> mapActionResult(services.deviceControl.ping(deviceId), "Ping device")
      AssistantIntentType.BLOCK_DEVICE -> mapActionResult(services.deviceControl.setBlocked(deviceId, true), "Block device")
      AssistantIntentType.UNBLOCK_DEVICE -> mapActionResult(services.deviceControl.setBlocked(deviceId, false), "Unblock device")
      AssistantIntentType.PAUSE_DEVICE -> mapActionResult(services.deviceControl.setPaused(deviceId, true), "Pause device")
      AssistantIntentType.RESUME_DEVICE -> mapActionResult(services.deviceControl.setPaused(deviceId, false), "Resume device")
      else -> AssistantToolResult(false, false, false, AssistantSeverity.WARNING, "Unsupported", "Intent not handled by DeviceTool")
    }
  }

  private fun mapActionResult(result: ActionResult, title: String): AssistantToolResult {
    val severity = when {
      result.ok -> AssistantSeverity.SUCCESS
      result.status == ServiceStatus.NOT_SUPPORTED || result.status == ServiceStatus.NO_DATA -> AssistantSeverity.WARNING
      else -> AssistantSeverity.ERROR
    }
    return AssistantToolResult(
      handled = true,
      success = result.ok,
      supported = result.status != ServiceStatus.NOT_SUPPORTED,
      severity = severity,
      title = title,
      message = actionMessage(result, title),
      details = result.details,
      cards = if (result.details.isEmpty()) emptyList() else listOf(
        AssistantResponseCard(
          type = AssistantCardType.ACTION,
          title = "Result details",
          lines = result.details.entries.map { "${it.key}: ${it.value}" }
        )
      )
    )
  }

  private fun unsupported(title: String, message: String): AssistantToolResult {
    return AssistantToolResult(
      handled = true,
      success = false,
      supported = false,
      severity = AssistantSeverity.WARNING,
      title = title,
      message = message
    )
  }

  private fun actionMessage(result: ActionResult, title: String): String {
    return if (result.status == ServiceStatus.NOT_SUPPORTED || result.status == ServiceStatus.NO_DATA) {
      ActionSupportCatalog.messageFor(title, ActionSupportState(false, result.errorReason ?: result.message))
    } else {
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

class RouterTool(
  private val services: AppServices
) {
  suspend fun run(intent: AssistantIntent): AssistantToolResult {
    return when (intent.type) {
      AssistantIntentType.SET_GUEST_WIFI -> setGuestWifi(intent.toggleEnabled)
      AssistantIntentType.SET_DNS_SHIELD -> setDnsShield(intent.toggleEnabled)
      AssistantIntentType.REBOOT_ROUTER -> mapActionResult(services.routerControl.rebootRouter(), "Reboot router")
      AssistantIntentType.FLUSH_DNS -> mapActionResult(services.routerControl.flushDns(), "Flush DNS")
      else -> AssistantToolResult(false, false, false, AssistantSeverity.WARNING, "Unsupported", "Intent not handled by RouterTool")
    }
  }

  private suspend fun setGuestWifi(enabled: Boolean?): AssistantToolResult {
    return when (enabled) {
      true -> mapActionResult(services.routerControl.setGuestWifiEnabled(true), "Guest Wi-Fi")
      false -> mapActionResult(services.routerControl.setGuestWifiEnabled(false), "Guest Wi-Fi")
      null -> unsupported("Guest Wi-Fi", "Specify ON or OFF for guest Wi-Fi.")
    }
  }

  private suspend fun setDnsShield(enabled: Boolean?): AssistantToolResult {
    return when (enabled) {
      true -> mapActionResult(services.routerControl.setDnsShieldEnabled(true), "DNS Shield")
      false -> mapActionResult(services.routerControl.setDnsShieldEnabled(false), "DNS Shield")
      null -> unsupported("DNS Shield", "Specify ON or OFF for DNS Shield.")
    }
  }

  private fun mapActionResult(result: ActionResult, title: String): AssistantToolResult {
    val severity = when {
      result.ok -> AssistantSeverity.SUCCESS
      result.status == ServiceStatus.NOT_SUPPORTED || result.status == ServiceStatus.NO_DATA -> AssistantSeverity.WARNING
      else -> AssistantSeverity.ERROR
    }
    return AssistantToolResult(
      handled = true,
      success = result.ok,
      supported = result.status != ServiceStatus.NOT_SUPPORTED,
      severity = severity,
      title = title,
      message = if (result.status == ServiceStatus.NOT_SUPPORTED || result.status == ServiceStatus.NO_DATA) {
        ActionSupportCatalog.messageFor(title, ActionSupportState(false, result.errorReason ?: result.message))
      } else {
        buildString {
          append(result.message)
          result.errorReason?.takeIf { it.isNotBlank() }?.let {
            append(" ")
            append(it)
          }
        }
      }
    )
  }

  private fun unsupported(title: String, message: String): AssistantToolResult {
    return AssistantToolResult(
      handled = true,
      success = false,
      supported = false,
      severity = AssistantSeverity.WARNING,
      title = title,
      message = message
    )
  }
}

class NavigationTool {
  fun run(intent: AssistantIntent): AssistantToolResult {
    val destination = intent.destination
      ?: return AssistantToolResult(true, false, false, AssistantSeverity.WARNING, "Navigation", "No destination requested.")

    return AssistantToolResult(
      handled = true,
      success = true,
      supported = true,
      severity = AssistantSeverity.INFO,
      title = "Navigation",
      message = "Opening ${destination.name.lowercase()}.",
      destination = destination
    )
  }
}
