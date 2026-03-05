package com.nerf.netx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nerf.netx.domain.ActionResult
import com.nerf.netx.domain.AnalyticsService
import com.nerf.netx.domain.AnalyticsSnapshot
import com.nerf.netx.domain.AppServices
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.DeviceControlService
import com.nerf.netx.domain.DeviceDetails
import com.nerf.netx.domain.DevicesService
import com.nerf.netx.domain.MapLayoutMode
import com.nerf.netx.domain.MapLink
import com.nerf.netx.domain.MapNode
import com.nerf.netx.domain.MapService
import com.nerf.netx.domain.MapTopologyService
import com.nerf.netx.domain.MeasurementMode
import com.nerf.netx.domain.QosMode
import com.nerf.netx.domain.RouterControlService
import com.nerf.netx.domain.RouterInfoResult
import com.nerf.netx.domain.ScanEvent
import com.nerf.netx.domain.ScanPhase
import com.nerf.netx.domain.ScanService
import com.nerf.netx.domain.ScanState
import com.nerf.netx.domain.ServiceStatus
import com.nerf.netx.domain.SpeedtestService
import com.nerf.netx.domain.SpeedtestUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max

class HybridBackendGateway(
  context: Context,
  private val credentialsStore: RouterCredentialsStore = RouterCredentialsStore(context)
) : AppServices {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lanScanner = RealLanScanner(context)
  private val sharedDevices = MutableStateFlow<List<Device>>(emptyList())
  private val selectedNodeId = MutableStateFlow<String?>(null)

  override val speedtest: SpeedtestService = HybridSpeedtest(scope)
  override val scan: ScanService = HybridScanService(scope, lanScanner, sharedDevices)
  override val devices: DevicesService = HybridDevicesService(sharedDevices, scan)
  override val deviceControl: DeviceControlService = HybridDeviceControl(sharedDevices, lanScanner)
  override val map: MapService = HybridMapService(sharedDevices, selectedNodeId)
  override val topology: MapTopologyService = HybridTopologyService(sharedDevices, selectedNodeId)
  override val analytics: AnalyticsService = HybridAnalytics(speedtest, sharedDevices, scan)
  override val routerControl: RouterControlService = HybridRouterControl(context, credentialsStore)
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
  private val _ui = MutableStateFlow(
    SpeedtestUiState(
      running = false,
      progress01 = 0f,
      downMbps = null,
      upMbps = null,
      latencyMs = null,
      phase = "IDLE",
      mode = MeasurementMode.NOT_AVAILABLE,
      status = ServiceStatus.IDLE,
      message = "Download/upload benchmark endpoints are not configured."
    )
  )
  override val ui: StateFlow<SpeedtestUiState> = _ui.asStateFlow()
  private var job: Job? = null

  override suspend fun start() {
    if (_ui.value.running) return
    job?.cancel()
    job = scope.launch {
      _ui.value = _ui.value.copy(
        running = true,
        progress01 = 0.1f,
        phase = "PING",
        status = ServiceStatus.RUNNING,
        message = "Running best-effort latency check only; throughput endpoints not available."
      )

      val ping = measureInternetPingMs()
      _ui.value = _ui.value.copy(
        running = false,
        progress01 = 1f,
        latencyMs = ping,
        downMbps = null,
        upMbps = null,
        phase = "DONE",
        mode = MeasurementMode.NOT_AVAILABLE,
        status = ServiceStatus.NOT_SUPPORTED,
        message = "Speed throughput is NOT_SUPPORTED until real test endpoints are configured."
      )
    }
  }

  override suspend fun stop() {
    job?.cancel()
    job = null
    _ui.value = _ui.value.copy(running = false, phase = "STOPPED", status = ServiceStatus.IDLE)
  }

  override suspend fun reset() {
    job?.cancel()
    job = null
    _ui.value = SpeedtestUiState(
      running = false,
      progress01 = 0f,
      downMbps = null,
      upMbps = null,
      latencyMs = null,
      phase = "IDLE",
      mode = MeasurementMode.NOT_AVAILABLE,
      status = ServiceStatus.IDLE,
      message = "Download/upload benchmark endpoints are not configured."
    )
  }

  private fun measureInternetPingMs(): Int? {
    val samples = mutableListOf<Int>()
    repeat(3) {
      val started = System.nanoTime()
      val ok = runCatching {
        Socket().use { socket ->
          socket.connect(InetSocketAddress("1.1.1.1", 443), 350)
        }
        true
      }.getOrDefault(false)
      if (ok) {
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).toInt()
        samples += max(1, elapsed)
      }
    }
    if (samples.isEmpty()) return null
    val sorted = samples.sorted()
    return sorted[sorted.size / 2]
  }
}

