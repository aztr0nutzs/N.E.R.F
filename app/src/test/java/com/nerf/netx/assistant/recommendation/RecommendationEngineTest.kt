package com.nerf.netx.assistant.recommendation

import com.nerf.netx.assistant.model.AssistantActionStyle
import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.assistant.model.AssistantDiagnosisFinding
import com.nerf.netx.assistant.model.AssistantDiagnosisReport
import com.nerf.netx.assistant.model.AssistantDiagnosisType
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.DeviceControlStatusSnapshot
import com.nerf.netx.domain.ServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {

  private val engine = RecommendationEngine()

  @Test
  fun `orders recommendations by priority and includes device follow up`() {
    val target = Device(
      id = "device-1",
      name = "Office Laptop",
      ip = "192.168.1.20",
      online = true,
      reachable = true,
      hostname = "office-laptop",
      vendor = "Dell",
      deviceType = "COMPUTER"
    )
    val report = AssistantDiagnosisReport(
      title = "Diagnosis",
      summary = "Latency is high.",
      severity = AssistantSeverity.WARNING,
      findings = listOf(
        AssistantDiagnosisFinding(
          type = AssistantDiagnosisType.ISP_WAN_OUTAGE,
          title = "Possible ISP/WAN outage",
          summary = "Internet checks are failing.",
          severity = AssistantSeverity.ERROR,
          evidence = listOf("Router is reachable.")
        ),
        AssistantDiagnosisFinding(
          type = AssistantDiagnosisType.HIGH_LATENCY,
          title = "High latency",
          summary = "Latency is elevated.",
          severity = AssistantSeverity.WARNING,
          evidence = listOf("140 ms observed."),
          targetDeviceId = target.id
        )
      )
    )

    val recommendations = engine.buildRecommendations(
      report = report,
      context = AssistantContextSnapshot(
        speedtestUi = null,
        latestSpeedtest = null,
        scanState = null,
        devices = listOf(target),
        topologyNodes = emptyList(),
        topologyLinks = emptyList(),
        analytics = null,
        deviceControlStatus = DeviceControlStatusSnapshot(
          status = ServiceStatus.NOT_SUPPORTED,
          message = "Device control unsupported."
        ),
        routerInfo = null
      ),
      targetDevice = target
    )

    assertEquals("start speedtest", recommendations.first().action.command)
    assertTrue(recommendations.any { it.action.command == "ping device 192.168.1.20" })
    assertTrue(recommendations.any { it.action.style == AssistantActionStyle.PRIMARY })
  }
}
