package com.nerf.netx.assistant.orchestrator

import com.nerf.netx.assistant.model.AssistantEntityMatchType
import com.nerf.netx.assistant.model.AssistantEntityResolution
import com.nerf.netx.domain.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantEntityResolverTest {

  private val resolver = AssistantEntityResolver()
  private val devices = listOf(
    Device(
      id = "1",
      name = "John's iPhone",
      ip = "192.168.1.10",
      online = true,
      hostname = "johns-iphone",
      vendor = "Apple",
      deviceType = "PHONE"
    ),
    Device(
      id = "2",
      name = "Office Laptop",
      ip = "192.168.1.20",
      online = true,
      hostname = "office-laptop",
      vendor = "Dell",
      deviceType = "COMPUTER"
    ),
    Device(
      id = "3",
      name = "Living Room TV",
      ip = "192.168.1.30",
      online = true,
      hostname = "living-room-roku",
      vendor = "Apple",
      deviceType = "MEDIA"
    )
  )

  @Test
  fun `resolves device by hostname and ip`() {
    val hostname = resolver.resolveDevice("office-laptop", devices)
    val ip = resolver.resolveDevice("192.168.1.10", devices)

    assertEquals("2", (hostname as AssistantEntityResolution.Resolved).candidate.id)
    assertEquals(AssistantEntityMatchType.EXACT_HOSTNAME, hostname.candidate.matchType)
    assertEquals("1", (ip as AssistantEntityResolution.Resolved).candidate.id)
    assertEquals(AssistantEntityMatchType.EXACT_IP, ip.candidate.matchType)
  }

  @Test
  fun `resolves normalized nickname and fuzzy name`() {
    val nickname = resolver.resolveDevice("johns", devices)
    val fuzzy = resolver.resolveDevice("office lap", devices)

    assertEquals("1", (nickname as AssistantEntityResolution.Resolved).candidate.id)
    assertTrue(
      nickname.candidate.matchType == AssistantEntityMatchType.EXACT_NICKNAME ||
        nickname.candidate.matchType == AssistantEntityMatchType.FUZZY_NAME
    )
    assertEquals("2", (fuzzy as AssistantEntityResolution.Resolved).candidate.id)
  }

  @Test
  fun `returns ambiguous match for vendor and supports context reference`() {
    val vendor = resolver.resolveDevice("apple", devices)
    val context = resolver.resolveDevice("it", devices, lastDiscussedDeviceId = "2", allowContextFallback = false)

    assertTrue(vendor is AssistantEntityResolution.Ambiguous)
    assertEquals(2, (vendor as AssistantEntityResolution.Ambiguous).candidates.size)
    assertEquals("2", (context as AssistantEntityResolution.Resolved).candidate.id)
    assertEquals(AssistantEntityMatchType.CONTEXT_LAST_DISCUSSION, context.candidate.matchType)
  }
}
