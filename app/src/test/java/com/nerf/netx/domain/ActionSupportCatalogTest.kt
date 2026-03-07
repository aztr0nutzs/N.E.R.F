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
}
