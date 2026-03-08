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

  private class FakeRouterHttpSession(
    val nvram: MutableMap<String, String> = mutableMapOf(),
    private val applyAppHandler: (Map<String, String>, MutableMap<String, String>) -> RouterActionResult = { _, _ ->
      RouterActionResult(
        status = RouterActionStatus.OK,
        message = "Applied"
      )
    }
  ) : RouterHttpSession {
    var lastApplyApp: Map<String, String>? = null
      private set

    override suspend fun nvramGet(names: List<String>): Map<String, String> {
      return names.associateWith { name -> nvram[name].orEmpty() }
    }

    override suspend fun applyApp(postData: Map<String, String>): RouterActionResult {
      lastApplyApp = postData.toMap()
      return applyAppHandler(postData, nvram)
    }

    override suspend fun submitForm(path: String, postData: Map<String, String>): RouterActionResult {
      return RouterActionResult(
        status = RouterActionStatus.OK,
        message = "Submitted"
      )
    }
  }
}
