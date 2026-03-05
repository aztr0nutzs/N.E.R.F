package com.nerf.netx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.nerf.netx.domain.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

data class ReachabilityResult(
  val reachable: Boolean,
  val latencyMs: Int?,
  val methodUsed: String,
  val timestampEpochMs: Long = System.currentTimeMillis()
)

data class ScanNetworkPlan(
  val selfIp: String?,
  val gatewayIp: String?,
  val prefixLength: Int,
  val targets: List<String>,
  val warning: String? = null
)

class RealLanScanner(private val context: Context) {
  private val tcpFallbackPorts = intArrayOf(443, 80, 53)
  private val probeTimeoutMs = 300

  suspend fun scanHosts(
    onStarted: (targetsPlanned: Int, warning: String?) -> Unit,
    onProgress: (probesSent: Int, devicesFound: Int, targetsPlanned: Int) -> Unit,
    onDevice: (device: Device, updated: Boolean) -> Unit
  ): List<Device> = coroutineScope {
    val plan = resolvePlan()
    onStarted(plan.targets.size, plan.warning)

    val devicesByIp = linkedMapOf<String, Device>()
    val semaphore = Semaphore(64)
    var probesSent = 0
    var devicesFound = 0

    val jobs = plan.targets.map { host ->
      launch(Dispatchers.IO) {
        delay(6)
        semaphore.withPermit {
          coroutineContext.ensureActive()
          val probe = probeReachability(host)

          synchronized(devicesByIp) {
            probesSent += 1
          }

          if (probe.reachable) {
            val resolved = buildDevice(
              host = host,
              plan = plan,
              probe = probe,
              existing = synchronized(devicesByIp) { devicesByIp[host] }
            )
            val updated = synchronized(devicesByIp) {
              val had = devicesByIp.containsKey(host)
              devicesByIp[host] = resolved
              if (!had) devicesFound += 1
              had
            }
            onDevice(resolved, updated)
          }

          if (probesSent % 24 == 0 || probesSent == plan.targets.size) {
            val arp = readArpTable()
            if (arp.isNotEmpty()) {
              synchronized(devicesByIp) {
                val updates = devicesByIp.values.mapNotNull { current ->
                  val arpMac = arp[current.ip]
                  if (arpMac != null && current.mac == "unknown") {
                    val patched = current.copy(
                      mac = arpMac,
                      vendor = vendorFromMac(arpMac),
                      lastSeenEpochMs = System.currentTimeMillis()
                    )
                    devicesByIp[current.ip] = patched
                    patched
                  } else {
                    null
                  }
                }
                updates.forEach { onDevice(it, true) }
              }
            }
          }

          val progressSnapshot = synchronized(devicesByIp) {
            Triple(probesSent, devicesFound, plan.targets.size)
          }
          onProgress(progressSnapshot.first, progressSnapshot.second, progressSnapshot.third)
        }
      }
    }

    jobs.forEach { it.join() }
    synchronized(devicesByIp) { devicesByIp.values.sortedBy { ipSortKey(it.ip) } }
  }

  suspend fun probeReachability(host: String): ReachabilityResult {
    val icmp = probeIcmp(host, probeTimeoutMs)
    if (icmp.reachable) return icmp

    tcpFallbackPorts.forEach { port ->
      val tcp = probeTcp(host, port, probeTimeoutMs)
      if (tcp.reachable) return tcp
    }

    return ReachabilityResult(
      reachable = false,
      latencyMs = null,
      methodUsed = "NONE"
    )
  }

  private suspend fun buildDevice(
    host: String,
    plan: ScanNetworkPlan,
    probe: ReachabilityResult,
    existing: Device?
  ): Device {
    val hostname = resolveHostname(host)
    val isGateway = plan.gatewayIp != null && host == plan.gatewayIp
    val arp = readArpTable()
    val mac = arp[host] ?: existing?.mac ?: "unknown"
    val vendor = vendorFromMac(mac)
    val latency = measureLatencyMedian(host, probe)
    val rssi = if (isGateway) readGatewayRssiDbm() else null
    val type = inferDeviceType(hostname, isGateway)
    val name = inferDisplayName(hostname, host, isGateway)
    return Device(
      id = host,
      name = name,
      ip = host,
      online = true,
      rssiDbm = rssi,
      latencyMs = latency,
      reachabilityMethod = probe.methodUsed,
      mac = mac,
      vendor = vendor,
      hostname = hostname,
      deviceType = type,
      isGateway = isGateway,
      lastSeenEpochMs = System.currentTimeMillis(),
      transport = "LAN",
      openPortsSummary = probe.methodUsed,
      riskScore = existing?.riskScore ?: 0
    )
  }

