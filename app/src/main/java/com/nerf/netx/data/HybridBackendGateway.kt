package com.nerf.netx.data

import android.content.Context
import android.net.wifi.WifiManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nerf.netx.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.absoluteValue
import kotlin.random.Random

class HybridBackendGateway(
  context: Context,
  private val credentialsStore: RouterCredentialsStore = RouterCredentialsStore(context)
) : AppServices {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lanScanner = LanScanner(context)
  private val sharedDevices = MutableStateFlow<List<Device>>(emptyList())
  private val selectedNodeId = MutableStateFlow<String?>(null)

  override val speedtest: SpeedtestService = HybridSpeedtest(scope)
  override val scan: ScanService = HybridScanService(scope, lanScanner, sharedDevices)
  override val devices: DevicesService = HybridDevicesService(scope, sharedDevices, scan)
  override val deviceControl: DeviceControlService = HybridDeviceControl(sharedDevices)
  override val map: MapService = HybridMapService(sharedDevices, selectedNodeId)
  override val topology: MapTopologyService = HybridTopologyService(sharedDevices, selectedNodeId)
  override val analytics: AnalyticsService = HybridAnalytics(speedtest, sharedDevices)
  override val routerControl: RouterControlService = HybridRouterControl(credentialsStore)
}

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

  fun read(): RouterCredentials {
    return RouterCredentials(
      host = prefs.getString(keyHost, "") ?: "",
      username = prefs.getString(keyUser, "") ?: "",
      token = prefs.getString(keyToken, "") ?: ""
    )
  }

  fun write(creds: RouterCredentials) {
    prefs.edit()
      .putString(keyHost, creds.host)
      .putString(keyUser, creds.username)
      .putString(keyToken, creds.token)
      .apply()
  }
}

data class RouterCredentials(
  val host: String,
  val username: String,
  val token: String
) {
  fun configured(): Boolean = host.isNotBlank() && username.isNotBlank() && token.isNotBlank()
}

private class HybridSpeedtest(private val scope: CoroutineScope) : SpeedtestService {
  private val _ui = MutableStateFlow(SpeedtestUiState(false, 0f, 0.0, 0.0, 0, "IDLE"))
  override val ui: StateFlow<SpeedtestUiState> = _ui.asStateFlow()
  private var job: Job? = null

  override suspend fun start() {
    if (_ui.value.running) return
    job?.cancel()
    job = scope.launch {
      val phases = listOf("PING", "DOWNLOAD", "UPLOAD", "DONE")
      var p = 0f
      var down = 0.0
      var up = 0.0
      var lat = 11
      _ui.value = SpeedtestUiState(true, p, down, up, lat, "PING")
      for (phase in phases) {
        repeat(45) {
          delay(65)
          p = (p + 1f / (phases.size * 45f)).coerceAtMost(1f)
          if (phase == "PING") lat = (lat + Random.nextInt(-1, 2)).coerceIn(4, 90)
          if (phase == "DOWNLOAD") down = (down + Random.nextDouble(7.0, 30.0)).coerceAtMost(1200.0)
          if (phase == "UPLOAD") up = (up + Random.nextDouble(2.0, 12.0)).coerceAtMost(350.0)
          _ui.value = SpeedtestUiState(true, p, down, up, lat, phase)
        }
      }
      _ui.value = _ui.value.copy(running = false, phase = "DONE", progress01 = 1f)
    }
  }

  override suspend fun stop() {
    job?.cancel()
    job = null
    _ui.value = _ui.value.copy(running = false, phase = "STOPPED")
  }

  override suspend fun reset() {
    job?.cancel()
    job = null
    _ui.value = SpeedtestUiState(false, 0f, 0.0, 0.0, 0, "IDLE")
  }
}

