package com.nerf.netx.assistant.orchestrator

import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.assistant.recommendation.AssistantStarterPromptsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAmbiguityHandlingTest {

  private val resolver = AssistantEntityResolver()
  private val composer = AssistantResponseComposer(AssistantStarterPromptsProvider())

  @Test
  fun `builds clarification response with candidate actions`() {
    val devices = listOf(
      device(id = "1", name = "Apple TV", ip = "192.168.1.11", hostname = "apple-tv", vendor = "Apple"),
      device(id = "2", name = "Apple iPhone", ip = "192.168.1.12", hostname = "apple-phone", vendor = "Apple")
    )

    val resolution = resolver.resolveDevice("apple", devices)
    assertTrue(resolution is com.nerf.netx.assistant.model.AssistantEntityResolution.Ambiguous)

    val response = composer.ambiguousDeviceTarget(
      intent = AssistantIntent(type = AssistantIntentType.PING_DEVICE, rawMessage = "ping apple", targetDeviceQuery = "apple"),
      candidates = (resolution as com.nerf.netx.assistant.model.AssistantEntityResolution.Ambiguous).candidates
    )

    assertEquals(AssistantSeverity.WARNING, response.severity)
    assertEquals(2, response.cards.first().lines.size)
    assertEquals(2, response.suggestedActions.size)
    assertTrue(response.suggestedActions.all { it.command.startsWith("ping device ") })
  }

  private fun device(
    id: String,
    name: String,
    ip: String,
    hostname: String,
    vendor: String
  ) = com.nerf.netx.domain.Device(
    id = id,
    name = name,
    ip = ip,
    online = true,
    hostname = hostname,
    vendor = vendor
  )
}
