package com.nerf.netx.domain

import kotlinx.coroutines.flow.StateFlow

interface SpeedtestService {
  val ui: StateFlow<SpeedtestUiState>
  suspend fun start()
  suspend fun stop()
  suspend fun reset()
}

data class SpeedtestUiState(
  val running: Boolean,
  val progress01: Float,
  val downMbps: Double,
  val upMbps: Double,
  val latencyMs: Int,
  val phase: String
)

enum class ScanPhase { IDLE, RUNNING, COMPLETE, ERROR }

data class ScanState(
  val phase: ScanPhase,
  val scannedHosts: Int,
  val discoveredHosts: Int,
  val message: String? = null
)

interface ScanService {
  val scanState: StateFlow<ScanState>
  val results: StateFlow<List<Device>>
  suspend fun startDeepScan()
  suspend fun stopScan()
}

interface DevicesService {
  val devices: StateFlow<List<Device>>
  suspend fun refresh()
}

interface DeviceControlService {
  suspend fun ping(deviceId: String): ActionResult
  suspend fun block(deviceId: String): ActionResult
  suspend fun prioritize(deviceId: String): ActionResult
  suspend fun deviceDetails(deviceId: String): DeviceDetails?
}

data class Device(
  val id: String,
  val name: String,
  val ip: String,
  val online: Boolean,
  val rssiDbm: Int,
  val mac: String = "unknown",
  val vendor: String = "unknown",
  val hostname: String = "unknown",
  val deviceType: String = "unknown",
  val lastSeenEpochMs: Long = System.currentTimeMillis(),
  val transport: String = "LAN",
  val openPortsSummary: String = "",
  val riskScore: Int = 0
)

data class DeviceDetails(
  val device: Device,
  val pingMs: Int? = null,
  val notes: String = ""
)

enum class MapLayoutMode { RADIAL, TOPOLOGY, SIGNAL }

data class MapNode(
  val id: String,
  val label: String,
  val strength: Int,
  val ip: String,
  val selected: Boolean = false
)

data class MapLink(
  val fromId: String,
  val toId: String,
  val quality: Int
)

interface MapService {
  val nodes: StateFlow<List<MapNode>>
  suspend fun refresh()
}

interface MapTopologyService {
  val layoutMode: StateFlow<MapLayoutMode>
  val nodes: StateFlow<List<MapNode>>
  val links: StateFlow<List<MapLink>>
  suspend fun refreshTopology()
  suspend fun selectNode(id: String?)
}

data class AnalyticsSnapshot(
  val downMbps: Double,
  val upMbps: Double,
  val latencyMs: Double,
  val jitterMs: Double,
  val packetLossPct: Double,
  val deviceCount: Int
)

interface AnalyticsService {
  val events: StateFlow<List<String>>
  val snapshot: StateFlow<AnalyticsSnapshot>
  suspend fun refresh()
}

enum class QosMode { BALANCED, GAMING, STREAMING }

data class ActionResult(
  val ok: Boolean,
  val message: String,
  val details: Map<String, String> = emptyMap()
)

interface RouterControlService {
  suspend fun toggleGuest(): ActionResult
  suspend fun setQos(mode: QosMode): ActionResult
  suspend fun renewDhcp(): ActionResult
  suspend fun flushDns(): ActionResult
  suspend fun rebootRouter(): ActionResult
  suspend fun toggleFirewall(): ActionResult
  suspend fun toggleVpn(): ActionResult
}

interface AppServices {
  val speedtest: SpeedtestService
  val scan: ScanService
  val devices: DevicesService
  val deviceControl: DeviceControlService
  val map: MapService
  val topology: MapTopologyService
  val analytics: AnalyticsService
  val routerControl: RouterControlService
}
