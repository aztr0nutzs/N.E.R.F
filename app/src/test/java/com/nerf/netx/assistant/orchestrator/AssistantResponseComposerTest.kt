package com.nerf.netx.assistant.orchestrator

import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType
import com.nerf.netx.assistant.recommendation.AssistantStarterPromptsProvider
import com.nerf.netx.domain.ActionSupportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResponseComposerTest {

  private val composer = AssistantResponseComposer(AssistantStarterPromptsProvider())

  @Test
  fun `confirmation response includes ui metadata for risky action`() {
    val response = composer.confirmationRequired(
      intent = AssistantIntent(
        type = AssistantIntentType.BLOCK_DEVICE,
        rawMessage = "block laptop"
      ),
      resolvedTarget = "Dad Laptop",
      detailLines = listOf("Target: Dad Laptop", "IP: 192.168.1.44")
    )

    assertTrue(response.requiresConfirmation)
    assertEquals("Confirm action", response.title)
    assertNotNull(response.confirmationUi)
    assertEquals("Block Dad Laptop", response.confirmationUi?.title)
    assertEquals("Block device", response.confirmationUi?.confirmLabel)
    assertTrue(response.confirmationPrompt.orEmpty().contains("yes"))
    assertTrue(response.suggestedActions.all { it.command != "yes" && it.command != "cancel" })
  }

  @Test
  fun `unsupported action explains backend limitation and alternatives`() {
    val response = composer.unsupportedAction(
      label = "Pause device",
      support = ActionSupportState(
        supported = false,
        reason = "Pause/resume is unsupported on the current backend/router."
      )
    )

    assertTrue(response.message.contains("backend/router capability limit"))
    assertTrue(response.cards.isNotEmpty())
    assertTrue(response.cards.first().lines.any { it.contains("could not be sent") })
    assertFalse(response.suggestedActions.isEmpty())
  }
}
