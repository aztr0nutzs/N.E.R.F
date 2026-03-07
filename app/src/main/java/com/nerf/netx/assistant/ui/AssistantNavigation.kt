package com.nerf.netx.assistant.ui

import com.nerf.netx.assistant.model.AssistantDestination
import com.nerf.netx.ui.nav.Routes

fun AssistantDestination.toRoute(): String {
  return when (this) {
    AssistantDestination.SPEEDTEST -> Routes.SPEED
    AssistantDestination.DEVICES -> Routes.DEVICES
    AssistantDestination.MAP -> Routes.MAP
    AssistantDestination.ANALYTICS -> Routes.ANALYTICS
    AssistantDestination.ASSISTANT -> Routes.ASSISTANT
  }
}