private class HybridScanService(
  private val scope: CoroutineScope,
  private val lanScanner: RealLanScanner,
  private val sharedDevices: MutableStateFlow<List<Device>>
) : ScanService {
  private val _scanState = MutableStateFlow(ScanState(ScanPhase.IDLE, 0, 0))
  override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()
  private val _results = MutableStateFlow<List<Device>>(emptyList())
  override val results: StateFlow<List<Device>> = _results.asStateFlow()
  private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 128)
  override val events: SharedFlow<ScanEvent> = _events.asSharedFlow()
  private var scanJob: Job? = null
  private val knownDevicesByIp = linkedMapOf<String, Device>()

  override suspend fun startDeepScan() {
    if (scanJob?.isActive == true) return
    scanJob = scope.launch {
      val foundIps = linkedSetOf<String>()
      var targetsPlanned = 0
      try {
        lanScanner.scanHosts(
          onStarted = { planned, warning, metadata ->
            targetsPlanned = planned
            _scanState.value = ScanState(
              phase = ScanPhase.RUNNING,
              scannedHosts = 0,
              discoveredHosts = 0,
              message = warning ?: "Deep scan started"
            )
            _events.tryEmit(ScanEvent.ScanStarted(targetsPlanned = planned, metadata = metadata))
          },
          onProgress = { probesSent, devicesFound, planned ->
            _scanState.value = ScanState(
              phase = ScanPhase.RUNNING,
              scannedHosts = probesSent,
              discoveredHosts = devicesFound,
              message = "Scanning LAN"
            )
            _events.tryEmit(
              ScanEvent.ScanProgress(
                targetsPlanned = planned,
                probesSent = probesSent,
                devicesFound = devicesFound
              )
            )
          },
          onDevice = { device, updated ->
            val now = System.currentTimeMillis()
            val enriched = device.copy(
              online = true,
              reachable = true,
              deviceType = inferDeviceType(device.hostName),
              lastSeenEpochMs = now,
              lastSeen = now
            )
            synchronized(knownDevicesByIp) {
              foundIps += enriched.ip
              knownDevicesByIp[enriched.ip] = enriched
              val snapshot = knownDevicesByIp.values.sortedBy { it.ip }
              _results.value = snapshot
              sharedDevices.value = snapshot
            }
            _events.tryEmit(ScanEvent.ScanDevice(device = enriched, updated = updated))
          }
        ).also { outcome ->
          val postScanUpdates = mutableListOf<Device>()
          val finalRows = synchronized(knownDevicesByIp) {
            knownDevicesByIp.values.toList().forEach { previous ->
              if (!foundIps.contains(previous.ip)) {
                val offline = previous.copy(
                  online = false,
                  reachable = false,
                  latencyMs = null,
                  latencyReason = "Not observed in latest scan",
                  methodUsed = null,
                  reachabilityMethod = "N/A",
                  rssi = if (previous.isGateway) outcome.metadata.wifiRssi else null,
                  rssiDbm = if (previous.isGateway) outcome.metadata.wifiRssi else null,
                  unresolvedReasons = previous.unresolvedReasons + ("online" to "Host not discovered in current scan"),
                  lastSeen = previous.lastSeen,
                  lastSeenEpochMs = previous.lastSeenEpochMs
                )
                knownDevicesByIp[previous.ip] = offline
                postScanUpdates += offline
              } else if (previous.isGateway) {
                val gatewayUpdated = previous.copy(
                  rssi = outcome.metadata.wifiRssi,
                  rssiDbm = outcome.metadata.wifiRssi
                )
                knownDevicesByIp[previous.ip] = gatewayUpdated
                postScanUpdates += gatewayUpdated
              }
            }
            knownDevicesByIp.values.sortedBy { it.ip }
          }
          postScanUpdates.forEach { _events.tryEmit(ScanEvent.ScanDevice(device = it, updated = true)) }
          _results.value = finalRows
          sharedDevices.value = finalRows
          _scanState.value = ScanState(
            phase = ScanPhase.COMPLETE,
            scannedHosts = targetsPlanned,
            discoveredHosts = finalRows.size,
            message = "Scan complete"
          )
          _events.tryEmit(
            ScanEvent.ScanDone(
              targetsPlanned = targetsPlanned,
              probesSent = targetsPlanned,
              devicesFound = finalRows.size,
              metadata = outcome.metadata
            )
          )
        }
      } catch (ce: CancellationException) {
        _scanState.value = ScanState(ScanPhase.IDLE, 0, 0, "Scan stopped")
        throw ce
      } catch (t: Throwable) {
        _scanState.value = ScanState(ScanPhase.ERROR, 0, 0, t.message ?: "Scan error")
        _events.tryEmit(ScanEvent.ScanError(t.message ?: "Scan error"))
      }
    }
  }

  override suspend fun stopScan() {
    scanJob?.cancel()
    scanJob = null
    _scanState.value = ScanState(ScanPhase.IDLE, 0, 0, "Stopped")
  }

  private fun inferDeviceType(hostname: String?): String {
    if (hostname.isNullOrBlank()) return "UNKNOWN"
    val h = hostname.lowercase()
    return when {
      h.contains("iphone") || h.contains("ipad") -> "PHONE"
      h.contains("android") || h.contains("pixel") || h.contains("samsung") -> "PHONE"
      h.contains("macbook") || h.contains("imac") -> "COMPUTER"
      h.contains("windows") || h.contains("desktop") || h.contains("laptop") -> "COMPUTER"
      h.contains("tv") || h.contains("roku") || h.contains("chromecast") -> "MEDIA"
      h.contains("printer") -> "PRINTER"
      h.contains("cam") || h.contains("camera") -> "CAMERA"
      else -> "UNKNOWN"
    }
  }
}

