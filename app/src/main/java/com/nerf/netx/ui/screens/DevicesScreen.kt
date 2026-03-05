package com.nerf.netx.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nerf.netx.domain.DeviceControlService
import com.nerf.netx.domain.DevicesService
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(service: DevicesService, deviceControl: DeviceControlService) {
  val devices by service.devices.collectAsState()
  val scope = rememberCoroutineScope()
  var actionText by remember { mutableStateOf("Ready") }

  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("DEVICES", style = MaterialTheme.typography.titleLarge)
      Button(onClick = { scope.launch { service.refresh() } }) { Text("Refresh") }
    }

    Text(actionText, style = MaterialTheme.typography.bodySmall)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(devices, key = { it.id }) { d ->
        ElevatedCard {
          Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.name, style = MaterialTheme.typography.titleMedium)
            Text("${d.ip}  |  ${d.vendor}  |  MAC ${d.mac}")
            Text("RSSI ${d.rssiDbm} dBm  |  Risk ${d.riskScore}  |  ${if (d.online) "ONLINE" else "OFFLINE"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = {
                scope.launch {
                  val r = deviceControl.ping(d.id)
                  actionText = r.message + " (" + d.name + ")"
                }
              }) { Text("Ping") }
              OutlinedButton(onClick = {
                scope.launch {
                  val r = deviceControl.block(d.id)
                  actionText = r.message + " (" + d.name + ")"
                }
              }) { Text("Block") }
              OutlinedButton(onClick = {
                scope.launch {
                  val r = deviceControl.prioritize(d.id)
                  actionText = r.message + " (" + d.name + ")"
                }
              }) { Text("Prioritize") }
            }
          }
        }
      }
    }
  }
}
