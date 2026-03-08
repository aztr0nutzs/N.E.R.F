package com.nerf.netx.assistant.state

import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSessionMemoryTest {

  @Test
  fun `stores device and pending confirmation`() {
    val memory = AssistantSessionMemory(maxRecentActions = 3)
    val pending = AssistantIntent(type = AssistantIntentType.REBOOT_ROUTER, rawMessage = "reboot router")

    memory.updateLastDiscussedDevice("device-1")
    memory.setPendingConfirmation(pending)

    assertEquals("device-1", memory.lastDiscussedDeviceId)
    assertEquals(AssistantIntentType.REBOOT_ROUTER, memory.lastPendingConfirmationIntent?.type)
    assertTrue(memory.hasPendingConfirmation())

    memory.clearPendingConfirmation()
    assertNull(memory.lastPendingConfirmationIntent)
    assertFalse(memory.hasPendingConfirmation())
  }

  @Test
  fun `keeps bounded recent actions`() {
    val memory = AssistantSessionMemory(maxRecentActions = 2)
    memory.recordAction(AssistantIntentType.SCAN_NETWORK)
    memory.recordAction(AssistantIntentType.START_SPEEDTEST)
    memory.recordAction(AssistantIntentType.RUN_DIAGNOSTICS)

    assertEquals(listOf(AssistantIntentType.START_SPEEDTEST, AssistantIntentType.RUN_DIAGNOSTICS), memory.recentActions)
  }
}