private class HybridDevicesService(
  private val sharedDevices: MutableStateFlow<List<Device>>,
  private val scanService: ScanService
) : DevicesService {
  override val devices: StateFlow<List<Device>> = sharedDevices.asStateFlow()

  override suspend fun refresh() {
    scanService.startDeepScan()
  }
}

private class HybridDeviceControl(
  private val sharedDevices: MutableStateFlow<List<Device>>,
  private val lanScanner: RealLanScanner
) : DeviceControlService {
  override suspend fun ping(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(
        ok = false,
        status = ServiceStatus.ERROR,
        code = "DEVICE_NOT_FOUND",
        message = "Device not found",
        errorReason = "No device with id=$deviceId"
      )

    val probe = lanScanner.probeReachability(d.ip)
    return if (probe.reachable) {
      ActionResult(
        ok = true,
        status = ServiceStatus.OK,
        code = "PING_OK",
        message = "Ping completed",
        details = mapOf(
          "ip" to d.ip,
          "latencyMs" to (probe.latencyMs?.toString() ?: "N/A"),
          "methodUsed" to (probe.methodUsed ?: "N/A"),
          "reachable" to "true"
        )
      )
    } else {
      ActionResult(
        ok = false,
        status = ServiceStatus.NO_DATA,
        code = "PING_UNAVAILABLE",
        message = "Reachability unavailable",
        details = mapOf(
          "ip" to d.ip,
          "latencyMs" to "N/A",
          "methodUsed" to "UNAVAILABLE",
          "reachable" to "false"
        ),
        errorReason = "ICMP blocked and TCP fallback ports did not accept connections."
      )
    }
  }

  override suspend fun block(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, ServiceStatus.ERROR, "DEVICE_NOT_FOUND", "Device not found")
    return ActionResult(
      ok = false,
      status = ServiceStatus.NOT_SUPPORTED,
      code = "ROUTER_API_REQUIRED",
      message = "Block action is NOT_SUPPORTED",
      details = mapOf("device" to d.name),
      errorReason = "Blocking devices requires router vendor API integration and authenticated credentials."
    )
  }

  override suspend fun prioritize(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, ServiceStatus.ERROR, "DEVICE_NOT_FOUND", "Device not found")
    return ActionResult(
      ok = false,
      status = ServiceStatus.NOT_SUPPORTED,
      code = "ROUTER_API_REQUIRED",
      message = "Prioritize action is NOT_SUPPORTED",
      details = mapOf("device" to d.name),
      errorReason = "QoS prioritization requires router vendor API integration and authenticated credentials."
    )
  }

  override suspend fun deviceDetails(deviceId: String): DeviceDetails? {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId } ?: return null
    val probe = lanScanner.probeReachability(d.ip)
    return DeviceDetails(
      device = d,
      pingMs = probe.latencyMs,
      notes = if (probe.reachable) "Reachable via ${probe.methodUsed}" else "Reachability unavailable"
    )
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
        strength = toStrength(d),
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
        strength = toStrength(it),
        ip = it.ip,
        selected = selectedNodeId.value == it.id
      )
    }

    val gateway = devices.firstOrNull { it.isGateway || it.deviceType.equals("router", true) || it.name.equals("GATEWAY", true) }
      ?: devices.firstOrNull()
    _links.value = if (gateway == null) {
      emptyList()
    } else {
      devices
        .filter { it.id != gateway.id }
        .map { MapLink(gateway.id, it.id, toStrength(it)) }
    }
  }

  override suspend fun selectNode(id: String?) {
    selectedNodeId.value = id
    refreshTopology()
  }
}