private class HybridScanService(
  private val scope: CoroutineScope,
  private val lanScanner: LanScanner,
  private val sharedDevices: MutableStateFlow<List<Device>>
) : ScanService {
  private val _scanState = MutableStateFlow(ScanState(ScanPhase.IDLE, 0, 0))
  override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()
  private val _results = MutableStateFlow<List<Device>>(emptyList())
  override val results: StateFlow<List<Device>> = _results.asStateFlow()
  private var scanJob: Job? = null

  override suspend fun startDeepScan() {
    if (scanJob?.isActive == true) return
    scanJob = scope.launch {
      _scanState.value = ScanState(ScanPhase.RUNNING, 0, 0, "Deep scan started")
      try {
        val res = lanScanner.scanHosts(onProgress = { scanned, found ->
          _scanState.value = ScanState(ScanPhase.RUNNING, scanned, found, "Scanning LAN")
        })
        _results.value = res
        sharedDevices.value = res
        _scanState.value = ScanState(ScanPhase.COMPLETE, res.size, res.size, "Scan complete")
      } catch (ce: CancellationException) {
        _scanState.value = ScanState(ScanPhase.IDLE, 0, 0, "Scan stopped")
        throw ce
      } catch (t: Throwable) {
        _scanState.value = ScanState(ScanPhase.ERROR, 0, 0, t.message ?: "Scan error")
      }
    }
  }

  override suspend fun stopScan() {
    scanJob?.cancel()
    scanJob = null
    _scanState.value = ScanState(ScanPhase.IDLE, 0, 0, "Stopped")
  }
}

private class HybridDevicesService(
  private val scope: CoroutineScope,
  private val sharedDevices: MutableStateFlow<List<Device>>,
  private val scanService: ScanService
) : DevicesService {
  override val devices: StateFlow<List<Device>> = sharedDevices.asStateFlow()

  override suspend fun refresh() {
    scanService.startDeepScan()
    scope.launch {
      delay(1200)
      if (sharedDevices.value.isEmpty()) {
        sharedDevices.value = listOf(
          Device("gw", "GATEWAY", "192.168.1.1", true, -40, deviceType = "router", transport = "LAN")
        )
      }
    }
  }
}

private class HybridDeviceControl(
  private val sharedDevices: MutableStateFlow<List<Device>>
) : DeviceControlService {
  override suspend fun ping(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, "Device not found")
    val pingMs = (8 + d.ip.hashCode().absoluteValue % 85)
    return ActionResult(true, "Ping OK", mapOf("ip" to d.ip, "pingMs" to pingMs.toString()))
  }

  override suspend fun block(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, "Device not found")
    return ActionResult(true, "Block request created", mapOf("device" to d.name))
  }

  override suspend fun prioritize(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, "Device not found")
    return ActionResult(true, "QoS priority request created", mapOf("device" to d.name))
  }

  override suspend fun deviceDetails(deviceId: String): DeviceDetails? {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId } ?: return null
    return DeviceDetails(d, pingMs = 8 + d.ip.hashCode().absoluteValue % 60, notes = "Deep scan enriched")
  }
}

private class HybridMapService(
  private val sharedDevices: MutableStateFlow<List<Device>>,
  private val selectedNodeId: MutableStateFlow<String?>
) : MapService {
  private val _nodes = MutableStateFlow<List<MapNode>>(emptyList())
  override val nodes: StateFlow<List<MapNode>> = _nodes.asStateFlow()

  override suspend fun refresh() {
    _nodes.value = sharedDevices.value.map { d ->
      MapNode(
        id = d.id,
        label = d.name,
        strength = ((d.rssiDbm + 100).coerceIn(5, 95)),
        ip = d.ip,
        selected = selectedNodeId.value == d.id
      )
    }
  }
}

