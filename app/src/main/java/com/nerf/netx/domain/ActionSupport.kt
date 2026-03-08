package com.nerf.netx.domain

import com.nerf.netx.data.RouterCapability

enum class DeviceControlAction(
  val label: String,
  val unsupportedReason: String
) {
  BLOCK(
    label = "Block device",
    unsupportedReason = "Blocking devices is unsupported on the current backend. Verified router device-control APIs are unavailable."
  ),
  UNBLOCK(
    label = "Unblock device",
    unsupportedReason = "Unblocking devices is unsupported on the current backend. Verified router device-control APIs are unavailable."
  ),
  PAUSE(
    label = "Pause device",
    unsupportedReason = "Pausing devices is unsupported on the current backend. Verified router device-control APIs are unavailable."
  ),
  RESUME(
    label = "Resume device",
    unsupportedReason = "Resuming devices is unsupported on the current backend. Verified router device-control APIs are unavailable."
  ),
  RENAME(
    label = "Rename device",
    unsupportedReason = "Renaming devices is unsupported on the current backend. No authoritative rename path is available."
  ),
  PRIORITIZE(
    label = "Prioritize device",
    unsupportedReason = "Device prioritization is unsupported on the current backend. Verified QoS device-control APIs are unavailable."
  )
}

enum class RouterWriteAction(
  val label: String
) {
  GUEST_WIFI("Guest Wi-Fi"),
  DNS_SHIELD("DNS Shield"),
  FIREWALL("Firewall"),
  VPN("VPN"),
  QOS("QoS"),
  REBOOT("Router reboot"),
  FLUSH_DNS("Flush DNS"),
  RENEW_DHCP("Renew DHCP")
}

data class ActionAvailability(
  val supported: Boolean,
  val label: String,
  val reason: String
)

data class ActionSupportState(
  val supported: Boolean,
  val reason: String,
  val label: String? = null
)

object AppActionId {
  const val DEVICE_BLOCK = "device.block"
  const val DEVICE_UNBLOCK = "device.unblock"
  const val DEVICE_PAUSE = "device.pause"
  const val DEVICE_RESUME = "device.resume"
  const val DEVICE_RENAME = "device.rename"
  const val DEVICE_PRIORITIZE = "device.prioritize"
  const val ROUTER_GUEST_WIFI = "router.guestWifi"
  const val ROUTER_DNS_SHIELD = "router.dnsShield"
  const val ROUTER_FIREWALL = "router.firewall"
  const val ROUTER_VPN = "router.vpn"
  const val ROUTER_QOS = "router.qos"
  const val ROUTER_REBOOT = "router.reboot"
  const val ROUTER_FLUSH_DNS = "router.flushDns"
  const val ROUTER_RENEW_DHCP = "router.renewDhcp"
}

object ActionSupportCatalog {
  private val deviceActionIds: Map<String, DeviceControlAction> = linkedMapOf(
    AppActionId.DEVICE_BLOCK to DeviceControlAction.BLOCK,
    AppActionId.DEVICE_UNBLOCK to DeviceControlAction.UNBLOCK,
    AppActionId.DEVICE_PAUSE to DeviceControlAction.PAUSE,
    AppActionId.DEVICE_RESUME to DeviceControlAction.RESUME,
    AppActionId.DEVICE_RENAME to DeviceControlAction.RENAME,
    AppActionId.DEVICE_PRIORITIZE to DeviceControlAction.PRIORITIZE
  )

  private val routerActionIds: Map<String, RouterWriteAction> = linkedMapOf(
    AppActionId.ROUTER_GUEST_WIFI to RouterWriteAction.GUEST_WIFI,
    AppActionId.ROUTER_DNS_SHIELD to RouterWriteAction.DNS_SHIELD,
    AppActionId.ROUTER_FIREWALL to RouterWriteAction.FIREWALL,
    AppActionId.ROUTER_VPN to RouterWriteAction.VPN,
    AppActionId.ROUTER_QOS to RouterWriteAction.QOS,
    AppActionId.ROUTER_REBOOT to RouterWriteAction.REBOOT,
    AppActionId.ROUTER_FLUSH_DNS to RouterWriteAction.FLUSH_DNS,
    AppActionId.ROUTER_RENEW_DHCP to RouterWriteAction.RENEW_DHCP
  )