private class HybridAnalytics(
  speedtestService: SpeedtestService,
  devicesFlow: StateFlow<List<Device>>,
  scanService: ScanService
) : AnalyticsService {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val _events = MutableStateFlow<List<String>>(listOf("BOOT: backend online"))
  override val events: StateFlow<List<String>> = _events.asStateFlow()
  private val _snapshot = MutableStateFlow(
    AnalyticsSnapshot(
      downMbps = null,
      upMbps = null,
      latencyMs = null,
      jitterMs = null,
      packetLossPct = null,
      deviceCount = 0,
      reachableCount = 0,
      avgRttMs = null,
      medianRttMs = null,
      scanDurationMs = null,
      lastScanEpochMs = null,
      status = ServiceStatus.NO_DATA,
      message = "No scan data available yet."
    )
  )
  override val snapshot: StateFlow<AnalyticsSnapshot> = _snapshot.asStateFlow()

  private val scanStartedAt = MutableStateFlow<Long?>(null)
  private val lastScanDuration = MutableStateFlow<Int?>(null)
  private val lastScanTimestamp = MutableStateFlow<Long?>(null)

  init {
    scope.launch {
      scanService.events.collect { event ->
        when (event) {
          is ScanEvent.ScanStarted -> scanStartedAt.value = event.startedAtEpochMs
          is ScanEvent.ScanDone -> {
            lastScanTimestamp.value = event.completedAtEpochMs
            val started = scanStartedAt.value
            lastScanDuration.value = if (started == null) null else ((event.completedAtEpochMs - started).coerceAtLeast(0L)).toInt()
          }
          else -> Unit
        }
      }
    }

    scope.launch {
      while (true) {
        val st = speedtestService.ui.value
        val devices = devicesFlow.value
        val reachable = devices.filter { it.online }
        val rtts = reachable.mapNotNull { it.latencyMs }.sorted()
        val avgRtt = if (rtts.isEmpty()) null else rtts.average()
        val medianRtt = if (rtts.isEmpty()) null else rtts[rtts.size / 2].toDouble()

        val status = if (devices.isEmpty()) ServiceStatus.NO_DATA else ServiceStatus.OK
        val message = if (devices.isEmpty()) "No scan data available yet." else null

        _snapshot.value = AnalyticsSnapshot(
          downMbps = st.downMbps,
          upMbps = st.upMbps,
          latencyMs = st.latencyMs?.toDouble(),
          jitterMs = null,
          packetLossPct = null,
          deviceCount = devices.size,
          reachableCount = reachable.size,
          avgRttMs = avgRtt,
          medianRttMs = medianRtt,
          scanDurationMs = lastScanDuration.value,
          lastScanEpochMs = lastScanTimestamp.value,
          status = status,
          message = message
        )
        delay(1000)
      }
    }
  }

  override suspend fun refresh() {
    val s = snapshot.value
    _events.value = listOf(
      "STATUS=${s.status}",
      "DEVICES=${s.deviceCount} REACHABLE=${s.reachableCount}",
      "RTT_AVG=${s.avgRttMs?.let { "%.1f".format(it) } ?: "N/A"}ms RTT_MED=${s.medianRttMs?.let { "%.1f".format(it) } ?: "N/A"}ms",
      "LAST_SCAN=${s.lastScanEpochMs ?: 0} DURATION_MS=${s.scanDurationMs ?: -1}"
    )
  }
}

