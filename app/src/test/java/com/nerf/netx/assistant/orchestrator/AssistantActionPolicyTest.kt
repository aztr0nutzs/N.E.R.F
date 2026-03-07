package com.nerf.netx.assistant.orchestrator

import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantActionPolicyTest {

  private val policy = AssistantActionPolicy()

  @Test
  fun `requires confirmation for risky intents`() {
    val risky = listOf(
      AssistantIntentType.REBOOT_ROUTER,
      AssistantIntentType.BLOCK_DEVICE,
      AssistantIntentType.UNBLOCK_DEVICE,
      AssistantIntentType.PAUSE_DEVICE,
      AssistantIntentType.RESUME_DEVICE,
      AssistantIntentType.SET_GUEST_WIFI,
      AssistantIntentType.SET_DNS_SHIELD
    )

    risky.forEach { type ->
      assertTrue(policy.requiresConfirmation(AssistantIntent(type = type, rawMessage = type.name)))
    }
  }

  @Test
  fun `does not require confirmation for safe intents`() {
    val safe = AssistantIntent(type = AssistantIntentType.START_SPEEDTEST, rawMessage = "start speedtest")
    assertFalse(policy.requiresConfirmation(safe))
  }
}
