package com.nerf.netx.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nerf.netx.domain.DevicesService
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(service: DevicesService) {
  val devices by service.devices.collectAsState()
  val scope = rememberCoroutineScope()
  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("DEVICES", style = MaterialTheme.typography.titleLarge)
      Button(onClick = { scope.launch { service.refresh() } }) { Text("Refresh") }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(devices, key = { it.id }) { d ->
        ElevatedCard {
          Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.name, style = MaterialTheme.typography.titleMedium)
            Text(d.ip + "  |  RSSI " + d.rssiDbm.toString() + " dBm")
            Text(if (d.online) "ONLINE" else "OFFLINE")
          }
        }
      }
    }
  }
}
