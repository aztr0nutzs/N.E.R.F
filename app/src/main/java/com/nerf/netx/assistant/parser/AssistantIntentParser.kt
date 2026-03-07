package com.nerf.netx.assistant.parser

import com.nerf.netx.assistant.model.AssistantDestination
import com.nerf.netx.assistant.model.AssistantIntent
import com.nerf.netx.assistant.model.AssistantIntentType

class AssistantIntentParser {

  fun parse(message: String): AssistantIntent {
    val raw = message.trim()
    val normalized = normalize(raw)

    if (normalized in setOf("yes", "confirm", "do it", "proceed", "ok", "okay")) {
      return AssistantIntent(AssistantIntentType.CONFIRM_PENDING_ACTION, rawMessage = raw)
    }
    if (normalized in setOf("no", "cancel", "stop", "never mind", "dont", "don't")) {
      return AssistantIntent(AssistantIntentType.CANCEL_PENDING_ACTION, rawMessage = raw)
    }

    if (containsAny(normalized, "network status", "status summary", "network summary")) {
      return AssistantIntent(AssistantIntentType.NETWORK_STATUS_SUMMARY, rawMessage = raw)
    }
    if (containsAny(normalized, "run diagnostics", "diagnostics", "diagnose network")) {
      return AssistantIntent(AssistantIntentType.RUN_DIAGNOSTICS, rawMessage = raw)
    }
    if (containsAny(normalized, "scan network", "run scan", "start scan", "deep scan")) {
      return AssistantIntent(AssistantIntentType.SCAN_NETWORK, rawMessage = raw)
    }
    if (containsAny(normalized, "start speedtest", "run speedtest", "begin speedtest")) {
      return AssistantIntent(AssistantIntentType.START_SPEEDTEST, rawMessage = raw)
    }
    if (containsAny(normalized, "stop speedtest", "abort speedtest")) {
      return AssistantIntent(AssistantIntentType.STOP_SPEEDTEST, rawMessage = raw)
    }
    if (containsAny(normalized, "reset speedtest", "clear speedtest")) {
      return AssistantIntent(AssistantIntentType.RESET_SPEEDTEST, rawMessage = raw)
    }
    if (containsAny(normalized, "refresh topology", "refresh map", "reload topology")) {
      return AssistantIntent(AssistantIntentType.REFRESH_TOPOLOGY, rawMessage = raw)
    }

    parseOpenDestination(raw, normalized)?.let { return it }
    parseExplainIntent(raw, normalized)?.let { return it }
    parseRouterIntent(raw, normalized)?.let { return it }
    parseDeviceIntent(raw, normalized)?.let { return it }

    return AssistantIntent(AssistantIntentType.UNKNOWN, rawMessage = raw)
  }

  private fun parseOpenDestination(raw: String, normalized: String): AssistantIntent? {
    if (!containsAny(normalized, "open", "go to", "show")) return null
    val destination = when {
      normalized.contains("speedtest") || normalized.contains("speed") -> AssistantDestination.SPEEDTEST
      normalized.contains("devices") || normalized.contains("device list") -> AssistantDestination.DEVICES
      normalized.contains("map") || normalized.contains("topology") -> AssistantDestination.MAP
      normalized.contains("analytics") -> AssistantDestination.ANALYTICS
      else -> null
    } ?: return null
    return AssistantIntent(
      type = AssistantIntentType.OPEN_DESTINATION,
      rawMessage = raw,
      destination = destination
    )
  }

  private fun parseExplainIntent(raw: String, normalized: String): AssistantIntent? {
    val metricPrefix = listOf("explain metric", "what is metric", "what does")
    metricPrefix.forEach { prefix ->
      if (normalized.startsWith(prefix)) {
        val metric = normalized.removePrefix(prefix).trim().ifBlank { null }
        return AssistantIntent(AssistantIntentType.EXPLAIN_METRIC, rawMessage = raw, metricKey = metric)
      }
    }

    val featurePrefix = listOf("explain feature", "what is feature")
    featurePrefix.forEach { prefix ->
      if (normalized.startsWith(prefix)) {
        val feature = normalized.removePrefix(prefix).trim().ifBlank { null }
        return AssistantIntent(AssistantIntentType.EXPLAIN_FEATURE, rawMessage = raw, featureKey = feature)
      }
    }

    if (normalized.startsWith("explain ")) {
      val value = normalized.removePrefix("explain ").trim().ifBlank { null }
      if (value != null) {
        return AssistantIntent(AssistantIntentType.EXPLAIN_METRIC, rawMessage = raw, metricKey = value)
      }
    }

    return null
  }

  private fun parseRouterIntent(raw: String, normalized: String): AssistantIntent? {
    if (normalized.contains("guest wifi")) {
      return AssistantIntent(
        type = AssistantIntentType.SET_GUEST_WIFI,
        rawMessage = raw,
        toggleEnabled = parseOnOff(normalized)
      )
    }
    if (containsAny(normalized, "dns shield", "dnsshield")) {
      return AssistantIntent(
        type = AssistantIntentType.SET_DNS_SHIELD,
        rawMessage = raw,
        toggleEnabled = parseOnOff(normalized)
      )
    }
    if (containsAny(normalized, "reboot router", "restart router")) {
      return AssistantIntent(AssistantIntentType.REBOOT_ROUTER, rawMessage = raw)
    }
    if (containsAny(normalized, "flush dns", "clear dns cache")) {
      return AssistantIntent(AssistantIntentType.FLUSH_DNS, rawMessage = raw)
    }
    return null
  }

  private fun parseDeviceIntent(raw: String, normalized: String): AssistantIntent? {
    val pairs = listOf(
      AssistantIntentType.PING_DEVICE to listOf("ping device", "ping "),
      AssistantIntentType.BLOCK_DEVICE to listOf("block device", "block "),
      AssistantIntentType.UNBLOCK_DEVICE to listOf("unblock device", "unblock "),
      AssistantIntentType.PAUSE_DEVICE to listOf("pause device", "pause "),
      AssistantIntentType.RESUME_DEVICE to listOf("resume device", "resume ")
    )

    pairs.forEach { (type, patterns) ->
      patterns.forEach { pattern ->
        if (normalized.startsWith(pattern)) {
          val query = normalized.removePrefix(pattern).trim().ifBlank { null }
          return AssistantIntent(type = type, rawMessage = raw, targetDeviceQuery = query)
        }
      }
    }

    return null
  }

  private fun parseOnOff(value: String): Boolean? {
    return when {
      containsAny(value, " on", " enable", "enabled", "turn on") -> true
      containsAny(value, " off", " disable", "disabled", "turn off") -> false
      else -> null
    }
  }

  private fun containsAny(haystack: String, vararg needles: String): Boolean {
    return needles.any { haystack.contains(it) }
  }

  private fun normalize(value: String): String {
    return value.lowercase()
      .replace("?", " ")
      .replace(",", " ")
      .replace(Regex("\\s+"), " ")
      .trim()
  }
}
