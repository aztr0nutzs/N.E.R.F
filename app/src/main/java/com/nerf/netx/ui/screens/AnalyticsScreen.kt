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
        Text("Down ${"%.1f".format(snap.downMbps)} Mbps")
        Text("Up ${"%.1f".format(snap.upMbps)} Mbps")
        Text("Latency ${"%.1f".format(snap.latencyMs)} ms")
        Text("Jitter ${"%.1f".format(snap.jitterMs)} ms")
        Text("Loss ${"%.1f".format(snap.packetLossPct)} %")
        Text("Devices ${snap.deviceCount}")
      }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(events) { e ->
        ElevatedCard { Text(e, modifier = Modifier.padding(12.dp)) }
      }
    }
  }
}
