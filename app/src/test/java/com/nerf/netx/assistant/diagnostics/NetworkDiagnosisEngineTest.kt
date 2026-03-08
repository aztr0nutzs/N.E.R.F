package com.nerf.netx.assistant.diagnostics

import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.assistant.model.AssistantDiagnosisFocus
import com.nerf.netx.assistant.model.AssistantDiagnosisType
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.domain.AnalyticsSnapshot
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.DeviceControlStatusSnapshot
import com.nerf.netx.domain.RouterInfoResult
import com.nerf.netx.domain.ScanPhase
import com.nerf.netx.domain.ScanState
import com.nerf.netx.domain.ServiceStatus
import com.nerf.netx.domain.SpeedtestPhase
import com.nerf.netx.domain.SpeedtestResult
import com.nerf.netx.domain.SpeedtestUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosisEngineTest {

  private val engine = NetworkDiagnosisEngine()

  @Test
  fun `detects possible wan outage when router is reachable but internet checks fail`() {
    val report = engine.diagnose(
      context = snapshot(
        devices = listOf(gatewayDevice()),
        speedtestUi = baseSpeedtestUi(status = ServiceStatus.ERROR, message = "No reachable speedtest server found."),
        latestSpeedtest = SpeedtestResult(
          phase = SpeedtestPhase.ERROR,
          serverId = null,
          serverName = null,
          pingMs = null,
          jitterMs = null,
          packetLossPct = null,
          downloadMbps = 0.0,
          uploadMbps = 0.0,
          samples = emptyList(),
          error = "Internet path unavailable",
          startedAt = 1L,
          finishedAt = 2L
        ),
        analytics = analyticsSnapshot(downMbps = 0.0, upMbps = 0.0, latencyMs = null, jitterMs = null, lossPct = null),
        routerInfo = RouterInfoResult(
          status = ServiceStatus.OK,
          message = "Router reachable",
          gatewayIp = "192.168.1.1",
          ssid = "NERF"
        )
      )
    )

    assertTrue(report.findings.any { it.type == AssistantDiagnosisType.ISP_WAN_OUTAGE })
    assertEquals(AssistantSeverity.ERROR, report.severity)
  }

  @Test
  fun `detects latency jitter loss congestion hog and unknown risk from snapshot evidence`() {
    val devices = listOf(
      gatewayDevice(),
      device(id = "2", name = "Living Room TV", ip = "192.168.1.20", hostname = "living-room-tv", vendor = "Samsung", deviceType = "MEDIA"),
      device(id = "3", name = "Unknown", ip = "192.168.1.30", hostname = "mystery-device", vendor = "", deviceType = "UNKNOWN", riskScore = 65, openPortsSummary = "80/tcp"),
      device(id = "4", name = "Phone A", ip = "192.168.1.31", hostname = "phone-a", vendor = "Apple", deviceType = "PHONE"),
      device(id = "5", name = "Phone B", ip = "192.168.1.32", hostname = "phone-b", vendor = "Apple", deviceType = "PHONE"),
      device(id = "6", name = "Phone C", ip = "192.168.1.33", hostname = "phone-c", vendor = "Apple", deviceType = "PHONE"),
      device(id = "7", name = "Phone D", ip = "192.168.1.34", hostname = "phone-d", vendor = "Apple", deviceType = "PHONE"),
      device(id = "8", name = "Tablet", ip = "192.168.1.35", hostname = "tablet", vendor = "Amazon", deviceType = "PHONE"),
      device(id = "9", name = "Sensor", ip = "192.168.1.36", hostname = "sensor", vendor = "Tuya", deviceType = "IOT")
    )
    val target = devices[0].copy(name = "Gateway", rssiDbm = -78)
    val report = engine.diagnose(
      context = snapshot(
        devices = listOf(target) + devices.drop(1),
        analytics = analyticsSnapshot(downMbps = 42.0, upMbps = 8.0, latencyMs = 140.0, jitterMs = 24.0, lossPct = 3.5),
        routerInfo = RouterInfoResult(ServiceStatus.OK, "Router reachable", gatewayIp = "192.168.1.1", ssid = "NERF"),
        speedtestUi = baseSpeedtestUi(status = ServiceStatus.OK, pingMs = 140.0, jitterMs = 24.0, lossPct = 3.5)
      ),
      focus = AssistantDiagnosisFocus.SPEED
    )

    assertTrue(report.findings.any { it.type == AssistantDiagnosisType.HIGH_LATENCY })
    assertTrue(report.findings.any { it.type == AssistantDiagnosisType.HIGH_JITTER })
    assertTrue(report.findings.any { it.type == AssistantDiagnosisType.PACKET_LOSS })
    assertTrue(report.findings.any { it.type == AssistantDiagnosisType.WEAK_WIFI_SIGNAL })
    assertTrue(report.findings.any { it.type == AssistantDiagnosisType.SAME_CHANNEL_CONGESTION })
    assertTrue(report.findings.any { it.type == AssistantDiagnosisType.HIGH_THROUGHPUT_HOG_DEVICE })
    assertTrue(report.findings.any { it.type == AssistantDiagnosisType.UNKNOWN_DEVICE_RISK })
  }

  private fun snapshot(
    devices: List<Device>,
    speedtestUi: SpeedtestUiState = baseSpeedtestUi(),
    latestSpeedtest: SpeedtestResult? = null,
    analytics: AnalyticsSnapshot = analyticsSnapshot(),
    routerInfo: RouterInfoResult? = null
  ): AssistantContextSnapshot {
    return AssistantContextSnapshot(
      speedtestUi = speedtestUi,
      latestSpeedtest = latestSpeedtest,
      scanState = ScanState(ScanPhase.COMPLETE, scannedHosts = devices.size, discoveredHosts = devices.size),
      devices = devices,
      topologyNodes = emptyList(),
      topologyLinks = emptyList(),
      analytics = analytics,
      deviceControlStatus = DeviceControlStatusSnapshot(
        status = ServiceStatus.NOT_SUPPORTED,
        message = "Device control unsupported."
      ),
      routerInfo = routerInfo
    )
  }

  private fun analyticsSnapshot(
    downMbps: Double? = 80.0,
    upMbps: Double? = 15.0,
    latencyMs: Double? = 20.0,
    jitterMs: Double? = 3.0,
    lossPct: Double? = 0.0
  ) = AnalyticsSnapshot(
    downMbps = downMbps,
    upMbps = upMbps,
    latencyMs = latencyMs,
    jitterMs = jitterMs,
    packetLossPct = lossPct,
    deviceCount = 0,
    reachableCount = 0,
    avgRttMs = latencyMs,
    medianRttMs = latencyMs,
    scanDurationMs = 1000,
    lastScanEpochMs = 1L,
    status = ServiceStatus.OK,
    message = "ok"
  )

  private fun baseSpeedtestUi(
    status: ServiceStatus = ServiceStatus.IDLE,
    pingMs: Double? = null,
    jitterMs: Double? = null,
    lossPct: Double? = null,
    message: String? = null
  ) = SpeedtestUiState(
    running = false,
    progress01 = 0f,
    downMbps = null,
    upMbps = null,
    latencyMs = pingMs?.toInt(),
    phase = SpeedtestPhase.IDLE.name,
    phaseEnum = SpeedtestPhase.IDLE,
    pingMs = pingMs,
    jitterMs = jitterMs,
    packetLossPct = lossPct,
    status = status,
    message = message
  )

  private fun gatewayDevice() = device(
    id = "gw",
    name = "Gateway",
    ip = "192.168.1.1",
    hostname = "gateway",
    vendor = "Netgear",
    deviceType = "ROUTER",
    isGateway = true,
    rssiDbm = -62
  )

  private fun device(
    id: String,
    name: String,
    ip: String,
    hostname: String,
    vendor: String,
    deviceType: String,
    riskScore: Int = 0,
    openPortsSummary: String = "",
    isGateway: Boolean = false,
    rssiDbm: Int? = null
  ) = Device(
    id = id,
    name = name,
    ip = ip,
    online = true,
    reachable = true,
    hostname = hostname,
    vendor = vendor,
    deviceType = deviceType,
    riskScore = riskScore,
    openPortsSummary = openPortsSummary,
    isGateway = isGateway,
    rssiDbm = rssiDbm
  )
}
