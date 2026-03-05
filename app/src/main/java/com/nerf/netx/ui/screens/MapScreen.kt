package com.nerf.netx.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nerf.netx.domain.DeviceControlService
import com.nerf.netx.domain.DeviceDetails
import com.nerf.netx.domain.MapLink
import com.nerf.netx.domain.MapNode
import com.nerf.netx.domain.MapTopologyService
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private data class GraphLayout(
  val positions: Map<String, Offset>,
  val bounds: Rect,
  val coreNodeId: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
  topology: MapTopologyService,
  deviceControl: DeviceControlService
) {
  val nodes by topology.nodes.collectAsState()
  val links by topology.links.collectAsState()
  val scope = rememberCoroutineScope()

  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  var panWorld by remember { mutableStateOf(Offset.Zero) }
  var zoom by remember { mutableStateOf(1f) }
  var userAdjustedViewport by remember { mutableStateOf(false) }
  var selectedDetails by remember { mutableStateOf<DeviceDetails?>(null) }

  val layout = remember(nodes, links) { buildStableTopologyLayout(nodes, links) }
  val layoutKey = remember(layout) { layout.positions.keys.joinToString("|") + layout.bounds.toString() }

  fun fitToView() {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return
    val graphW = max(layout.bounds.width, 200f)
    val graphH = max(layout.bounds.height, 200f)
    val sx = (canvasSize.width * 0.82f) / graphW
    val sy = (canvasSize.height * 0.82f) / graphH
    zoom = min(sx, sy).coerceIn(0.55f, 3.2f)
    val center = Offset(
      x = (layout.bounds.left + layout.bounds.right) * 0.5f,
      y = (layout.bounds.top + layout.bounds.bottom) * 0.5f
    )
    panWorld = Offset(-center.x, -center.y)
  }

  LaunchedEffect(Unit) {
    topology.refreshTopology()
  }

  LaunchedEffect(layoutKey, canvasSize) {
    if (!userAdjustedViewport) fitToView()
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("NETWORK MAP", style = MaterialTheme.typography.titleLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
          userAdjustedViewport = false
          fitToView()
        }) { Text("FIT") }
        Button(onClick = {
          scope.launch {
            topology.refreshTopology()
            userAdjustedViewport = false
            fitToView()
          }
        }) { Text("Refresh") }
      }
    }

    ElevatedCard(Modifier.fillMaxWidth().weight(0.62f)) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(10.dp)
          .background(Color(0x14000000))
      ) {
        Canvas(
          modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(layoutKey, zoom, panWorld, canvasSize) {
              detectTransformGestures { _, panChange, zoomChange, _ ->
                if (canvasSize.width <= 0 || canvasSize.height <= 0) return@detectTransformGestures
                val nextZoom = (zoom * zoomChange).coerceIn(0.55f, 3.2f)
                val worldPanDelta = Offset(panChange.x / nextZoom, panChange.y / nextZoom)
                zoom = nextZoom
                panWorld = clampPan(panWorld + worldPanDelta, layout.bounds)
                userAdjustedViewport = true
              }
            }
            .pointerInput(layoutKey, zoom, panWorld) {
              detectTapGestures(
                onDoubleTap = {
                  userAdjustedViewport = false
                  fitToView()
                },
                onTap = { tap ->
                  val hit = hitTestNode(
                    tap = tap,
                    nodes = nodes,
                    positions = layout.positions,
                    canvasSize = canvasSize,
                    panWorld = panWorld,
                    zoom = zoom
                  )
                  if (hit != null) {
                    scope.launch {
                      topology.selectNode(hit.id)
                      selectedDetails = deviceControl.deviceDetails(hit.id)
                    }
                  }
                }
              )
            }
        ) {
          val coreId = layout.coreNodeId
          val nodeCount = max(nodes.size, 1)
          val baseRadius = (28f - (nodeCount / 4f)).coerceIn(16f, 26f)
          val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 26f
          }
          val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(178, 7, 12, 17)
            style = Paint.Style.FILL
          }

          fun toScreen(world: Offset): Offset {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            return center + (world + panWorld) * zoom
          }

          links.forEach { link ->
            val from = layout.positions[link.fromId] ?: return@forEach
            val to = layout.positions[link.toId] ?: return@forEach
            val quality01 = (link.quality / 100f).coerceIn(0.05f, 1f)
            drawLine(
              color = Color(0xFF5EC8FF).copy(alpha = 0.24f + 0.55f * quality01),
              start = toScreen(from),
              end = toScreen(to),
              strokeWidth = (2.2f + quality01 * 3.8f),
              cap = StrokeCap.Round
            )
          }

          val labelNodeIds = mutableSetOf<String>()
          coreId?.let { labelNodeIds += it }
          nodes.filter { it.selected }.forEach { labelNodeIds += it.id }
          nodes.sortedByDescending { it.strength }.take(8).forEach { labelNodeIds += it.id }

          nodes.forEach { node ->
            val world = layout.positions[node.id] ?: return@forEach
            val center = toScreen(world)
            val strength01 = (node.strength / 100f).coerceIn(0.05f, 1f)
            val isCore = node.id == coreId
            val isSelected = node.selected
            val radius = (baseRadius + (if (isCore) 5f else 0f) + (if (isSelected) 4f else 0f)).coerceIn(16f, 32f)
            val fill = when {
              isCore -> Color(0xFFFFA93A)
              isSelected -> Color(0xFF9BEEFF)
              else -> Color(0xFF52C6FF).copy(alpha = 0.7f + strength01 * 0.3f)
            }

            drawCircle(
              color = fill,
              radius = radius,
              center = center
            )
            drawCircle(
              color = Color(0xFF031018),
              radius = radius,
              center = center,
              style = Stroke(width = 2.8f)
            )
            if (isSelected) {
              drawCircle(
                color = Color(0xFFFFE8A0),
                radius = radius + 6f,
                center = center,
                style = Stroke(width = 2.2f)
              )
            }

            if (labelNodeIds.contains(node.id)) {
              val label = displayLabel(node, isCore)
              val textW = textPaint.measureText(label)
              val bx = center.x - textW * 0.5f - 10f
              val by = center.y + radius + 10f
              val bw = textW + 20f
              val bh = 30f
              drawRoundRect(
                color = Color(0xB2061018),
                topLeft = Offset(bx, by),
                size = androidx.compose.ui.geometry.Size(bw, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
              )
              drawContext.canvas.nativeCanvas.drawRoundRect(
                bx,
                by,
                bx + bw,
                by + bh,
                8f,
                8f,
                haloPaint
              )
              drawContext.canvas.nativeCanvas.drawText(label, bx + 10f, by + 21f, textPaint)
            }
          }
        }

        if (nodes.isEmpty()) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(18.dp),
            verticalArrangement = Arrangement.Center
          ) {
            Text("No discovered devices yet.", style = MaterialTheme.typography.titleMedium)
            Text("Run a scan to build topology.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(onClick = {
                scope.launch { topology.refreshTopology() }
              }) { Text("Run Scan") }
              OutlinedButton(onClick = {
                userAdjustedViewport = false
                fitToView()
              }) { Text("Fit") }
            }
          }
        }
      }
    }

    ElevatedCard(Modifier.fillMaxWidth().weight(0.38f)) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        items(nodes, key = { it.id }) { n ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
              Text(displayLabel(n, n.id == layout.coreNodeId), style = MaterialTheme.typography.bodyMedium)
              Text(n.ip, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("Signal ${n.strength}", style = MaterialTheme.typography.bodySmall)
              OutlinedButton(
                modifier = Modifier.height(32.dp),
                onClick = {
                  scope.launch {
                    topology.selectNode(n.id)
                    selectedDetails = deviceControl.deviceDetails(n.id)
                  }
                }
              ) { Text("Details") }
            }
          }
        }
      }
    }
  }

  if (selectedDetails != null) {
    val details = selectedDetails!!
    ModalBottomSheet(onDismissRequest = { selectedDetails = null }) {
      Column(
        Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(details.device.name, style = MaterialTheme.typography.titleLarge)
        Text("IP ${details.device.ip}")
        Text("MAC ${details.device.mac}")
        Text("Vendor ${details.device.vendor}")
        Text("Type ${details.device.deviceType}")
        Text("RSSI ${details.device.rssiDbm?.let { "$it dBm" } ?: "N/A"}")
        Text("Latency ${details.device.latencyMs?.let { "$it ms" } ?: "N/A"}")
        Text("Risk ${details.device.riskScore}")
        Text("Ping ${details.pingMs ?: "-"} ms")
      }
    }
  }
}

