package com.nerf.netx.assistant.orchestrator

import com.nerf.netx.assistant.model.AssistantDeviceCandidate
import com.nerf.netx.assistant.model.AssistantEntityMatchType
import com.nerf.netx.assistant.model.AssistantEntityResolution
import com.nerf.netx.domain.Device

class AssistantEntityResolver {

  fun resolveDevice(
    query: String?,
    devices: List<Device>,
    lastDiscussedDeviceId: String? = null,
    allowContextFallback: Boolean = true
  ): AssistantEntityResolution {
    if (devices.isEmpty()) {
      return AssistantEntityResolution.Missing(query)
    }

    val trimmedQuery = query?.trim()
    if (trimmedQuery.isNullOrBlank()) {
      if (!allowContextFallback) {
        return AssistantEntityResolution.Missing(null)
      }
      val remembered = lastDiscussedDeviceId?.let { id -> devices.firstOrNull { it.id == id } }
      return if (remembered != null) {
        AssistantEntityResolution.Resolved(toCandidate(remembered, AssistantEntityMatchType.CONTEXT_LAST_DISCUSSION))
      } else {
        AssistantEntityResolution.Missing(null)
      }
    }

    val normalizedQuery = normalize(trimmedQuery)
    if (normalizedQuery in CONTEXT_REFERENCE_WORDS) {
      val remembered = lastDiscussedDeviceId?.let { id -> devices.firstOrNull { it.id == id } }
      if (remembered != null) {
        return AssistantEntityResolution.Resolved(toCandidate(remembered, AssistantEntityMatchType.CONTEXT_LAST_DISCUSSION))
      }
    }

    findExactIp(trimmedQuery, devices)?.let { return it }
    findExactHostname(trimmedQuery, devices)?.let { return it }
    findExactName(trimmedQuery, devices)?.let { return it }
    findNickname(trimmedQuery, devices)?.let { return it }
    findVendor(trimmedQuery, devices)?.let { return it }
    findFuzzy(trimmedQuery, devices)?.let { return it }

    return AssistantEntityResolution.Missing(trimmedQuery)
  }

  private fun findExactIp(query: String, devices: List<Device>): AssistantEntityResolution? {
    val matches = devices.filter { it.ip.equals(query, ignoreCase = true) }
    return toResolution(query, matches, AssistantEntityMatchType.EXACT_IP)
  }

  private fun findExactHostname(query: String, devices: List<Device>): AssistantEntityResolution? {
    val normalized = normalize(query)
    val matches = devices.filter { normalize(it.hostname) == normalized }
    return toResolution(query, matches, AssistantEntityMatchType.EXACT_HOSTNAME)
  }

  private fun findExactName(query: String, devices: List<Device>): AssistantEntityResolution? {
    val normalized = normalize(query)
    val matches = devices.filter { normalize(it.name) == normalized }
    return toResolution(query, matches, AssistantEntityMatchType.EXACT_NAME)
  }

  private fun findNickname(query: String, devices: List<Device>): AssistantEntityResolution? {
    val normalized = normalize(query)
    val matches = devices.filter { nicknameAliases(it).contains(normalized) }
    return toResolution(query, matches, AssistantEntityMatchType.EXACT_NICKNAME)
  }

  private fun findVendor(query: String, devices: List<Device>): AssistantEntityResolution? {
    val normalized = normalize(query)
    val matches = devices.filter {
      val vendor = normalize(it.vendor)
      val vendorAndType = normalize("${it.vendor} ${it.deviceType}")
      vendor == normalized || vendorAndType == normalized
    }
    return toResolution(query, matches, AssistantEntityMatchType.EXACT_VENDOR)
  }