private class HybridRouterControl(
  private val context: Context,
  private val credentialsStore: RouterCredentialsStore
) : RouterControlService {

  override suspend fun info(): RouterInfoResult {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return RouterInfoResult(ServiceStatus.ERROR, "ConnectivityManager unavailable")

    val active = cm.activeNetwork
      ?: return RouterInfoResult(ServiceStatus.NO_DATA, "No active network")
    val link = cm.getLinkProperties(active)
    val caps = cm.getNetworkCapabilities(active)
    val gatewayIp = link?.routes?.firstOrNull { it.hasGateway() }?.gateway?.hostAddress
    val dns = link?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()

    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val ssid = if (onWifi) wifi?.connectionInfo?.ssid?.trim('"') else null
    val linkSpeed = if (onWifi) wifi?.connectionInfo?.linkSpeed else null

    return RouterInfoResult(
      status = ServiceStatus.OK,
      message = "Read-only router/network info available",
      gatewayIp = gatewayIp,
      dnsServers = dns,
      ssid = ssid,
      linkSpeedMbps = linkSpeed
    )
  }

  private fun notSupported(action: String): ActionResult {
    val configured = credentialsStore.read().configured()
    val reason = if (configured) {
      "Router control requires vendor-specific API implementation and is not integrated yet."
    } else {
      "Router credentials are not configured and vendor API integration is not implemented."
    }
    return ActionResult(
      ok = false,
      status = ServiceStatus.NOT_SUPPORTED,
      code = "NOT_SUPPORTED",
      message = "$action is NOT_SUPPORTED",
      errorReason = reason
    )
  }

  override suspend fun toggleGuest(): ActionResult = notSupported("toggleGuest")
  override suspend fun setQos(mode: QosMode): ActionResult = notSupported("setQos")
  override suspend fun renewDhcp(): ActionResult = notSupported("renewDhcp")
  override suspend fun flushDns(): ActionResult = notSupported("flushDns")
  override suspend fun rebootRouter(): ActionResult = notSupported("rebootRouter")
  override suspend fun toggleFirewall(): ActionResult = notSupported("toggleFirewall")
  override suspend fun toggleVpn(): ActionResult = notSupported("toggleVpn")
}

private fun toStrength(device: Device): Int {
  if (device.isGateway) {
    device.rssiDbm?.let { return (it + 100).coerceIn(5, 99) }
  }
  device.latencyMs?.let {
    val score = 100 - it
    return score.coerceIn(8, 95)
  }
  return if (device.online) 55 else 8
}
