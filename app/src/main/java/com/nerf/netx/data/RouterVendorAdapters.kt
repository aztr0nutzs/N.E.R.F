package com.nerf.netx.data

internal interface RouterHttpSession {
  suspend fun nvramGet(names: List<String>): Map<String, String>
  suspend fun applyApp(postData: Map<String, String>): RouterActionResult
}

internal interface RouterVendorAdapter {
  val id: String

  fun matches(info: RouterInfo): Boolean

  suspend fun probe(session: RouterHttpSession): RouterRuntimeCapabilities

  suspend fun setDnsShieldEnabled(session: RouterHttpSession, enabled: Boolean): RouterActionResult {
    return notSupported("DNS Shield write control is not implemented for this router adapter.")
  }

  suspend fun setFirewallEnabled(session: RouterHttpSession, enabled: Boolean): RouterActionResult {
    return notSupported("Firewall write control is not implemented for this router adapter.")
  }

  suspend fun setVpnEnabled(session: RouterHttpSession, enabled: Boolean): RouterActionResult {
    return notSupported("VPN write control is not implemented for this router adapter.")
  }

  suspend fun setQosConfig(session: RouterHttpSession, config: QosConfig): RouterActionResult {
    return notSupported("QoS write control is not implemented for this router adapter.")
  }

  suspend fun setGuestWifiEnabled(session: RouterHttpSession, enabled: Boolean): RouterActionResult {
    return notSupported("Guest Wi-Fi write control is not implemented for this router adapter.")
  }

  suspend fun reboot(session: RouterHttpSession): RouterActionResult {
    return notSupported("Reboot control is not implemented for this router adapter.")
  }

  suspend fun flushDns(session: RouterHttpSession): RouterActionResult {
    return notSupported("DNS flush is not implemented for this router adapter.")
  }

  suspend fun renewDhcp(session: RouterHttpSession): RouterActionResult {
    return notSupported("DHCP renew is not implemented for this router adapter.")
  }

  fun notSupported(reason: String): RouterActionResult {
    return RouterActionResult(
      status = RouterActionStatus.NOT_SUPPORTED,
      message = reason,
      errorCode = "NOT_SUPPORTED"
    )
  }
}

internal class GenericReadOnlyRouterAdapter : RouterVendorAdapter {
  override val id: String = "generic-read-only"

  override fun matches(info: RouterInfo): Boolean = true

  override suspend fun probe(session: RouterHttpSession): RouterRuntimeCapabilities {
    return RouterRuntimeCapabilities(
      adapterId = id,
      detected = true,
      authenticated = true,
      readable = true,
      writable = false,
      capabilities = setOf(RouterCapability.READ_INFO),
      message = "Router detected, but no verified write integration exists for this vendor/model."
    )
  }
}

internal class AsusRouterAdapter : RouterVendorAdapter {
  override val id: String = "asuswrt-app"

  private val source = "Verified from ASUSWRT httpApi.js via appGet.cgi/applyapp.cgi"
  private val guestKeys = listOf("wl0.1_bss_enabled", "wl1.1_bss_enabled")
  private val vpnKeys = listOf("vpn_serverx_eas", "vpn_serverx_start")

  override fun matches(info: RouterInfo): Boolean {
    val vendor = info.vendorName.orEmpty()
    val model = info.modelName.orEmpty()
    return vendor.contains("ASUS", ignoreCase = true) || model.contains("ASUS", ignoreCase = true)
  }

