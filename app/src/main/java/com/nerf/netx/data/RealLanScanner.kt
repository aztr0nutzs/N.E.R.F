package com.nerf.netx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.wifi.WifiManager
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.ScanMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

data class ReachabilityResult(
  val reachable: Boolean,
  val methodUsed: String?,
  val successfulPort: Int?,
  val latencyMs: Int?,
  val reason: String?
)

data class ScanNetworkPlan(
  val localIp: String?,
  val gatewayIp: String?,
  val prefixLength: Int,
  val scanRange: List<String>,
  val warning: String? = null
)

data class ScanExecutionResult(
  val devices: List<Device>,
  val metadata: ScanMetadata
)

data class RealLanScannerOverrides(
  val resolvePlan: (() -> ScanNetworkPlan)? = null,
  val probeReachability: (suspend (String) -> ReachabilityResult)? = null,
  val reverseLookupHostname: (suspend (String) -> Pair<String?, String?>)? = null,
  val measureLatencyMedian: (suspend (String, ReachabilityResult) -> Pair<Int?, String?>)? = null,
  val parseArpTable: (() -> Map<String, String>)? = null,
  val lookupVendor: ((String) -> String?)? = null
)

class RealLanScanner(
  private val context: Context,
  private val overrides: RealLanScannerOverrides? = null
) {
  private val tcpFallbackPorts = intArrayOf(443, 80, 53)
  private val probeTimeoutMs = 325
  private val resolverTimeoutMs = 325L
  private val maxConcurrentProbes = 64

  suspend fun scanHosts(
    onStarted: (targetsPlanned: Int, warning: String?, metadata: ScanMetadata) -> Unit,
    onProgress: (probesSent: Int, devicesFound: Int, targetsPlanned: Int) -> Unit,
    onDevice: (device: Device, updated: Boolean) -> Unit
  ): ScanExecutionResult = coroutineScope {
    val startedAt = System.currentTimeMillis()
    val plan = overrides?.resolvePlan?.invoke() ?: resolvePlan()
    val baseMetadata = ScanMetadata(
      localIp = plan.localIp,
      prefixLength = plan.prefixLength,
      gatewayIp = plan.gatewayIp,
      wifiRssi = readWifiRssiDbm(),
      startTime = startedAt,
      warning = plan.warning
    )

    onStarted(plan.scanRange.size, plan.warning, baseMetadata)

    val semaphore = Semaphore(maxConcurrentProbes)
    val devicesByIp = linkedMapOf<String, Device>()
    var probesSent = 0
    var devicesFound = 0

    val jobs = plan.scanRange.map { host ->
      launch(Dispatchers.IO) {
        semaphore.withPermit {
          coroutineContext.ensureActive()
          val probe = probeReachability(host)

          synchronized(devicesByIp) {
            probesSent += 1
          }

          if (probe.reachable) {
            val now = System.currentTimeMillis()
            val unresolved = mutableMapOf<String, String>()
            val hostnameOutcome = reverseLookupHostname(host)
            val hostname = hostnameOutcome.first
            hostnameOutcome.second?.let { unresolved["hostname"] = it }

            val latencyOutcome = measureLatencyMedian(host, probe)
            val isGateway = plan.gatewayIp != null && host == plan.gatewayIp
            val mac = parseArpTable()[host]
            if (mac == null) unresolved["mac"] = "ARP entry not present"
            val vendor = mac?.let { lookupVendor(it) }
            if (mac != null && vendor == null) unresolved["vendor"] = "Unknown OUI"
            if (latencyOutcome.first == null && latencyOutcome.second != null) {
              unresolved["latencyMs"] = latencyOutcome.second
            }

            val finalHostname = hostname
            val type = inferDeviceType(finalHostname, vendor)
            val method = probe.methodUsed
            if (method == null && probe.reason != null) {
              unresolved["methodUsed"] = probe.reason
            }

            val device = Device(
              id = host,
              name = inferDisplayName(finalHostname, host, isGateway),
              ip = host,
              online = true,
              reachable = true,
              rssiDbm = if (isGateway) baseMetadata.wifiRssi else null,
              rssi = if (isGateway) baseMetadata.wifiRssi else null,
              latencyMs = latencyOutcome.first,
              latencyReason = latencyOutcome.second,
              reachabilityMethod = method ?: "N/A",
              methodUsed = method,
              mac = mac ?: "",
              macAddress = mac,
              vendor = vendor ?: "",
              vendorName = vendor,
              hostname = finalHostname ?: "",
              hostName = finalHostname,
              deviceType = type,
              isGateway = isGateway,
              lastSeenEpochMs = now,
              lastSeen = now,
              unresolvedReasons = unresolved,
              transport = "LAN",
              openPortsSummary = when (method) {
                "TCP" -> probe.successfulPort?.let { "TCP:$it" } ?: "TCP"
                "ICMP" -> "ICMP"
                else -> ""
              }
            )

            val updated = synchronized(devicesByIp) {
              val had = devicesByIp.containsKey(host)
              devicesByIp[host] = device
              if (!had) devicesFound += 1
              had
            }
            onDevice(device, updated)
          }

          val progressSnapshot = synchronized(devicesByIp) {
            Triple(probesSent, devicesFound, plan.scanRange.size)
          }
          onProgress(progressSnapshot.first, progressSnapshot.second, progressSnapshot.third)
        }
      }
    }

    jobs.forEach { it.join() }

    val arp = parseArpTable()
    if (arp.isNotEmpty()) {
      synchronized(devicesByIp) {
        devicesByIp.values.toList().forEach { existing ->
          val arpMac = arp[existing.ip]
          if (arpMac != null && existing.macAddress == null) {
            val vendor = lookupVendor(arpMac)
            val unresolved = existing.unresolvedReasons.toMutableMap().apply {
              remove("mac")
              if (vendor != null) remove("vendor") else put("vendor", "Unknown OUI")
            }
            val patched = existing.copy(
              mac = arpMac,
              macAddress = arpMac,
              vendor = vendor ?: "",
              vendorName = vendor,
              unresolvedReasons = unresolved
            )
            devicesByIp[existing.ip] = patched
            onDevice(patched, true)
          }
        }
      }
    }

    val endedAt = System.currentTimeMillis()
    val metadata = baseMetadata.copy(
      endTime = endedAt,
      durationMs = endedAt - startedAt
    )
    ScanExecutionResult(
      devices = synchronized(devicesByIp) { devicesByIp.values.sortedBy { ipv4ToLong(it.ip) ?: Long.MAX_VALUE } },
      metadata = metadata
    )
  }

  suspend fun probeReachability(host: String): ReachabilityResult {
    overrides?.probeReachability?.let { return it(host) }
    val icmp = probeIcmp(host, probeTimeoutMs)
    if (icmp.reachable) {
      return ReachabilityResult(
        reachable = true,
        methodUsed = "ICMP",
        successfulPort = null,
        latencyMs = icmp.latencyMs,
        reason = null
      )
    }

    tcpFallbackPorts.forEach { port ->
      val tcp = probeTcp(host, port, probeTimeoutMs)
      if (tcp.reachable) {
        return ReachabilityResult(
          reachable = true,
          methodUsed = "TCP",
          successfulPort = port,
          latencyMs = tcp.latencyMs,
          reason = null
        )
      }
    }

    return ReachabilityResult(
      reachable = false,
      methodUsed = null,
      successfulPort = null,
      latencyMs = null,
      reason = "ICMP blocked/unreachable and TCP fallback ports [443,80,53] unavailable"
    )
  }

  fun parseArpTable(): Map<String, String> {
    overrides?.parseArpTable?.let { return it() }
    return runCatching {
      val arpFile = File("/proc/net/arp")
      if (!arpFile.exists()) return emptyMap<String, String>()
      arpFile.readLines()
        .drop(1)
        .mapNotNull { line ->
          val cols = line.trim().split(Regex("\\s+"))
          if (cols.size < 4) return@mapNotNull null
          val ip = cols[0]
          val mac = normalizeMac(cols[3])
          if (mac == null || mac == "00:00:00:00:00:00") null else ip to mac
        }
        .toMap()
    }.getOrElse { emptyMap() }
  }

  fun lookupVendor(mac: String): String? {
    overrides?.lookupVendor?.let { return it(mac) }
    val normalized = normalizeMac(mac) ?: return null
    return OuiLookup.lookup(normalized, context)
  }

  private suspend fun measureLatencyMedian(host: String, probe: ReachabilityResult): Pair<Int?, String?> {
    overrides?.measureLatencyMedian?.let { return it(host, probe) }
    if (!probe.reachable) return null to "Host unreachable"

    val samples = mutableListOf<Int>()
    repeat(3) { idx ->
      coroutineContext.ensureActive()
      val sampled = when (probe.methodUsed) {
        "ICMP" -> probeIcmp(host, probeTimeoutMs)
        "TCP" -> {
          val port = probe.successfulPort ?: tcpFallbackPorts.first()
          probeTcp(host, port, probeTimeoutMs)
        }
        else -> null
      }

      if (sampled?.reachable == true && sampled.latencyMs != null) {
        samples += sampled.latencyMs
      }
      if (idx < 2) delay(35)
    }

    if (samples.isEmpty()) {
      return null to "No successful RTT sample (3 attempts)"
    }

    val sorted = samples.sorted()
    return sorted[sorted.size / 2] to null
  }

  private suspend fun reverseLookupHostname(ip: String): Pair<String?, String?> {
    overrides?.reverseLookupHostname?.let { return it(ip) }
    val host = withContext(Dispatchers.IO) {
      withTimeoutOrNull(resolverTimeoutMs) {
        runCatching {
          InetAddress.getByName(ip).canonicalHostName
        }.getOrNull()
      }
    }
    if (host.isNullOrBlank() || host == ip) {
      return null to "Reverse DNS unavailable"
    }
    return host to null
  }

  private fun resolvePlan(): ScanNetworkPlan {
    resolveFromLinkProperties()?.let { return it }
    resolveFromWifiDhcp()?.let { return it }
    resolveFromConnectivityRoutes()?.let { return it }

    val local = discoverLocalIpv4Address()
    if (local != null) {
      return buildPlan(local, 24, null, "Using /24 fallback from local IPv4 address")
    }

    return ScanNetworkPlan(
      localIp = null,
      gatewayIp = null,
      prefixLength = 24,
      scanRange = emptyList(),
      warning = "Unable to determine local subnet"
    )
  }

  private fun resolveFromLinkProperties(): ScanNetworkPlan? {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return null
    val active = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(active) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
      return null
    }

    val link = cm.getLinkProperties(active) ?: return null
    val ipv4 = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
    val localIp = ipv4.address.hostAddress ?: return null
    val gatewayIp = defaultGatewayFromRoutes(link.routes) ?: readGatewayFromDhcp()
    return buildPlan(localIp, ipv4.prefixLength, gatewayIp, null)
  }

  private fun resolveFromWifiDhcp(): ScanNetworkPlan? {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    val dhcp = wifi.dhcpInfo ?: return null
    val localIp = intToIpLittleEndian(dhcp.ipAddress) ?: return null
    val gatewayIp = intToIpLittleEndian(dhcp.gateway)
    val prefix = netmaskToPrefix(dhcp.netmask).coerceIn(8, 30)
    return buildPlan(localIp, prefix, gatewayIp, null)
  }

  private fun resolveFromConnectivityRoutes(): ScanNetworkPlan? {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return null

    val candidate = cm.allNetworks
      .asSequence()
      .mapNotNull { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
          return@mapNotNull null
        }
        val link = cm.getLinkProperties(network) ?: return@mapNotNull null
        val ipv4 = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return@mapNotNull null
        Triple(network, link.routes, ipv4)
      }
      .firstOrNull() ?: return null

    val localIp = candidate.third.address.hostAddress ?: return null
    val prefix = candidate.third.prefixLength
    val gatewayIp = defaultGatewayFromRoutes(candidate.second) ?: readGatewayFromDhcp()
    return buildPlan(localIp, prefix, gatewayIp, null)
  }

  private fun buildPlan(localIp: String, prefixLength: Int, gatewayIp: String?, warning: String?): ScanNetworkPlan {
    val localLong = ipv4ToLong(localIp)
    if (localLong == null) {
      return ScanNetworkPlan(
        localIp = localIp,
        gatewayIp = gatewayIp,
        prefixLength = 24,
        scanRange = emptyList(),
        warning = "Invalid local IPv4 address"
      )
    }

    val safePrefix = prefixLength.coerceIn(8, 30)
    val mask = maskForPrefix(safePrefix)
    val network = localLong and mask
    val broadcast = network or mask.inv().and(0xFFFFFFFFL)

    val scanRange = mutableListOf<String>()
    var cur = network + 1L
    while (cur < broadcast) {
      val ip = longToIpv4(cur)
      if (ip != null && ip != localIp) {
        scanRange += ip
      }
      cur += 1L
    }

    return ScanNetworkPlan(
      localIp = localIp,
      gatewayIp = gatewayIp,
      prefixLength = safePrefix,
      scanRange = scanRange,
      warning = warning
    )
  }

  private fun defaultGatewayFromRoutes(routes: List<RouteInfo>): String? {
    return routes.firstOrNull { route ->
      val gateway = route.gateway
      val isIpv4Gateway = gateway is Inet4Address
      val isDefault = route.destination == null || route.destination.prefixLength == 0
      isIpv4Gateway && isDefault
    }?.gateway?.hostAddress
  }

  private fun readGatewayFromDhcp(): String? {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    val dhcp = wifi.dhcpInfo ?: return null
    return intToIpLittleEndian(dhcp.gateway)
  }

  private fun discoverLocalIpv4Address(): String? {
    return runCatching {
      Collections.list(NetworkInterface.getNetworkInterfaces())
        .filter { it.isUp && !it.isLoopback }
        .flatMap { Collections.list(it.inetAddresses) }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
    }.getOrNull()
  }

  private fun inferDisplayName(hostname: String?, ip: String, isGateway: Boolean): String {
    if (isGateway) return "GATEWAY"
    if (!hostname.isNullOrBlank()) return hostname.take(32)
    return "Device ${ip.substringAfterLast('.', "0")}"
  }

  private fun inferDeviceType(hostname: String?, vendor: String?): String {
    return BackendMath.inferDeviceType(hostname, vendor)
  }

  private fun probeIcmp(host: String, timeoutMs: Int): ReachabilityResult {
    val started = System.nanoTime()
    val reachable = runCatching {
      InetAddress.getByName(host).isReachable(timeoutMs)
    }.getOrDefault(false)
    return ReachabilityResult(
      reachable = reachable,
      methodUsed = if (reachable) "ICMP" else null,
      successfulPort = null,
      latencyMs = if (reachable) elapsedMs(started) else null,
      reason = if (reachable) null else "ICMP unreachable or blocked"
    )
  }

  private fun probeTcp(host: String, port: Int, timeoutMs: Int): ReachabilityResult {
    val started = System.nanoTime()
    val reachable = runCatching {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), timeoutMs)
      }
      true
    }.getOrDefault(false)

    return ReachabilityResult(
      reachable = reachable,
      methodUsed = if (reachable) "TCP" else null,
      successfulPort = if (reachable) port else null,
      latencyMs = if (reachable) elapsedMs(started) else null,
      reason = if (reachable) null else "TCP $port connect failed"
    )
  }

  private fun readWifiRssiDbm(): Int? {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    val rssi = runCatching { wifi.connectionInfo?.rssi }.getOrNull() ?: return null
    return if (rssi in -120..0) rssi else null
  }

  private fun normalizeMac(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val mac = raw.replace('-', ':').uppercase()
    if (!macRegex.matches(mac)) return null
    return mac
  }

  private fun elapsedMs(startNs: Long): Int {
    val elapsed = (System.nanoTime() - startNs) / 1_000_000L
    return max(1L, min(elapsed, Int.MAX_VALUE.toLong())).toInt()
  }

  private fun netmaskToPrefix(mask: Int): Int {
    var value = mask
    var bits = 0
    repeat(32) {
      if ((value and 1) == 1) bits += 1
      value = value ushr 1
    }
    return bits
  }

  private fun maskForPrefix(prefix: Int): Long {
    if (prefix <= 0) return 0L
    return (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
  }

  private fun ipv4ToLong(ip: String): Long? {
    return runCatching {
      val addr = InetAddress.getByName(ip)
      if (addr !is Inet4Address) return null
      addr.address.fold(0L) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF).toLong() }
    }.getOrNull()
  }

  private fun longToIpv4(value: Long): String? {
    val bytes = byteArrayOf(
      ((value shr 24) and 0xFF).toByte(),
      ((value shr 16) and 0xFF).toByte(),
      ((value shr 8) and 0xFF).toByte(),
      (value and 0xFF).toByte()
    )
    return runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull()
  }

  private fun intToIpLittleEndian(value: Int): String? {
    return runCatching {
      val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
      InetAddress.getByAddress(bb.array()).hostAddress
    }.getOrNull()
  }

  private companion object {
    val macRegex = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

  }
}