  fun deviceActionSupport(support: DeviceActionSupport): Map<String, ActionSupportState> {
    return linkedMapOf(
      AppActionId.DEVICE_BLOCK to support.availability(DeviceControlAction.BLOCK).toState(),
      AppActionId.DEVICE_UNBLOCK to support.availability(DeviceControlAction.UNBLOCK).toState(),
      AppActionId.DEVICE_PAUSE to support.availability(DeviceControlAction.PAUSE).toState(),
      AppActionId.DEVICE_RESUME to support.availability(DeviceControlAction.RESUME).toState(),
      AppActionId.DEVICE_RENAME to support.availability(DeviceControlAction.RENAME).toState(),
      AppActionId.DEVICE_PRIORITIZE to support.availability(DeviceControlAction.PRIORITIZE).toState()
    )
  }

  fun routerActionSupport(snapshot: RouterStatusSnapshot): Map<String, ActionSupportState> {
    return linkedMapOf(
      AppActionId.ROUTER_GUEST_WIFI to snapshot.availability(RouterWriteAction.GUEST_WIFI).toState(),
      AppActionId.ROUTER_DNS_SHIELD to snapshot.availability(RouterWriteAction.DNS_SHIELD).toState(),
      AppActionId.ROUTER_FIREWALL to snapshot.availability(RouterWriteAction.FIREWALL).toState(),
      AppActionId.ROUTER_VPN to snapshot.availability(RouterWriteAction.VPN).toState(),
      AppActionId.ROUTER_QOS to snapshot.availability(RouterWriteAction.QOS).toState(),
      AppActionId.ROUTER_REBOOT to snapshot.availability(RouterWriteAction.REBOOT).toState(),
      AppActionId.ROUTER_FLUSH_DNS to snapshot.availability(RouterWriteAction.FLUSH_DNS).toState(),
      AppActionId.ROUTER_RENEW_DHCP to snapshot.availability(RouterWriteAction.RENEW_DHCP).toState()
    )
  }

  fun deviceActionState(
    actionId: String,
    support: DeviceActionSupport = defaultDeviceActionSupport()
  ): ActionSupportState? {
    val action = deviceActionIds[actionId] ?: return null
    return support.availability(action).toState()
  }

  fun routerActionState(
    actionId: String,
    snapshot: RouterStatusSnapshot
  ): ActionSupportState? {
    val action = routerActionIds[actionId] ?: return null
    return snapshot.availability(action).toState()
  }

  fun messageFor(label: String, support: ActionSupportState): String {
    if (support.supported) return "$label is available."
    val reason = support.reason.trim()
    return if (reason.isBlank()) {
      "$label is unsupported on the current backend/router."
    } else {
      "$label is unsupported on the current backend/router. $reason"
    }
  }

  fun deviceActionState(
    actionId: String,
    device: Device,
    snapshot: DeviceControlStatusSnapshot?
  ): ActionSupportState? {
    val base = deviceActionState(actionId) ?: return null
    val capabilityKey = when (actionId) {
      AppActionId.DEVICE_UNBLOCK -> AppActionId.DEVICE_BLOCK
      AppActionId.DEVICE_RESUME -> AppActionId.DEVICE_PAUSE
      else -> actionId
    }
    val capability = snapshot?.deviceCapabilities?.get(capabilityKey) ?: return base
    if (!capability.writable) {
      return ActionSupportState(
        supported = false,
        reason = capability.reason,
        label = capability.label
      )
    }
    if (!deviceHasStableRouterId(device)) {
      return ActionSupportState(
        supported = false,
        reason = "Router-backed device control requires a stable MAC address for this device.",
        label = capability.label
      )
    }
    return ActionSupportState(
      supported = true,
      reason = "${capability.label} is available.",
      label = capability.label
    )
  }