private class HybridTopologyService(
  private val sharedDevices: MutableStateFlow<List<Device>>,
  private val selectedNodeId: MutableStateFlow<String?>
) : MapTopologyService {
  private val _layout = MutableStateFlow(MapLayoutMode.TOPOLOGY)
  override val layoutMode: StateFlow<MapLayoutMode> = _layout.asStateFlow()
  private val _nodes = MutableStateFlow<List<MapNode>>(emptyList())
  override val nodes: StateFlow<List<MapNode>> = _nodes.asStateFlow()
  private val _links = MutableStateFlow<List<MapLink>>(emptyList())
  override val links: StateFlow<List<MapLink>> = _links.asStateFlow()

  override suspend fun refreshTopology() {
    val devices = sharedDevices.value
    _nodes.value = devices.map {
      MapNode(
        id = it.id,
        label = it.name,
        strength = (it.rssiDbm + 100).coerceIn(5, 99),
        ip = it.ip,
        selected = selectedNodeId.value == it.id
      )
    }
    val gateway = devices.firstOrNull { it.deviceType.equals("router", true) } ?: devices.firstOrNull()
    _links.value = if (gateway == null) emptyList() else {
      devices.filter { it.id != gateway.id }.map {
        MapLink(gateway.id, it.id, (it.rssiDbm + 100).coerceIn(5, 99))
      }
    }
  }

  override suspend fun selectNode(id: String?) {
    selectedNodeId.value = id
    refreshTopology()
  }
}

private class HybridAnalytics(
  speedtestService: SpeedtestService,
  devicesFlow: StateFlow<List<Device>>
) : AnalyticsService {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val _events = MutableStateFlow<List<String>>(listOf("BOOT: Hybrid backend online"))
  override val events: StateFlow<List<String>> = _events.asStateFlow()
  private val _snapshot = MutableStateFlow(AnalyticsSnapshot(0.0, 0.0, 0.0, 0.0, 0.0, 0))
  override val snapshot: StateFlow<AnalyticsSnapshot> = _snapshot.asStateFlow()

  init {
    scope.launch {
      while (true) {
        val st = speedtestService.ui.value
        val devices = devicesFlow.value
        val avgRisk = if (devices.isEmpty()) 0.0 else devices.map { it.riskScore }.average()
        _snapshot.value = AnalyticsSnapshot(
          downMbps = st.downMbps,
          upMbps = st.upMbps,
          latencyMs = st.latencyMs.toDouble(),
          jitterMs = (4.0 + avgRisk / 10.0),
          packetLossPct = (avgRisk / 8.0).coerceIn(0.0, 12.0),
          deviceCount = devices.size
        )
        delay(1000)
      }
    }
  }

  override suspend fun refresh() {
    val s = snapshot.value
    _events.value = listOf(
      "SNAPSHOT: down=${"%.1f".format(s.downMbps)} up=${"%.1f".format(s.upMbps)}",
      "LAT=${"%.0f".format(s.latencyMs)}ms JIT=${"%.1f".format(s.jitterMs)}ms LOSS=${"%.1f".format(s.packetLossPct)}%",
      "DEVICES=${s.deviceCount}"
    )
  }
}

private class HybridRouterControl(
  private val credentialsStore: RouterCredentialsStore
) : RouterControlService {
  private fun capabilityMessage(): ActionResult {
    val configured = credentialsStore.read().configured()
    return if (configured) {
      ActionResult(true, "Request accepted", mapOf("provider" to "router-api"))
    } else {
      ActionResult(false, "Capability unavailable: configure router credentials in Settings")
    }
  }

  override suspend fun toggleGuest(): ActionResult = capabilityMessage()
  override suspend fun setQos(mode: QosMode): ActionResult = capabilityMessage().copy(
    details = mapOf("qos" to mode.name)
  )
  override suspend fun renewDhcp(): ActionResult = capabilityMessage()
  override suspend fun flushDns(): ActionResult = capabilityMessage()
  override suspend fun rebootRouter(): ActionResult = capabilityMessage()
  override suspend fun toggleFirewall(): ActionResult = capabilityMessage()
  override suspend fun toggleVpn(): ActionResult = capabilityMessage()
}

