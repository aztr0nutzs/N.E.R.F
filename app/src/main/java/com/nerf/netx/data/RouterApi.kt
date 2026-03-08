package com.nerf.netx.data

enum class RouterAuthType {
  NONE,
  BASIC,
  DIGEST
}

enum class RouterCapability {
  READ_INFO,
  DHCP_LEASES_READ,
  DHCP_LEASES_WRITE,
  DNS_FLUSH,
  DNS_SHIELD_TOGGLE,
  FIREWALL_TOGGLE,
  VPN_TOGGLE,
  QOS_CONFIG,
  GUEST_WIFI_TOGGLE,
  REBOOT
}

enum class RouterActionStatus {
  OK,
  ERROR,
  NOT_SUPPORTED
}

enum class RouterAccessMode {
  READ_ONLY,
  READ_WRITE
}

data class RouterConnectionInfo(
  val routerIp: String,
  val adminUrl: String? = null,
  val username: String? = null,
  val password: String? = null,
  val preferredAuthType: RouterAuthType? = null,
  val timeoutMs: Int = 2500
)

data class RouterInfo(
  val modelName: String?,
  val vendorName: String?,
  val firmwareVersion: String?,
  val routerIp: String,
  val adminUrl: String?,
  val detectedAuthType: RouterAuthType
)

data class RouterActionResult(
  val status: RouterActionStatus,
  val message: String,
  val errorCode: String? = null
)

data class RouterActionCapability(
  val capability: RouterCapability,
  val supported: Boolean = false,
  val readable: Boolean = false,
  val writable: Boolean = false,
  val reason: String,
  val source: String? = null
)

data class RouterFeatureReadback(
  val capability: RouterCapability,
  val supported: Boolean = false,
  val readable: Boolean = false,
  val writable: Boolean = false,
  val enabled: Boolean? = null,
  val message: String? = null,
  val source: String? = null
)

data class RouterRuntimeCapabilities(
  val adapterId: String? = null,
  val detected: Boolean = false,
  val authenticated: Boolean = false,
  val readable: Boolean = false,
  val writable: Boolean = false,
  val capabilities: Set<RouterCapability> = setOf(RouterCapability.READ_INFO),
  val actionCapabilities: Map<RouterCapability, RouterActionCapability> = emptyMap(),
  val featureReadback: Map<RouterCapability, RouterFeatureReadback> = emptyMap(),
  val message: String = "Router capability probe not run."
)

enum class RouterDeviceCapability {
  BLOCK,
  PAUSE,
  RENAME,
  PRIORITIZE
}

data class RouterManagedDevice(
  val deviceId: String,
  val macAddress: String?,
  val ipAddress: String?,
  val hostName: String?,
  val displayName: String?
)

data class RouterDeviceActionCapability(
  val capability: RouterDeviceCapability,
  val supported: Boolean = false,
  val readable: Boolean = false,
  val writable: Boolean = false,
  val reason: String,
  val source: String? = null
)

data class RouterDeviceReadback(
  val blocked: Boolean? = null,
  val paused: Boolean? = null,
  val nickname: String? = null,
  val prioritized: Boolean? = null
)

data class RouterDeviceRuntimeCapabilities(
  val adapterId: String? = null,
  val detected: Boolean = false,
  val authenticated: Boolean = false,
  val readable: Boolean = false,
  val writable: Boolean = false,
  val actionCapabilities: Map<RouterDeviceCapability, RouterDeviceActionCapability> = emptyMap(),
  val readback: RouterDeviceReadback = RouterDeviceReadback(),
  val message: String = "Device capability probe not run."
)

internal val routerDeviceCapabilities: List<RouterDeviceCapability> = listOf(
  RouterDeviceCapability.BLOCK,
  RouterDeviceCapability.PAUSE,
  RouterDeviceCapability.RENAME,
  RouterDeviceCapability.PRIORITIZE
)

internal fun routerDeviceCapabilityLabel(capability: RouterDeviceCapability): String {
  return when (capability) {
    RouterDeviceCapability.BLOCK -> "Block device"
    RouterDeviceCapability.PAUSE -> "Pause device"
    RouterDeviceCapability.RENAME -> "Rename device"
    RouterDeviceCapability.PRIORITIZE -> "Prioritize device"
  }
}

internal fun unsupportedRouterDeviceActionCapabilities(
  source: String? = null,
  reasonFor: (RouterDeviceCapability) -> String
): Map<RouterDeviceCapability, RouterDeviceActionCapability> {
  return routerDeviceCapabilities.associateWith { capability ->
    RouterDeviceActionCapability(
      capability = capability,
      supported = false,
      readable = false,
      writable = false,
      reason = reasonFor(capability),
      source = source
    )
  }
}

