package com.nerf.netx.assistant.orchestrator

import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType

class AssistantActionPolicy {

  fun requiresConfirmation(intent: AssistantIntent): Boolean {
    return when (intent.type) {
      AssistantIntentType.REBOOT_ROUTER,
      AssistantIntentType.BLOCK_DEVICE,
      AssistantIntentType.UNBLOCK_DEVICE,
      AssistantIntentType.PAUSE_DEVICE,
      AssistantIntentType.RESUME_DEVICE,
      AssistantIntentType.SET_GUEST_WIFI,
      AssistantIntentType.SET_DNS_SHIELD -> true
      else -> false
    }
  }
}