  fun deviceActionSupport(
    device: Device,
    snapshot: DeviceControlStatusSnapshot?
  ): Map<String, ActionSupportState> {
    return linkedMapOf(
      AppActionId.DEVICE_BLOCK to requireNotNull(deviceActionState(AppActionId.DEVICE_BLOCK, device, snapshot)),
      AppActionId.DEVICE_UNBLOCK to requireNotNull(deviceActionState(AppActionId.DEVICE_UNBLOCK, device, snapshot)),
      AppActionId.DEVICE_PAUSE to requireNotNull(deviceActionState(AppActionId.DEVICE_PAUSE, device, snapshot)),
      AppActionId.DEVICE_RESUME to requireNotNull(deviceActionState(AppActionId.DEVICE_RESUME, device, snapshot)),
      AppActionId.DEVICE_RENAME to requireNotNull(deviceActionState(AppActionId.DEVICE_RENAME, device, snapshot)),
      AppActionId.DEVICE_PRIORITIZE to requireNotNull(deviceActionState(AppActionId.DEVICE_PRIORITIZE, device, snapshot))
    )
  }
}

fun DeviceActionSupport.availability(action: DeviceControlAction): ActionAvailability {
  val supported = when (action) {
    DeviceControlAction.BLOCK -> canBlock
    DeviceControlAction.UNBLOCK -> canUnblock
    DeviceControlAction.PAUSE -> canPause
    DeviceControlAction.RESUME -> canResume
    DeviceControlAction.RENAME -> canRename
    DeviceControlAction.PRIORITIZE -> canPrioritize
  }
  return ActionAvailability(
    supported = supported,
    label = action.label,
    reason = if (supported) "${action.label} is available." else action.unsupportedReason
  )
}

fun defaultDeviceActionSupport(): DeviceActionSupport = DeviceActionSupport()

fun deviceHasStableRouterId(device: Device): Boolean {
  val mac = device.macAddress ?: device.mac
  return Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$").matches(mac.trim())
}

fun defaultDeviceActionAvailability(action: DeviceControlAction): ActionAvailability {
  return defaultDeviceActionSupport().availability(action)
}

private fun ActionAvailability.toState(): ActionSupportState {
  return ActionSupportState(
    supported = supported,
    reason = reason,
    label = label
  )
}

fun DeviceControlAction.unsupportedResult(
  deviceLabel: String? = null
): ActionResult {
  val details = deviceLabel?.let { mapOf("device" to it) } ?: emptyMap()
  return ActionResult(
    ok = false,
    status = ServiceStatus.NOT_SUPPORTED,
    code = "NOT_SUPPORTED",
    message = "${label} is unsupported on the current backend.",
    details = details,
    errorReason = unsupportedReason
  )
}

fun RouterWriteAction.unavailableResult(reason: String): ActionResult {
  return ActionResult(
    ok = false,
    status = ServiceStatus.NO_DATA,
    code = "ROUTER_UNAVAILABLE",
    message = "$label is unavailable.",
    errorReason = reason
  )
}

fun RouterWriteAction.unsupportedResult(reason: String): ActionResult {
  return ActionResult(
    ok = false,
    status = ServiceStatus.NOT_SUPPORTED,
    code = "NOT_SUPPORTED",
    message = "$label is unsupported on the current router/backend.",
    errorReason = reason
  )
}