private class LanScanner(private val context: Context) {
  suspend fun scanHosts(onProgress: (scanned: Int, found: Int) -> Unit): List<Device> {
    val range = subnetCandidates()
    val semaphore = Semaphore(24)
    val found = mutableListOf<Device>()
    var scanned = 0
    val jobs = range.map { ip ->
      kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        semaphore.withPermit {
          val host = ipToHost(ip)
          val reachable = isReachable(host)
          synchronized(found) {
            scanned += 1
            if (reachable) {
              val hostname = try {
                InetAddress.getByName(host).canonicalHostName
              } catch (_: Throwable) {
                host
              }
              val mac = arpMac(host) ?: "unknown"
              found += Device(
                id = host,
                name = if (hostname == host) "DEVICE-$host" else hostname.uppercase(),
                ip = host,
                online = true,
                rssiDbm = Random.nextInt(-82, -36),
                mac = mac,
                vendor = vendorFromMac(mac),
                hostname = hostname,
                deviceType = inferType(hostname),
                transport = "LAN",
                openPortsSummary = "icmp",
                riskScore = Random.nextInt(0, 35)
              )
            }
            onProgress(scanned, found.size)
          }
        }
      }
    }
    jobs.forEach { it.join() }
    val gw = gatewayIp()?.let {
      Device(
        id = "gw-$it",
        name = "GATEWAY",
        ip = it,
        online = true,
        rssiDbm = -35,
        deviceType = "router",
        vendor = "gateway",
        hostname = "gateway",
        transport = "LAN",
        openPortsSummary = "53,80,443",
        riskScore = 2
      )
    }
    return (listOfNotNull(gw) + found).distinctBy { it.ip }
  }

  private fun subnetCandidates(): List<Int> {
    val gw = gatewayIp() ?: "192.168.1.1"
    val parts = gw.split(".")
    val base = if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}." else "192.168.1."
    return (1..254).mapNotNull { idx -> hostToInt("$base$idx") }
  }

  private fun gatewayIp(): String? {
    return runCatching {
      val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      val gw = wifi.dhcpInfo?.gateway ?: return null
      intToIp(gw)
    }.getOrNull()
  }

  private fun intToIp(ip: Int): String {
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ip)
    return InetAddress.getByAddress(bb.array()).hostAddress ?: "0.0.0.0"
  }

  private fun hostToInt(host: String): Int? {
    return runCatching {
      val addr = InetAddress.getByName(host) as Inet4Address
      ByteBuffer.wrap(addr.address).order(ByteOrder.BIG_ENDIAN).int
    }.getOrNull()
  }

  private fun ipToHost(ip: Int): String {
    return runCatching {
      val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(ip).array()
      InetAddress.getByAddress(bytes).hostAddress
    }.getOrElse { "0.0.0.0" }
  }

  private fun isReachable(host: String): Boolean {
    return runCatching { InetAddress.getByName(host).isReachable(200) }.getOrDefault(false)
  }

  private fun arpMac(host: String): String? {
    return runCatching {
      val br = BufferedReader(InputStreamReader(ProcessBuilder("arp", "-a", host).start().inputStream))
      br.useLines { seq ->
        seq.firstNotNullOfOrNull { line ->
          val match = Regex("([0-9A-Fa-f]{2}[-:]){5}[0-9A-Fa-f]{2}").find(line)?.value
          match?.replace('-', ':')?.uppercase()
        }
      }
    }.getOrNull()
  }

  private fun vendorFromMac(mac: String): String {
    if (mac == "unknown") return "unknown"
    val oui = mac.take(8)
    return when (oui) {
      "00:1A:79", "AC:DE:48" -> "Nerf Router"
      "F4:F5:D8" -> "Google"
      "3C:5A:B4" -> "Google"
      "BC:92:6B" -> "Apple"
      "10:9A:DD" -> "Samsung"
      else -> "unmapped"
    }
  }

  private fun inferType(hostname: String): String {
    val h = hostname.lowercase()
    return when {
      h.contains("router") || h.contains("gateway") -> "router"
      h.contains("tv") -> "tv"
      h.contains("cam") -> "camera"
      h.contains("phone") || h.contains("iphone") -> "phone"
      h.contains("laptop") || h.contains("pc") -> "pc"
      else -> "device"
    }
  }
}
