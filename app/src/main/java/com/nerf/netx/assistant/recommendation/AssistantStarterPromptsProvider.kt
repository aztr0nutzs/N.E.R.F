package com.nerf.netx.assistant.recommendation

import com.nerf.netx.assistant.model.AssistantSuggestedAction

class AssistantStarterPromptsProvider {
  fun prompts(): List<String> {
    return listOf(
      "network status summary",
      "run diagnostics",
      "scan network",
      "start speedtest",
      "open analytics",
      "explain metric ping",
      "ping device <name>",
      "reboot router"
    )
  }

  fun followUpActions(): List<AssistantSuggestedAction> {
    return listOf(
      AssistantSuggestedAction(label = "Run Scan", command = "scan network"),
      AssistantSuggestedAction(label = "Run Diagnostics", command = "run diagnostics"),
      AssistantSuggestedAction(label = "Open Devices", command = "open devices")
    )
  }
}