fun RouterStatusSnapshot.availability(action: RouterWriteAction): ActionAvailability {
  val mappedActionId = when (action) {
    RouterWriteAction.GUEST_WIFI -> AppActionId.ROUTER_GUEST_WIFI
    RouterWriteAction.DNS_SHIELD -> AppActionId.ROUTER_DNS_SHIELD
    RouterWriteAction.FIREWALL -> AppActionId.ROUTER_FIREWALL
    RouterWriteAction.VPN -> AppActionId.ROUTER_VPN
    RouterWriteAction.QOS -> AppActionId.ROUTER_QOS
    RouterWriteAction.REBOOT -> AppActionId.ROUTER_REBOOT
    RouterWriteAction.FLUSH_DNS -> AppActionId.ROUTER_FLUSH_DNS
    RouterWriteAction.RENEW_DHCP -> AppActionId.ROUTER_RENEW_DHCP
  }
  routerCapabilities[mappedActionId]?.let { capability ->
    return ActionAvailability(
      supported = capability.writable,
      label = capability.label,
      reason = if (capability.writable) {
        "${capability.label} is available."
      } else {
        capability.reason
      }
    )
  }

  val fallbackReason = message.ifBlank {
    "Router control availability could not be verified from the current backend."
  }
  if (status != ServiceStatus.OK) {
    return ActionAvailability(
      supported = false,
      label = action.label,
      reason = fallbackReason
    )
  }

  val capabilitiesSet = capabilities.toSet()
  fun featureReason(feature: RouterFeatureState?): String {
    return feature?.message?.takeIf { it.isNotBlank() } ?: fallbackReason
  }

  return when (action) {
    RouterWriteAction.GUEST_WIFI -> routerFeatureAvailability(
      action = action,
      capability = RouterCapability.GUEST_WIFI_TOGGLE,
      capabilities = capabilitiesSet,
      accessMode = accessMode,
      feature = guestWifi,
      fallbackReason = featureReason(guestWifi)
    )

    RouterWriteAction.DNS_SHIELD -> routerFeatureAvailability(
      action = action,
      capability = RouterCapability.DNS_SHIELD_TOGGLE,
      capabilities = capabilitiesSet,
      accessMode = accessMode,
      feature = dnsShield,
      fallbackReason = featureReason(dnsShield)
    )

    RouterWriteAction.FIREWALL -> routerFeatureAvailability(
      action = action,
      capability = RouterCapability.FIREWALL_TOGGLE,
      capabilities = capabilitiesSet,
      accessMode = accessMode,
      feature = firewall,
      fallbackReason = featureReason(firewall)
    )

    RouterWriteAction.VPN -> routerFeatureAvailability(
      action = action,
      capability = RouterCapability.VPN_TOGGLE,
      capabilities = capabilitiesSet,
      accessMode = accessMode,
      feature = vpn,
      fallbackReason = featureReason(vpn)
    )

    RouterWriteAction.QOS -> routerCapabilityAvailability(
      action = action,
      capability = RouterCapability.QOS_CONFIG,
      capabilities = capabilitiesSet,
      accessMode = accessMode,
      fallbackReason = if (qosMode == null) {
        "QoS control is unsupported on the current router/backend."
      } else {
        fallbackReason
      }
    )

    RouterWriteAction.REBOOT -> routerCapabilityAvailability(
      action = action,
      capability = RouterCapability.REBOOT,
      capabilities = capabilitiesSet,
      accessMode = accessMode,
      fallbackReason = fallbackReason
    )

    RouterWriteAction.FLUSH_DNS -> routerCapabilityAvailability(
      action = action,
      capability = RouterCapability.DNS_FLUSH,
      capabilities = capabilitiesSet,
      accessMode = accessMode,
      fallbackReason = fallbackReason
    )

    RouterWriteAction.RENEW_DHCP -> routerCapabilityAvailability(
      action = action,
      capability = RouterCapability.DHCP_LEASES_WRITE,
      capabilities = capabilitiesSet,
      accessMode = accessMode,
      fallbackReason = "Renew DHCP is unsupported on the current router/backend. Verified DHCP write endpoints are unavailable."
    )
  }
}

private fun routerFeatureAvailability(
  action: RouterWriteAction,
  capability: RouterCapability,
  capabilities: Set<String>,
  accessMode: String?,
  feature: RouterFeatureState,
  fallbackReason: String
): ActionAvailability {
  return routerCapabilityAvailability(
    action = action,
    capability = capability,
    capabilities = capabilities,
    accessMode = accessMode,
    fallbackReason = feature.message?.takeIf { it.isNotBlank() } ?: fallbackReason,
    readbackRequired = true,
    hasReadback = feature.enabled != null
  )
}

private fun routerCapabilityAvailability(
  action: RouterWriteAction,
  capability: RouterCapability,
  capabilities: Set<String>,
  accessMode: String?,
  fallbackReason: String,
  readbackRequired: Boolean = false,
  hasReadback: Boolean = true
): ActionAvailability {
  val isWritable = accessMode.equals("READ_WRITE", ignoreCase = true)
  val hasCapability = capabilities.contains(capability.name)
  val supported = isWritable && hasCapability && (!readbackRequired || hasReadback)
  return ActionAvailability(
    supported = supported,
    label = action.label,
    reason = if (supported) {
      "${action.label} is available."
    } else {
      fallbackReason
    }
  )
}
