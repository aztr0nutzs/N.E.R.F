package com.nerf.netx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nerf.netx.domain.ActionResult
import com.nerf.netx.domain.ActionSupportCatalog
import com.nerf.netx.domain.AppActionId
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.DeviceActionSupport
import com.nerf.netx.domain.DeviceControlService
import com.nerf.netx.domain.DeviceDetails
import com.nerf.netx.domain.DevicesService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class DeviceTypeFilter {
  ALL,
  PHONE,
  COMPUTER,
  IOT,
  MEDIA,
  ROUTER,
  UNKNOWN
}

private enum class DeviceSortOption {
  QUALITY_DESC,
  NAME_ASC,
  TYPE_ASC,
  LAST_SEEN_DESC
}

@Composable
fun DevicesScreen(service: DevicesService, deviceControl: DeviceControlService) {
  val devices by service.devices.collectAsState()
  val scope = rememberCoroutineScope()

  var query by remember { mutableStateOf("") }
  var filter by remember { mutableStateOf(DeviceTypeFilter.ALL) }
  var sort by remember { mutableStateOf(DeviceSortOption.QUALITY_DESC) }

  val runningActions = remember { mutableStateMapOf<String, Boolean>() }
  val snackbarHost = remember { SnackbarHostState() }

  val filteredSorted = remember(devices, query, filter, sort) {
    applyDeviceFilters(devices, query, filter, sort)
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("DEVICES", style = MaterialTheme.typography.titleLarge)
      Button(onClick = { scope.launch { service.refresh() } }) { Text("Refresh") }
    }

    SnackbarHost(hostState = snackbarHost)

    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Search devices") },
      placeholder = { Text("Name, vendor, IP, or MAC") },
      singleLine = true
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      DeviceTypeFilter.entries.forEach { option ->
        FilterChip(
          selected = option == filter,
          onClick = { filter = option },
          label = { Text(option.name) }
        )
      }
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      DeviceSortOption.entries.forEach { option ->
        FilterChip(
          selected = option == sort,
          onClick = { sort = option },
          label = { Text(sortLabel(option)) }
        )
      }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
      items(filteredSorted, key = { it.id }) { d ->
        val type = inferDeviceType(d)
        val linkQuality = calculateLinkQuality(d)
        val running = runningActions[d.id] == true
        var details by remember(d.id) { mutableStateOf<DeviceDetails?>(null) }
        LaunchedEffect(d.id, d.isBlocked, d.isPaused, d.name, d.nickname) {
          details = deviceControl.deviceDetails(d.id)
        }
        val defaultSupport = remember {
          ActionSupportCatalog.deviceActionSupport(DeviceActionSupport())
        }
        val actionSupport = details?.actionSupport ?: defaultSupport
        val blockSupport = actionSupport[AppActionId.DEVICE_BLOCK] ?: defaultSupport.getValue(AppActionId.DEVICE_BLOCK)
        val prioritizeSupport = actionSupport[AppActionId.DEVICE_PRIORITIZE] ?: defaultSupport.getValue(AppActionId.DEVICE_PRIORITIZE)

        ElevatedCard {
          Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                DeviceTypeBadge(type = type)
                Column(Modifier.weight(1f)) {
                  Text(
                    d.name.ifBlank { "Unknown" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    "${d.ip}  |  MAC ${d.mac.ifBlank { "N/A" }}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }
              Text(
                if (d.online) "ONLINE" else "OFFLINE",
                style = MaterialTheme.typography.labelMedium,
                color = if (d.online) Color(0xFF69DAA8) else Color(0xFFE39A9A)
              )
            }

            Text(
              "Type ${type.name}  |  Vendor ${d.vendor.ifBlank { "Unknown" }}  |  Last seen: ${formatLastSeen(d.lastSeen)}",
              style = MaterialTheme.typography.bodySmall
            )

            if (d.isGateway) {
              val rssiText = d.rssiDbm?.let { "$it dBm" } ?: "N/A"
              Text("WiFi RSSI (gateway): $rssiText", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }

            Text(
              "Link Quality: $linkQuality  |  Latency: ${d.latencyMs?.let { "$it ms" } ?: "N/A"}",
              style = MaterialTheme.typography.bodySmall
            )
            QualityBar(score = linkQuality)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
              ActionButton(
                label = "Ping",
                running = running,
                enabled = !running,
                onClick = {
                  runDeviceAction(
                    scope = scope,
                    runningActions = runningActions,
                    deviceId = d.id,
                    snackbarHost = snackbarHost
                  ) { deviceControl.ping(d.id) }
                }
              )
              ActionButton(
                label = when {
                  !blockSupport.supported -> "Block (Unsupported)"
                  d.isBlocked -> "Unblock"
                  else -> "Block"
                },
                running = running,
                enabled = !running && blockSupport.supported,
                onClick = {
                  runDeviceAction(
                    scope = scope,
                    runningActions = runningActions,
                    deviceId = d.id,
                    snackbarHost = snackbarHost
                  ) {
                    if (d.isBlocked) deviceControl.setBlocked(d.id, false) else deviceControl.block(d.id)
                  }
                }
              )
              ActionButton(
                label = if (prioritizeSupport.supported) "Prioritize" else "Prioritize (Unsupported)",
                running = running,
                enabled = !running && prioritizeSupport.supported,
                onClick = {
                  runDeviceAction(
                    scope = scope,
                    runningActions = runningActions,
                    deviceId = d.id,
                    snackbarHost = snackbarHost
                  ) { deviceControl.prioritize(d.id) }
                }
              )

              if (running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Running...", style = MaterialTheme.typography.bodySmall)
              }
            }

            if (!blockSupport.supported || !prioritizeSupport.supported) {
              Text(
                "Unavailable: " + listOfNotNull(
                  blockSupport.reason?.takeIf { !blockSupport.supported },
                  prioritizeSupport.reason?.takeIf { !prioritizeSupport.supported && it != blockSupport.reason }
                ).joinToString(" "),
                style = MaterialTheme.typography.bodySmall
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ActionButton(
  label: String,
  running: Boolean,
  enabled: Boolean,
  onClick: () -> Unit
) {
  OutlinedButton(onClick = onClick, enabled = enabled && !running, modifier = Modifier.height(34.dp)) {
    Text(label)
  }
}

private fun runDeviceAction(
  scope: kotlinx.coroutines.CoroutineScope,
  runningActions: MutableMap<String, Boolean>,
  deviceId: String,
  snackbarHost: SnackbarHostState,
  action: suspend () -> ActionResult
) {
  scope.launch {
    runningActions[deviceId] = true
    val result = runCatching { action() }
      .getOrElse {
        ActionResult(
          ok = false,
          status = com.nerf.netx.domain.ServiceStatus.ERROR,
          code = "EXCEPTION",
          message = "Action failed",
          errorReason = it.message ?: "Unhandled error"
        )
      }
    runningActions[deviceId] = false

    val statusText = when {
      result.ok -> "OK"
      else -> result.status.name
    }
    val message = buildString {
      append(statusText)
      append(" • ")
      append(result.message)
      result.errorReason?.takeIf { it.isNotBlank() }?.let {
        append(" • ")
        append(it)
      }
    }
    snackbarHost.showSnackbar(message)
  }
}

@Composable
private fun DeviceTypeBadge(type: DeviceTypeFilter) {
  val (bg, fg, text) = when (type) {
    DeviceTypeFilter.PHONE -> Triple(Color(0xFF355B8C), Color(0xFFD5E6FF), "P")
    DeviceTypeFilter.COMPUTER -> Triple(Color(0xFF3A6B56), Color(0xFFD7FFE8), "C")
    DeviceTypeFilter.IOT -> Triple(Color(0xFF6C4E2C), Color(0xFFFFE7C8), "I")
    DeviceTypeFilter.MEDIA -> Triple(Color(0xFF6D3F6D), Color(0xFFFFD8FF), "M")
    DeviceTypeFilter.ROUTER -> Triple(Color(0xFF78402E), Color(0xFFFFDEC8), "R")
    DeviceTypeFilter.UNKNOWN, DeviceTypeFilter.ALL -> Triple(Color(0xFF3C4754), Color(0xFFDCE6F3), "?")
  }

  Box(
    modifier = Modifier
      .size(26.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(bg),
    contentAlignment = Alignment.Center
  ) {
    Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
private fun QualityBar(score: Int) {
  val clamped = score.coerceIn(0, 100)
  val color = when {
    clamped >= 70 -> Color(0xFF55D8A8)
    clamped >= 40 -> Color(0xFFFFC36D)
    else -> Color(0xFFEA8F8F)
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(8.dp)
      .clip(RoundedCornerShape(999.dp))
      .background(Color(0x22344757))
  ) {
    Box(
      modifier = Modifier
        .height(8.dp)
        .width((clamped.coerceIn(0, 100) * 2.4f).dp)
        .clip(RoundedCornerShape(999.dp))
        .background(color)
    )
  }
}

private fun applyDeviceFilters(
  devices: List<Device>,
  query: String,
  typeFilter: DeviceTypeFilter,
  sort: DeviceSortOption
): List<Device> {
  val q = query.trim().lowercase()

  val filtered = devices.filter { d ->
    val type = inferDeviceType(d)
    val typeMatches = typeFilter == DeviceTypeFilter.ALL || type == typeFilter
    val searchMatches = q.isBlank() || buildString {
      append(d.name.lowercase())
      append(' ')
      append(d.hostname.lowercase())
      append(' ')
      append(d.ip.lowercase())
      append(' ')
      append(d.mac.lowercase())
      append(' ')
      append(d.vendor.lowercase())
      append(' ')
      append(d.deviceType.lowercase())
    }.contains(q)
    typeMatches && searchMatches
  }

  val comparator = when (sort) {
    DeviceSortOption.QUALITY_DESC -> compareByDescending<Device> { calculateLinkQuality(it) }
      .thenByDescending { it.online }
      .thenBy { it.name.lowercase() }
      .thenBy { it.id }

    DeviceSortOption.NAME_ASC -> compareBy<Device> { it.name.lowercase() }
      .thenBy { it.ip }
      .thenBy { it.id }

    DeviceSortOption.TYPE_ASC -> compareBy<Device> { inferDeviceType(it).name }
      .thenBy { it.name.lowercase() }
      .thenBy { it.id }

    DeviceSortOption.LAST_SEEN_DESC -> compareByDescending<Device> { it.lastSeen }
      .thenBy { it.name.lowercase() }
      .thenBy { it.id }
  }

  return filtered.sortedWith(comparator)
}

private fun sortLabel(option: DeviceSortOption): String {
  return when (option) {
    DeviceSortOption.QUALITY_DESC -> "Quality"
    DeviceSortOption.NAME_ASC -> "Name"
    DeviceSortOption.TYPE_ASC -> "Type"
    DeviceSortOption.LAST_SEEN_DESC -> "Last Seen"
  }
}

private fun inferDeviceType(device: Device): DeviceTypeFilter {
  val normalized = device.deviceType.trim().uppercase()
  if (normalized.isNotBlank()) {
    return when (normalized) {
      "PHONE" -> DeviceTypeFilter.PHONE
      "COMPUTER" -> DeviceTypeFilter.COMPUTER
      "IOT", "PRINTER", "CAMERA" -> DeviceTypeFilter.IOT
      "MEDIA" -> DeviceTypeFilter.MEDIA
      "ROUTER", "GATEWAY" -> DeviceTypeFilter.ROUTER
      "UNKNOWN" -> DeviceTypeFilter.UNKNOWN
      else -> inferDeviceTypeFromText("${device.name} ${device.hostname}")
    }
  }
  return inferDeviceTypeFromText("${device.name} ${device.hostname}")
}

private fun inferDeviceTypeFromText(text: String): DeviceTypeFilter {
  val t = text.lowercase()
  return when {
    t.contains("router") || t.contains("gateway") -> DeviceTypeFilter.ROUTER
    t.contains("iphone") || t.contains("ipad") || t.contains("android") || t.contains("pixel") || t.contains("samsung") -> DeviceTypeFilter.PHONE
    t.contains("macbook") || t.contains("windows") || t.contains("desktop") || t.contains("laptop") || t.contains("pc") -> DeviceTypeFilter.COMPUTER
    t.contains("tv") || t.contains("roku") || t.contains("chromecast") -> DeviceTypeFilter.MEDIA
    t.contains("printer") || t.contains("cam") || t.contains("camera") || t.contains("iot") || t.contains("sensor") || t.contains("plug") -> DeviceTypeFilter.IOT
    else -> DeviceTypeFilter.UNKNOWN
  }
}

private fun calculateLinkQuality(device: Device): Int {
  if (!device.online || !device.reachable) return 0
  device.latencyMs?.let { latency ->
    return (100f - (latency * 1.2f)).roundToInt().coerceIn(0, 100)
  }
  return if (device.isGateway) {
    device.rssiDbm?.let { ((it + 100) * 2).coerceIn(0, 100) } ?: 50
  } else {
    50
  }
}

private fun formatLastSeen(lastSeenEpochMs: Long): String {
  val now = System.currentTimeMillis()
  val deltaMs = (now - lastSeenEpochMs).coerceAtLeast(0L)
  val seconds = deltaMs / 1000L
  return when {
    seconds < 10L -> "just now"
    seconds < 60L -> "${seconds}s ago"
    seconds < 3600L -> "${seconds / 60L}m ago"
    seconds < 86_400L -> "${seconds / 3600L}h ago"
    else -> "${seconds / 86_400L}d ago"
  }
}
