package com.nerf.netx.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeBridgeContractTest {

  @Test
  fun `required operational actions are registered`() {
    val required = setOf(
      ThemeBridgeContract.Actions.SCAN_START,
      ThemeBridgeContract.Actions.SPEEDTEST_START,
      ThemeBridgeContract.Actions.DEVICES_LIST,
      ThemeBridgeContract.Actions.DEVICE_PING,
      ThemeBridgeContract.Actions.MAP_REFRESH,
      ThemeBridgeContract.Actions.ANALYTICS_SNAPSHOT,
      ThemeBridgeContract.Actions.ROUTER_STATUS,
      ThemeBridgeContract.Actions.ROUTER_SET_GUEST,
      ThemeBridgeContract.Actions.ROUTER_SET_DNS_SHIELD,
      ThemeBridgeContract.Actions.ROUTER_REBOOT,
      ThemeBridgeContract.Actions.WIFI_ENVIRONMENT,
      ThemeBridgeContract.Actions.SECURITY_SUMMARY,
      ThemeBridgeContract.Actions.ANALYTICS_EXPORT,
      ThemeBridgeContract.Actions.DIAGNOSTICS_RUN_FULL,
      ThemeBridgeContract.Actions.NAVIGATION_OPEN,
      ThemeBridgeContract.Actions.CONSOLE_EXECUTE,
      ThemeBridgeContract.Actions.ASSISTANT_COMMAND
    )

    required.forEach { action ->
      assertTrue("Missing action contract: $action", ThemeBridgeContract.supportsAction(action))
    }
  }

  @Test
  fun `required live events are registered`() {
    val required = setOf(
      ThemeBridgeContract.Events.BRIDGE_READY,
      ThemeBridgeContract.Events.ACTION_RESULT,
      ThemeBridgeContract.Events.SCAN_STATE,
      ThemeBridgeContract.Events.SCAN_PROGRESS,
      ThemeBridgeContract.Events.SCAN_RESULTS,
      ThemeBridgeContract.Events.DEVICES_UPDATE,
      ThemeBridgeContract.Events.SPEEDTEST_STATE,
      ThemeBridgeContract.Events.SPEEDTEST_UI,
      ThemeBridgeContract.Events.SPEEDTEST_RESULT,
      ThemeBridgeContract.Events.MAP_STATE,
      ThemeBridgeContract.Events.TOPOLOGY_STATE,
      ThemeBridgeContract.Events.ANALYTICS_SNAPSHOT,
      ThemeBridgeContract.Events.ROUTER_STATUS,
      ThemeBridgeContract.Events.WIFI_ENVIRONMENT,
      ThemeBridgeContract.Events.SECURITY_SUMMARY,
      ThemeBridgeContract.Events.ASSISTANT_RESPONSE
    )

    required.forEach { event ->
      assertTrue("Missing live event contract: $event", ThemeBridgeContract.isLiveEvent(event))
    }
  }

  @Test
  fun `legacy aliases resolve to canonical actions`() {
    assertEquals(
      ThemeBridgeContract.Actions.ROUTER_REBOOT,
      ThemeBridgeContract.canonicalAction(ThemeBridgeContract.Actions.ROUTER_REBOOT_LEGACY)
    )
    assertEquals(
      ThemeBridgeContract.Actions.ANALYTICS_EXPORT,
      ThemeBridgeContract.canonicalAction(ThemeBridgeContract.Actions.ANALYTICS_EXPORT_JSON)
    )
    assertEquals(
      ThemeBridgeContract.Actions.DIAGNOSTICS_RUN_FULL,
      ThemeBridgeContract.canonicalAction(ThemeBridgeContract.Actions.DIAGNOSTICS_RUN_FULL_LEGACY)
    )
  }
}