  private suspend fun measureLatencyMedian(host: String, seed: ReachabilityResult): Int? {
    if (!seed.reachable) return null
    val samples = mutableListOf<Int>()
    if (seed.latencyMs != null) samples += seed.latencyMs
    repeat(2) {
      val next = if (seed.methodUsed.startsWith("TCP:")) {
        val port = seed.methodUsed.substringAfter("TCP:").toIntOrNull() ?: 443
        probeTcp(host, port, probeTimeoutMs)
      } else {
        probeIcmp(host, probeTimeoutMs)
      }
      if (next.reachable && next.latencyMs != null) {
        samples += next.latencyMs
      }
    }
    if (samples.isEmpty()) return null
    val sorted = samples.sorted()
    return sorted[sorted.size / 2]
  }

  private fun probeIcmp(host: String, timeoutMs: Int): ReachabilityResult {
    val started = System.nanoTime()
    val reachable = runCatching { InetAddress.getByName(host).isReachable(timeoutMs) }.getOrDefault(false)
    val latency = if (reachable) elapsedMs(started) else null
    return ReachabilityResult(
      reachable = reachable,
      latencyMs = latency,
      methodUsed = if (reachable) "ICMP" else "ICMP_BLOCKED"
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
    val latency = if (reachable) elapsedMs(started) else null
    return ReachabilityResult(
      reachable = reachable,
      latencyMs = latency,
      methodUsed = if (reachable) "TCP:$port" else "TCP_FAIL:$port"
    )
  }

  private fun resolvePlan(): ScanNetworkPlan {
    val fromLink = resolveFromLinkProperties()
    if (fromLink != null) return fromLink

    val fromDhcp = resolveFromDhcpInfo()
    if (fromDhcp != null) return fromDhcp

    val fallbackTargets = (1..254).map { "192.168.1.$it" }
    return ScanNetworkPlan(
      selfIp = null,
      gatewayIp = "192.168.1.1",
      prefixLength = 24,
      targets = fallbackTargets,
      warning = "Unable to resolve active subnet; using 192.168.1.0/24 fallback."
    )
  }

  private fun resolveFromLinkProperties(): ScanNetworkPlan? {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return null
    val active = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(active) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
      !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    ) return null

    val link = cm.getLinkProperties(active) ?: return null
    val ipv4 = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
    val selfIp = ipv4.address.hostAddress ?: return null
    val gatewayIp = link.routes.firstOrNull { it.hasGateway() && it.gateway is Inet4Address }?.gateway?.hostAddress

    return buildPlan(
      selfIp = selfIp,
      prefixLength = ipv4.prefixLength,
      gatewayIp = gatewayIp
    )
  }

  private fun resolveFromDhcpInfo(): ScanNetworkPlan? {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    val dhcp = wifi.dhcpInfo ?: return null
    val selfIp = intToIpLittleEndian(dhcp.ipAddress) ?: return null
    val gateway = intToIpLittleEndian(dhcp.gateway)
    val prefix = netmaskToPrefix(dhcp.netmask).coerceIn(8, 30)
    return buildPlan(selfIp, prefix, gateway)
  }