  override suspend fun probe(session: RouterHttpSession): RouterRuntimeCapabilities {
    val nvram = session.nvramGet(
      guestKeys + listOf(
        "dnsfilter_enable_x",
        "fw_lw_enable_x",
        "qos_enable",
        "qos_type"
      ) + vpnKeys
    )

    val guestKey = guestKeys.firstOrNull { !nvram[it].isNullOrBlank() }
    val guestEnabled = guestKey?.let { nvram[it] == "1" }
    val dnsEnabled = nvram["dnsfilter_enable_x"]?.takeIf { it.isNotBlank() }?.let { it == "1" }
    val firewallEnabled = nvram["fw_lw_enable_x"]?.takeIf { it.isNotBlank() }?.let { it == "1" }
    val qosEnabled = nvram["qos_enable"]?.takeIf { it.isNotBlank() }?.let { it == "1" }
    val qosType = nvram["qos_type"]?.takeIf { it.isNotBlank() }
    val vpnValue = vpnKeys.mapNotNull { key -> nvram[key]?.takeIf { it.isNotBlank() } }.firstOrNull()
    val vpnEnabled = vpnValue?.let { value -> value.contains("1") }

    val actionCapabilities = linkedMapOf(
      RouterCapability.GUEST_WIFI_TOGGLE to actionCapability(
        capability = RouterCapability.GUEST_WIFI_TOGGLE,
        supported = guestKey != null,
        readable = guestKey != null,
        writable = guestKey != null,
        reason = if (guestKey != null) {
          "Guest Wi-Fi is writable through $guestKey."
        } else {
          "No ASUS guest SSID NVRAM key was exposed for this model."
        }
      ),
      RouterCapability.DNS_SHIELD_TOGGLE to actionCapability(
        capability = RouterCapability.DNS_SHIELD_TOGGLE,
        supported = dnsEnabled != null,
        readable = dnsEnabled != null,
        writable = dnsEnabled != null,
        reason = if (dnsEnabled != null) {
          "DNS Shield maps to dnsfilter_enable_x with restart_dnsfilter."
        } else {
          "DNS Shield NVRAM is not exposed on this ASUSWRT model."
        }
      ),
      RouterCapability.FIREWALL_TOGGLE to actionCapability(
        capability = RouterCapability.FIREWALL_TOGGLE,
        supported = firewallEnabled != null,
        readable = firewallEnabled != null,
        writable = firewallEnabled != null,
        reason = if (firewallEnabled != null) {
          "Firewall maps to fw_lw_enable_x with restart_firewall."
        } else {
          "Firewall control NVRAM is not exposed on this ASUSWRT model."
        }
      ),
      RouterCapability.QOS_CONFIG to actionCapability(
        capability = RouterCapability.QOS_CONFIG,
        supported = qosEnabled != null || qosType != null,
        readable = qosEnabled != null || qosType != null,
        writable = false,
        reason = if (qosEnabled != null || qosType != null) {
          "QoS state is readable, but NERF QoS mode/config mapping to ASUS qos_type is not verified."
        } else {
          "QoS NVRAM is not exposed on this ASUSWRT model."
        }
      ),
      RouterCapability.VPN_TOGGLE to actionCapability(
        capability = RouterCapability.VPN_TOGGLE,
        supported = vpnEnabled != null,
        readable = vpnEnabled != null,
        writable = false,
        reason = if (vpnEnabled != null) {
          "VPN state is readable, but the NERF VPN action is broader than the verified ASUS OpenVPN server controls."
        } else {
          "VPN server state is not exposed on this ASUSWRT model."
        }
      ),
      RouterCapability.REBOOT to actionCapability(
        capability = RouterCapability.REBOOT,
        supported = true,
        readable = false,
        writable = true,
        reason = "Reboot is writable through applyapp.cgi rc_service=reboot."
      ),
      RouterCapability.DNS_FLUSH to actionCapability(
        capability = RouterCapability.DNS_FLUSH,
        supported = true,
        readable = false,
        writable = true,
        reason = "DNS flush maps to applyapp.cgi rc_service=restart_dnsmasq."
      ),
      RouterCapability.DHCP_LEASES_WRITE to actionCapability(
        capability = RouterCapability.DHCP_LEASES_WRITE,
        supported = false,
        readable = false,
        writable = false,
        reason = "No verified ASUSWRT DHCP renewal endpoint was found in the vendor integration sources."
      )
    )

    val featureReadback = linkedMapOf(
      RouterCapability.GUEST_WIFI_TOGGLE to featureReadback(
        capability = RouterCapability.GUEST_WIFI_TOGGLE,
        supported = guestKey != null,
        readable = guestKey != null,
        writable = guestKey != null,
        enabled = guestEnabled,
        message = if (guestKey != null) {
          "Read from $guestKey."
        } else {
          "Guest Wi-Fi readback is unavailable on this ASUSWRT model."
        }
      ),
      RouterCapability.DNS_SHIELD_TOGGLE to featureReadback(
        capability = RouterCapability.DNS_SHIELD_TOGGLE,
        supported = dnsEnabled != null,
        readable = dnsEnabled != null,
        writable = dnsEnabled != null,
        enabled = dnsEnabled,
        message = if (dnsEnabled != null) {
          "Read from dnsfilter_enable_x."
        } else {
          "DNS Shield readback is unavailable on this ASUSWRT model."
        }
      ),
      RouterCapability.FIREWALL_TOGGLE to featureReadback(
        capability = RouterCapability.FIREWALL_TOGGLE,
        supported = firewallEnabled != null,
        readable = firewallEnabled != null,
        writable = firewallEnabled != null,
        enabled = firewallEnabled,
        message = if (firewallEnabled != null) {
          "Read from fw_lw_enable_x."
        } else {
          "Firewall readback is unavailable on this ASUSWRT model."
        }
      ),
      RouterCapability.VPN_TOGGLE to featureReadback(
        capability = RouterCapability.VPN_TOGGLE,
        supported = vpnEnabled != null,
        readable = vpnEnabled != null,
        writable = false,
        enabled = vpnEnabled,
        message = if (vpnEnabled != null) {
          "Read from ${vpnKeys.firstOrNull { !nvram[it].isNullOrBlank() } ?: vpnKeys.first()}."
        } else {
          "VPN readback is unavailable on this ASUSWRT model."
        }
      )
    )

    val capabilities = buildSet {
      add(RouterCapability.READ_INFO)
      actionCapabilities.values
        .filter { it.writable }
        .forEach { add(it.capability) }
    }

    return RouterRuntimeCapabilities(
      adapterId = id,
      detected = true,
      authenticated = true,
      readable = true,
      writable = actionCapabilities.values.any { it.writable },
      capabilities = capabilities,
      actionCapabilities = actionCapabilities,
      featureReadback = featureReadback,
      message = "ASUSWRT capabilities verified through appGet.cgi/applyapp.cgi."
    )
  }

