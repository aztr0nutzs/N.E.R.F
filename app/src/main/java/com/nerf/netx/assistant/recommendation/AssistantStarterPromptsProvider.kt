package com.nerf.netx.assistant.recommendation

import com.nerf.netx.assistant.model.AssistantSuggestedAction

class AssistantStarterPromptsProvider {
  fun prompts(): List<String> {
    return listOf(
      "network status summary",
      "run diagnostics",
      "what's wrong with my network?",
      "why is my internet slow?",
      "why is latency high?",
      "what should I do next?",
      "scan network",
      "start speedtest",
      "open analytics",
      "explain metric ping",
      "ping device <name>"
    )
  }

  fun followUpActions(): List<AssistantSuggestedAction> {
    return listOf(
      AssistantSuggestedAction(label = "Run Scan", command = "scan network"),
      AssistantSuggestedAction(label = "Run Diagnostics", command = "run diagnostics"),
      AssistantSuggestedAction(label = "What's Wrong?", command = "what's wrong with my network?"),
      AssistantSuggestedAction(label = "Open Devices", command = "open devices")
    )
  }
}
