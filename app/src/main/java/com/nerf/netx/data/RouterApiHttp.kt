package com.nerf.netx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class RouterApiHttp(
  private val connectionInfo: RouterConnectionInfo
) : RouterApi {

  private var detectedInfo: RouterInfo? = null
  private var detectedCapabilities: Set<RouterCapability> = setOf(RouterCapability.READ_INFO)

  override suspend fun detect(info: RouterConnectionInfo): RouterInfo = withContext(Dispatchers.IO) {
    val candidates = candidateBaseUrls(info)
    var bestUrl: String? = null
    var bestAuth = RouterAuthType.NONE
    var bestServerHeader: String? = null
    var htmlTitle: String? = null

    for (base in candidates) {
      val probe = probeEndpoint(base)
      if (probe.reachable) {
        bestUrl = base
        bestAuth = probe.authType
        bestServerHeader = probe.serverHeader
        htmlTitle = probe.htmlTitle
        break
      }
    }

    val vendor = detectVendor(bestServerHeader, htmlTitle)
    val model = detectModel(htmlTitle)
    val firmware = detectFirmware(htmlTitle)

    val resolved = RouterInfo(
      modelName = model,
      vendorName = vendor,
      firmwareVersion = firmware,
      routerIp = sanitizeRouterIp(info.routerIp),
      adminUrl = bestUrl,
      detectedAuthType = bestAuth
    )
    detectedInfo = resolved
    detectedCapabilities = probeCapabilities(resolved)
    resolved
  }

  override suspend fun getCapabilities(): Set<RouterCapability> {
    if (detectedInfo == null) {
      detect(connectionInfo)
    }
    return detectedCapabilities
  }

  override suspend fun testConnection(): RouterActionResult = withContext(Dispatchers.IO) {
    val base = ensureDetected().adminUrl
      ?: return@withContext RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "Router not reachable via HTTP/HTTPS",
        errorCode = "ROUTER_UNREACHABLE"
      )

    val probe = probeEndpoint(base)
    if (probe.reachable) {
      RouterActionResult(
        status = RouterActionStatus.OK,
        message = "Router reachable at $base"
      )
    } else {
      RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "Router connection test failed",
        errorCode = "CONNECTION_FAILED"
      )
    }
  }

  override suspend fun validateCredentials(): RouterActionResult = withContext(Dispatchers.IO) {
    val info = ensureDetected()
    val base = info.adminUrl
      ?: return@withContext RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "Router endpoint not detected",
        errorCode = "ROUTER_UNREACHABLE"
      )

    val authType = info.detectedAuthType
    if (authType == RouterAuthType.NONE) {
      return@withContext RouterActionResult(
        status = RouterActionStatus.OK,
        message = "No authentication challenge detected"
      )
    }

    val username = connectionInfo.username?.trim().orEmpty()
    val password = connectionInfo.password.orEmpty()
    if (username.isBlank() || password.isBlank()) {
      return@withContext RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "Username/password required for router auth",
        errorCode = "AUTH_MISSING"
      )
    }

    val result = authenticatedRequest(
      url = base,
      method = "GET",
      username = username,
      password = password,
      authType = authType,
      body = null
    )

    if (result.code in 200..299) {
      RouterActionResult(
        status = RouterActionStatus.OK,
        message = "Credentials validated"
      )
    } else {
      RouterActionResult(
        status = RouterActionStatus.ERROR,
        message = "Credential validation failed (HTTP ${result.code})",
        errorCode = "AUTH_FAILED"
      )
    }
  }

  override suspend fun getDhcpLeases(): Result<List<DhcpLease>> {
    val caps = getCapabilities()
    if (!caps.contains(RouterCapability.DHCP_LEASES_READ)) {
      return Result.failure(UnsupportedOperationException(readOnlyReason()))
    }
    return Result.failure(UnsupportedOperationException(readOnlyReason()))
  }

  override suspend fun setDhcpLeaseName(macOrIp: String, name: String): RouterActionResult {
    return notSupported(readOnlyReason())
  }

  override suspend fun flushDns(): RouterActionResult {
    return notSupported(readOnlyReason())
  }

  override suspend fun setDnsShieldEnabled(enabled: Boolean): RouterActionResult {
    return notSupported(readOnlyReason())
  }

  override suspend fun setFirewallEnabled(enabled: Boolean): RouterActionResult {
    return notSupported(readOnlyReason())
  }

  override suspend fun setVpnEnabled(enabled: Boolean): RouterActionResult {
    return notSupported(readOnlyReason())
  }

  override suspend fun setQosConfig(config: QosConfig): RouterActionResult {
    return notSupported(readOnlyReason())
  }

  override suspend fun setGuestWifiEnabled(enabled: Boolean): RouterActionResult {
    return notSupported(readOnlyReason())
  }

  override suspend fun reboot(): RouterActionResult {
    return notSupported(readOnlyReason())
  }

  private suspend fun ensureDetected(): RouterInfo {
    return detectedInfo ?: detect(connectionInfo)
  }

  private fun probeCapabilities(@Suppress("UNUSED_PARAMETER") info: RouterInfo): Set<RouterCapability> {
    // No verified universal write endpoints in this project.
    // Conservative capability gating: read-only unless verified endpoint map exists.
    return setOf(RouterCapability.READ_INFO)
  }

  private fun readOnlyReason(): String {
    return "No verified API endpoint for this router model. Read-only mode."
  }

  private fun notSupported(reason: String): RouterActionResult {
    return RouterActionResult(
      status = RouterActionStatus.NOT_SUPPORTED,
      message = reason,
      errorCode = "NOT_SUPPORTED"
    )
  }

  private fun candidateBaseUrls(info: RouterConnectionInfo): List<String> {
    val normalizedIp = sanitizeRouterIp(info.routerIp)
    val fromAdmin = info.adminUrl?.trim()?.takeIf { it.isNotBlank() }
    val list = mutableListOf<String>()
    if (fromAdmin != null) {
      list += fromAdmin.trimEnd('/')
    }
    list += "https://$normalizedIp"
    list += "http://$normalizedIp"
    return list.distinct()
  }

  private fun sanitizeRouterIp(raw: String): String {
    return raw
      .trim()
      .removePrefix("http://")
      .removePrefix("https://")
      .substringBefore('/')
  }

  private fun probeEndpoint(baseUrl: String): ProbeResult {
    return runCatching {
      val conn = openConnection(baseUrl, "GET")
      try {
        conn.connect()
        val code = conn.responseCode
        val authHeader = conn.getHeaderField("WWW-Authenticate")
        val server = conn.getHeaderField("Server")
        val authType = authTypeFromChallenge(authHeader)
        val html = readSmallBody(conn)
        val title = extractHtmlTitle(html)

        ProbeResult(
          reachable = code in 200..499,
          authType = authType,
          serverHeader = server,
          htmlTitle = title,
          authHeader = authHeader
        )
      } finally {
        conn.disconnect()
      }
    }.getOrElse {
      ProbeResult(
        reachable = false,
        authType = RouterAuthType.NONE,
        serverHeader = null,
        htmlTitle = null,
        authHeader = null
      )
    }
  }

  private fun detectVendor(serverHeader: String?, htmlTitle: String?): String? {
    val src = listOfNotNull(serverHeader, htmlTitle).joinToString(" ").lowercase(Locale.US)
    return when {
      src.contains("fritz") -> "AVM"
      src.contains("asus") -> "ASUS"
      src.contains("netgear") -> "NETGEAR"
      src.contains("tplink") || src.contains("tp-link") -> "TP-Link"
      src.contains("d-link") -> "D-Link"
      src.contains("ubiquiti") -> "Ubiquiti"
      src.contains("mikrotik") -> "MikroTik"
      src.contains("huawei") -> "Huawei"
      src.contains("cisco") -> "Cisco"
      else -> null
    }
  }

  private fun detectModel(htmlTitle: String?): String? {
    if (htmlTitle.isNullOrBlank()) return null
    return htmlTitle.trim().take(80)
  }

  private fun detectFirmware(htmlTitle: String?): String? {
    if (htmlTitle.isNullOrBlank()) return null
    val regex = Regex("(v|V)?\\d+(\\.\\d+){1,3}")
    return regex.find(htmlTitle)?.value
  }

  private fun authTypeFromChallenge(challenge: String?): RouterAuthType {
    if (challenge.isNullOrBlank()) return RouterAuthType.NONE
    val lower = challenge.lowercase(Locale.US)
    return when {
      lower.contains("digest") -> RouterAuthType.DIGEST
      lower.contains("basic") -> RouterAuthType.BASIC
      else -> RouterAuthType.NONE
    }
  }

  private fun openConnection(url: String, method: String): HttpURLConnection {
    val conn = (URL(url).openConnection() as HttpURLConnection)
    conn.requestMethod = method
    conn.connectTimeout = connectionInfo.timeoutMs
    conn.readTimeout = connectionInfo.timeoutMs
    conn.useCaches = false
    conn.instanceFollowRedirects = false
    return conn
  }

  private fun readSmallBody(conn: HttpURLConnection): String? {
    val input = runCatching {
      if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
    }.getOrNull() ?: return null

    return runCatching {
      input.use { stream ->
        val reader = BufferedReader(InputStreamReader(stream))
        val sb = StringBuilder()
        var total = 0
        while (true) {
          val line = reader.readLine() ?: break
          total += line.length
          if (total > 8192) break
          sb.append(line)
        }
        sb.toString()
      }
    }.getOrNull()
  }

  private fun extractHtmlTitle(html: String?): String? {
    if (html.isNullOrBlank()) return null
    val match = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)
    return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
  }

  private fun authenticatedRequest(
    url: String,
    method: String,
    username: String,
    password: String,
    authType: RouterAuthType,
    body: ByteArray?
  ): HttpResponse {
    val first = openConnection(url, method)
    applyBody(first, body)
    applyAuth(first, authType, username, password, null, method, url)

    try {
      val firstCode = runCatching {
        first.connect()
        first.responseCode
      }.getOrElse { -1 }

      if (firstCode != 401 || authType != RouterAuthType.DIGEST) {
        return HttpResponse(firstCode)
      }

      val challenge = first.getHeaderField("WWW-Authenticate")
      first.disconnect()

      val second = openConnection(url, method)
      applyBody(second, body)
      applyAuth(second, authType, username, password, challenge, method, url)
      return try {
        second.connect()
        HttpResponse(second.responseCode)
      } finally {
        second.disconnect()
      }
    } finally {
      runCatching { first.disconnect() }
    }
  }

  private fun applyBody(conn: HttpURLConnection, body: ByteArray?) {
    if (body == null) return
    conn.doOutput = true
    conn.setFixedLengthStreamingMode(body.size)
    conn.outputStream.use { out ->
      out.write(body)
      out.flush()
    }
  }

  private fun applyAuth(
    conn: HttpURLConnection,
    authType: RouterAuthType,
    username: String,
    password: String,
    digestChallenge: String?,
    method: String,
    url: String
  ) {
    when (authType) {
      RouterAuthType.NONE -> Unit
      RouterAuthType.BASIC -> {
        val credential = "$username:$password".encodeToByteArray().toBase64()
        conn.setRequestProperty("Authorization", "Basic $credential")
      }
      RouterAuthType.DIGEST -> {
        val challenge = digestChallenge ?: return
        val header = buildDigestHeader(challenge, username, password, method, url) ?: return
        conn.setRequestProperty("Authorization", header)
      }
    }
  }

  private fun buildDigestHeader(
    challenge: String,
    username: String,
    password: String,
    method: String,
    url: String
  ): String? {
    val map = parseDigestChallenge(challenge)
    val realm = map["realm"] ?: return null
    val nonce = map["nonce"] ?: return null
    val qop = map["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" } ?: "auth"
    val opaque = map["opaque"]

    val uri = URL(url).path.ifBlank { "/" }
    val nc = "00000001"
    val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)

    val ha1 = md5("$username:$realm:$password")
    val ha2 = md5("$method:$uri")
    val response = md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")

    val parts = mutableListOf(
      "Digest username=\"$username\"",
      "realm=\"$realm\"",
      "nonce=\"$nonce\"",
      "uri=\"$uri\"",
      "qop=$qop",
      "nc=$nc",
      "cnonce=\"$cnonce\"",
      "response=\"$response\""
    )
    if (!opaque.isNullOrBlank()) {
      parts += "opaque=\"$opaque\""
    }
    return parts.joinToString(", ")
  }

  private fun parseDigestChallenge(challenge: String): Map<String, String> {
    val cleaned = challenge.removePrefix("Digest").trim()
    val regex = Regex("(\\w+)=\\\"?([^,\\\"]+)\\\"?")
    return regex.findAll(cleaned).associate { it.groupValues[1].lowercase(Locale.US) to it.groupValues[2] }
  }

  private fun md5(value: String): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { "%02x".format(it) }
  }

  private fun ByteArray.toBase64(): String {
    return android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
  }

  private data class ProbeResult(
    val reachable: Boolean,
    val authType: RouterAuthType,
    val serverHeader: String?,
    val htmlTitle: String?,
    val authHeader: String?
  )

  private data class HttpResponse(
    val code: Int
  )
}
