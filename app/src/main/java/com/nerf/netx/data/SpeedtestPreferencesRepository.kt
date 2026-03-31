package com.nerf.netx.data

import android.content.Context
import android.content.SharedPreferences
import com.nerf.netx.domain.SpeedtestConfig
import com.nerf.netx.domain.SpeedtestHistoryEntry
import com.nerf.netx.domain.SpeedtestServerScope
import com.nerf.netx.domain.SpeedtestTargetMode
import org.json.JSONArray
import org.json.JSONObject

internal interface SpeedtestPreferencesRepository {
  fun loadConfig(defaultConfig: SpeedtestConfig): SpeedtestConfig
  fun persistConfig(config: SpeedtestConfig)
  fun loadHistory(): List<SpeedtestHistoryEntry>
  fun persistHistory(entries: List<SpeedtestHistoryEntry>)
  fun clearHistory()
}

internal class SharedPrefsSpeedtestPreferencesRepository(
  context: Context
) : SpeedtestPreferencesRepository {
  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences("nerf_speedtest_prefs", Context.MODE_PRIVATE)
  private val historyKey = "speedtest_history_json"
  private val configKey = "speedtest_config_json"

  override fun loadConfig(defaultConfig: SpeedtestConfig): SpeedtestConfig {
    val raw = prefs.getString(configKey, null) ?: return defaultConfig
    return runCatching {
      val obj = JSONObject(raw)
      SpeedtestConfig(
        targetMode = obj.optString("targetMode").takeIf { it.isNotBlank() }?.let {
          runCatching { SpeedtestTargetMode.valueOf(it) }.getOrDefault(SpeedtestTargetMode.PUBLIC_INTERNET)
        } ?: SpeedtestTargetMode.PUBLIC_INTERNET,
        serverMode = obj.optString("serverMode").ifBlank { "AUTO" },
        selectedServerId = obj.optString("selectedServerId").ifBlank { null },
        downloadSizesBytes = obj.optJSONArray("downloadSizesBytes")?.toIntList() ?: defaultConfig.downloadSizesBytes,
        uploadSizesBytes = obj.optJSONArray("uploadSizesBytes")?.toIntList() ?: defaultConfig.uploadSizesBytes,
        threads = obj.optInt("threads", defaultConfig.threads),
        durationMs = obj.optLong("durationMs", defaultConfig.durationMs),
        timeoutMs = obj.optLong("timeoutMs", defaultConfig.timeoutMs),
        privateServerName = obj.optString("privateServerName").ifBlank { defaultConfig.privateServerName },
        privateServerBaseUrl = obj.optString("privateServerBaseUrl").ifBlank { null },
        privatePingPath = obj.optString("privatePingPath").ifBlank { defaultConfig.privatePingPath },
        privateDownloadSmallPath = obj.optString("privateDownloadSmallPath").ifBlank { defaultConfig.privateDownloadSmallPath },
        privateDownloadLargePath = obj.optString("privateDownloadLargePath").ifBlank { defaultConfig.privateDownloadLargePath },
        privateUploadPath = obj.optString("privateUploadPath").ifBlank { defaultConfig.privateUploadPath }
      ).sanitize()
    }.getOrDefault(defaultConfig)
  }

  override fun persistConfig(config: SpeedtestConfig) {
    val obj = JSONObject()
      .put("targetMode", config.targetMode.name)
      .put("serverMode", config.serverMode)
      .put("selectedServerId", config.selectedServerId)
      .put("downloadSizesBytes", JSONArray(config.downloadSizesBytes))
      .put("uploadSizesBytes", JSONArray(config.uploadSizesBytes))
      .put("threads", config.threads)
      .put("durationMs", config.durationMs)
      .put("timeoutMs", config.timeoutMs)
      .put("privateServerName", config.privateServerName)
      .put("privateServerBaseUrl", config.privateServerBaseUrl)
      .put("privatePingPath", config.privatePingPath)
      .put("privateDownloadSmallPath", config.privateDownloadSmallPath)
      .put("privateDownloadLargePath", config.privateDownloadLargePath)
      .put("privateUploadPath", config.privateUploadPath)
    prefs.edit().putString(configKey, obj.toString()).apply()
  }

  override fun loadHistory(): List<SpeedtestHistoryEntry> {
    val raw = prefs.getString(historyKey, null) ?: return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      (0 until arr.length()).mapNotNull { idx ->
        val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
        SpeedtestHistoryEntry(
          id = obj.optString("id"),
          timestamp = obj.optLong("timestamp"),
          serverName = obj.optString("serverName").ifBlank { null },
          targetMode = obj.optString("targetMode").takeIf { it.isNotBlank() }?.let {
            runCatching { SpeedtestTargetMode.valueOf(it) }.getOrDefault(SpeedtestTargetMode.PUBLIC_INTERNET)
          } ?: SpeedtestTargetMode.PUBLIC_INTERNET,
          serverScope = obj.optString("serverScope").takeIf { it.isNotBlank() }?.let {
            runCatching { SpeedtestServerScope.valueOf(it) }.getOrNull()
          },
          pingMs = obj.optDoubleOrNull("pingMs"),
          downMbps = obj.optDoubleOrNull("downMbps"),
          upMbps = obj.optDoubleOrNull("upMbps"),
          jitterMs = obj.optDoubleOrNull("jitterMs"),
          lossPct = obj.optDoubleOrNull("lossPct")
        )
      }
    }.getOrDefault(emptyList())
  }

  override fun persistHistory(entries: List<SpeedtestHistoryEntry>) {
    val arr = JSONArray()
    entries.forEach { entry ->
      arr.put(
        JSONObject()
          .put("id", entry.id)
          .put("timestamp", entry.timestamp)
          .put("serverName", entry.serverName)
          .put("targetMode", entry.targetMode.name)
          .put("serverScope", entry.serverScope?.name)
          .put("pingMs", entry.pingMs)
          .put("downMbps", entry.downMbps)
          .put("upMbps", entry.upMbps)
          .put("jitterMs", entry.jitterMs)
          .put("lossPct", entry.lossPct)
      )
    }
    prefs.edit().putString(historyKey, arr.toString()).apply()
  }

  override fun clearHistory() {
    prefs.edit().remove(historyKey).apply()
  }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
  if (isNull(key)) return null
  return runCatching { getDouble(key) }.getOrNull()
}

private fun JSONArray.toIntList(): List<Int> {
  return (0 until length()).mapNotNull { idx ->
    runCatching { getInt(idx) }.getOrNull()
  }
}