private fun buildStableTopologyLayout(nodes: List<MapNode>, links: List<MapLink>): GraphLayout {
  if (nodes.isEmpty()) {
    return GraphLayout(emptyMap(), Rect(-100f, -100f, 100f, 100f), null)
  }
  val coreId = links.firstOrNull()?.fromId ?: nodes.firstOrNull {
    it.label.equals("gateway", true) || it.label.contains("router", true)
  }?.id ?: nodes.first().id

  val core = nodes.firstOrNull { it.id == coreId } ?: nodes.first()
  val others = nodes.asSequence()
    .filter { it.id != core.id }
    .sortedWith(compareByDescending<MapNode> { it.strength }.thenBy { it.id })
    .toList()

  val positions = linkedMapOf<String, Offset>()
  positions[core.id] = Offset.Zero

  var ring = 1
  var idx = 0
  val minGap = 100f
  while (idx < others.size) {
    val radius = 145f * ring
    val circumference = (2f * PI.toFloat() * radius)
    val capacity = max(6, floor(circumference / minGap).toInt())
    for (slot in 0 until capacity) {
      if (idx >= others.size) break
      val theta = ((slot.toFloat() / capacity.toFloat()) * (2f * PI.toFloat())) - (PI.toFloat() * 0.5f)
      positions[others[idx].id] = Offset(
        x = radius * cos(theta),
        y = radius * sin(theta)
      )
      idx++
    }
    ring++
  }

  val all = positions.values
  val left = all.minOf { it.x } - 40f
  val top = all.minOf { it.y } - 40f
  val right = all.maxOf { it.x } + 40f
  val bottom = all.maxOf { it.y } + 40f
  return GraphLayout(positions, Rect(left, top, right, bottom), core.id)
}

