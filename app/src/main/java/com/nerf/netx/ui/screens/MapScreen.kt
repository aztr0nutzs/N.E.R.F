package com.nerf.netx.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.nerf.netx.domain.MapService
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MapScreen(service: MapService) {
  val nodes by service.nodes.collectAsState()
  val scope = rememberCoroutineScope()

  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("NETWORK MAP", style = MaterialTheme.typography.titleLarge)
      Button(onClick = { scope.launch { service.refresh() } }) { Text("Refresh") }
    }

    ElevatedCard(Modifier.fillMaxWidth().weight(0.62f)) {
      Box(Modifier.fillMaxSize().padding(12.dp)) {
        Canvas(Modifier.fillMaxSize()) {
          val cx = size.width / 2f
          val cy = size.height / 2f
          val r = minOf(size.width, size.height) * 0.33f
          val count = if (nodes.isEmpty()) 1 else nodes.size
          for (i in nodes.indices) {
            val ang = (i.toFloat() / count.toFloat()) * (Math.PI * 2.0).toFloat()
            val p = Offset(cx + r * cos(ang), cy + r * sin(ang))
            val s = nodes[i].strength.toFloat()
            val rad = 10f + (s / 9f)
            drawCircle(MaterialTheme.colorScheme.primary, radius = rad, center = p, alpha = 0.9f)
          }
        }
      }
    }

    ElevatedCard(Modifier.fillMaxWidth().weight(0.38f)) {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        items(nodes, key = { it.id }) { n ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
              Text(n.label, style = MaterialTheme.typography.bodyMedium)
              Text(n.ip, style = MaterialTheme.typography.bodySmall)
            }
            Text("Signal ${n.strength}", style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }
}
