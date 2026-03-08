package com.nerf.netx.data

internal interface RouterHttpSession {
  suspend fun nvramGet(names: List<String>): Map<String, String>
  suspend fun applyApp(postData: Map<String, String>): RouterActionResult
  suspend fun submitForm(path: String, postData: Map<String, String>): RouterActionResult
}

internal interface RouterVendorAdapter {
  val id: String

  fun matches(info: RouterInfo): Boolean

  suspend fun probe(session: RouterHttpSession): RouterRuntimeCapabilities

  suspend fun probeDevice(
    session: RouterHttpSession,
    device: RouterManagedDevice,
    routerRuntime: RouterRuntimeCapabilities
  ): RouterDeviceRuntimeCapabilities {
    return RouterDeviceRuntimeCapabilities(
      adapterId = id,
      detected = routerRuntime.detected,
      authenticated = routerRuntime.authenticated,
      readable = routerRuntime.readable,
      writable = false,
      message = "Device write control is not implemented for this router adapter."
    )
  }

  suspend fun setDeviceBlocked(session: RouterHttpSession, device: RouterManagedDevice, blocked: Boolean): RouterActionResult {
    return notSupported("Device block control is not implemented for this router adapter.")
  }

  suspend fun setDevicePaused(session: RouterHttpSession, device: RouterManagedDevice, paused: Boolean): RouterActionResult {
    return notSupported("Device pause control is not implemented for this router adapter.")
  }

  suspend fun renameDevice(session: RouterHttpSession, device: RouterManagedDevice, name: String): RouterActionResult {
    return notSupported("Device rename control is not implemented for this router adapter.")
  }

  suspend fun prioritizeDevice(session: RouterHttpSession, device: RouterManagedDevice): RouterActionResult {
    return notSupported("Device prioritization is not implemented for this router adapter.")
  }

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
  private val source = "Generic router fallback without verified write integration"

  override fun matches(info: RouterInfo): Boolean = true

  override suspend fun probe(session: RouterHttpSession): RouterRuntimeCapabilities {
    val reason = "No verified write integration exists for this router vendor/model."
    return RouterRuntimeCapabilities(
      adapterId = id,
      detected = true,
      authenticated = true,
      readable = true,
      writable = false,
      capabilities = setOf(RouterCapability.READ_INFO),
      actionCapabilities = unsupportedRouterActionCapabilities(source = source) { capability ->
        "${routerCapabilityLabel(capability)} is unsupported on this router backend. $reason"
      },
      featureReadback = unsupportedRouterFeatureReadback(source = source) { capability ->
        "${routerCapabilityLabel(capability)} state is unavailable on this router backend. $reason"
      },
      message = "Router detected, but no verified write integration exists for this vendor/model."
    )
  }
}

internal class AsusRouterAdapter : RouterVendorAdapter {
  override val id: String = "asuswrt-app"

  private val source = "Verified from ASUSWRT httpApi.js via appGet.cgi/applyapp.cgi"
  private val guestKeys = listOf("wl0.1_bss_enabled", "wl1.1_bss_enabled")
  private val vpnKeys = listOf("vpn_serverx_eas", "vpn_serverx_start")
  private val customClientListKey = "custom_clientlist"
  private val multiFilterAllKey = "MULTIFILTER_ALL"
  private val multiFilterEnableKey = "MULTIFILTER_ENABLE"
  private val multiFilterMacKey = "MULTIFILTER_MAC"
  private val multiFilterDeviceNameKey = "MULTIFILTER_DEVICENAME"
  private val multiFilterDaytimeKey = "MULTIFILTER_MACFILTER_DAYTIME"
  private val multiFilterDaytimeV2Key = "MULTIFILTER_MACFILTER_DAYTIME_V2"

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

