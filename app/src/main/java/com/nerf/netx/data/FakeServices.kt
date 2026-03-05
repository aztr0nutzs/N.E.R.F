package com.nerf.netx.data

import com.nerf.netx.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

class FakeServices : AppServices {
  override val speedtest: SpeedtestService = FakeSpeed()
  override val devices: DevicesService = FakeDevices()
  override val map: MapService = FakeMap()
  override val analytics: AnalyticsService = FakeAnalytics()
}

private class FakeSpeed : SpeedtestService {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val _ui = MutableStateFlow(SpeedtestUiState(false, 0f, 0.0, 0.0, 0, "IDLE"))
  override val ui: StateFlow<SpeedtestUiState> = _ui
  private var job: Job? = null

  override suspend fun start() {
    if (_ui.value.running) return
    job?.cancel()
    _ui.value = _ui.value.copy(running = true, phase = "PING", progress01 = 0f)
    job = scope.launch {
      var p = 0f
      var down = 0.0
      var up = 0.0
      var lat = 12
      val phases = listOf("PING", "DOWNLOAD", "UPLOAD", "DONE")
      for (ph in phases) {
        repeat(40) {
          delay(60)
          p = (p + 1f/160f).coerceAtMost(1f)
          if (ph == "PING") lat = (lat + Random.nextInt(-1,2)).coerceIn(6,60)
          if (ph == "DOWNLOAD") down = (down + Random.nextDouble(10.0, 30.0)).coerceAtMost(700.0)
          if (ph == "UPLOAD") up = (up + Random.nextDouble(3.0, 10.0)).coerceAtMost(180.0)
          _ui.value = SpeedtestUiState(true, p, down, up, lat, ph)
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

private class FakeDevices : DevicesService {
  private val _devices = MutableStateFlow(
    listOf(
      Device("rtr-1", "ROUTER", "192.168.0.1", true, -38),
      Device("dev-1", "PHONE-1", "192.168.0.21", true, -55),
      Device("dev-2", "PC-2", "192.168.0.22", true, -62),
      Device("dev-3", "TV-3", "192.168.0.23", false, -80)
    )
  )
  override val devices: StateFlow<List<Device>> = _devices
  override suspend fun refresh() {
    _devices.value = _devices.value.map {
      it.copy(
        online = (it.id == "rtr-1") or (Random.nextInt(0,10) != 0),
        rssiDbm = (it.rssiDbm + Random.nextInt(-2,3)).coerceIn(-95,-30)
      )
    }
  }
}

private class FakeMap : MapService {
  private val _nodes = MutableStateFlow(
    listOf(
      MapNode("rtr-1","ROUTER",95),
      MapNode("ap-1","AP-EXT",78),
      MapNode("dev-1","PHONE-1",60),
      MapNode("dev-2","PC-2",72)
    )
  )
  override val nodes: StateFlow<List<MapNode>> = _nodes
  override suspend fun refresh() {
    _nodes.value = _nodes.value.map { it.copy(strength = (it.strength + Random.nextInt(-3,4)).coerceIn(10,99)) }
  }
}

private class FakeAnalytics : AnalyticsService {
  private val _events = MutableStateFlow(listOf("BOOT: OK", "THEME: neon_nerf", "SCAN: simulated"))
  override val events: StateFlow<List<String>> = _events
  override suspend fun refresh() {
    _events.value = listOf("REFRESH: " + System.currentTimeMillis().toString(), "PACKET_LOSS: simulated")
  }
}
