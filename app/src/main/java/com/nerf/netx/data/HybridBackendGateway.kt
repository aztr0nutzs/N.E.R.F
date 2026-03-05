package com.nerf.netx.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nerf.netx.domain.*
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
import kotlinx.coroutines.launch
import kotlin.random.Random

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

  override suspend fun startDeepScan() {
    if (scanJob?.isActive == true) return
    scanJob = scope.launch {
      val byIp = linkedMapOf<String, Device>()
      var targetsPlanned = 0
      try {
        lanScanner.scanHosts(
          onStarted = { planned, warning ->
            targetsPlanned = planned
            _scanState.value = ScanState(
              phase = ScanPhase.RUNNING,
              scannedHosts = 0,
              discoveredHosts = 0,
              message = warning ?: "Deep scan started"
            )
            _events.tryEmit(ScanEvent.ScanStarted(targetsPlanned = planned))
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
            synchronized(byIp) {
              byIp[device.ip] = device
              val snapshot = byIp.values.sortedBy { it.ip }
              _results.value = snapshot
              sharedDevices.value = snapshot
            }
            _events.tryEmit(ScanEvent.ScanDevice(device = device, updated = updated))
          }
        ).also { complete ->
          val finalRows = complete.sortedBy { it.ip }
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
              devicesFound = finalRows.size
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
      ?: return ActionResult(false, "Device not found")
    val probe = lanScanner.probeReachability(d.ip)
    return if (probe.reachable) {
      ActionResult(
        ok = true,
        message = "Ping OK",
        details = mapOf(
          "ip" to d.ip,
          "pingMs" to (probe.latencyMs?.toString() ?: "N/A"),
          "method" to probe.methodUsed
        )
      )
    } else {
      ActionResult(false, "Device unreachable", mapOf("ip" to d.ip, "method" to probe.methodUsed))
    }
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
    val probe = lanScanner.probeReachability(d.ip)
    return DeviceDetails(
      device = d,
      pingMs = probe.latencyMs,
      notes = if (probe.reachable) "Reachable via ${probe.methodUsed}" else "Unreachable"
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
    _links.value = if (gateway == null) emptyList() else {
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

private fun toStrength(device: Device): Int {
  device.rssiDbm?.let { return (it + 100).coerceIn(5, 99) }
  device.latencyMs?.let {
    val score = 100 - it
    return score.coerceIn(8, 95)
  }
  return if (device.online) 55 else 8
}
