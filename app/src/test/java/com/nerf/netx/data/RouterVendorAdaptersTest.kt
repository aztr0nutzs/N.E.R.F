package com.nerf.netx.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterVendorAdaptersTest {

  @Test
  fun `generic read only adapter exposes explicit unsupported router actions`() = runTest {
    val runtime = GenericReadOnlyRouterAdapter().probe(FakeRouterHttpSession())

    assertEquals(routerWriteCapabilities.toSet(), runtime.actionCapabilities.keys)
    assertEquals(routerFeatureCapabilities.toSet(), runtime.featureReadback.keys)
    assertTrue(runtime.actionCapabilities.values.all { !it.readable && !it.writable })
    assertTrue(runtime.featureReadback.values.all { !it.readable && !it.writable && it.enabled == null })
    assertTrue(runtime.actionCapabilities.values.all { it.reason.contains("unsupported", ignoreCase = true) })
  }

  @Test
  fun `asus adapter reports partial write support by action`() = runTest {
    val session = FakeRouterHttpSession(
      nvram = mutableMapOf(
        "wl0.1_bss_enabled" to "1",
        "dnsfilter_enable_x" to "1",
        "fw_lw_enable_x" to "0",
        "qos_enable" to "1",
        "qos_type" to "0",
        "vpn_serverx_start" to "1"
      )
    )

    val runtime = AsusRouterAdapter().probe(session)

    assertTrue(runtime.actionCapabilities.getValue(RouterCapability.GUEST_WIFI_TOGGLE).writable)
    assertTrue(runtime.actionCapabilities.getValue(RouterCapability.DNS_SHIELD_TOGGLE).writable)
    assertTrue(runtime.actionCapabilities.getValue(RouterCapability.FIREWALL_TOGGLE).writable)
    assertTrue(runtime.actionCapabilities.getValue(RouterCapability.REBOOT).writable)
    assertTrue(runtime.actionCapabilities.getValue(RouterCapability.DNS_FLUSH).writable)

    assertTrue(runtime.actionCapabilities.getValue(RouterCapability.QOS_CONFIG).readable)
    assertFalse(runtime.actionCapabilities.getValue(RouterCapability.QOS_CONFIG).writable)
    assertTrue(runtime.actionCapabilities.getValue(RouterCapability.VPN_TOGGLE).readable)
    assertFalse(runtime.actionCapabilities.getValue(RouterCapability.VPN_TOGGLE).writable)
    assertFalse(runtime.actionCapabilities.getValue(RouterCapability.DHCP_LEASES_WRITE).writable)

    assertNotNull(runtime.featureReadback[RouterCapability.GUEST_WIFI_TOGGLE]?.enabled)
    assertNotNull(runtime.featureReadback[RouterCapability.DNS_SHIELD_TOGGLE]?.enabled)
    assertNotNull(runtime.featureReadback[RouterCapability.FIREWALL_TOGGLE]?.enabled)
    assertNotNull(runtime.featureReadback[RouterCapability.VPN_TOGGLE]?.enabled)
  }

  @Test
  fun `asus guest wifi write uses verified applyapp path and readback`() = runTest {
    val session = FakeRouterHttpSession(
      nvram = mutableMapOf("wl0.1_bss_enabled" to "0"),
      applyAppHandler = { data, store ->
        data["wl0.1_bss_enabled"]?.let { store["wl0.1_bss_enabled"] = it }
        RouterActionResult(
          status = RouterActionStatus.OK,
          message = "Applied"
        )
      }
    )

    val result = AsusRouterAdapter().setGuestWifiEnabled(session, enabled = true)

    assertEquals(RouterActionStatus.OK, result.status)
    assertEquals("1", session.lastApplyApp?.get("wl0.1_bss_enabled"))
    assertEquals(
      "restart_wireless;restart_qos;restart_firewall;",
      session.lastApplyApp?.get("rc_service")
    )
    assertEquals("1", session.nvram["wl0.1_bss_enabled"])
  }

  @Test
  fun `default device probe exposes explicit unsupported device actions`() = runTest {
    val runtime = object : RouterVendorAdapter {
      override val id: String = "fake-adapter"
      override fun matches(info: RouterInfo): Boolean = true
      override suspend fun probe(session: RouterHttpSession): RouterRuntimeCapabilities = RouterRuntimeCapabilities()
    }.probeDevice(
      session = FakeRouterHttpSession(),
      device = RouterManagedDevice(
        deviceId = "device-1",
        macAddress = "AA:BB:CC:DD:EE:FF",
        ipAddress = "192.168.1.20",
        hostName = "tablet",
        displayName = "Tablet"
      ),
      routerRuntime = RouterRuntimeCapabilities(
        adapterId = "fake-adapter",
        detected = true,
        authenticated = true,
        readable = true
      )
    )

    assertEquals(routerDeviceCapabilities.toSet(), runtime.actionCapabilities.keys)
    assertTrue(runtime.actionCapabilities.values.all { !it.readable && !it.writable })
  }

  @Test
  fun `asus device probe reports real block and rename support only`() = runTest {
    val session = FakeRouterHttpSession(
      nvram = mutableMapOf(
        "custom_clientlist" to "Tablet>AA:BB:CC:DD:EE:FF>0>0>>",
        "MULTIFILTER_ENABLE" to "2",
        "MULTIFILTER_MAC" to "AA:BB:CC:DD:EE:FF",
        "MULTIFILTER_DEVICENAME" to "Tablet"
      )
    )

    val runtime = AsusRouterAdapter().probeDevice(
      session = session,
      device = RouterManagedDevice(
        deviceId = "device-1",
        macAddress = "AA:BB:CC:DD:EE:FF",
        ipAddress = "192.168.1.20",
        hostName = "tablet",
        displayName = "Tablet"
      ),
      routerRuntime = RouterRuntimeCapabilities(
        adapterId = "asuswrt-app",
        detected = true,
        authenticated = true,
        readable = true,
        writable = true
      )
    )

    assertTrue(runtime.actionCapabilities.getValue(RouterDeviceCapability.BLOCK).writable)
    assertTrue(runtime.actionCapabilities.getValue(RouterDeviceCapability.RENAME).writable)
    assertFalse(runtime.actionCapabilities.getValue(RouterDeviceCapability.PAUSE).writable)
    assertFalse(runtime.actionCapabilities.getValue(RouterDeviceCapability.PRIORITIZE).writable)
    assertEquals(true, runtime.readback.blocked)
    assertEquals("Tablet", runtime.readback.nickname)
  }

  @Test
  fun `asus device rename uses verified start_apply2 path and readback`() = runTest {
    val session = FakeRouterHttpSession(
      nvram = mutableMapOf(
        "custom_clientlist" to "Tablet>AA:BB:CC:DD:EE:FF>0>0>>"
      ),
      submitFormHandler = { _, data, store ->
        data["custom_clientlist"]?.let { store["custom_clientlist"] = it }
        RouterActionResult(
          status = RouterActionStatus.OK,
          message = "Submitted"
        )
      }
    )

    val result = AsusRouterAdapter().renameDevice(
      session = session,
      device = RouterManagedDevice(
        deviceId = "device-1",
        macAddress = "AA:BB:CC:DD:EE:FF",
        ipAddress = "192.168.1.20",
        hostName = "tablet",
        displayName = "Tablet"
      ),
      name = "Kids iPad"
    )

    assertEquals(RouterActionStatus.OK, result.status)
    assertEquals("/start_apply2.htm", session.lastSubmitPath)
    assertTrue(session.nvram["custom_clientlist"].orEmpty().contains("Kids iPad"))
  }

  private class FakeRouterHttpSession(
    val nvram: MutableMap<String, String> = mutableMapOf(),
    private val applyAppHandler: (Map<String, String>, MutableMap<String, String>) -> RouterActionResult = { _, _ ->
      RouterActionResult(
        status = RouterActionStatus.OK,
        message = "Applied"
      )
    },
    private val submitFormHandler: (String, Map<String, String>, MutableMap<String, String>) -> RouterActionResult = { _, _, _ ->
      RouterActionResult(
        status = RouterActionStatus.OK,
        message = "Submitted"
      )
    }
  ) : RouterHttpSession {
    var lastApplyApp: Map<String, String>? = null
      private set
    var lastSubmitPath: String? = null
      private set

    override suspend fun nvramGet(names: List<String>): Map<String, String> {
      return names.associateWith { name -> nvram[name].orEmpty() }
    }

    override suspend fun applyApp(postData: Map<String, String>): RouterActionResult {
      lastApplyApp = postData.toMap()
      return applyAppHandler(postData, nvram)
    }

    override suspend fun submitForm(path: String, postData: Map<String, String>): RouterActionResult {
      lastSubmitPath = path
      return submitFormHandler(path, postData, nvram)
    }
  }
}