data class DhcpLease(
  val ip: String,
  val mac: String,
  val hostname: String? = null,
  val expiresAtEpochMs: Long? = null
)

data class QosConfig(
  val mode: String,
  val targetMacs: List<String> = emptyList(),
  val targetIps: List<String> = emptyList(),
  val maxDownKbps: Int? = null,
  val maxUpKbps: Int? = null
)

interface RouterApi {
  suspend fun detect(info: RouterConnectionInfo): RouterInfo
  suspend fun getCapabilities(): Set<RouterCapability>
  suspend fun getRuntimeCapabilities(): RouterRuntimeCapabilities
  suspend fun getDeviceRuntimeCapabilities(device: RouterManagedDevice): RouterDeviceRuntimeCapabilities
  suspend fun testConnection(): RouterActionResult
  suspend fun validateCredentials(): RouterActionResult
  suspend fun getDhcpLeases(): Result<List<DhcpLease>>
  suspend fun setDhcpLeaseName(macOrIp: String, name: String): RouterActionResult
  suspend fun setDeviceBlocked(device: RouterManagedDevice, blocked: Boolean): RouterActionResult
  suspend fun setDevicePaused(device: RouterManagedDevice, paused: Boolean): RouterActionResult
  suspend fun renameDevice(device: RouterManagedDevice, name: String): RouterActionResult
  suspend fun prioritizeDevice(device: RouterManagedDevice): RouterActionResult
  suspend fun renewDhcp(): RouterActionResult
  suspend fun flushDns(): RouterActionResult
  suspend fun setDnsShieldEnabled(enabled: Boolean): RouterActionResult
  suspend fun setFirewallEnabled(enabled: Boolean): RouterActionResult
  suspend fun setVpnEnabled(enabled: Boolean): RouterActionResult
  suspend fun setQosConfig(config: QosConfig): RouterActionResult
  suspend fun setGuestWifiEnabled(enabled: Boolean): RouterActionResult
  suspend fun reboot(): RouterActionResult
}

internal val routerWriteCapabilities: List<RouterCapability> = listOf(
  RouterCapability.GUEST_WIFI_TOGGLE,
  RouterCapability.DNS_SHIELD_TOGGLE,
  RouterCapability.FIREWALL_TOGGLE,
  RouterCapability.VPN_TOGGLE,
  RouterCapability.QOS_CONFIG,
  RouterCapability.REBOOT,
  RouterCapability.DNS_FLUSH,
  RouterCapability.DHCP_LEASES_WRITE
)

internal val routerFeatureCapabilities: List<RouterCapability> = listOf(
  RouterCapability.GUEST_WIFI_TOGGLE,
  RouterCapability.DNS_SHIELD_TOGGLE,
  RouterCapability.FIREWALL_TOGGLE,
  RouterCapability.VPN_TOGGLE
)

internal fun routerCapabilityLabel(capability: RouterCapability): String {
  return when (capability) {
    RouterCapability.GUEST_WIFI_TOGGLE -> "Guest Wi-Fi"
    RouterCapability.DNS_SHIELD_TOGGLE -> "DNS Shield"
    RouterCapability.FIREWALL_TOGGLE -> "Firewall"
    RouterCapability.VPN_TOGGLE -> "VPN"
    RouterCapability.QOS_CONFIG -> "QoS"
    RouterCapability.REBOOT -> "Router reboot"
    RouterCapability.DNS_FLUSH -> "Flush DNS"
    RouterCapability.DHCP_LEASES_WRITE -> "Renew DHCP"
    RouterCapability.DHCP_LEASES_READ -> "DHCP lease read"
    RouterCapability.READ_INFO -> "Router info"
  }
}

internal fun unsupportedRouterActionCapabilities(
  source: String? = null,
  reasonFor: (RouterCapability) -> String
): Map<RouterCapability, RouterActionCapability> {
  return routerWriteCapabilities.associateWith { capability ->
    RouterActionCapability(
      capability = capability,
      supported = false,
      readable = false,
      writable = false,
      reason = reasonFor(capability),
      source = source
    )
  }
}

internal fun unsupportedRouterFeatureReadback(
  source: String? = null,
  messageFor: (RouterCapability) -> String
): Map<RouterCapability, RouterFeatureReadback> {
  return routerFeatureCapabilities.associateWith { capability ->
    RouterFeatureReadback(
      capability = capability,
      supported = false,
      readable = false,
      writable = false,
      enabled = null,
      message = messageFor(capability),
      source = source
    )
  }
}
