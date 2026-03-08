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

  @Test
  fun `device action lookup requires stable mac even when backend supports the action`() {
    val support = ActionSupportCatalog.deviceActionState(
      actionId = AppActionId.DEVICE_BLOCK,
      device = Device(
        id = "device-1",
        name = "Tablet",
        ip = "192.168.1.50",
        online = true,
        mac = ""
      ),
      snapshot = DeviceControlStatusSnapshot(
        status = ServiceStatus.OK,
        message = "ASUS device control available.",
        deviceCapabilities = mapOf(
          AppActionId.DEVICE_BLOCK to DeviceCapabilityState(
            actionId = AppActionId.DEVICE_BLOCK,
            label = "Block device",
            supported = true,
            detected = true,
            authenticated = true,
            readable = true,
            writable = true,
            status = ServiceStatus.OK,
            reason = "Per-device internet blocking is available."
          )
        )
      )
    )

    assertFalse(support?.supported == true)
    assertEquals("Block device", support?.label)
    assertTrue(support?.reason?.contains("stable MAC address") == true)
  }
}