  override suspend fun setGuestWifiEnabled(session: RouterHttpSession, enabled: Boolean): RouterActionResult {
    val nvram = session.nvramGet(guestKeys)
    val guestKey = guestKeys.firstOrNull { !nvram[it].isNullOrBlank() }
      ?: return notSupported("Guest Wi-Fi write is unsupported on this ASUSWRT model.")

    val result = session.applyApp(
      mapOf(
        "action_mode" to "apply",
        guestKey to boolValue(enabled),
        "rc_service" to "restart_wireless;restart_qos;restart_firewall;"
      )
    )
    if (result.status != RouterActionStatus.OK) {
      return result
    }

    val readback = session.nvramGet(listOf(guestKey))[guestKey]
    return verifyBooleanReadback(
      currentValue = readback,
      expected = enabled,
      label = "Guest Wi-Fi"
    )
  }

  override suspend fun setDnsShieldEnabled(session: RouterHttpSession, enabled: Boolean): RouterActionResult {
    val result = session.applyApp(
      mapOf(
        "action_mode" to "apply",
        "dnsfilter_enable_x" to boolValue(enabled),
        "rc_service" to "restart_dnsfilter"
      )
    )
    if (result.status != RouterActionStatus.OK) {
      return result
    }

    val readback = session.nvramGet(listOf("dnsfilter_enable_x"))["dnsfilter_enable_x"]
    return verifyBooleanReadback(
      currentValue = readback,
      expected = enabled,
      label = "DNS Shield"
    )
  }

  override suspend fun setFirewallEnabled(session: RouterHttpSession, enabled: Boolean): RouterActionResult {
    val result = session.applyApp(
      mapOf(
        "action_mode" to "apply",
        "fw_lw_enable_x" to boolValue(enabled),
        "rc_service" to "restart_firewall"
      )
    )
    if (result.status != RouterActionStatus.OK) {
      return result
    }

    val readback = session.nvramGet(listOf("fw_lw_enable_x"))["fw_lw_enable_x"]
    return verifyBooleanReadback(
      currentValue = readback,
      expected = enabled,
      label = "Firewall"
    )
  }

  override suspend fun reboot(session: RouterHttpSession): RouterActionResult {
    return session.applyApp(
      mapOf(
        "action_mode" to "apply",
        "rc_service" to "reboot"
      )
    ).let { result ->
      if (result.status == RouterActionStatus.OK) {
        RouterActionResult(
          status = RouterActionStatus.OK,
          message = "Router reboot command accepted by ASUSWRT."
        )
      } else {
        result
      }
    }
  }

  override suspend fun flushDns(session: RouterHttpSession): RouterActionResult {
    return session.applyApp(
      mapOf(
        "action_mode" to "apply",
        "rc_service" to "restart_dnsmasq"
      )
    ).let { result ->
      if (result.status == RouterActionStatus.OK) {
        RouterActionResult(
          status = RouterActionStatus.OK,
          message = "DNS service restart requested on ASUSWRT."
        )
      } else {
        result
      }
    }
  }

  private fun actionCapability(
    capability: RouterCapability,
    supported: Boolean,
    readable: Boolean,
    writable: Boolean,
    reason: String
  ): RouterActionCapability {
    return RouterActionCapability(
      capability = capability,
      supported = supported,
      readable = readable,
      writable = writable,
      reason = reason,
      source = source
    )
  }

  private fun featureReadback(
    capability: RouterCapability,
    supported: Boolean,
    readable: Boolean,
    writable: Boolean,
    enabled: Boolean?,
    message: String
  ): RouterFeatureReadback {
    return RouterFeatureReadback(
      capability = capability,
      supported = supported,
      readable = readable,
      writable = writable,
      enabled = enabled,
      message = message,
      source = source
    )
  }

  private fun verifyBooleanReadback(
    currentValue: String?,
    expected: Boolean,
    label: String
  ): RouterActionResult {
    return when (currentValue) {
      null, "" -> RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "$label write submitted but readback was unavailable.",
        errorCode = "READBACK_UNAVAILABLE"
      )
      boolValue(expected) -> RouterActionResult(
        status = RouterActionStatus.OK,
        message = "$label set to ${if (expected) "enabled" else "disabled"}."
      )
      else -> RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "$label write was rejected by router readback.",
        errorCode = "READBACK_MISMATCH"
      )
    }
  }

  private fun boolValue(enabled: Boolean): String = if (enabled) "1" else "0"
}
