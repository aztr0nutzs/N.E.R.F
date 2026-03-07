package com.nerf.netx.assistant.parser

import com.nerf.netx.assistant.model.AssistantDestination
import com.nerf.netx.assistant.model.AssistantIntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantIntentParserTest {

  private val parser = AssistantIntentParser()

  @Test
  fun `parses speedtest and diagnostics intents`() {
    assertEquals(AssistantIntentType.START_SPEEDTEST, parser.parse("start speedtest").type)
    assertEquals(AssistantIntentType.STOP_SPEEDTEST, parser.parse("stop speedtest").type)
    assertEquals(AssistantIntentType.RESET_SPEEDTEST, parser.parse("reset speedtest").type)
    assertEquals(AssistantIntentType.RUN_DIAGNOSTICS, parser.parse("run diagnostics").type)
  }

  @Test
  fun `parses navigation and device intents`() {
    val open = parser.parse("open analytics")
    assertEquals(AssistantIntentType.OPEN_DESTINATION, open.type)
    assertEquals(AssistantDestination.ANALYTICS, open.destination)

    val ping = parser.parse("ping device office-laptop")
    assertEquals(AssistantIntentType.PING_DEVICE, ping.type)
    assertEquals("office-laptop", ping.targetDeviceQuery)
  }

  @Test
  fun `parses router toggles with on off values`() {
    val guest = parser.parse("set guest wifi on")
    assertEquals(AssistantIntentType.SET_GUEST_WIFI, guest.type)
    assertEquals(true, guest.toggleEnabled)

    val dns = parser.parse("set dns shield off")
    assertEquals(AssistantIntentType.SET_DNS_SHIELD, dns.type)
    assertEquals(false, dns.toggleEnabled)
  }

  @Test
  fun `returns unknown for unsupported text`() {
    val intent = parser.parse("tell me a joke")
    assertEquals(AssistantIntentType.UNKNOWN, intent.type)
    assertTrue(intent.rawMessage.contains("joke"))
  }
}
