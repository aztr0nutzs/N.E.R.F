package com.nerf.netx.networkdoctor.presentation

import com.nerf.netx.assistant.model.AssistantActionStyle
import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.assistant.model.AssistantDiagnosisFinding
import com.nerf.netx.assistant.model.AssistantDiagnosisReport
import com.nerf.netx.assistant.model.AssistantDiagnosisType
import com.nerf.netx.assistant.model.AssistantRecommendation
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.assistant.model.AssistantSuggestedAction
import com.nerf.netx.domain.AnalyticsSnapshot
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.DeviceControlStatusSnapshot
import com.nerf.netx.domain.RouterInfoResult
import com.nerf.netx.domain.RouterStatusSnapshot
import com.nerf.netx.domain.ServiceStatus
import com.nerf.netx.networkdoctor.model.NetworkDoctorAction
import com.nerf.netx.networkdoctor.model.NetworkDoctorHealthStatus
import com.nerf.netx.ui.nav.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDoctorStateMapperTest {

  private val mapper = NetworkDoctorStateMapper()

  @Test
  fun `maps healthy state when no findings exist`() {
    val state = mapper.mapReadyState(
      context = context(),
      report = AssistantDiagnosisReport(
        title = "Diagnosis",
        summary = "No issues",
        severity = AssistantSeverity.INFO,
        findings = emptyList()
      ),
      recommendations = emptyList()
    )

    assertTrue(state.isHealthy)
    assertEquals(NetworkDoctorHealthStatus.HEALTHY, state.healthSummary?.status)
    assertTrue(state.emptyStateMessage?.contains("No active issues") == true)
  }

  @Test
  fun `preserves issue ranking order from diagnosis report`() {
    val report = AssistantDiagnosisReport(
      title = "Diagnosis",
      summary = "Issues",
      severity = AssistantSeverity.ERROR,
      findings = listOf(
        finding(AssistantDiagnosisType.ISP_WAN_OUTAGE, "WAN", AssistantSeverity.ERROR),
        finding(AssistantDiagnosisType.HIGH_LATENCY, "Latency", AssistantSeverity.WARNING)
      )
    )

    val state = mapper.mapReadyState(context(), report, emptyList())

    assertEquals("WAN", state.issues[0].title)
    assertEquals("Latency", state.issues[1].title)
  }

  @Test
  fun `maps recommendations into actionable doctor items`() {
    val state = mapper.mapReadyState(
      context = context(),
      report = AssistantDiagnosisReport("Diagnosis", "Issues", AssistantSeverity.WARNING, emptyList()),
      recommendations = listOf(
        AssistantRecommendation(
          title = "Open analytics",
          rationale = "Inspect latency history.",
          action = AssistantSuggestedAction(
            label = "Open Analytics",
            command = "open analytics",
            style = AssistantActionStyle.PRIMARY
          ),
          priority = 8
        )
      )
    )

    assertEquals("Open analytics", state.recommendations.first().title)
    assertTrue(state.recommendations.first().action?.action is NetworkDoctorAction.OpenRoute)
    assertEquals(Routes.ANALYTICS, (state.recommendations.first().action?.action as NetworkDoctorAction.OpenRoute).route)
  }

  @Test
  fun `adds unavailable items when backend data is missing`() {
    val state = mapper.mapReadyState(
      context = context(routerInfo = RouterInfoResult(ServiceStatus.NO_DATA, "Router credentials not configured.")),
      report = AssistantDiagnosisReport("Diagnosis", "Issues", AssistantSeverity.INFO, emptyList()),
      recommendations = emptyList()
    )

    assertTrue(state.unavailableItems.any { it.title.contains("Per-device traffic") })
    assertTrue(state.unavailableItems.any { it.title.contains("Public IP") })
    assertTrue(state.unavailableItems.any { it.title.contains("Direct Wi-Fi congestion") })
    assertTrue(state.unavailableItems.any { it.title.contains("Router control") })
  }

  private fun finding(
    type: AssistantDiagnosisType,
    title: String,
    severity: AssistantSeverity
  ) = AssistantDiagnosisFinding(
    type = type,
    title = title,
    summary = "$title summary",
    severity = severity,
    evidence = listOf("evidence")
  )

  private fun context(
    routerInfo: RouterInfoResult? = RouterInfoResult(ServiceStatus.OK, "Router reachable", gatewayIp = "192.168.1.1")
  ) = AssistantContextSnapshot(
    speedtestUi = null,
    latestSpeedtest = null,
    scanState = null,
    devices = listOf(
      Device(
        id = "1",
        name = "Laptop",
        ip = "192.168.1.10",
        online = true,
        reachable = true,
        hostname = "laptop",
        vendor = "Dell",
        deviceType = "COMPUTER"
      )
    ),
    topologyNodes = emptyList(),
    topologyLinks = emptyList(),
    analytics = AnalyticsSnapshot(
      downMbps = 120.0,
      upMbps = 20.0,
      latencyMs = 12.0,
      jitterMs = 2.0,
      packetLossPct = 0.0,
      deviceCount = 1,
      reachableCount = 1,
      avgRttMs = 12.0,
      medianRttMs = 12.0,
      scanDurationMs = 500,
      lastScanEpochMs = 1L,
      status = ServiceStatus.OK
    ),
    deviceControlStatus = DeviceControlStatusSnapshot(
      status = ServiceStatus.NOT_SUPPORTED,
      message = "Device control unsupported."
    ),
    routerInfo = routerInfo,
    routerStatus = routerInfo?.toRouterStatus()
  )

  private fun RouterInfoResult.toRouterStatus(): RouterStatusSnapshot {
    return RouterStatusSnapshot(
      status = status,
      message = message,
      gatewayIp = gatewayIp,
      dnsServers = dnsServers,
      ssid = ssid,
      linkSpeedMbps = linkSpeedMbps
    )
  }
}
