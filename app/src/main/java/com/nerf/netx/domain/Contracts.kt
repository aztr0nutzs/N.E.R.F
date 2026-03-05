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

interface DevicesService {
  val devices: StateFlow<List<Device>>
  suspend fun refresh()
}

data class Device(
  val id: String,
  val name: String,
  val ip: String,
  val online: Boolean,
  val rssiDbm: Int
)

interface MapService {
  val nodes: StateFlow<List<MapNode>>
  suspend fun refresh()
}

data class MapNode(val id: String, val label: String, val strength: Int)

interface AnalyticsService {
  val events: StateFlow<List<String>>
  suspend fun refresh()
}

interface AppServices {
  val speedtest: SpeedtestService
  val devices: DevicesService
  val map: MapService
  val analytics: AnalyticsService
}