  private fun findFuzzy(query: String, devices: List<Device>): AssistantEntityResolution? {
    val normalized = normalize(query)
    val scored = devices.mapNotNull { device ->
      val score = fuzzyScore(normalized, device)
      if (score <= 0) null else device to score
    }.sortedWith(compareByDescending<Pair<Device, Int>> { it.second }.thenBy { it.first.name })

    if (scored.isEmpty()) return null

    val topScore = scored.first().second
    if (topScore < 3) return null

    val topCandidates = scored
      .filter { it.second >= topScore - 1 }
      .take(5)
      .map { it.first }

    return if (topCandidates.size == 1) {
      AssistantEntityResolution.Resolved(toCandidate(topCandidates.first(), AssistantEntityMatchType.FUZZY_NAME))
    } else {
      AssistantEntityResolution.Ambiguous(
        query = query,
        candidates = topCandidates.map { toCandidate(it, AssistantEntityMatchType.FUZZY_NAME) }
      )
    }
  }

  private fun toResolution(
    query: String,
    matches: List<Device>,
    matchType: AssistantEntityMatchType
  ): AssistantEntityResolution? {
    if (matches.isEmpty()) return null
    return if (matches.size == 1) {
      AssistantEntityResolution.Resolved(toCandidate(matches.first(), matchType))
    } else {
      AssistantEntityResolution.Ambiguous(
        query = query,
        candidates = matches.take(6).map { toCandidate(it, matchType) }
      )
    }
  }

  private fun toCandidate(device: Device, matchType: AssistantEntityMatchType): AssistantDeviceCandidate {
    return AssistantDeviceCandidate(
      id = device.id,
      name = device.name.ifBlank { device.hostname.ifBlank { "Unknown device" } },
      ip = device.ip,
      vendor = device.vendorName ?: device.vendor.ifBlank { null },
      hostname = device.hostName ?: device.hostname.ifBlank { null },
      matchType = matchType
    )
  }

  private fun nicknameAliases(device: Device): Set<String> {
    val aliases = linkedSetOf<String>()
    listOf(device.name, device.hostname).forEach { source ->
      val normalized = normalize(source)
      if (normalized.isBlank()) return@forEach
      aliases += normalized

      val tokens = normalized.split(" ").filter { it.isNotBlank() }
      val filtered = tokens.filter { it !in GENERIC_DEVICE_WORDS }
      if (filtered.isNotEmpty()) {
        aliases += filtered.joinToString(" ")
      }
      if (filtered.size >= 2) {
        aliases += filtered.take(2).joinToString(" ")
        aliases += filtered.takeLast(2).joinToString(" ")
      }
      filtered.forEach { aliases += it }
    }
    return aliases
  }

  private fun fuzzyScore(query: String, device: Device): Int {
    val normalizedName = normalize(device.name)
    val normalizedHostname = normalize(device.hostname)
    val normalizedVendor = normalize(device.vendor)
    val searchable = listOf(normalizedName, normalizedHostname, normalizedVendor).joinToString(" ").trim()
    if (searchable.isBlank()) return 0

    var score = 0
    if (normalizedName.contains(query)) score += 6
    if (normalizedHostname.contains(query)) score += 5
    if (normalizedVendor.contains(query)) score += 3

    val queryTokens = query.split(" ").filter { it.isNotBlank() }
    val searchTokens = searchable.split(" ").filter { it.isNotBlank() }.toSet()
    val overlap = queryTokens.count { token -> searchTokens.any { it.contains(token) || token.contains(it) } }
    score += overlap * 2

    if (nicknameAliases(device).contains(query)) score += 6
    if (queryTokens.isNotEmpty() && queryTokens.all { token -> searchable.contains(token) }) {
      score += 3
    }

    return score
  }

  private fun normalize(value: String?): String {
    return value.orEmpty()
      .lowercase()
      .replace(Regex("[^a-z0-9]+"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  private companion object {
    val GENERIC_DEVICE_WORDS = setOf(
      "device",
      "phone",
      "iphone",
      "android",
      "pc",
      "computer",
      "laptop",
      "desktop",
      "router",
      "gateway",
      "wifi"
    )

    val CONTEXT_REFERENCE_WORDS = setOf(
      "it",
      "this",
      "that",
      "this device",
      "that device",
      "this one",
      "that one"
    )
  }
}
