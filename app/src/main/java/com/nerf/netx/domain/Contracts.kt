package com.nerf.netx.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface SpeedtestService {
  val ui: StateFlow<SpeedtestUiState>
  val servers: StateFlow<List<SpeedtestServer>>
  val config: StateFlow<SpeedtestConfig>
  val history: StateFlow<List<SpeedtestHistoryEntry>>
  val latestResult: StateFlow<SpeedtestResult?>
  suspend fun start()
  suspend fun stop()
  suspend fun reset()
  suspend fun updateConfig(config: SpeedtestConfig)
  suspend fun clearHistory()
}

enum class ServiceStatus { OK, NO_DATA, NOT_SUPPORTED, ERROR, RUNNING, IDLE }
enum class MeasurementMode { REAL, SIMULATED, NOT_AVAILABLE }
enum class SpeedtestPhase { IDLE, PING, DOWNLOAD, UPLOAD, DONE, ERROR, ABORTED }

data class SpeedtestServer(
  val id: String,
  val name: String,
  val baseUrl: String,
  val pingUrl: String,
  val downloadPaths: Map<Int, String>,
  val uploadUrl: String
)

data class SpeedtestConfig(
  val serverMode: String = "AUTO",
  val selectedServerId: String? = null,
  val downloadSizesBytes: List<Int> = listOf(5_000_000, 20_000_000),
  val uploadSizesBytes: List<Int> = listOf(2_000_000, 10_000_000),
  val threads: Int = 4,
  val durationMs: Long = 8_000,
  val timeoutMs: Long = 5_000
)

data class ThroughputSample(
  val phase: SpeedtestPhase,
  val tMs: Long,
  val mbps: Double
)

data class SpeedtestResult(
  val phase: SpeedtestPhase,
  val serverId: String?,
  val serverName: String?,
  val pingMs: Double?,
  val jitterMs: Double?,
  val packetLossPct: Double?,
  val downloadMbps: Double?,
  val uploadMbps: Double?,
  val samples: List<ThroughputSample>,
  val error: String?,
  val startedAt: Long,
  val finishedAt: Long,
  val reasons: Map<String, String> = emptyMap()
)

data class SpeedtestHistoryEntry(
  val id: String,
  val timestamp: Long,
  val serverName: String?,
  val pingMs: Double?,
  val downMbps: Double?,
  val upMbps: Double?,
  val jitterMs: Double?,
  val lossPct: Double?
)

data class SpeedtestUiState(
  val running: Boolean,
  val progress01: Float,
  val downMbps: Double?,
  val upMbps: Double?,
  val latencyMs: Int?,
  val phase: String,
  val phaseEnum: SpeedtestPhase = SpeedtestPhase.IDLE,
  val currentMbps: Double? = null,
  val samples: List<ThroughputSample> = emptyList(),
  val pingMs: Double? = null,
  val jitterMs: Double? = null,
  val packetLossPct: Double? = null,
  val activeServerId: String? = null,
  val activeServerName: String? = null,
  val mode: MeasurementMode = MeasurementMode.NOT_AVAILABLE,
  val status: ServiceStatus = ServiceStatus.IDLE,
  val message: String? = null,
  val reason: String? = null
)

enum class ScanPhase { IDLE, RUNNING, COMPLETE, ERROR }

data class ScanState(
  val phase: ScanPhase,
  val scannedHosts: Int,
  val discoveredHosts: Int,
  val message: String? = null
)

data class ScanMetadata(
  val localIp: String?,
  val prefixLength: Int?,
  val gatewayIp: String?,
  val wifiRssi: Int?,
  val startTime: Long,
  val endTime: Long? = null,
  val durationMs: Long? = null,
  val warning: String? = null
)

interface ScanService {
  val scanState: StateFlow<ScanState>
  val results: StateFlow<List<Device>>
  val events: SharedFlow<ScanEvent>
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
  val reachable: Boolean = online,
  val rssiDbm: Int? = null,
  val rssi: Int? = rssiDbm,
  val latencyMs: Int? = null,
  val latencyReason: String? = null,
  val reachabilityMethod: String = "N/A",
  val methodUsed: String? = if (reachabilityMethod == "N/A") null else reachabilityMethod,
  val mac: String = "",
  val macAddress: String? = if (mac.isBlank()) null else mac,
  val vendor: String = "",
  val vendorName: String? = if (vendor.isBlank()) null else vendor,
  val hostname: String = "",
  val hostName: String? = if (hostname.isBlank()) null else hostname,
  val deviceType: String = "UNKNOWN",
  val isGateway: Boolean = false,
  val lastSeenEpochMs: Long = System.currentTimeMillis(),
  val lastSeen: Long = lastSeenEpochMs,
  val unresolvedReasons: Map<String, String> = emptyMap(),
  val transport: String = "LAN",
  val openPortsSummary: String = "",
  val riskScore: Int = 0
)

data class DeviceDetails(
  val device: Device,
  val pingMs: Int? = null,
  val notes: String = ""
)

sealed class ScanEvent {
  data class ScanStarted(
    val targetsPlanned: Int,
    val metadata: ScanMetadata? = null,
    val startedAtEpochMs: Long = System.currentTimeMillis()
  ) : ScanEvent()

  data class ScanProgress(
    val targetsPlanned: Int,
    val probesSent: Int,
    val devicesFound: Int
  ) : ScanEvent()

  data class ScanDevice(
    val device: Device,
    val updated: Boolean
  ) : ScanEvent()

  data class ScanDone(
    val targetsPlanned: Int,
    val probesSent: Int,
    val devicesFound: Int,
    val metadata: ScanMetadata? = null,
    val completedAtEpochMs: Long = System.currentTimeMillis()
  ) : ScanEvent()

  data class ScanError(
    val message: String
  ) : ScanEvent()
}

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
  val downMbps: Double?,
  val upMbps: Double?,
  val latencyMs: Double?,
  val jitterMs: Double?,
  val packetLossPct: Double?,
  val deviceCount: Int,
  val reachableCount: Int,
  val avgRttMs: Double?,
  val medianRttMs: Double?,
  val scanDurationMs: Int?,
  val lastScanEpochMs: Long?,
  val status: ServiceStatus,
  val message: String? = null
)

interface AnalyticsService {
  val events: StateFlow<List<String>>
  val snapshot: StateFlow<AnalyticsSnapshot>
  suspend fun refresh()
}

enum class QosMode { BALANCED, GAMING, STREAMING }

data class ActionResult(
  val ok: Boolean,
  val status: ServiceStatus,
  val code: String,
  val message: String,
  val details: Map<String, String> = emptyMap(),
  val errorReason: String? = null
)

data class RouterInfoResult(
  val status: ServiceStatus,
  val message: String,
  val gatewayIp: String? = null,
  val dnsServers: List<String> = emptyList(),
  val ssid: String? = null,
  val linkSpeedMbps: Int? = null
)

interface RouterControlService {
  suspend fun info(): RouterInfoResult
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