private fun clampPan(pan: Offset, bounds: Rect): Offset {
  val maxX = max(180f, bounds.width * 0.8f)
  val maxY = max(180f, bounds.height * 0.8f)
  return Offset(
    x = pan.x.coerceIn(-maxX, maxX),
    y = pan.y.coerceIn(-maxY, maxY)
  )
}

private fun displayLabel(node: MapNode, isCore: Boolean): String {
  if (isCore) return "GATEWAY"
  val trimmed = node.label.trim()
  if (trimmed.isNotEmpty() && !trimmed.equals("unknown", true) && !trimmed.startsWith("DEVICE-")) {
    return trimmed.take(18)
  }
  val ipParts = node.ip.split(".")
  return if (ipParts.size == 4) "IP .${ipParts[2]}.${ipParts[3]}" else node.ip
}

private fun hitTestNode(
  tap: Offset,
  nodes: List<MapNode>,
  positions: Map<String, Offset>,
  canvasSize: IntSize,
  panWorld: Offset,
  zoom: Float
): MapNode? {
  if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
  val center = Offset(canvasSize.width * 0.5f, canvasSize.height * 0.5f)
  val nodeCount = max(nodes.size, 1)
  val baseRadius = (28f - (nodeCount / 4f)).coerceIn(16f, 26f)

  return nodes.sortedByDescending { it.selected }.firstOrNull { node ->
    val world = positions[node.id] ?: return@firstOrNull false
    val screen = center + (world + panWorld) * zoom
    val radius = baseRadius + if (node.selected) 4f else 0f
    val dx = tap.x - screen.x
    val dy = tap.y - screen.y
    sqrt(dx * dx + dy * dy) <= (radius + 14f)
  }
}
