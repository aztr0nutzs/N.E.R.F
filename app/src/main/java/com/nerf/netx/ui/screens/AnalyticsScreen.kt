package com.nerf.netx.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nerf.netx.domain.AnalyticsService
import kotlinx.coroutines.launch

@Composable
fun AnalyticsScreen(service: AnalyticsService) {
  val events by service.events.collectAsState()
  val snap by service.snapshot.collectAsState()
  val scope = rememberCoroutineScope()

  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("ANALYTICS", style = MaterialTheme.typography.titleLarge)
      Button(onClick = { scope.launch { service.refresh() } }) { Text("Refresh") }
    }

    ElevatedCard {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Status ${snap.status}")
        Text("Down ${snap.downMbps?.let { "%.1f".format(it) + " Mbps" } ?: "N/A"}")
        Text("Up ${snap.upMbps?.let { "%.1f".format(it) + " Mbps" } ?: "N/A"}")
        Text("Latency ${snap.latencyMs?.let { "%.1f".format(it) + " ms" } ?: "N/A"}")
        Text("Jitter ${snap.jitterMs?.let { "%.1f".format(it) + " ms" } ?: "N/A"}")
        Text("Loss ${snap.packetLossPct?.let { "%.1f".format(it) + " %" } ?: "N/A"}")
        Text("Devices ${snap.deviceCount}")
        Text("Reachable ${snap.reachableCount}")
        Text("Avg RTT ${snap.avgRttMs?.let { "%.1f".format(it) + " ms" } ?: "N/A"}")
        Text("Median RTT ${snap.medianRttMs?.let { "%.1f".format(it) + " ms" } ?: "N/A"}")
        Text("Scan Duration ${snap.scanDurationMs?.toString()?.plus(" ms") ?: "N/A"}")
        if (!snap.message.isNullOrBlank()) {
          Text(snap.message ?: "", style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(events) { e ->
        ElevatedCard { Text(e, modifier = Modifier.padding(12.dp)) }
      }
    }
  }
}
