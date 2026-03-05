package com.nerf.netx.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
  val authType: RouterAuthType = RouterAuthType.NONE,
  val capabilities: Set<RouterCapability> = setOf(RouterCapability.READ_INFO),
  val mode: RouterAccessMode = RouterAccessMode.READ_ONLY,
  val lastValidatedEpochMs: Long? = null
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
  private val keyCapabilities = "capabilities"
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
      authType = parseAuthType(prefs.getString(keyAuthType, null)),
      capabilities = caps,
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
    val caps = api.getCapabilities()
    val profile = RouterProfile(
      routerIp = detected.routerIp,
      adminUrl = detected.adminUrl,
      modelName = detected.modelName,
      vendorName = detected.vendorName,
      firmwareVersion = detected.firmwareVersion,
      authType = detected.detectedAuthType,
      capabilities = caps,
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

    val finalCaps = api.getCapabilities()
    val finalMode = deriveMode(finalCaps)
    val finalProfile = profile.copy(
      capabilities = finalCaps,
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
      .putString(keyCapabilities, profile.capabilities.joinToString(",") { it.name })
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
}
