package com.nerf.netx.assistant.model

enum class AssistantIntentType {
  UNKNOWN,
  NETWORK_STATUS_SUMMARY,
  RUN_DIAGNOSTICS,
  SCAN_NETWORK,
  START_SPEEDTEST,
  STOP_SPEEDTEST,
  RESET_SPEEDTEST,
  REFRESH_TOPOLOGY,
  OPEN_DESTINATION,
  EXPLAIN_METRIC,
  EXPLAIN_FEATURE,
  SET_GUEST_WIFI,
  SET_DNS_SHIELD,
  REBOOT_ROUTER,
  FLUSH_DNS,
  PING_DEVICE,
  BLOCK_DEVICE,
  UNBLOCK_DEVICE,
  PAUSE_DEVICE,
  RESUME_DEVICE,
  CONFIRM_PENDING_ACTION,
  CANCEL_PENDING_ACTION
}

enum class AssistantDestination {
  SPEEDTEST,
  DEVICES,
  MAP,
  ANALYTICS,
  ASSISTANT
}

data class AssistantIntent(
  val type: AssistantIntentType,
  val rawMessage: String,
  val targetDeviceQuery: String? = null,
  val targetDeviceId: String? = null,
  val metricKey: String? = null,
  val featureKey: String? = null,
  val destination: AssistantDestination? = null,
  val toggleEnabled: Boolean? = null
)
