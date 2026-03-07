package com.nerf.netx.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nerf.netx.domain.AppActionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class RouterCredentials(
  val host: String,
  val username: String,
  val token: String,
  val adminUrl: String? = null,
  val authType: RouterAuthType = RouterAuthType.NONE
) {
  fun configured(): Boolean = host.isNotBlank() && username.isNotBlank() && token.isNotBlank()
}

data class RouterProfile(
  val routerIp: String? = null,
  val adminUrl: String? = null,
  val modelName: String? = null,
  val vendorName: String? = null,
  val firmwareVersion: String? = null,
  val adapterId: String? = null,
  val authType: RouterAuthType = RouterAuthType.NONE,
  val detected: Boolean = false,
  val authenticated: Boolean = false,
  val readable: Boolean = false,
  val writable: Boolean = false,
  val capabilities: Set<RouterCapability> = setOf(RouterCapability.READ_INFO),
  val actionCapabilities: Map<String, RouterProfileAction> = emptyMap(),
  val mode: RouterAccessMode = RouterAccessMode.READ_ONLY,
  val lastValidatedEpochMs: Long? = null
)

data class RouterProfileAction(
  val actionId: String,
  val label: String,
  val readable: Boolean = false,
  val writable: Boolean = false,
  val reason: String,
  val source: String? = null
)

data class RouterCredentialCheck(
  val ok: Boolean,
  val status: RouterActionStatus,
  val message: String,
  val profile: RouterProfile? = null
)

