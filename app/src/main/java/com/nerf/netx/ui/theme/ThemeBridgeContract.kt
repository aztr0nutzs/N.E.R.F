package com.nerf.netx.ui.theme

object ThemeBridgeContract {
  object Actions {
    const val SCAN_START = "scan.start"
    const val SCAN_STOP = "scan.stop"
    const val SCAN_STATE = "scan.state"
    const val DEVICES_LIST = "devices.list"
    const val DEVICE_DETAILS = "device.details"
    const val DEVICE_PING = "device.ping"
    const val DEVICE_BLOCK = "device.block"
    const val DEVICE_SET_BLOCKED = "device.setBlocked"
    const val DEVICE_SET_PAUSED = "device.setPaused"
    const val DEVICE_RENAME = "device.rename"
    const val DEVICE_PRIORITIZE = "device.prioritize"
    const val MAP_REFRESH = "map.refresh"
    const val MAP_STATE = "map.state"
    const val MAP_SELECT_NODE = "map.selectNode"
    const val SPEEDTEST_START = "speedtest.start"
    const val SPEEDTEST_STOP = "speedtest.stop"
    const val SPEEDTEST_RESET = "speedtest.reset"
    const val SPEEDTEST_STATE = "speedtest.state"
    const val SPEEDTEST_HISTORY = "speedtest.history"
    const val ROUTER_INFO = "router.info"
    const val ROUTER_STATUS = "router.status"
    const val ROUTER_TOGGLE_GUEST = "router.toggleGuest"
    const val ROUTER_SET_GUEST = "router.setGuest"
    const val ROUTER_SET_DNS_SHIELD = "router.setDnsShield"
    const val ROUTER_REBOOT = "router.rebootRouter"
    const val ROUTER_TOGGLE_FIREWALL = "router.toggleFirewall"
    const val ROUTER_SET_FIREWALL = "router.setFirewall"
    const val ROUTER_TOGGLE_VPN = "router.toggleVpn"
    const val ROUTER_SET_VPN = "router.setVpn"
    const val ROUTER_RENEW_DHCP = "router.renewDhcp"
    const val ROUTER_FLUSH_DNS = "router.flushDns"
    const val ROUTER_SET_QOS = "router.setQos"
    const val WIFI_ENVIRONMENT = "wifi.environment"
    const val SECURITY_SUMMARY = "security.summary"
    const val ANALYTICS_SNAPSHOT = "analytics.snapshot"
    const val ANALYTICS_EVENTS = "analytics.events"
    const val ANALYTICS_EXPORT_JSON = "analytics.exportJson"
    const val DIAGNOSTICS_RUN_FULL = "diag.runFull"
    const val CONSOLE_EXECUTE = "console.execute"
    const val ASSISTANT_QUICK_ACTION = "assistant.quickAction"
    const val ASSISTANT_COMMAND = "assistant.command"
  }

  object Events {
    const val ACTION_RESULT = "action.result"
    const val SCAN_STATE = "scan.state"
    const val SCAN_PROGRESS = "scan.progress"
    const val SCAN_RESULTS = "scan.results"
    const val SCAN_PROGRESS_LEGACY = "scan_progress"
    const val SCAN_DONE_LEGACY = "scan_done"
    const val SCAN_ERROR_LEGACY = "scan_error"
    const val DEVICES_UPDATE = "devices.update"
    const val SPEEDTEST_UI = "speedtest.ui"
    const val SPEEDTEST_RESULT = "speedtest.result"
    const val TOPOLOGY_STATE = "topology.state"
    const val ANALYTICS_SNAPSHOT = "analytics.snapshot"
    const val ANALYTICS_EVENTS = "analytics.events"
    const val ROUTER_STATUS = "router.status"
    const val WIFI_ENVIRONMENT = "wifi.environment"
    const val SECURITY_SUMMARY = "security.summary"
    const val ASSISTANT_RESPONSE = "assistant.response"
  }

  val supportedActions: Set<String> = setOf(
    Actions.SCAN_START,
    Actions.SCAN_STOP,
    Actions.SCAN_STATE,
    Actions.DEVICES_LIST,
    Actions.DEVICE_DETAILS,
    Actions.DEVICE_PING,
    Actions.DEVICE_BLOCK,
    Actions.DEVICE_SET_BLOCKED,
    Actions.DEVICE_SET_PAUSED,
    Actions.DEVICE_RENAME,
    Actions.DEVICE_PRIORITIZE,
    Actions.MAP_REFRESH,
    Actions.MAP_STATE,
    Actions.MAP_SELECT_NODE,
    Actions.SPEEDTEST_START,
    Actions.SPEEDTEST_STOP,
    Actions.SPEEDTEST_RESET,
    Actions.SPEEDTEST_STATE,
    Actions.SPEEDTEST_HISTORY,
    Actions.ROUTER_INFO,
    Actions.ROUTER_STATUS,
    Actions.ROUTER_TOGGLE_GUEST,
    Actions.ROUTER_SET_GUEST,
    Actions.ROUTER_SET_DNS_SHIELD,
    Actions.ROUTER_REBOOT,
    Actions.ROUTER_TOGGLE_FIREWALL,
    Actions.ROUTER_SET_FIREWALL,
    Actions.ROUTER_TOGGLE_VPN,
    Actions.ROUTER_SET_VPN,
    Actions.ROUTER_RENEW_DHCP,
    Actions.ROUTER_FLUSH_DNS,
    Actions.ROUTER_SET_QOS,
    Actions.WIFI_ENVIRONMENT,
    Actions.SECURITY_SUMMARY,
    Actions.ANALYTICS_SNAPSHOT,
    Actions.ANALYTICS_EVENTS,
    Actions.ANALYTICS_EXPORT_JSON,
    Actions.DIAGNOSTICS_RUN_FULL,
    Actions.CONSOLE_EXECUTE,
    Actions.ASSISTANT_QUICK_ACTION,
    Actions.ASSISTANT_COMMAND
  )

  val liveEvents: Set<String> = setOf(
    Events.ACTION_RESULT,
    Events.SCAN_STATE,
    Events.SCAN_PROGRESS,
    Events.SCAN_RESULTS,
    Events.SCAN_PROGRESS_LEGACY,
    Events.SCAN_DONE_LEGACY,
    Events.SCAN_ERROR_LEGACY,
    Events.DEVICES_UPDATE,
    Events.SPEEDTEST_UI,
    Events.SPEEDTEST_RESULT,
    Events.TOPOLOGY_STATE,
    Events.ANALYTICS_SNAPSHOT,
    Events.ANALYTICS_EVENTS,
    Events.ROUTER_STATUS,
    Events.WIFI_ENVIRONMENT,
    Events.SECURITY_SUMMARY,
    Events.ASSISTANT_RESPONSE
  )

  fun supportsAction(action: String): Boolean = supportedActions.contains(action)
  fun isLiveEvent(name: String): Boolean = liveEvents.contains(name)
}