  private fun buildPlan(selfIp: String, prefixLength: Int, gatewayIp: String?): ScanNetworkPlan {
    val selfLong = ipv4ToLong(selfIp) ?: return ScanNetworkPlan(
      selfIp = selfIp,
      gatewayIp = gatewayIp,
      prefixLength = 24,
      targets = (1..254).map { "192.168.1.$it" },
      warning = "Invalid local IP detected; falling back to /24."
    )
    val boundedPrefix = prefixLength.coerceIn(8, 30)
    val networkMask = maskForPrefix(boundedPrefix)
    val network = selfLong and networkMask
    val broadcast = network or networkMask.inv().and(0xFFFFFFFFL)
    val hostCount = (broadcast - network - 1L).coerceAtLeast(0L)
    val needsCap = hostCount > 254L

    val targets = if (needsCap) {
      val base = selfLong and 0xFFFFFF00
      (1..254).mapNotNull { idx ->
        val ipLong = base + idx
        val ip = longToIpv4(ipLong)
        if (ip == selfIp) null else ip
      }
    } else {
      generateSequence(network + 1L) { prev ->
        val next = prev + 1L
        if (next < broadcast) next else null
      }.mapNotNull { ipLong ->
        val ip = longToIpv4(ipLong)
        if (ip == selfIp) null else ip
      }.toList()
    }

    val warning = if (needsCap) {
      "Subnet /$boundedPrefix is larger than /24. Scan capped to local /24 (${targets.size} hosts)."
    } else {
      null
    }

    return ScanNetworkPlan(
      selfIp = selfIp,
      gatewayIp = gatewayIp,
      prefixLength = boundedPrefix,
      targets = targets,
      warning = warning
    )
  }

  private fun resolveHostname(ip: String): String {
    return runCatching {
      val name = InetAddress.getByName(ip).canonicalHostName
      if (name.isBlank() || name == ip) "unknown" else name
    }.getOrDefault("unknown")
  }

  private fun readArpTable(): Map<String, String> {
    return runCatching {
      val arpFile = File("/proc/net/arp")
      if (!arpFile.exists()) return emptyMap<String, String>()
      arpFile.readLines().drop(1).mapNotNull { line ->
        val cols = line.trim().split(Regex("\\s+"))
        if (cols.size < 4) return@mapNotNull null
        val ip = cols[0]
        val mac = cols[3].replace('-', ':').uppercase()
        if (!mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) || mac == "00:00:00:00:00:00") {
          null
        } else {
          ip to mac
        }
      }.toMap()
    }.getOrElse { emptyMap() }
  }

  private fun inferDisplayName(hostname: String, ip: String, isGateway: Boolean): String {
    if (isGateway) return "GATEWAY"
    if (hostname != "unknown") return hostname.uppercase().take(28)
    val suffix = ip.substringAfterLast('.', "0")
    return "DEVICE-$suffix"
  }

  private fun inferDeviceType(hostname: String, isGateway: Boolean): String {
    if (isGateway) return "router"
    val h = hostname.lowercase()
    return when {
      h.contains("phone") || h.contains("iphone") || h.contains("android") -> "phone"
      h.contains("laptop") || h.contains("desktop") || h.contains("pc") -> "pc"
      h.contains("tv") -> "tv"
      h.contains("cam") -> "camera"
      h.contains("printer") -> "printer"
      else -> "device"
    }
  }

  private fun vendorFromMac(mac: String): String {
    if (mac == "unknown") return "unknown"
    val oui = mac.take(8)
    return when (oui) {
      "F4:F5:D8", "3C:5A:B4", "A4:77:33" -> "Google"
      "BC:92:6B", "D8:96:95", "28:CF:E9" -> "Apple"
      "10:9A:DD", "CC:F9:E4", "40:4E:36" -> "Samsung"
      "00:1A:79", "AC:DE:48" -> "Nerf Router"
      else -> "unknown"
    }
  }

  private fun readGatewayRssiDbm(): Int? {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    val rssi = runCatching { wifi.connectionInfo?.rssi }.getOrNull() ?: return null
    return if (rssi in -120..0) rssi else null
  }

  private fun ipSortKey(ip: String): Long {
    return ipv4ToLong(ip) ?: Long.MAX_VALUE
  }

  private fun elapsedMs(startNs: Long): Int {
    val deltaMs = (System.nanoTime() - startNs) / 1_000_000L
    return max(1L, min(deltaMs, Int.MAX_VALUE.toLong())).toInt()
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
}