class RouterCredentialsStore(context: Context) {
  private val prefs = run {
    val key = MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
    EncryptedSharedPreferences.create(
      context,
      "nerf_router_creds",
      key,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
  }

  private val keyHost = "host"
  private val keyUser = "user"
  private val keyToken = "token"
  private val keyAdminUrl = "admin_url"
  private val keyAuthType = "auth_type"
  private val keyModel = "model"
  private val keyVendor = "vendor"
  private val keyFirmware = "firmware"
  private val keyAdapterId = "adapter_id"
  private val keyCapabilities = "capabilities"
  private val keyDetected = "detected"
  private val keyAuthenticated = "authenticated"
  private val keyReadable = "readable"
  private val keyWritable = "writable"
  private val keyActionCapabilities = "action_capabilities"
  private val keyMode = "access_mode"
  private val keyLastValidated = "last_validated"

  fun read(): RouterCredentials {
    return RouterCredentials(
      host = prefs.getString(keyHost, "") ?: "",
      username = prefs.getString(keyUser, "") ?: "",
      token = prefs.getString(keyToken, "") ?: "",
      adminUrl = prefs.getString(keyAdminUrl, null),
      authType = parseAuthType(prefs.getString(keyAuthType, null))
    )
  }

  fun readProfile(): RouterProfile {
    val capsCsv = prefs.getString(keyCapabilities, null)
    val caps = parseCapabilities(capsCsv)
    return RouterProfile(
      routerIp = prefs.getString(keyHost, null),
      adminUrl = prefs.getString(keyAdminUrl, null),
      modelName = prefs.getString(keyModel, null),
      vendorName = prefs.getString(keyVendor, null),
      firmwareVersion = prefs.getString(keyFirmware, null),
      adapterId = prefs.getString(keyAdapterId, null),
      authType = parseAuthType(prefs.getString(keyAuthType, null)),
      detected = prefs.getBoolean(keyDetected, false),
      authenticated = prefs.getBoolean(keyAuthenticated, false),
      readable = prefs.getBoolean(keyReadable, false),
      writable = prefs.getBoolean(keyWritable, false),
      capabilities = caps,
      actionCapabilities = parseActionCapabilities(prefs.getString(keyActionCapabilities, null)),
      mode = parseAccessMode(prefs.getString(keyMode, null), caps),
      lastValidatedEpochMs = prefs.getLong(keyLastValidated, -1L).takeIf { it > 0L }
    )
  }

  suspend fun testConnection(candidate: RouterCredentials): RouterCredentialCheck = withContext(Dispatchers.IO) {
    val host = candidate.host.trim()
    if (host.isBlank()) {
      return@withContext RouterCredentialCheck(
        ok = false,
        status = RouterActionStatus.ERROR,
        message = "Router IP / URL is required."
      )
    }

    val api = RouterApiHttp(
      RouterConnectionInfo(
        routerIp = host,
        adminUrl = candidate.adminUrl,
        username = candidate.username.ifBlank { null },
        password = candidate.token.ifBlank { null },
        preferredAuthType = candidate.authType
      )
    )

    val detected = api.detect(
      RouterConnectionInfo(
        routerIp = host,
        adminUrl = candidate.adminUrl,
        username = candidate.username.ifBlank { null },
        password = candidate.token.ifBlank { null },
        preferredAuthType = candidate.authType
      )
    )
    val connection = api.testConnection()
    val runtime = api.getRuntimeCapabilities()
    val caps = runtime.capabilities
    val profile = RouterProfile(
      routerIp = detected.routerIp,
      adminUrl = detected.adminUrl,
      modelName = detected.modelName,
      vendorName = detected.vendorName,
      firmwareVersion = detected.firmwareVersion,
      adapterId = runtime.adapterId,
      authType = detected.detectedAuthType,
      detected = runtime.detected,
      authenticated = runtime.authenticated,
      readable = runtime.readable,
      writable = runtime.writable,
      capabilities = caps,
      actionCapabilities = runtime.actionCapabilities.values.associate { capability ->
        actionIdFor(capability.capability) to RouterProfileAction(
          actionId = actionIdFor(capability.capability),
          label = labelFor(capability.capability),
          readable = capability.readable,
          writable = capability.writable,
          reason = capability.reason,
          source = capability.source
        )
      },
      mode = deriveMode(caps),
      lastValidatedEpochMs = System.currentTimeMillis()
    )

    RouterCredentialCheck(
      ok = connection.status == RouterActionStatus.OK,
      status = connection.status,
      message = connection.message,
      profile = profile
    )
  }

  suspend fun validateAndSave(candidate: RouterCredentials): RouterCredentialCheck = withContext(Dispatchers.IO) {
    val connection = testConnection(candidate)
    if (!connection.ok || connection.profile == null) {
      return@withContext connection
    }

    val profile = connection.profile
    val api = RouterApiHttp(
      RouterConnectionInfo(
        routerIp = profile.routerIp ?: candidate.host,
        adminUrl = profile.adminUrl,
        username = candidate.username.ifBlank { null },
        password = candidate.token.ifBlank { null },
        preferredAuthType = profile.authType
      )
    )

    val authResult = api.validateCredentials()
    if (authResult.status != RouterActionStatus.OK) {
      return@withContext RouterCredentialCheck(
        ok = false,
        status = authResult.status,
        message = authResult.message,
        profile = profile
      )
    }

    val finalRuntime = api.getRuntimeCapabilities()
    val finalCaps = finalRuntime.capabilities
    val finalMode = deriveMode(finalCaps)
    val finalProfile = profile.copy(
      adapterId = finalRuntime.adapterId,
      detected = finalRuntime.detected,
      authenticated = finalRuntime.authenticated,
      readable = finalRuntime.readable,
      writable = finalRuntime.writable,
      capabilities = finalCaps,
      actionCapabilities = finalRuntime.actionCapabilities.values.associate { capability ->
        actionIdFor(capability.capability) to RouterProfileAction(
          actionId = actionIdFor(capability.capability),
          label = labelFor(capability.capability),
          readable = capability.readable,
          writable = capability.writable,
          reason = capability.reason,
          source = capability.source
        )
      },
      mode = finalMode,
      lastValidatedEpochMs = System.currentTimeMillis()
    )

    saveInternal(
      credentials = candidate.copy(
        adminUrl = finalProfile.adminUrl,
        authType = finalProfile.authType
      ),
      profile = finalProfile
    )

    RouterCredentialCheck(
      ok = true,
      status = RouterActionStatus.OK,
      message = if (finalMode == RouterAccessMode.READ_ONLY) {
        "Validated and saved in READ_ONLY mode."
      } else {
        "Validated and saved in READ_WRITE mode."
      },
      profile = finalProfile
    )
  }

  fun write(credentials: RouterCredentials) {
    saveInternal(credentials, readProfile())
  }

  private fun saveInternal(credentials: RouterCredentials, profile: RouterProfile) {
    prefs.edit()
      .putString(keyHost, credentials.host)
      .putString(keyUser, credentials.username)
      .putString(keyToken, credentials.token)
      .putString(keyAdminUrl, profile.adminUrl ?: credentials.adminUrl)
      .putString(keyAuthType, profile.authType.name)
      .putString(keyModel, profile.modelName)
      .putString(keyVendor, profile.vendorName)
      .putString(keyFirmware, profile.firmwareVersion)
      .putString(keyAdapterId, profile.adapterId)
      .putString(keyCapabilities, profile.capabilities.joinToString(",") { it.name })
      .putBoolean(keyDetected, profile.detected)
      .putBoolean(keyAuthenticated, profile.authenticated)
      .putBoolean(keyReadable, profile.readable)
      .putBoolean(keyWritable, profile.writable)
      .putString(keyActionCapabilities, encodeActionCapabilities(profile.actionCapabilities))
      .putString(keyMode, profile.mode.name)
      .putLong(keyLastValidated, profile.lastValidatedEpochMs ?: System.currentTimeMillis())
      .apply()
  }

  private fun deriveMode(capabilities: Set<RouterCapability>): RouterAccessMode {
    val writable = capabilities.any {
      it != RouterCapability.READ_INFO && it != RouterCapability.DHCP_LEASES_READ
    }
    return if (writable) RouterAccessMode.READ_WRITE else RouterAccessMode.READ_ONLY
  }

  private fun parseAuthType(raw: String?): RouterAuthType {
    return runCatching { RouterAuthType.valueOf(raw ?: "NONE") }.getOrDefault(RouterAuthType.NONE)
  }

  private fun parseCapabilities(raw: String?): Set<RouterCapability> {
    val parsed = raw
      ?.split(',')
      ?.mapNotNull { runCatching { RouterCapability.valueOf(it.trim()) }.getOrNull() }
      ?.toSet()
      .orEmpty()
    return if (parsed.isEmpty()) setOf(RouterCapability.READ_INFO) else parsed
  }

  private fun parseAccessMode(raw: String?, capabilities: Set<RouterCapability>): RouterAccessMode {
    return runCatching { RouterAccessMode.valueOf(raw ?: "") }.getOrElse { deriveMode(capabilities) }
  }

  private fun parseActionCapabilities(raw: String?): Map<String, RouterProfileAction> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
      val root = JSONObject(raw)
      buildMap {
        val keys = root.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          val item = root.getJSONObject(key)
          put(
            key,
            RouterProfileAction(
              actionId = key,
              label = item.optString("label", key),
              readable = item.optBoolean("readable", false),
              writable = item.optBoolean("writable", false),
              reason = item.optString("reason", ""),
              source = item.optString("source").takeIf { it.isNotBlank() }
            )
          )
        }
      }
    }.getOrDefault(emptyMap())
  }

  private fun encodeActionCapabilities(value: Map<String, RouterProfileAction>): String {
    val root = JSONObject()
    value.forEach { (key, action) ->
      root.put(
        key,
        JSONObject()
          .put("label", action.label)
          .put("readable", action.readable)
          .put("writable", action.writable)
          .put("reason", action.reason)
          .put("source", action.source)
      )
    }
    return root.toString()
  }

  private fun actionIdFor(capability: RouterCapability): String {
    return when (capability) {
      RouterCapability.GUEST_WIFI_TOGGLE -> AppActionId.ROUTER_GUEST_WIFI
      RouterCapability.DNS_SHIELD_TOGGLE -> AppActionId.ROUTER_DNS_SHIELD
      RouterCapability.FIREWALL_TOGGLE -> AppActionId.ROUTER_FIREWALL
      RouterCapability.VPN_TOGGLE -> AppActionId.ROUTER_VPN
      RouterCapability.QOS_CONFIG -> AppActionId.ROUTER_QOS
      RouterCapability.REBOOT -> AppActionId.ROUTER_REBOOT
      RouterCapability.DNS_FLUSH -> AppActionId.ROUTER_FLUSH_DNS
      RouterCapability.DHCP_LEASES_WRITE -> AppActionId.ROUTER_RENEW_DHCP
      else -> capability.name
    }
  }

  private fun labelFor(capability: RouterCapability): String {
    return when (capability) {
      RouterCapability.GUEST_WIFI_TOGGLE -> "Guest Wi-Fi"
      RouterCapability.DNS_SHIELD_TOGGLE -> "DNS Shield"
      RouterCapability.FIREWALL_TOGGLE -> "Firewall"
      RouterCapability.VPN_TOGGLE -> "VPN"
      RouterCapability.QOS_CONFIG -> "QoS"
      RouterCapability.REBOOT -> "Router reboot"
      RouterCapability.DNS_FLUSH -> "Flush DNS"
      RouterCapability.DHCP_LEASES_WRITE -> "Renew DHCP"
      else -> capability.name
    }
  }
}