  override suspend fun probeDevice(
    session: RouterHttpSession,
    device: RouterManagedDevice,
    routerRuntime: RouterRuntimeCapabilities
  ): RouterDeviceRuntimeCapabilities {
    val normalizedMac = normalizeMac(device.macAddress)
      ?: return deviceIdRequired(routerRuntime, "Router-backed device control requires a stable MAC address.")

    val nvram = session.nvramGet(
      listOf(
        customClientListKey,
        multiFilterEnableKey,
        multiFilterMacKey,
        multiFilterDeviceNameKey
      )
    )
    val nickname = parseCustomClientList(nvram[customClientListKey]).nicknameForMac(normalizedMac)
    val filterState = parseMultiFilterState(
      enableRaw = nvram[multiFilterEnableKey],
      macRaw = nvram[multiFilterMacKey],
      deviceNameRaw = nvram[multiFilterDeviceNameKey]
    )
    val blocked = filterState.ruleForMac(normalizedMac)?.enable == "2"

    val actionCapabilities = linkedMapOf(
      RouterDeviceCapability.BLOCK to RouterDeviceActionCapability(
        capability = RouterDeviceCapability.BLOCK,
        supported = true,
        readable = true,
        writable = true,
        reason = "Per-device internet blocking maps to MULTIFILTER_* on ASUSWRT.",
        source = source
      ),
      RouterDeviceCapability.RENAME to RouterDeviceActionCapability(
        capability = RouterDeviceCapability.RENAME,
        supported = true,
        readable = true,
        writable = true,
        reason = "Device rename maps to custom_clientlist on ASUSWRT.",
        source = source
      ),
      RouterDeviceCapability.PAUSE to RouterDeviceActionCapability(
        capability = RouterDeviceCapability.PAUSE,
        supported = false,
        readable = false,
        writable = false,
        reason = "ASUSWRT exposes scheduled parental-control rules, but NERF pause/resume semantics are not verified.",
        source = source
      ),
      RouterDeviceCapability.PRIORITIZE to RouterDeviceActionCapability(
        capability = RouterDeviceCapability.PRIORITIZE,
        supported = false,
        readable = false,
        writable = false,
        reason = "No verified ASUSWRT per-device QoS tagging endpoint was found in the vendor integration sources.",
        source = source
      )
    )

    return RouterDeviceRuntimeCapabilities(
      adapterId = id,
      detected = routerRuntime.detected,
      authenticated = routerRuntime.authenticated,
      readable = true,
      writable = actionCapabilities.values.any { it.writable },
      actionCapabilities = actionCapabilities,
      readback = RouterDeviceReadback(
        blocked = blocked,
        paused = null,
        nickname = nickname,
        prioritized = null
      ),
      message = "ASUSWRT device capabilities verified through custom_clientlist and MULTIFILTER_*."
    )
  }

