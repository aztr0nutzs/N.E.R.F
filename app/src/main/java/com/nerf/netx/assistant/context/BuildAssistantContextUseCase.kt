package com.nerf.netx.assistant.context

import com.nerf.netx.assistant.model.AssistantContextSnapshot
import com.nerf.netx.domain.AppServices

class BuildAssistantContextUseCase(
  private val services: AppServices
) {
  suspend operator fun invoke(): AssistantContextSnapshot {
    val deviceControlStatus = runCatching { services.deviceControl.refreshStatus() }.getOrNull()
      ?: services.deviceControl.status.value
    val routerStatus = runCatching { services.routerControl.refreshStatus() }.getOrNull()
      ?: services.routerControl.status.value
    val routerInfo = runCatching { services.routerControl.info() }.getOrNull()
    return AssistantContextSnapshot(
      speedtestUi = services.speedtest.ui.value,
      latestSpeedtest = services.speedtest.latestResult.value,
      scanState = services.scan.scanState.value,
      devices = services.devices.devices.value,
      topologyNodes = services.topology.nodes.value,
      topologyLinks = services.topology.links.value,
      analytics = services.analytics.snapshot.value,
      deviceControlStatus = deviceControlStatus,
      routerInfo = routerInfo,
      routerStatus = routerStatus
    )
  }
}
