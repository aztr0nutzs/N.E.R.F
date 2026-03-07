package com.nerf.netx.assistant.tools

import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.assistant.model.AssistantToolResult

class AssistantToolRegistry(
  private val scanTool: ScanTool,
  private val speedtestTool: SpeedtestTool,
  private val deviceTool: DeviceTool,
  private val routerTool: RouterTool,
  private val navigationTool: NavigationTool
) {
  suspend fun execute(intent: AssistantIntent): AssistantToolResult {
    return when (intent.type) {
      AssistantIntentType.SCAN_NETWORK,
      AssistantIntentType.REFRESH_TOPOLOGY -> scanTool.run(intent)
      AssistantIntentType.START_SPEEDTEST,
      AssistantIntentType.STOP_SPEEDTEST,
      AssistantIntentType.RESET_SPEEDTEST -> speedtestTool.run(intent)
      AssistantIntentType.PING_DEVICE,
      AssistantIntentType.BLOCK_DEVICE,
      AssistantIntentType.UNBLOCK_DEVICE,
      AssistantIntentType.PAUSE_DEVICE,
      AssistantIntentType.RESUME_DEVICE -> deviceTool.run(intent)
      AssistantIntentType.SET_GUEST_WIFI,
      AssistantIntentType.SET_DNS_SHIELD,
      AssistantIntentType.REBOOT_ROUTER,
      AssistantIntentType.FLUSH_DNS -> routerTool.run(intent)
      AssistantIntentType.OPEN_DESTINATION -> navigationTool.run(intent)
      else -> AssistantToolResult(
        handled = false,
        success = false,
        supported = false,
        severity = AssistantSeverity.WARNING,
        title = "Unsupported",
        message = "No tool is registered for ${intent.type.name.lowercase()}."
      )
    }
  }
}