  override suspend fun setDeviceBlocked(session: RouterHttpSession, device: RouterManagedDevice, blocked: Boolean): RouterActionResult {
    val normalizedMac = normalizeMac(device.macAddress)
      ?: return RouterActionResult(
        status = RouterActionStatus.NOT_SUPPORTED,
        message = "Router-backed device control requires a stable MAC address.",
        errorCode = "DEVICE_ID_UNSUPPORTED"
      )

    val nvram = session.nvramGet(
      listOf(
        multiFilterAllKey,
        multiFilterEnableKey,
        multiFilterMacKey,
        multiFilterDeviceNameKey,
        multiFilterDaytimeKey,
        multiFilterDaytimeV2Key,
        customClientListKey
      )
    )
    val customNames = parseCustomClientList(nvram[customClientListKey])
    val preferredName = sanitizeRuleText(
      device.displayName
        ?: customNames.nicknameForMac(normalizedMac)
        ?: normalizedMac
    )
    val filterState = parseMultiFilterState(
      enableRaw = nvram[multiFilterEnableKey],
      macRaw = nvram[multiFilterMacKey],
      deviceNameRaw = nvram[multiFilterDeviceNameKey],
      daytimeRaw = nvram[multiFilterDaytimeKey],
      daytimeV2Raw = nvram[multiFilterDaytimeV2Key],
      allRaw = nvram[multiFilterAllKey]
    )
    val updatedState = filterState.withBlocked(normalizedMac, preferredName, blocked)
    val form = linkedMapOf(
      "modified" to "0",
      "flag" to "background",
      "action_mode" to "apply",
      "action_script" to "restart_firewall",
      "action_wait" to "1",
      multiFilterAllKey to updatedState.allValue,
      multiFilterEnableKey to updatedState.enable.joinToString(">"),
      multiFilterMacKey to updatedState.macs.joinToString(">"),
      multiFilterDeviceNameKey to updatedState.deviceNames.joinToString(">"),
      multiFilterDaytimeKey to updatedState.daytime.joinToString(">"),
      multiFilterDaytimeV2Key to updatedState.daytimeV2.joinToString(">")
    )

    val result = session.submitForm("/start_apply2.htm", form)
    if (result.status != RouterActionStatus.OK) {
      return result
    }

    val readback = probeDevice(
      session = session,
      device = device.copy(displayName = preferredName, macAddress = normalizedMac),
      routerRuntime = RouterRuntimeCapabilities(
        adapterId = id,
        detected = true,
        authenticated = true,
        readable = true,
        writable = true
      )
    )
    return when (readback.readback.blocked) {
      blocked -> RouterActionResult(
        status = RouterActionStatus.OK,
        message = if (blocked) {
          "Device internet block enabled."
        } else {
          "Device internet block disabled."
        }
      )
      null -> RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "Device block write submitted but readback was unavailable.",
        errorCode = "READBACK_UNAVAILABLE"
      )
      else -> RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "Device block write was rejected by router readback.",
        errorCode = "READBACK_MISMATCH"
      )
    }
  }

  override suspend fun renameDevice(session: RouterHttpSession, device: RouterManagedDevice, name: String): RouterActionResult {
    val normalizedMac = normalizeMac(device.macAddress)
      ?: return RouterActionResult(
        status = RouterActionStatus.NOT_SUPPORTED,
        message = "Router-backed device rename requires a stable MAC address.",
        errorCode = "DEVICE_ID_UNSUPPORTED"
      )
    val sanitizedName = validateRename(name)
      ?: return RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "Device name is invalid.",
        errorCode = "INVALID_NAME"
      )

    val nvram = session.nvramGet(listOf(customClientListKey))
    val customNames = parseCustomClientList(nvram[customClientListKey]).withNickname(
      mac = normalizedMac,
      nickname = sanitizedName
    )
    val result = session.submitForm(
      path = "/start_apply2.htm",
      postData = linkedMapOf(
        "modified" to "0",
        "flag" to "background",
        "action_mode" to "apply",
        "action_script" to "saveNvram",
        "action_wait" to "1",
        customClientListKey to customNames.serialize()
      )
    )
    if (result.status != RouterActionStatus.OK) {
      return result
    }

    val readback = session.nvramGet(listOf(customClientListKey))[customClientListKey]
    val nickname = parseCustomClientList(readback).nicknameForMac(normalizedMac)
    return if (nickname == sanitizedName) {
      RouterActionResult(
        status = RouterActionStatus.OK,
        message = "Device name updated."
      )
    } else {
      RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "Device rename write was rejected by router readback.",
        errorCode = "READBACK_MISMATCH"
      )
    }
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

  private fun deviceIdRequired(
    runtime: RouterRuntimeCapabilities,
    reason: String
  ): RouterDeviceRuntimeCapabilities {
    val unsupported = linkedMapOf(
      RouterDeviceCapability.BLOCK to RouterDeviceActionCapability(
        capability = RouterDeviceCapability.BLOCK,
        reason = reason,
        source = source
      ),
      RouterDeviceCapability.PAUSE to RouterDeviceActionCapability(
        capability = RouterDeviceCapability.PAUSE,
        reason = reason,
        source = source
      ),
      RouterDeviceCapability.RENAME to RouterDeviceActionCapability(
        capability = RouterDeviceCapability.RENAME,
        reason = reason,
        source = source
      ),
      RouterDeviceCapability.PRIORITIZE to RouterDeviceActionCapability(
        capability = RouterDeviceCapability.PRIORITIZE,
        reason = reason,
        source = source
      )
    )
    return RouterDeviceRuntimeCapabilities(
      adapterId = id,
      detected = runtime.detected,
      authenticated = runtime.authenticated,
      readable = false,
      writable = false,
      actionCapabilities = unsupported,
      message = reason
    )
  }

  private fun normalizeMac(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val compact = raw.uppercase().replace("-", ":").trim()
    return if (Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(compact)) compact else null
  }

  private fun validateRename(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.contains('<') || trimmed.contains('>')) return null
    if (trimmed.toByteArray(Charsets.UTF_8).size > 32) return null
    return trimmed
  }

  private fun sanitizeRuleText(raw: String): String {
    return raw.trim().replace("<", "").replace(">", "").takeIf { it.isNotBlank() } ?: "Unknown"
  }

  private fun parseCustomClientList(raw: String?): AsusCustomClientList {
    val entries = raw.orEmpty()
      .split('<')
      .mapNotNull { row ->
        if (row.isBlank()) return@mapNotNull null
        val cols = row.split('>')
        val mac = normalizeMac(cols.getOrNull(1))
        if (mac == null) return@mapNotNull null
        AsusCustomClientEntry(
          nickname = cols.getOrNull(0).orEmpty(),
          mac = mac,
          type = cols.getOrNull(3).orEmpty()
        )
      }
      .toMutableList()
    return AsusCustomClientList(entries)
  }

  private fun parseMultiFilterState(
    enableRaw: String?,
    macRaw: String?,
    deviceNameRaw: String?,
    daytimeRaw: String? = null,
    daytimeV2Raw: String? = null,
    allRaw: String? = null
  ): AsusMultiFilterState {
    val enable = splitDelimited(enableRaw)
    val macs = splitDelimited(macRaw)
    val names = splitDelimited(deviceNameRaw)
    val daytime = splitDelimited(daytimeRaw)
    val daytimeV2 = splitDelimited(daytimeV2Raw)
    val size = listOf(enable.size, macs.size, names.size, daytime.size, daytimeV2.size).maxOrNull() ?: 0
    return AsusMultiFilterState(
      allValue = allRaw?.takeIf { it.isNotBlank() } ?: "0",
      enable = MutableList(size) { idx -> enable.getOrElse(idx) { "0" } },
      macs = MutableList(size) { idx -> normalizeMac(macs.getOrNull(idx)) ?: "" },
      deviceNames = MutableList(size) { idx -> names.getOrElse(idx) { "" } },
      daytime = MutableList(size) { idx -> daytime.getOrElse(idx) { "" } },
      daytimeV2 = MutableList(size) { idx -> daytimeV2.getOrElse(idx) { "" } }
    ).normalized()
  }

  private fun splitDelimited(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split('>').map { it.trim() }
  }

  private data class AsusCustomClientEntry(
    val nickname: String,
    val mac: String,
    val type: String
  )

  private data class AsusCustomClientList(
    val entries: MutableList<AsusCustomClientEntry>
  ) {
    fun nicknameForMac(mac: String): String? {
      return entries.firstOrNull { it.mac == mac }?.nickname?.takeIf { it.isNotBlank() }
    }

    fun withNickname(mac: String, nickname: String): AsusCustomClientList {
      val filtered = entries.filterNot { it.mac == mac }.toMutableList()
      filtered += AsusCustomClientEntry(
        nickname = nickname,
        mac = mac,
        type = entries.firstOrNull { it.mac == mac }?.type.orEmpty()
      )
      return copy(entries = filtered)
    }

    fun serialize(): String {
      return entries.joinToString("<") { entry ->
        listOf(entry.nickname, entry.mac, "0", entry.type, "", "").joinToString(">")
      }
    }
  }

  private data class AsusMultiFilterRule(
    val enable: String,
    val mac: String,
    val deviceName: String
  )

  private data class AsusMultiFilterState(
    val allValue: String,
    val enable: MutableList<String>,
    val macs: MutableList<String>,
    val deviceNames: MutableList<String>,
    val daytime: MutableList<String>,
    val daytimeV2: MutableList<String>
  ) {
    fun normalized(): AsusMultiFilterState {
      val indices = macs.indices.filter { macs[it].isNotBlank() }
      return AsusMultiFilterState(
        allValue = allValue,
        enable = indices.map { enable.getOrElse(it) { "0" } }.toMutableList(),
        macs = indices.map { macs[it] }.toMutableList(),
        deviceNames = indices.map { deviceNames.getOrElse(it) { "" } }.toMutableList(),
        daytime = indices.map { daytime.getOrElse(it) { "" } }.toMutableList(),
        daytimeV2 = indices.map { daytimeV2.getOrElse(it) { "" } }.toMutableList()
      )
    }

    fun ruleForMac(mac: String): AsusMultiFilterRule? {
      val index = macs.indexOf(mac)
      if (index < 0) return null
      return AsusMultiFilterRule(
        enable = enable.getOrElse(index) { "0" },
        mac = mac,
        deviceName = deviceNames.getOrElse(index) { "" }
      )
    }

    fun withBlocked(mac: String, deviceName: String, blocked: Boolean): AsusMultiFilterState {
      val normalized = normalized()
      val index = normalized.macs.indexOf(mac)
      if (index >= 0) {
        normalized.enable[index] = if (blocked) "2" else "0"
        if (deviceName.isNotBlank()) {
          normalized.deviceNames[index] = deviceName
        }
      } else if (blocked) {
        normalized.enable += "2"
        normalized.macs += mac
        normalized.deviceNames += deviceName
        normalized.daytime += "<"
        normalized.daytimeV2 += "W03E21000700<W04122000800"
      }

      return normalized.copy(
        allValue = if (blocked && normalized.allValue == "0") "1" else normalized.allValue
      )
    }
  }
}
