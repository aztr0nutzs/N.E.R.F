package com.nerf.netx.ui.theme

enum class ThemeAuditStatus {
  WIRED,
  VISUAL_ONLY,
  EXPLICIT_UNSUPPORTED
}

data class ThemeAuditEntry(
  val themeId: ThemeId,
  val screen: String,
  val control: String,
  val command: String?,
  val status: ThemeAuditStatus,
  val notes: String
)

object ThemeOperationalAudit {
  val entries: List<ThemeAuditEntry> = mutableListOf<ThemeAuditEntry>().apply {
    addSharedDashboardAudit(ThemeId.NERF_HUD_ALT_HTML)
    addSharedDashboardAudit(ThemeId.NERF_MAIN_HUD_HTML)
    add(
      ThemeAuditEntry(
        themeId = ThemeId.NERF_DASH_NEW_HTML,
        screen = "overview",
        control = "scan action",
        command = ThemeBridgeContract.Actions.SCAN_START,
        status = ThemeAuditStatus.WIRED,
        notes = "Overview scan starts the real network scan and waits for native scan lifecycle events."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = ThemeId.NERF_DASH_NEW_HTML,
        screen = "overview",
        control = "device pause/block toggle",
        command = ThemeBridgeContract.Actions.DEVICE_SET_PAUSED,
        status = ThemeAuditStatus.EXPLICIT_UNSUPPORTED,
        notes = "UI issues native pause/block requests; unsupported backend responses are surfaced back into the log and assistant state."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = ThemeId.NERF_DASH_NEW_HTML,
        screen = "overview",
        control = "guest wifi / dns shield",
        command = ThemeBridgeContract.Actions.ROUTER_SET_GUEST,
        status = ThemeAuditStatus.EXPLICIT_UNSUPPORTED,
        notes = "Real setter path is used. When router write APIs are unavailable, the UI keeps state unchanged and shows the backend reason."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = ThemeId.NERF_DASH_NEW_HTML,
        screen = "overview",
        control = "router reboot",
        command = ThemeBridgeContract.Actions.ROUTER_REBOOT,
        status = ThemeAuditStatus.WIRED,
        notes = "Reboot uses the native router command path and confirmation modal."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = ThemeId.NERF_DASH_NEW_HTML,
        screen = "speed",
        control = "start / stop / reset",
        command = ThemeBridgeContract.Actions.SPEEDTEST_START,
        status = ThemeAuditStatus.WIRED,
        notes = "Speedtest controls are fully native-driven; the HTML gauge becomes a renderer over live state."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = ThemeId.NERF_DASH_NEW_HTML,
        screen = "devices",
        control = "scan / ping / block",
        command = ThemeBridgeContract.Actions.DEVICE_PING,
        status = ThemeAuditStatus.EXPLICIT_UNSUPPORTED,
        notes = "Device ping is real. Block requests are real where supported; unsupported responses are surfaced honestly."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = ThemeId.NERF_DASH_NEW_HTML,
        screen = "map",
        control = "radar / threat / reset",
        command = ThemeBridgeContract.Actions.MAP_STATE,
        status = ThemeAuditStatus.VISUAL_ONLY,
        notes = "Map overlays and pan/zoom remain local visual controls; device inventory backing the map is native."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = ThemeId.NERF_DASH_NEW_HTML,
        screen = "intel-console",
        control = "quick actions / command execute",
        command = ThemeBridgeContract.Actions.CONSOLE_EXECUTE,
        status = ThemeAuditStatus.WIRED,
        notes = "Console now routes through native command handling for scan, diagnostics, export, flush DNS, speedtest, and ping."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = ThemeId.NERF_DASH_NEW_HTML,
        screen = "assistant",
        control = "quick actions / send message",
        command = ThemeBridgeContract.Actions.ASSISTANT_COMMAND,
        status = ThemeAuditStatus.WIRED,
        notes = "Assistant quick actions and free text now use the real assistant orchestrator; fake canned replies are no longer the source of truth."
      )
    )
  }

  fun entriesFor(themeId: ThemeId): List<ThemeAuditEntry> = entries.filter { it.themeId == themeId }

  private fun MutableList<ThemeAuditEntry>.addSharedDashboardAudit(themeId: ThemeId) {
    add(
      ThemeAuditEntry(
        themeId = themeId,
        screen = "overview",
        control = "scan / refresh / router refresh",
        command = ThemeBridgeContract.Actions.SCAN_START,
        status = ThemeAuditStatus.WIRED,
        notes = "Overview actions load devices, analytics, scan state, and router status from the native bridge."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = themeId,
        screen = "speed",
        control = "start / stop / reset",
        command = ThemeBridgeContract.Actions.SPEEDTEST_START,
        status = ThemeAuditStatus.WIRED,
        notes = "Speedtest controls are wired to native speedtest lifecycle commands."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = themeId,
        screen = "devices",
        control = "refresh / ping / modal details",
        command = ThemeBridgeContract.Actions.DEVICE_PING,
        status = ThemeAuditStatus.WIRED,
        notes = "Device refresh and ping are real; device list and status come from live native device state."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = themeId,
        screen = "devices",
        control = "block / prioritize",
        command = ThemeBridgeContract.Actions.DEVICE_SET_BLOCKED,
        status = ThemeAuditStatus.EXPLICIT_UNSUPPORTED,
        notes = "Unsupported device controls remain disabled with an explicit reason."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = themeId,
        screen = "map",
        control = "refresh topology / select inferred node",
        command = ThemeBridgeContract.Actions.MAP_REFRESH,
        status = ThemeAuditStatus.WIRED,
        notes = "Topology refresh uses native map services; node layout remains an inferred visualization over live device data."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = themeId,
        screen = "analytics",
        control = "refresh analytics",
        command = ThemeBridgeContract.Actions.ANALYTICS_SNAPSHOT,
        status = ThemeAuditStatus.WIRED,
        notes = "Analytics panels render the live native analytics snapshot."
      )
    )
    add(
      ThemeAuditEntry(
        themeId = themeId,
        screen = "console",
        control = "renew dhcp / flush dns / firewall / vpn / qos / reboot",
        command = ThemeBridgeContract.Actions.CONSOLE_EXECUTE,
        status = ThemeAuditStatus.EXPLICIT_UNSUPPORTED,
        notes = "Each router action is now individually gated by verified router capabilities and readback availability."
      )
    )
  }
}
