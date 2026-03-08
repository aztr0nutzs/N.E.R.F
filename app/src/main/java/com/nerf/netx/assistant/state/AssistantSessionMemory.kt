package com.nerf.netx.assistant.state

import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType

class AssistantSessionMemory(
  private val maxRecentActions: Int = 12
) {
  var lastDiscussedDeviceId: String? = null
    private set

  var lastPendingConfirmationIntent: AssistantIntent? = null
    private set

  private val _recentActions = ArrayDeque<AssistantIntentType>()
  val recentActions: List<AssistantIntentType>
    get() = _recentActions.toList()

  fun updateLastDiscussedDevice(deviceId: String?) {
    lastDiscussedDeviceId = deviceId
  }

  fun setPendingConfirmation(intent: AssistantIntent) {
    lastPendingConfirmationIntent = intent
  }

  fun clearPendingConfirmation() {
    lastPendingConfirmationIntent = null
  }

  fun hasPendingConfirmation(): Boolean = lastPendingConfirmationIntent != null

  fun recordAction(type: AssistantIntentType) {
    _recentActions.addLast(type)
    while (_recentActions.size > maxRecentActions) {
      _recentActions.removeFirst()
    }
  }
}
