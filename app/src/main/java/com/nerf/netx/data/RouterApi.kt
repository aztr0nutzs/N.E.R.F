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
  suspend fun testConnection(): RouterActionResult
  suspend fun validateCredentials(): RouterActionResult
  suspend fun getDhcpLeases(): Result<List<DhcpLease>>
  suspend fun setDhcpLeaseName(macOrIp: String, name: String): RouterActionResult
  suspend fun flushDns(): RouterActionResult
  suspend fun setDnsShieldEnabled(enabled: Boolean): RouterActionResult
  suspend fun setFirewallEnabled(enabled: Boolean): RouterActionResult
  suspend fun setVpnEnabled(enabled: Boolean): RouterActionResult
  suspend fun setQosConfig(config: QosConfig): RouterActionResult
  suspend fun setGuestWifiEnabled(enabled: Boolean): RouterActionResult
  suspend fun reboot(): RouterActionResult
}
