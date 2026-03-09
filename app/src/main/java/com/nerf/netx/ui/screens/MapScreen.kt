package com.nerf.netx.ui.screens

import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nerf.netx.domain.DeviceControlService
import com.nerf.netx.domain.DeviceDetails
import com.nerf.netx.domain.MapLink
import com.nerf.netx.domain.MapNode
import com.nerf.netx.domain.MapTopologyService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private enum class TopologyType {
  ALL,
  PHONE,
  COMPUTER,
  IOT,
  MEDIA,
  ROUTER,
  UNKNOWN
}

private data class GraphLayout(
  val positions: Map<String, Offset>,
  val bounds: Rect,
  val coreNodeId: String?,
  val groupAnchors: Map<TopologyType, Offset>
)

private data class NodeMeta(
  val node: MapNode,
  val type: TopologyType,
  val quality: Int,
  val matchScore: Int
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

  var query by remember { mutableStateOf("") }
  var typeFilter by remember { mutableStateOf(TopologyType.ALL) }
  val selectedNodeIds = remember { mutableStateMapOf<String, Boolean>() }
  val detailsCache = remember { mutableStateMapOf<String, DeviceDetails>() }

  var selectionStart by remember { mutableStateOf<Offset?>(null) }
  var selectionEnd by remember { mutableStateOf<Offset?>(null) }

  val layout = remember(nodes, links) { buildClusteredTopologyLayout(nodes, links) }
  val layoutKey = remember(layout) { layout.positions.keys.joinToString("|") + layout.bounds.toString() }
  val pulse = rememberInfiniteTransition(label = "ringPulse").animateFloat(
    initialValue = 0.94f,
    targetValue = 1.08f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 2400, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "ringPulseAnim"
  )

  val filteredNodes = remember(nodes, query, typeFilter, detailsCache) {
    buildNodeMeta(nodes, query, typeFilter, detailsCache)
  }
  val visibleNodeIds = filteredNodes.filter { it.matchScore > 0 }.map { it.node.id }.toSet()
  val dimNodeIds = filteredNodes.filter { it.matchScore == 0 }.map { it.node.id }.toSet()

  fun fitToView(animate: Boolean) {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return

    val visibleWorldPositions = if (visibleNodeIds.isEmpty()) {
      layout.positions.values.toList()
    } else {
      layout.positions.filterKeys { visibleNodeIds.contains(it) }.values.toList()
    }
    if (visibleWorldPositions.isEmpty()) return

    val left = visibleWorldPositions.minOf { it.x } - 90f
    val right = visibleWorldPositions.maxOf { it.x } + 90f
    val top = visibleWorldPositions.minOf { it.y } - 90f
    val bottom = visibleWorldPositions.maxOf { it.y } + 90f
    val worldBounds = Rect(left, top, right, bottom)

    val graphW = max(worldBounds.width, 200f)
    val graphH = max(worldBounds.height, 200f)
    val sx = (canvasSize.width * 0.84f) / graphW
    val sy = (canvasSize.height * 0.84f) / graphH
    val targetZoom = min(sx, sy).coerceIn(0.52f, 3.2f)
    val center = Offset(
      x = (worldBounds.left + worldBounds.right) * 0.5f,
      y = (worldBounds.top + worldBounds.bottom) * 0.5f
    )
    val targetPan = Offset(-center.x, -center.y)

    if (!animate) {
      zoom = targetZoom
      panWorld = clampPan(targetPan, layout.bounds)
      return
    }

    scope.launch {
      val startZoom = zoom
      val startPan = panWorld
      val steps = 16
      repeat(steps) { i ->
        val t = (i + 1) / steps.toFloat()
        zoom = lerp(startZoom, targetZoom, t)
        val currentPan = Offset(
          x = lerp(startPan.x, targetPan.x, t),
          y = lerp(startPan.y, targetPan.y, t)
        )
        panWorld = clampPan(currentPan, layout.bounds)
        delay(18)
      }
    }
  }

  LaunchedEffect(Unit) {
    topology.refreshTopology()
  }

  LaunchedEffect(nodes.size, links.size, query, typeFilter, canvasSize) {
    if (canvasSize.width > 0 && canvasSize.height > 0) {
      if (!userAdjustedViewport || nodes.size > 1) {
        fitToView(animate = true)
      }
    }
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
          fitToView(animate = true)
        }) { Text("FIT") }
        Button(onClick = {
          scope.launch {
            topology.refreshTopology()
            userAdjustedViewport = false
            fitToView(animate = true)
          }
        }) { Text("Refresh") }
      }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Search nodes") },
          placeholder = { Text("Name or IP") },
          singleLine = true
        )

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          TopologyType.entries.forEach { option ->
            FilterChip(
              selected = option == typeFilter,
              onClick = { typeFilter = option },
              label = { Text(option.name) }
            )
          }
        }
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
                val nextZoom = (zoom * zoomChange).coerceIn(0.52f, 3.2f)
                val worldPanDelta = Offset(panChange.x / nextZoom, panChange.y / nextZoom)
                zoom = nextZoom
                panWorld = clampPan(panWorld + worldPanDelta, layout.bounds)
                userAdjustedViewport = true
              }
            }
            .pointerInput(layoutKey, zoom, panWorld, canvasSize, visibleNodeIds) {
              awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startNode = hitTestNode(
                  tap = down.position,
                  nodes = nodes,
                  positions = layout.positions,
                  canvasSize = canvasSize,
                  panWorld = panWorld,
                  zoom = zoom
                )

                var last = down.position
                var selecting = false
                selectionStart = null
                selectionEnd = null

                while (true) {
                  val event = awaitPointerEvent(pass = PointerEventPass.Main)
                  val active = event.changes.filter { it.pressed }
                  if (active.isEmpty()) break
                  if (active.size > 1) {
                    selecting = false
                    selectionStart = null
                    selectionEnd = null
                    break
                  }
                  val change = active.first()
                  val delta = change.position - last
                  val distance = (change.position - down.position).getDistance()

                  if (startNode == null && !selecting && distance > 16f) {
                    selecting = true
                    selectionStart = down.position
                    selectionEnd = change.position
                    change.consume()
                    userAdjustedViewport = true
                  } else if (selecting) {
                    selectionEnd = change.position
                    change.consume()
                  } else {
                    if (distance > 4f && startNode != null) {
                      panWorld = clampPan(panWorld + Offset(delta.x / zoom, delta.y / zoom), layout.bounds)
                      change.consume()
                      userAdjustedViewport = true
                    }
                  }
                  last = change.position
                }

                if (selecting && selectionStart != null && selectionEnd != null) {
                  val rect = selectionRect(selectionStart!!, selectionEnd!!)
                  selectedNodeIds.clear()
                  nodes.forEach { node ->
                    if (!visibleNodeIds.contains(node.id)) return@forEach
                    val world = layout.positions[node.id] ?: return@forEach
                    val screen = worldToScreen(world, canvasSize, panWorld, zoom)
                    if (rect.contains(screen)) {
                      selectedNodeIds[node.id] = true
                    }
                  }
                }
                selectionStart = null
                selectionEnd = null
              }
            }
            .pointerInput(layoutKey, zoom, panWorld) {
              detectTapGestures(
                onDoubleTap = {
                  userAdjustedViewport = false
                  fitToView(animate = true)
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
                  if (hit != null && visibleNodeIds.contains(hit.id)) {
                    selectedNodeIds.clear()
                    selectedNodeIds[hit.id] = true
                    scope.launch {
                      topology.selectNode(hit.id)
                      val details = deviceControl.deviceDetails(hit.id)
                      if (details != null) {
                        detailsCache[hit.id] = details
                        selectedDetails = details
                      }
                    }
                  }
                }
              )
            }
        ) {
          val coreId = layout.coreNodeId
          val nodeCount = max(nodes.size, 1)
          val baseRadius = (26f - (nodeCount / 5f)).coerceIn(14f, 24f)
          val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
          }
          val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(170, 6, 10, 16)
            style = Paint.Style.FILL
          }

          val visibleMeta = filteredNodes.filter { it.matchScore > 0 }
          val labelNodeIds = mutableSetOf<String>()
          coreId?.let { labelNodeIds += it }
          selectedNodeIds.keys.forEach { labelNodeIds += it }
          visibleMeta.sortedByDescending { it.quality }.take(8).forEach { labelNodeIds += it.node.id }

          layout.groupAnchors.forEach { (type, anchorWorld) ->
            if (type == TopologyType.ALL) return@forEach
            val groupHasVisible = visibleMeta.any { it.type == type }
            if (!groupHasVisible) return@forEach
            val anchor = worldToScreen(anchorWorld, canvasSize, panWorld, zoom)
            drawCircle(
              color = Color(0x334FC3FF),
              center = anchor,
              radius = 120f * zoom.coerceIn(0.55f, 1.25f),
              style = Stroke(width = 1.2f)
            )
            drawContext.canvas.nativeCanvas.drawText(
              typeLabel(type),
              anchor.x - 42f,
              anchor.y - (118f * zoom.coerceIn(0.55f, 1.25f)),
              textPaint
            )
          }

          links.forEach { link ->
            val from = layout.positions[link.fromId] ?: return@forEach
            val to = layout.positions[link.toId] ?: return@forEach
            val edgeVisible = visibleNodeIds.contains(link.fromId) && visibleNodeIds.contains(link.toId)
            val quality = link.quality.coerceIn(0, 100)
            val quality01 = quality / 100f
            val poor = quality < 30
            val edgeColor = when {
              quality >= 70 -> Color(0xFF69E6FF)
              quality >= 40 -> Color(0xFFFFD36A)
              else -> Color(0xFFFF8E66)
            }
            drawLine(
              color = edgeColor.copy(alpha = if (edgeVisible) (0.2f + 0.8f * quality01) else 0.08f),
              start = worldToScreen(from, canvasSize, panWorld, zoom),
              end = worldToScreen(to, canvasSize, panWorld, zoom),
              strokeWidth = 1f + (quality01 * 3f),
              cap = StrokeCap.Round,
              pathEffect = if (poor) PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) else null
            )
          }

          nodes.forEach { node ->
            val world = layout.positions[node.id] ?: return@forEach
            val center = worldToScreen(world, canvasSize, panWorld, zoom)
            val meta = filteredNodes.firstOrNull { it.node.id == node.id }
            val quality = meta?.quality ?: node.strength.coerceIn(0, 100)
            val quality01 = quality / 100f
            val isCore = node.id == coreId
            val isSelected = selectedNodeIds.contains(node.id) || node.selected
            val visible = visibleNodeIds.contains(node.id)
            val alpha = if (visible) 1f else 0.18f

            val radius = (baseRadius + (if (isCore) 5f else 0f) + (if (isSelected) 4f else 0f)).coerceIn(14f, 32f)
            val fill = when {
              isCore -> Color(0xFFFFA93A)
              isSelected -> Color(0xFF9BEEFF)
              else -> Color(0xFF52C6FF).copy(alpha = 0.66f + quality01 * 0.34f)
            }

            drawCircle(
              color = qualityColor(quality).copy(alpha = (0.22f * alpha)),
              radius = radius * (1.55f + (pulse.value - 1f) * 0.45f),
              center = center,
              style = Stroke(width = 1.6f)
            )
            if (quality >= 35) {
              drawCircle(
                color = qualityColor(quality).copy(alpha = 0.18f * alpha),
                radius = radius * (1.95f + (pulse.value - 1f) * 0.35f),
                center = center,
                style = Stroke(width = 1.2f)
              )
            }
            if (quality >= 70) {
              drawCircle(
                color = qualityColor(quality).copy(alpha = 0.14f * alpha),
                radius = radius * (2.35f + (pulse.value - 1f) * 0.22f),
                center = center,
                style = Stroke(width = 1f)
              )
            }

            drawCircle(
              color = fill.copy(alpha = alpha),
              radius = radius,
              center = center
            )
            drawCircle(
              color = Color(0xFF031018).copy(alpha = alpha),
              radius = radius,
              center = center,
              style = Stroke(width = 2.6f)
            )
            if (isSelected) {
              drawCircle(
                color = Color(0xFFFFE8A0).copy(alpha = alpha),
                radius = radius + 5f,
                center = center,
                style = Stroke(width = 2f)
              )
            }

            if (labelNodeIds.contains(node.id) && visible) {
              val labelPrimary = displayLabel(node, isCore)
              val labelSecondary = "${node.ip}  Q:${quality}"
              val maxPrimary = labelPrimary.take(18)
              val textW = max(textPaint.measureText(maxPrimary), textPaint.measureText(labelSecondary))
              val bx = center.x - textW * 0.5f - 10f
              val by = center.y + radius + 8f
              val bw = textW + 20f
              val bh = 44f
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
              drawContext.canvas.nativeCanvas.drawText(maxPrimary, bx + 10f, by + 18f, textPaint)
              textPaint.textSize = 20f
              drawContext.canvas.nativeCanvas.drawText(labelSecondary, bx + 10f, by + 36f, textPaint)
              textPaint.textSize = 24f
            }
          }

          if (selectionStart != null && selectionEnd != null) {
            val rect = selectionRect(selectionStart!!, selectionEnd!!)
            drawRect(
              color = Color(0x557BE7FF),
              topLeft = rect.topLeft,
              size = rect.size
            )
            drawRect(
              color = Color(0xFF7BE7FF),
              topLeft = rect.topLeft,
              size = rect.size,
              style = Stroke(width = 1.6f)
            )
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
                fitToView(animate = true)
              }) { Text("Fit") }
            }
          }
        }
      }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text("Selected: ${selectedNodeIds.size} devices", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            enabled = selectedNodeIds.isNotEmpty(),
            onClick = {
              scope.launch {
                selectedNodeIds.keys.forEach { id ->
                  deviceControl.ping(id)
                }
              }
            }
          ) { Text("Ping Selected") }
          OutlinedButton(
            enabled = selectedNodeIds.isNotEmpty(),
            onClick = { selectedNodeIds.clear() }
          ) { Text("Clear Selection") }
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
        items(filteredNodes.filter { it.matchScore > 0 }, key = { it.node.id }) { meta ->
          val n = meta.node
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
              Text(
                displayLabel(n, n.id == layout.coreNodeId),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text("${n.ip}  |  Q:${meta.quality}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(typeLabel(meta.type), style = MaterialTheme.typography.bodySmall)
              OutlinedButton(
                modifier = Modifier.height(32.dp),
                onClick = {
                  selectedNodeIds.clear()
                  selectedNodeIds[n.id] = true
                  scope.launch {
                    topology.selectNode(n.id)
                    val details = deviceControl.deviceDetails(n.id)
                    if (details != null) {
                      detailsCache[n.id] = details
                      selectedDetails = details
                    }
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
        Text("MAC ${details.device.mac.ifBlank { "N/A" }}")
        Text("Vendor ${details.device.vendor.ifBlank { "N/A" }}")
        Text("Type ${details.device.deviceType}")
        val quality = deviceQuality(details.device.online, details.device.latencyMs)
        Text("Link Quality $quality")
        Text("WiFi RSSI ${if (details.device.isGateway) (details.device.rssiDbm?.let { "$it dBm" } ?: "N/A") else "N/A (gateway only)"}")
        Text("Latency ${details.device.latencyMs?.let { "$it ms" } ?: "N/A"}")
        Text("Risk ${details.device.riskScore}")
        Text("Ping ${details.pingMs ?: "-"} ms")
      }
    }
  }
}

private fun buildNodeMeta(
  nodes: List<MapNode>,
  query: String,
  typeFilter: TopologyType,
  detailsCache: Map<String, DeviceDetails>
): List<NodeMeta> {
  val normalizedQuery = query.trim().lowercase()
  return nodes.map { node ->
    val type = inferTopologyType(node)
    val quality = node.strength.coerceIn(0, 100)
    val mac = detailsCache[node.id]?.device?.mac?.lowercase().orEmpty()
    val searchable = buildString {
      append(node.label.lowercase())
      append(' ')
      append(node.ip.lowercase())
      append(' ')
      append(mac)
    }
    val queryMatches = normalizedQuery.isBlank() || searchable.contains(normalizedQuery)
    val typeMatches = typeFilter == TopologyType.ALL || typeFilter == type
    val score = if (queryMatches && typeMatches) 1 else 0
    NodeMeta(node = node, type = type, quality = quality, matchScore = score)
  }
}

private fun buildClusteredTopologyLayout(nodes: List<MapNode>, links: List<MapLink>): GraphLayout {
  if (nodes.isEmpty()) {
    return GraphLayout(emptyMap(), Rect(-100f, -100f, 100f, 100f), null, emptyMap())
  }

  val coreId = links.firstOrNull()?.fromId ?: nodes.firstOrNull {
    it.label.equals("gateway", true) || it.label.contains("router", true)
  }?.id ?: nodes.first().id

  val core = nodes.firstOrNull { it.id == coreId } ?: nodes.first()
  val byType = nodes
    .asSequence()
    .filter { it.id != core.id }
    .groupBy { inferTopologyType(it) }

  val sectorCenter = mapOf(
    TopologyType.PHONE to (-40f),
    TopologyType.COMPUTER to 35f,
    TopologyType.IOT to 110f,
    TopologyType.MEDIA to 185f,
    TopologyType.UNKNOWN to 255f,
    TopologyType.ROUTER to 320f
  )

  val positions = linkedMapOf<String, Offset>()
  val anchors = linkedMapOf<TopologyType, Offset>()
  positions[core.id] = Offset.Zero

  sectorCenter.forEach { (type, centerDeg) ->
    val group = byType[type].orEmpty().sortedWith(compareByDescending<MapNode> { it.strength }.thenBy { it.id })
    if (group.isEmpty()) return@forEach

    val thetaCenter = Math.toRadians(centerDeg.toDouble()).toFloat()
    val sectorHalfWidth = Math.toRadians(24.0).toFloat()
    val ringBase = 180f
    val ringStep = 94f

    val groupAnchor = Offset(
      x = cos(thetaCenter) * (ringBase + ringStep),
      y = sin(thetaCenter) * (ringBase + ringStep)
    )
    anchors[type] = groupAnchor

    val columns = max(2, ceil(sqrt(group.size.toFloat())).toInt())
    val rows = max(1, ceil(group.size / columns.toFloat()).toInt())

    group.forEachIndexed { idx, node ->
      val row = idx / columns
      val col = idx % columns
      val rowBias = (row - (rows - 1) / 2f) * 0.22f
      val colBias = (col - (columns - 1) / 2f) / max(1f, columns - 1f)
      val angle = thetaCenter + (sectorHalfWidth * (colBias + rowBias)).coerceIn(-sectorHalfWidth, sectorHalfWidth)
      val radius = ringBase + row * ringStep + abs(colBias) * 14f
      positions[node.id] = Offset(
        x = radius * cos(angle),
        y = radius * sin(angle)
      )
    }
  }

  val unknownGroup = byType.filterKeys { it !in sectorCenter.keys }.values.flatten().sortedBy { it.id }
  unknownGroup.forEachIndexed { i, node ->
    val theta = ((i.toFloat() / max(1, unknownGroup.size)) * (2f * PI.toFloat())) - (PI.toFloat() * 0.5f)
    positions[node.id] = Offset(x = 240f * cos(theta), y = 240f * sin(theta))
  }

  val all = positions.values
  val left = all.minOf { it.x } - 48f
  val top = all.minOf { it.y } - 48f
  val right = all.maxOf { it.x } + 48f
  val bottom = all.maxOf { it.y } + 48f
  return GraphLayout(positions, Rect(left, top, right, bottom), core.id, anchors)
}

private fun clampPan(pan: Offset, bounds: Rect): Offset {
  val maxX = max(220f, bounds.width * 0.9f)
  val maxY = max(220f, bounds.height * 0.9f)
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

private fun inferTopologyType(node: MapNode): TopologyType {
  val text = "${node.label} ${node.ip}".lowercase()
  return when {
    text.contains("router") || text.contains("gateway") -> TopologyType.ROUTER
    text.contains("iphone") || text.contains("ipad") || text.contains("android") || text.contains("pixel") || text.contains("samsung") -> TopologyType.PHONE
    text.contains("macbook") || text.contains("imac") || text.contains("windows") || text.contains("desktop") || text.contains("laptop") || text.contains("pc") -> TopologyType.COMPUTER
    text.contains("tv") || text.contains("roku") || text.contains("chromecast") || text.contains("stream") -> TopologyType.MEDIA
    text.contains("printer") || text.contains("cam") || text.contains("camera") || text.contains("iot") || text.contains("sensor") || text.contains("switch") || text.contains("plug") -> TopologyType.IOT
    else -> TopologyType.UNKNOWN
  }
}

private fun typeLabel(type: TopologyType): String {
  return when (type) {
    TopologyType.ALL -> "ALL"
    TopologyType.PHONE -> "PHONES"
    TopologyType.COMPUTER -> "COMPUTERS"
    TopologyType.IOT -> "IOT"
    TopologyType.MEDIA -> "MEDIA"
    TopologyType.ROUTER -> "ROUTERS"
    TopologyType.UNKNOWN -> "UNKNOWN"
  }
}

private fun qualityColor(quality: Int): Color {
  return when {
    quality >= 70 -> Color(0xFF52E0FF)
    quality >= 40 -> Color(0xFFFFC25E)
    quality >= 1 -> Color(0xFFFF8A66)
    else -> Color(0xFF95A4B1)
  }
}

private fun selectionRect(start: Offset, end: Offset): Rect {
  return Rect(
    left = min(start.x, end.x),
    top = min(start.y, end.y),
    right = max(start.x, end.x),
    bottom = max(start.y, end.y)
  )
}

private fun worldToScreen(world: Offset, canvasSize: IntSize, panWorld: Offset, zoom: Float): Offset {
  val center = Offset(canvasSize.width * 0.5f, canvasSize.height * 0.5f)
  return center + (world + panWorld) * zoom
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
  val nodeCount = max(nodes.size, 1)
  val baseRadius = (26f - (nodeCount / 5f)).coerceIn(14f, 24f)

  return nodes.sortedByDescending { it.selected }.firstOrNull { node ->
    val world = positions[node.id] ?: return@firstOrNull false
    val screen = worldToScreen(world, canvasSize, panWorld, zoom)
    val radius = baseRadius + if (node.selected) 4f else 0f
    val dx = tap.x - screen.x
    val dy = tap.y - screen.y
    sqrt(dx * dx + dy * dy) <= (radius + 12f)
  }
}

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

private fun deviceQuality(online: Boolean, latencyMs: Int?): Int {
  if (!online) return 0
  if (latencyMs == null) return 50
  return (100f - (latencyMs * 1.2f)).toInt().coerceIn(0, 100)
}
