package com.nerf.netx.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSupportCatalogTest {

  @Test
  fun `device action lookup reflects unsupported controls`() {
    val support = ActionSupportCatalog.deviceActionState(
      actionId = AppActionId.DEVICE_BLOCK,
      support = DeviceActionSupport(canBlock = false)
    )

    assertFalse(support?.supported == true)
    assertEquals("Block device", support?.label)
  }

  @Test
  fun `router action lookup reflects supported capabilities`() {
    val snapshot = RouterStatusSnapshot(
      status = ServiceStatus.OK,
      message = "Router write actions are available.",
      accessMode = "READ_WRITE",
      capabilities = listOf("REBOOT"),
      actionSupport = emptyMap()
    )

    val support = ActionSupportCatalog.routerActionState(
      actionId = AppActionId.ROUTER_REBOOT,
      snapshot = snapshot
    )

    assertTrue(support?.supported == true)
    assertEquals("Router reboot", support?.label)
  }

  @Test
  fun `router action lookup reflects readable but not writable capability state`() {
    val snapshot = RouterStatusSnapshot(
      status = ServiceStatus.OK,
      message = "Router capability model loaded.",
      routerCapabilities = mapOf(
        AppActionId.ROUTER_QOS to RouterCapabilityState(
          actionId = AppActionId.ROUTER_QOS,
          label = "QoS",
          supported = true,
          detected = true,
          authenticated = true,
          readable = true,
          writable = false,
          status = ServiceStatus.NO_DATA,
          reason = "QoS state is readable, but write mapping is unverified."
        )
      )
    )

    val support = ActionSupportCatalog.routerActionState(
      actionId = AppActionId.ROUTER_QOS,
      snapshot = snapshot
    )

    assertFalse(support?.supported == true)
    assertEquals("QoS", support?.label)
    assertEquals("QoS state is readable, but write mapping is unverified.", support?.reason)
  }
}
