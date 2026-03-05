package com.nerf.netx.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nerf.netx.domain.SpeedtestHistoryEntry
import com.nerf.netx.domain.SpeedtestPhase
import com.nerf.netx.domain.SpeedtestService
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import java.util.Locale

@Composable
fun SpeedtestScreen(service: SpeedtestService) {
  val ui by service.ui.collectAsState()
  val servers by service.servers.collectAsState()
  val config by service.config.collectAsState()
  val history by service.history.collectAsState()
  val latest by service.latestResult.collectAsState()
  val scope = rememberCoroutineScope()

  var serverMenuExpanded by remember { mutableStateOf(false) }
  var selectedHistoryId by remember { mutableStateOf<String?>(null) }

  LazyColumn(
    modifier = Modifier
      .fillMaxWidth()
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Text("NERF SPEEDTEST", style = MaterialTheme.typography.titleLarge)
    }

    item {
      ElevatedCard {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text("Live Gauge", style = MaterialTheme.typography.titleMedium)
          SpeedGauge(
            mbps = ui.currentMbps ?: when (ui.phaseEnum) {
              SpeedtestPhase.DOWNLOAD -> ui.downMbps
              SpeedtestPhase.UPLOAD -> ui.upMbps
              else -> null
            },
            phase = ui.phaseEnum
          )
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Down ${ui.downMbps?.format1() ?: "N/A"} Mbps")
            Text("Up ${ui.upMbps?.format1() ?: "N/A"} Mbps")
          }
          Text("Ping ${ui.pingMs?.format1() ?: "N/A"} ms  |  Jitter ${ui.jitterMs?.format1() ?: "N/A"} ms")
          Text("Loss ${ui.packetLossPct?.format2() ?: "N/A"}%")
          Text("Phase ${ui.phaseEnum.name}  |  ${ui.status}")
          if (!ui.message.isNullOrBlank()) {
            Text(ui.message ?: "", style = MaterialTheme.typography.bodySmall)
          }
          if (!ui.reason.isNullOrBlank()) {
            Text(ui.reason ?: "", style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }

    item {
      ElevatedCard {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text("Realtime Throughput", style = MaterialTheme.typography.titleMedium)
          ThroughputGraph(samples = ui.samples, modifier = Modifier.fillMaxWidth().height(180.dp))
          Text("Samples ${ui.samples.size}", style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    item {
      ElevatedCard {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text("Server Selection", style = MaterialTheme.typography.titleMedium)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = {
                scope.launch {
                  service.updateConfig(config.copy(serverMode = "AUTO", selectedServerId = null))
                }
              }
            ) {
              Text(if (config.serverMode == "AUTO") "AUTO ON" else "AUTO")
            }
            OutlinedButton(
              onClick = {
                val defaultServerId = config.selectedServerId ?: servers.firstOrNull()?.id
                scope.launch {
                  service.updateConfig(config.copy(serverMode = "MANUAL", selectedServerId = defaultServerId))
                }
              }
            ) {
              Text(if (config.serverMode == "MANUAL") "MANUAL ON" else "MANUAL")
            }
          }

          if (config.serverMode == "MANUAL") {
            Box {
              OutlinedButton(onClick = { serverMenuExpanded = true }) {
                val selected = servers.firstOrNull { it.id == config.selectedServerId }
                Text(selected?.name ?: "Select Server")
              }
              androidx.compose.material3.DropdownMenu(
                expanded = serverMenuExpanded,
                onDismissRequest = { serverMenuExpanded = false }
              ) {
                servers.forEach { server ->
                  androidx.compose.material3.DropdownMenuItem(
                    text = { Text(server.name) },
                    onClick = {
                      serverMenuExpanded = false
                      scope.launch {
                        service.updateConfig(config.copy(serverMode = "MANUAL", selectedServerId = server.id))
                      }
                    }
                  )
                }
              }
            }
          }

          val activeServerName = ui.activeServerName
            ?: servers.firstOrNull { it.id == config.selectedServerId }?.name
          Text("Active server: ${activeServerName ?: "AUTO (latency-based)"}")
          Text(
            "Threads ${config.threads} | Duration ${config.durationMs}ms | Timeout ${config.timeoutMs}ms",
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
    }

    item {
      ElevatedCard {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text("Phases", style = MaterialTheme.typography.titleMedium)
          PhaseRow(current = ui.phaseEnum)
          androidx.compose.material3.LinearProgressIndicator(progress = { ui.progress01 }, modifier = Modifier.fillMaxWidth())
        }
      }
    }

    item {
      ElevatedCard {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Button(onClick = { scope.launch { service.start() } }, enabled = !ui.running) { Text("START") }
          OutlinedButton(onClick = { scope.launch { service.stop() } }, enabled = ui.running) { Text("ABORT") }
          TextButton(onClick = { scope.launch { service.reset() } }) { Text("RESET") }
        }
      }
    }

    item {
      ElevatedCard {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("History", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { scope.launch { service.clearHistory() } }) { Text("Clear History") }
          }

          if (history.isEmpty()) {
            Text("No history yet", style = MaterialTheme.typography.bodySmall)
          } else {
            HistoryList(
              entries = history,
              selectedId = selectedHistoryId,
              onSelect = { selectedHistoryId = it }
            )
          }
        }
      }
    }

    item {
      latest?.let {
        ElevatedCard {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text("Latest Result", style = MaterialTheme.typography.titleMedium)
            Text("Server ${it.serverName ?: "N/A"}")
            Text("Ping ${it.pingMs?.format1() ?: "N/A"} ms | Jitter ${it.jitterMs?.format1() ?: "N/A"} ms | Loss ${it.packetLossPct?.format2() ?: "N/A"}%")
            Text("Down ${it.downloadMbps?.format1() ?: "N/A"} Mbps | Up ${it.uploadMbps?.format1() ?: "N/A"} Mbps")
            if (!it.error.isNullOrBlank()) {
              Text("Error ${it.error}")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SpeedGauge(mbps: Double?, phase: SpeedtestPhase) {
  val speed = (mbps ?: 0.0).coerceAtLeast(0.0)
  val maxSpeed = 1000.0
  val normalized = (ln(speed + 1.0) / ln(maxSpeed + 1.0)).toFloat().coerceIn(0f, 1f)

  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Canvas(modifier = Modifier.size(220.dp)) {
        val stroke = 14.dp.toPx()
        drawArc(
          color = Color(0xFF1E88E5),
          startAngle = 150f,
          sweepAngle = 240f,
          useCenter = false,
          style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawArc(
          color = Color(0xFF66BB6A),
          startAngle = 150f,
          sweepAngle = 240f * normalized,
          useCenter = false,
          style = Stroke(stroke, cap = StrokeCap.Round)
        )

        val angle = Math.toRadians((150f + 240f * normalized).toDouble())
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.37f
        val needleX = cx + (radius * cos(angle)).toFloat()
        val needleY = cy + (radius * sin(angle)).toFloat()

        drawLine(
          color = Color.White,
          start = Offset(cx, cy),
          end = Offset(needleX, needleY),
          strokeWidth = 6f,
          cap = StrokeCap.Round
        )
        drawCircle(color = Color.White, radius = 7f, center = Offset(cx, cy))
      }
    }
    Text("${speed.format1()} Mbps", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(phase.name, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun ThroughputGraph(samples: List<com.nerf.netx.domain.ThroughputSample>, modifier: Modifier = Modifier) {
  val maxY = max(10.0, samples.maxOfOrNull { it.mbps } ?: 10.0)
  Canvas(modifier = modifier.background(Color(0x1AFFFFFF), RoundedCornerShape(10.dp)).padding(6.dp)) {
    if (samples.size < 2) return@Canvas

    val pathDown = Path()
    val pathUp = Path()
    var downStarted = false
    var upStarted = false

    val width = size.width
    val height = size.height
    val plot = samples.takeLast(180)
    plot.forEachIndexed { index, sample ->
      val x = (index.toFloat() / (plot.size - 1).coerceAtLeast(1)) * width
      val y = height - ((sample.mbps / maxY).toFloat() * height)
      when (sample.phase) {
        SpeedtestPhase.DOWNLOAD -> {
          if (!downStarted) {
            pathDown.moveTo(x, y)
            downStarted = true
          } else {
            pathDown.lineTo(x, y)
          }
        }
        SpeedtestPhase.UPLOAD -> {
          if (!upStarted) {
            pathUp.moveTo(x, y)
            upStarted = true
          } else {
            pathUp.lineTo(x, y)
          }
        }
        else -> Unit
      }
    }

    drawPath(pathDown, color = Color(0xFF42A5F5), style = Stroke(width = 4f))
    drawPath(pathUp, color = Color(0xFFFFA726), style = Stroke(width = 4f))
  }
}

@Composable
private fun PhaseRow(current: SpeedtestPhase) {
  val phases = listOf(
    SpeedtestPhase.PING,
    SpeedtestPhase.DOWNLOAD,
    SpeedtestPhase.UPLOAD,
    SpeedtestPhase.DONE
  )
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    phases.forEach { phase ->
      val active = phase == current
      Box(
        modifier = Modifier
          .weight(1f)
          .background(if (active) Color(0xFF2E7D32) else Color(0xFF455A64), RoundedCornerShape(8.dp))
          .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(phase.name, style = MaterialTheme.typography.labelSmall, color = Color.White)
      }
    }
  }
}

@Composable
private fun HistoryList(
  entries: List<SpeedtestHistoryEntry>,
  selectedId: String?,
  onSelect: (String) -> Unit
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    entries.take(25).forEach { entry ->
      val selected = selectedId == entry.id
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(if (selected) Color(0x2232A852) else Color(0x11222222), RoundedCornerShape(8.dp))
          .clickable { onSelect(entry.id) }
          .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
      ) {
        Text("${entry.serverName ?: "Unknown Server"}  |  ${entry.timestamp}")
        Text("Ping ${entry.pingMs?.format1() ?: "N/A"} ms  Down ${entry.downMbps?.format1() ?: "N/A"} Mbps  Up ${entry.upMbps?.format1() ?: "N/A"} Mbps")
        if (selected) {
          Text("Jitter ${entry.jitterMs?.format1() ?: "N/A"} ms  Loss ${entry.lossPct?.format2() ?: "N/A"}%", style = MaterialTheme.typography.bodySmall)
        }
      }
    }
  }
}

private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)
private fun Double.format2(): String = String.format(Locale.US, "%.2f", this)
