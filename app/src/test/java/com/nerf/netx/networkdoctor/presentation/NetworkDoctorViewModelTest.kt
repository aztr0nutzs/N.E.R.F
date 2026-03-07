package com.nerf.netx.networkdoctor.presentation

import com.nerf.netx.assistant.context.BuildAssistantContextUseCase
import com.nerf.netx.assistant.diagnostics.NetworkDiagnosisEngine
import com.nerf.netx.assistant.recommendation.RecommendationEngine
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
import com.nerf.netx.domain.QosMode
import com.nerf.netx.domain.RouterControlService
import com.nerf.netx.domain.RouterInfoResult
import com.nerf.netx.domain.ScanEvent
import com.nerf.netx.domain.ScanPhase
import com.nerf.netx.domain.ScanService
import com.nerf.netx.domain.ScanState
import com.nerf.netx.domain.ServiceStatus
import com.nerf.netx.domain.SpeedtestConfig
import com.nerf.netx.domain.SpeedtestHistoryEntry
import com.nerf.netx.domain.SpeedtestResult
import com.nerf.netx.domain.SpeedtestServer
import com.nerf.netx.domain.SpeedtestService
import com.nerf.netx.domain.SpeedtestUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDoctorViewModelTest {

  @Test
  fun `generates ready state from assistant context and diagnosis stack`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val services = FakeAppServices()
    val viewModel = NetworkDoctorViewModel(
      services = services,
      contextUseCase = BuildAssistantContextUseCase(services),
      diagnosisEngine = NetworkDiagnosisEngine(),
      recommendationEngine = RecommendationEngine(),
      stateMapper = NetworkDoctorStateMapper(),
      dispatcher = dispatcher
    )

    advanceUntilIdle()

    assertEquals(com.nerf.netx.networkdoctor.state.NetworkDoctorLoadState.READY, viewModel.uiState.value.loadState)
    assertTrue(viewModel.uiState.value.healthSummary != null)
    assertTrue(viewModel.uiState.value.issues.any { it.title.contains("High latency") })
  }

  private class FakeAppServices : AppServices {
    private val device = Device(
      id = "1",
      name = "Laptop",
      ip = "192.168.1.10",
      online = true,
      reachable = true,
      hostname = "laptop",
      vendor = "Dell",
      deviceType = "COMPUTER",
      latencyMs = 160
    )

    override val speedtest: SpeedtestService = object : SpeedtestService {
      private val uiFlow = MutableStateFlow(
        SpeedtestUiState(
          running = false,
          progress01 = 0f,
          downMbps = 80.0,
          upMbps = 18.0,
          latencyMs = 160,
          phase = "DONE",
          pingMs = 160.0,
          jitterMs = 22.0,
          packetLossPct = 2.0,
          status = ServiceStatus.OK
        )
      )
      override val ui: StateFlow<SpeedtestUiState> = uiFlow.asStateFlow()
      override val servers: StateFlow<List<SpeedtestServer>> = MutableStateFlow(emptyList()).asStateFlow()
      override val config: StateFlow<SpeedtestConfig> = MutableStateFlow(SpeedtestConfig()).asStateFlow()
      override val history: StateFlow<List<SpeedtestHistoryEntry>> = MutableStateFlow(emptyList()).asStateFlow()
      override val latestResult: StateFlow<SpeedtestResult?> = MutableStateFlow(null).asStateFlow()
      override suspend fun start() = Unit
      override suspend fun stop() = Unit
      override suspend fun reset() = Unit
      override suspend fun updateConfig(config: SpeedtestConfig) = Unit
      override suspend fun clearHistory() = Unit
    }

    override val scan: ScanService = object : ScanService {
      override val scanState: StateFlow<ScanState> = MutableStateFlow(ScanState(ScanPhase.COMPLETE, 1, 1)).asStateFlow()
      override val results: StateFlow<List<Device>> = MutableStateFlow(listOf(device)).asStateFlow()
      override val events: SharedFlow<ScanEvent> = MutableSharedFlow<ScanEvent>().asSharedFlow()
      override suspend fun startDeepScan() = Unit
      override suspend fun stopScan() = Unit
    }

    override val devices: DevicesService = object : DevicesService {
      override val devices: StateFlow<List<Device>> = MutableStateFlow(listOf(device)).asStateFlow()
      override suspend fun refresh() = Unit
    }

    override val deviceControl: DeviceControlService = object : DeviceControlService {
      override suspend fun ping(deviceId: String): ActionResult = ActionResult(true, ServiceStatus.OK, "OK", "Pinged")
      override suspend fun block(deviceId: String): ActionResult = ActionResult(false, ServiceStatus.NOT_SUPPORTED, "NOT_SUPPORTED", "Block unsupported")
      override suspend fun prioritize(deviceId: String): ActionResult = ActionResult(false, ServiceStatus.NOT_SUPPORTED, "NOT_SUPPORTED", "Prioritize unsupported")
      override suspend fun deviceDetails(deviceId: String): DeviceDetails? = null
    }

    override val map: MapService = object : MapService {
      override val nodes: StateFlow<List<MapNode>> = MutableStateFlow(emptyList()).asStateFlow()
      override suspend fun refresh() = Unit
    }

    override val topology: MapTopologyService = object : MapTopologyService {
      override val layoutMode: StateFlow<MapLayoutMode> = MutableStateFlow(MapLayoutMode.TOPOLOGY).asStateFlow()
      override val nodes: StateFlow<List<MapNode>> = MutableStateFlow(emptyList()).asStateFlow()
      override val links: StateFlow<List<MapLink>> = MutableStateFlow(emptyList()).asStateFlow()
      override suspend fun refreshTopology() = Unit
      override suspend fun selectNode(id: String?) = Unit
    }

    override val analytics: AnalyticsService = object : AnalyticsService {
      override val events: StateFlow<List<String>> = MutableStateFlow(emptyList()).asStateFlow()
      override val snapshot: StateFlow<AnalyticsSnapshot> = MutableStateFlow(
        AnalyticsSnapshot(
          downMbps = 80.0,
          upMbps = 18.0,
          latencyMs = 160.0,
          jitterMs = 22.0,
          packetLossPct = 2.0,
          deviceCount = 1,
          reachableCount = 1,
          avgRttMs = 160.0,
          medianRttMs = 160.0,
          scanDurationMs = 500,
          lastScanEpochMs = 1L,
          status = ServiceStatus.OK
        )
      ).asStateFlow()
      override suspend fun refresh() = Unit
    }

    override val routerControl: RouterControlService = object : RouterControlService {
      override suspend fun info(): RouterInfoResult = RouterInfoResult(ServiceStatus.OK, "Router reachable", gatewayIp = "192.168.1.1")
      override suspend fun toggleGuest(): ActionResult = ActionResult(false, ServiceStatus.NOT_SUPPORTED, "NOT_SUPPORTED", "Unsupported")
      override suspend fun setQos(mode: QosMode): ActionResult = ActionResult(false, ServiceStatus.NOT_SUPPORTED, "NOT_SUPPORTED", "Unsupported")
      override suspend fun renewDhcp(): ActionResult = ActionResult(false, ServiceStatus.NOT_SUPPORTED, "NOT_SUPPORTED", "Unsupported")
      override suspend fun flushDns(): ActionResult = ActionResult(false, ServiceStatus.NOT_SUPPORTED, "NOT_SUPPORTED", "Unsupported")
      override suspend fun rebootRouter(): ActionResult = ActionResult(false, ServiceStatus.NOT_SUPPORTED, "NOT_SUPPORTED", "Unsupported")
      override suspend fun toggleFirewall(): ActionResult = ActionResult(false, ServiceStatus.NOT_SUPPORTED, "NOT_SUPPORTED", "Unsupported")
      override suspend fun toggleVpn(): ActionResult = ActionResult(false, ServiceStatus.NOT_SUPPORTED, "NOT_SUPPORTED", "Unsupported")
    }
  }
}
