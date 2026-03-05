package com.nerf.netx.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nerf.netx.domain.SpeedtestService
import kotlinx.coroutines.launch

@Composable
fun SpeedtestScreen(service: SpeedtestService) {
  val ui by service.ui.collectAsState()
  val scope = rememberCoroutineScope()

  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("NERF SPEEDTEST", style = MaterialTheme.typography.titleLarge)

    ElevatedCard {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Gauge (mobile-safe sizing)", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { ui.progress01 }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("DL " + String.format("%.1f", ui.downMbps) + " Mbps")
          Text("UL " + String.format("%.1f", ui.upMbps) + " Mbps")
        }
        Text("Latency " + ui.latencyMs.toString() + " ms  |  " + ui.phase)
      }
    }

    ElevatedCard {
      Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { scope.launch { service.start() } }, enabled = !ui.running) { Text("Start") }
        OutlinedButton(onClick = { scope.launch { service.stop() } }, enabled = ui.running) { Text("Stop") }
        TextButton(onClick = { scope.launch { service.reset() } }) { Text("Reset") }
      }
    }
  }
}
