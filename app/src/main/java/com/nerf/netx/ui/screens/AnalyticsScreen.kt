package com.nerf.netx.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nerf.netx.domain.AnalyticsService
import com.nerf.netx.domain.ServiceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

private data class TrendUi(
  val latencyDirection: String = "STABLE",
  val onlineDirection: String = "STABLE",
  val throughputDirection: String = "STABLE"
)

private data class HealthUi(
  val name: String,
  val ip: String,
  val macMasked: String?,
  val score: Int,
  val onlineRatio: Double,
  val medianLatencyMs: Double?,
  val reasons: List<String>
)

private data class ReportUi(
  val timestamp: Long,
  val durationMs: Int?,
  val devicesFound: Int,
  val devicesOnline: Int,
  val newDevices: List<String>,
  val offlineDevices: List<String>,
  val slowDevices: List<String>,
  val warnings: List<String>
)

@Composable
fun AnalyticsScreen(service: AnalyticsService) {
  val events by service.events.collectAsState()
  val snap by service.snapshot.collectAsState()
  val scope = rememberCoroutineScope()
  val snackbarHost = remember { SnackbarHostState() }

  var payloadRaw by remember { mutableStateOf<String?>(null) }
  var trend by remember { mutableStateOf(TrendUi()) }
  var seriesLatency by remember { mutableStateOf<List<Double>>(emptyList()) }
  var seriesOnline by remember { mutableStateOf<List<Double>>(emptyList()) }
  var seriesThroughput by remember { mutableStateOf<List<Double>>(emptyList()) }
  var health by remember { mutableStateOf<List<HealthUi>>(emptyList()) }
  var reports by remember { mutableStateOf<List<ReportUi>>(emptyList()) }
  var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
  var expandedReports = remember { mutableStateMapOf<Long, Boolean>() }
  var exportRunning by remember { mutableStateOf(false) }

  suspend fun loadPayload() {
    val json = readPayloadJson(service, events)
    payloadRaw = json
    if (json.isNullOrBlank()) return

    runCatching {
      val obj = JSONObject(json)
      val trendObj = obj.optJSONObject("trend")
      trend = TrendUi(
        latencyDirection = trendObj?.optString("latencyDirection").orEmpty().ifBlank { "STABLE" },
        onlineDirection = trendObj?.optString("onlineDirection").orEmpty().ifBlank { "STABLE" },
        throughputDirection = trendObj?.optString("throughputDirection").orEmpty().ifBlank { "STABLE" }
      )

      val seriesArr = obj.optJSONArray("series")
      val latency = mutableListOf<Double>()
      val online = mutableListOf<Double>()
      val throughput = mutableListOf<Double>()
      if (seriesArr != null) {
        for (i in 0 until seriesArr.length()) {
          val item = seriesArr.optJSONObject(i) ?: continue
          item.optDoubleOrNull("medianLatencyOnline")?.let { latency += it }
          online += item.optDouble("deviceCountOnline")
          item.optDoubleOrNull("downloadMbps")?.let { throughput += it }
        }
      }
      seriesLatency = latency.takeLast(40)
      seriesOnline = online.takeLast(40)
      seriesThroughput = throughput.takeLast(40)

      val healthArr = obj.optJSONArray("deviceHealth")
      val healthRows = mutableListOf<HealthUi>()
      if (healthArr != null) {
        for (i in 0 until healthArr.length()) {
          val item = healthArr.optJSONObject(i) ?: continue
          healthRows += HealthUi(
            name = item.optString("name").ifBlank { "Unknown" },
            ip = item.optString("ip"),
            macMasked = item.optString("macMasked").takeIf { it.isNotBlank() },
            score = item.optInt("score"),
            onlineRatio = item.optDouble("onlineRatio"),
            medianLatencyMs = item.optDoubleOrNull("medianLatencyMs"),
            reasons = item.optStringList("reasons")
          )
        }
      }
      health = healthRows.sortedBy { it.score }.take(10)

      val reportsArr = obj.optJSONArray("scanReports")
      val reportRows = mutableListOf<ReportUi>()
      if (reportsArr != null) {
        for (i in 0 until reportsArr.length()) {
          val item = reportsArr.optJSONObject(i) ?: continue
          reportRows += ReportUi(
            timestamp = item.optLong("timestamp"),
            durationMs = item.optIntOrNull("durationMs"),
            devicesFound = item.optInt("devicesFound"),
            devicesOnline = item.optInt("devicesOnline"),
            newDevices = item.optStringList("newDevices"),
            offlineDevices = item.optStringList("offlineDevices"),
            slowDevices = item.optStringList("slowDevices"),
            warnings = item.optStringList("warnings")
          )
        }
      }
      reports = reportRows.sortedByDescending { it.timestamp }

      warnings = obj.optJSONArray("warnings")?.let { arr ->
        (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { s -> s.isNotBlank() } }
      }.orEmpty()
    }
  }

  LaunchedEffect(Unit) {
    service.refresh()
    loadPayload()
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("ANALYTICS", style = MaterialTheme.typography.titleLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
          scope.launch {
            service.refresh()
            loadPayload()
          }
        }) { Text("Refresh") }
        Button(
          enabled = !exportRunning,
          onClick = {
            scope.launch {
              exportRunning = true
              val outcome = exportPayload(service)
              exportRunning = false
              snackbarHost.showSnackbar(outcome)
              service.refresh()
              loadPayload()
            }
          }
        ) {
          if (exportRunning) {
            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
          } else {
            Text("Export Analytics")
          }
        }
      }
    }

    SnackbarHost(hostState = snackbarHost)

    if (snap.status == ServiceStatus.NO_DATA) {
      ElevatedCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("No analytics data yet", style = MaterialTheme.typography.titleMedium)
          Text("Run at least one LAN scan and one speedtest to populate trends, health scores, and reports.")
        }
      }
      return@Column
    }

    ElevatedCard {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Snapshot", style = MaterialTheme.typography.titleMedium)
        Text("Online devices: ${snap.reachableCount} / ${snap.deviceCount}")
        Text("Median latency: ${snap.medianRttMs?.let { "%.1f ms".format(it) } ?: "N/A"}")
        Text("Scan duration: ${snap.scanDurationMs?.let { "$it ms" } ?: "N/A"}")
        Text("Last speedtest: ping ${snap.latencyMs?.let { "%.1f".format(it) } ?: "N/A"} ms, down ${snap.downMbps?.let { "%.1f".format(it) } ?: "N/A"} Mbps, up ${snap.upMbps?.let { "%.1f".format(it) } ?: "N/A"} Mbps")
        Text("Warnings: ${if (warnings.isEmpty()) "None" else warnings.joinToString(" | ")}", fontWeight = FontWeight.Medium)
        if (!snap.message.isNullOrBlank()) {
          Text(snap.message ?: "", style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    ElevatedCard {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Trends", style = MaterialTheme.typography.titleMedium)
        Text("Latency: ${trendText(trend.latencyDirection)}")
        Text("Online devices: ${trendText(trend.onlineDirection)}")
        Text("Throughput: ${trendText(trend.throughputDirection)}")
        Sparkline("Latency", seriesLatency, Color(0xFF7ED0FF))
        Sparkline("Online", seriesOnline, Color(0xFF8BE3A8))
        Sparkline("Throughput", seriesThroughput, Color(0xFFFFD07A))
      }
    }

    ElevatedCard(Modifier.weight(1f)) {
      LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
          Text("Device Health (worst 10)", style = MaterialTheme.typography.titleMedium)
        }
        items(health, key = { "${it.ip}-${it.score}" }) { row ->
          ElevatedCard {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("${row.name} (${row.ip})")
              Text("MAC ${row.macMasked ?: "N/A"}")
              Text("Score ${row.score} | Online ${(row.onlineRatio * 100.0).toInt()}% | Median ${row.medianLatencyMs?.let { "%.0fms".format(it) } ?: "N/A"}")
              Text(if (row.reasons.isEmpty()) "Healthy" else row.reasons.joinToString(" | "), style = MaterialTheme.typography.bodySmall)
            }
          }
        }

        item {
          Text("Scan Reports", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        }
        items(reports, key = { it.timestamp }) { report ->
          val expanded = expandedReports[report.timestamp] == true
          ElevatedCard(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { expandedReports[report.timestamp] = !expanded }
          ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("${formatTime(report.timestamp)} | ${report.devicesOnline}/${report.devicesFound} online")
              Text("Duration: ${report.durationMs?.let { "$it ms" } ?: "N/A"}")
              if (expanded) {
                Text("New: ${report.newDevices.joinToString(", ").ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                Text("Offline: ${report.offlineDevices.joinToString(", ").ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                Text("Slow: ${report.slowDevices.joinToString(", ").ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                Text("Warnings: ${report.warnings.joinToString(" | ").ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Sparkline(label: String, values: List<Double>, color: Color) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(72.dp)
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        if (values.size < 2) {
          drawLine(
            color = Color(0x33FFFFFF),
            start = Offset(0f, size.height * 0.5f),
            end = Offset(size.width, size.height * 0.5f),
            strokeWidth = 2f
          )
          return@Canvas
        }

        val minV = values.minOrNull() ?: 0.0
        val maxV = values.maxOrNull() ?: 1.0
        val spread = max(1e-6, maxV - minV)

        var prev: Offset? = null
        values.forEachIndexed { idx, v ->
          val x = (idx.toFloat() / (values.lastIndex.toFloat().coerceAtLeast(1f))) * size.width
          val yNorm = ((v - minV) / spread).toFloat()
          val y = size.height - (yNorm * size.height)
          val curr = Offset(x, y)
          if (prev != null) {
            drawLine(
              color = color,
              start = prev!!,
              end = curr,
              strokeWidth = 2.4f,
              cap = StrokeCap.Round
            )
          }
          prev = curr
        }
        drawRect(
          color = color.copy(alpha = 0.18f),
          style = Stroke(width = 1f)
        )
      }
    }
  }
}

private suspend fun readPayloadJson(service: AnalyticsService, events: List<String>): String? {
  val reflected = withContext(Dispatchers.Default) {
    runCatching {
      val method = service.javaClass.methods.firstOrNull { it.name == "getAnalyticsPayloadJson" }
      method?.invoke(service) as? String
    }.getOrNull()
  }
  if (!reflected.isNullOrBlank()) return reflected

  return events.firstOrNull { it.startsWith("PAYLOAD_JSON=") }?.removePrefix("PAYLOAD_JSON=")
}

private suspend fun exportPayload(service: AnalyticsService): String {
  return withContext(Dispatchers.IO) {
    runCatching {
      val method = service.javaClass.methods.firstOrNull { it.name == "exportAnalyticsJsonToFile" }
        ?: error("Export hook unavailable")
      val path = method.invoke(service) as? String ?: error("Export path missing")
      "Saved: $path"
    }.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
  }
}

private fun trendText(direction: String): String {
  return when (direction.uppercase()) {
    "IMPROVING" -> "Improving"
    "WORSENING" -> "Worsening"
    else -> "Stable"
  }
}

private fun formatTime(epochMs: Long): String {
  val now = System.currentTimeMillis()
  val diff = (now - epochMs).coerceAtLeast(0L) / 1000L
  return when {
    diff < 60L -> "${diff}s ago"
    diff < 3600L -> "${diff / 60L}m ago"
    diff < 86_400L -> "${diff / 3600L}h ago"
    else -> "${diff / 86_400L}d ago"
  }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
  if (!has(name) || isNull(name)) return null
  return optDouble(name)
}

private fun JSONObject.optIntOrNull(name: String): Int? {
  if (!has(name) || isNull(name)) return null
  return optInt(name)
}

private fun JSONObject.optStringList(name: String): List<String> {
  val arr = optJSONArray(name) ?: return emptyList()
  return (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
}