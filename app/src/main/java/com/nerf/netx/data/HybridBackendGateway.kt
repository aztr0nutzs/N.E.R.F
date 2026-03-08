package com.nerf.netx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.nerf.netx.domain.ActionResult
import com.nerf.netx.domain.ActionSupportCatalog
import com.nerf.netx.domain.ActionSupportState
import com.nerf.netx.domain.AnalyticsService
import com.nerf.netx.domain.AnalyticsSnapshot
import com.nerf.netx.domain.AppActionId
import com.nerf.netx.domain.AppServices
import com.nerf.netx.domain.DeviceControlAction
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.DeviceActionSupport
import com.nerf.netx.domain.DeviceCapabilityState
import com.nerf.netx.domain.DeviceControlBackendState
import com.nerf.netx.domain.DeviceControlService
import com.nerf.netx.domain.DeviceControlStatusSnapshot
import com.nerf.netx.domain.DeviceDetails
import com.nerf.netx.domain.DevicesService
import com.nerf.netx.domain.MapLayoutMode
import com.nerf.netx.domain.MapLink
import com.nerf.netx.domain.MapNode
import com.nerf.netx.domain.MapService
import com.nerf.netx.domain.MapTopologyService
import com.nerf.netx.domain.MeasurementMode
import com.nerf.netx.domain.QosMode
import com.nerf.netx.domain.RouterBackendState
import com.nerf.netx.domain.RouterCapabilityState
import com.nerf.netx.domain.RouterControlService
import com.nerf.netx.domain.RouterFeatureState
import com.nerf.netx.domain.RouterInfoResult
import com.nerf.netx.domain.RouterStatusSnapshot
import com.nerf.netx.domain.RouterWriteAction
import com.nerf.netx.domain.ScanEvent
import com.nerf.netx.domain.ScanPhase
import com.nerf.netx.domain.ScanService
import com.nerf.netx.domain.ScanState
import com.nerf.netx.domain.ServiceStatus
import com.nerf.netx.domain.SpeedtestConfig
import com.nerf.netx.domain.SpeedtestHistoryEntry
import com.nerf.netx.domain.SpeedtestPhase
import com.nerf.netx.domain.SpeedtestResult
import com.nerf.netx.domain.SpeedtestService
import com.nerf.netx.domain.SpeedtestServer
import com.nerf.netx.domain.ThroughputSample
import com.nerf.netx.domain.SpeedtestUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

class HybridBackendGateway(
  context: Context,
  private val credentialsStore: RouterCredentialsStore = RouterCredentialsStore(context)
) : AppServices {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lanScanner = RealLanScanner(context)
  private val sharedDevices = MutableStateFlow<List<Device>>(emptyList())
  private val selectedNodeId = MutableStateFlow<String?>(null)

  override val speedtest: SpeedtestService = HybridSpeedtest(context, scope)
  override val scan: ScanService = HybridScanService(scope, lanScanner, sharedDevices)
  override val devices: DevicesService = HybridDevicesService(sharedDevices, scan)
  override val deviceControl: DeviceControlService = HybridDeviceControl(sharedDevices, lanScanner, credentialsStore)
  override val map: MapService = HybridMapService(sharedDevices, selectedNodeId)
  override val topology: MapTopologyService = HybridTopologyService(sharedDevices, selectedNodeId)
  override val analytics: AnalyticsService = HybridAnalytics(context, speedtest, sharedDevices, scan)
  override val routerControl: RouterControlService = HybridRouterControl(context, credentialsStore)
}

private class HybridSpeedtest(
  context: Context,
  private val scope: CoroutineScope
) : SpeedtestService {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences("nerf_speedtest_prefs", Context.MODE_PRIVATE)
  private val historyKey = "speedtest_history_json"
  private val uiMutex = Mutex()
  private val activeConnections = linkedSetOf<HttpURLConnection>()
  private val connectionMutex = Mutex()

  private val _servers = MutableStateFlow(defaultServers())
  override val servers: StateFlow<List<SpeedtestServer>> = _servers.asStateFlow()

  private val _config = MutableStateFlow(defaultConfig())
  override val config: StateFlow<SpeedtestConfig> = _config.asStateFlow()

  private val _history = MutableStateFlow(loadHistory())
  override val history: StateFlow<List<SpeedtestHistoryEntry>> = _history.asStateFlow()

  private val _latestResult = MutableStateFlow<SpeedtestResult?>(null)
  override val latestResult: StateFlow<SpeedtestResult?> = _latestResult.asStateFlow()

  private val _ui = MutableStateFlow(
    SpeedtestUiState(
      running = false,
      progress01 = 0f,
      downMbps = null,
      upMbps = null,
      latencyMs = null,
      phase = SpeedtestPhase.IDLE.name,
      phaseEnum = SpeedtestPhase.IDLE,
      mode = MeasurementMode.NOT_AVAILABLE,
      status = ServiceStatus.IDLE,
      message = "Ready"
    )
  )
  override val ui: StateFlow<SpeedtestUiState> = _ui.asStateFlow()
  private var job: Job? = null

  override suspend fun start() {
    if (_ui.value.running) return
    job?.cancel()
    job = scope.launch {
      runSpeedtest()
    }
  }

  override suspend fun stop() {
    job?.cancel(CancellationException("ABORT_REQUESTED"))
    closeActiveConnections()
    job = null
    uiMutex.withLock {
      _ui.value = _ui.value.copy(
        running = false,
        phase = SpeedtestPhase.ABORTED.name,
        phaseEnum = SpeedtestPhase.ABORTED,
        status = ServiceStatus.IDLE,
        message = "Test aborted by user."
      )
    }
  }

  override suspend fun reset() {
    job?.cancel(CancellationException("RESET_REQUESTED"))
    closeActiveConnections()
    job = null
    uiMutex.withLock {
      _ui.value = SpeedtestUiState(
        running = false,
        progress01 = 0f,
        downMbps = null,
        upMbps = null,
        latencyMs = null,
        phase = SpeedtestPhase.IDLE.name,
        phaseEnum = SpeedtestPhase.IDLE,
        mode = MeasurementMode.NOT_AVAILABLE,
        status = ServiceStatus.IDLE,
        message = "Ready"
      )
    }
    _latestResult.value = null
  }

  override suspend fun updateConfig(config: SpeedtestConfig) {
    _config.value = config.sanitize()
  }

  override suspend fun clearHistory() {
    _history.value = emptyList()
    prefs.edit().remove(historyKey).apply()
  }

  private suspend fun runSpeedtest() {
    val startedAt = System.currentTimeMillis()
    val cfg = _config.value.sanitize()
    _config.value = cfg
    val allSamples = mutableListOf<ThroughputSample>()
    val reasons = mutableMapOf<String, String>()

    uiMutex.withLock {
      _ui.value = _ui.value.copy(
        running = true,
        progress01 = 0f,
        phase = SpeedtestPhase.PING.name,
        phaseEnum = SpeedtestPhase.PING,
        currentMbps = null,
        samples = emptyList(),
        downMbps = null,
        upMbps = null,
        pingMs = null,
        jitterMs = null,
        packetLossPct = null,
        latencyMs = null,
        mode = MeasurementMode.REAL,
        status = ServiceStatus.RUNNING,
        message = "Selecting server and measuring ping...",
        reason = null
      )
    }

    try {
      val selected = selectServer(cfg)
      if (selected == null) {
        val message = if (_servers.value.isEmpty()) {
          "NOT_CONFIGURED: Add valid speedtest servers with ping/download/upload endpoints."
        } else {
          "No reachable speedtest server found."
        }
        reasons["server"] = message
        completeWithError(
          startedAt = startedAt,
          message = message,
          reasons = reasons
        )
        return
      }

      val server = selected.server
      val pingStats = pingServer(server, samples = 10, timeoutMs = cfg.timeoutMs)
      pingStats.reason?.let { reasons["ping"] = it }

      uiMutex.withLock {
        _ui.value = _ui.value.copy(
          progress01 = 0.2f,
          phase = SpeedtestPhase.DOWNLOAD.name,
          phaseEnum = SpeedtestPhase.DOWNLOAD,
          activeServerId = server.id,
          activeServerName = server.name,
          pingMs = pingStats.medianMs,
          jitterMs = pingStats.jitterMs,
          packetLossPct = pingStats.packetLossPct,
          latencyMs = pingStats.medianMs?.toInt(),
          message = "Running download throughput..."
        )
      }

      val downOutcome = runDownloadPhase(server, cfg) { progress, sample ->
        allSamples += sample
        updateUiSample(
          phase = SpeedtestPhase.DOWNLOAD,
          progress01 = 0.2f + (0.35f * progress),
          currentMbps = sample.mbps,
          samples = allSamples
        )
      }
      downOutcome.reason?.let { reasons["download"] = it }

      uiMutex.withLock {
        _ui.value = _ui.value.copy(
          progress01 = 0.58f,
          phase = SpeedtestPhase.UPLOAD.name,
          phaseEnum = SpeedtestPhase.UPLOAD,
          downMbps = downOutcome.mbps,
          message = "Running upload throughput..."
        )
      }

      val upOutcome = runUploadPhase(server, cfg) { progress, sample ->
        allSamples += sample
        updateUiSample(
          phase = SpeedtestPhase.UPLOAD,
          progress01 = 0.58f + (0.35f * progress),
          currentMbps = sample.mbps,
          samples = allSamples
        )
      }
      upOutcome.reason?.let { reasons["upload"] = it }

      val finishedAt = System.currentTimeMillis()
      val result = SpeedtestResult(
        phase = SpeedtestPhase.DONE,
        serverId = server.id,
        serverName = server.name,
        pingMs = pingStats.medianMs,
        jitterMs = pingStats.jitterMs,
        packetLossPct = pingStats.packetLossPct,
        downloadMbps = downOutcome.mbps,
        uploadMbps = upOutcome.mbps,
        samples = allSamples.toList(),
        error = null,
        startedAt = startedAt,
        finishedAt = finishedAt,
        reasons = reasons
      )
      _latestResult.value = result
      appendHistory(result)

      uiMutex.withLock {
        _ui.value = _ui.value.copy(
          running = false,
          progress01 = 1f,
          phase = SpeedtestPhase.DONE.name,
          phaseEnum = SpeedtestPhase.DONE,
          downMbps = downOutcome.mbps,
          upMbps = upOutcome.mbps,
          latencyMs = pingStats.medianMs?.toInt(),
          pingMs = pingStats.medianMs,
          jitterMs = pingStats.jitterMs,
          packetLossPct = pingStats.packetLossPct,
          currentMbps = null,
          samples = allSamples.toList(),
          mode = MeasurementMode.REAL,
          status = ServiceStatus.OK,
          message = "Speedtest completed."
        )
      }
    } catch (ce: CancellationException) {
      val abortedAt = System.currentTimeMillis()
      val aborted = SpeedtestResult(
        phase = SpeedtestPhase.ABORTED,
        serverId = _ui.value.activeServerId,
        serverName = _ui.value.activeServerName,
        pingMs = _ui.value.pingMs,
        jitterMs = _ui.value.jitterMs,
        packetLossPct = _ui.value.packetLossPct,
        downloadMbps = _ui.value.downMbps,
        uploadMbps = _ui.value.upMbps,
        samples = _ui.value.samples,
        error = "Aborted",
        startedAt = startedAt,
        finishedAt = abortedAt
      )
      _latestResult.value = aborted
      uiMutex.withLock {
        _ui.value = _ui.value.copy(
          running = false,
          phase = SpeedtestPhase.ABORTED.name,
          phaseEnum = SpeedtestPhase.ABORTED,
          status = ServiceStatus.IDLE,
          message = "Test aborted by user."
        )
      }
    } catch (t: Throwable) {
      reasons["error"] = t.message ?: "Unhandled speedtest error"
      completeWithError(
        startedAt = startedAt,
        message = t.message ?: "Speedtest failed.",
        reasons = reasons
      )
    }
  }

  private suspend fun completeWithError(
    startedAt: Long,
    message: String,
    reasons: Map<String, String>
  ) {
    val finishedAt = System.currentTimeMillis()
    val result = SpeedtestResult(
      phase = SpeedtestPhase.ERROR,
      serverId = _ui.value.activeServerId,
      serverName = _ui.value.activeServerName,
      pingMs = _ui.value.pingMs,
      jitterMs = _ui.value.jitterMs,
      packetLossPct = _ui.value.packetLossPct,
      downloadMbps = _ui.value.downMbps,
      uploadMbps = _ui.value.upMbps,
      samples = _ui.value.samples,
      error = message,
      startedAt = startedAt,
      finishedAt = finishedAt,
      reasons = reasons
    )
    _latestResult.value = result
    uiMutex.withLock {
      _ui.value = _ui.value.copy(
        running = false,
        phase = SpeedtestPhase.ERROR.name,
        phaseEnum = SpeedtestPhase.ERROR,
        status = ServiceStatus.ERROR,
        message = message,
        reason = reasons.entries.joinToString(" | ") { "${it.key}:${it.value}" }
      )
    }
  }

  private suspend fun updateUiSample(
    phase: SpeedtestPhase,
    progress01: Float,
    currentMbps: Double,
    samples: List<ThroughputSample>
  ) {
    uiMutex.withLock {
      _ui.value = _ui.value.copy(
        progress01 = progress01.coerceIn(0f, 0.99f),
        phase = phase.name,
        phaseEnum = phase,
        currentMbps = currentMbps,
        samples = samples.takeLast(600)
      )
    }
  }

  private suspend fun selectServer(config: SpeedtestConfig): SelectedServer? = coroutineScope {
    val available = _servers.value.filter { it.baseUrl.isNotBlank() && it.pingUrl.isNotBlank() }
    if (available.isEmpty()) return@coroutineScope null

    if (config.serverMode.uppercase(Locale.US) == "MANUAL") {
      val chosen = available.firstOrNull { it.id == config.selectedServerId } ?: return@coroutineScope null
      return@coroutineScope SelectedServer(chosen, null)
    }

    val scored = available.map { server ->
      async(Dispatchers.IO) {
        server to pingServer(server, samples = 5, timeoutMs = minOf(1_500L, config.timeoutMs))
      }
    }.map { it.await() }

    val winner = scored
      .filter { it.second.medianMs != null }
      .minByOrNull { it.second.medianMs ?: Double.MAX_VALUE }
      ?: return@coroutineScope null
    SelectedServer(winner.first, winner.second)
  }

  private suspend fun pingServer(server: SpeedtestServer, samples: Int, timeoutMs: Long): PingStats {
    val successful = mutableListOf<Double>()
    var lost = 0
    repeat(samples) {
      val elapsed = measureHttpPing(server, timeoutMs)
      if (elapsed == null) {
        lost += 1
      } else {
        successful += elapsed
      }
      delay(90)
    }
    val median = successful.median()
    val jitter = successful.standardDeviation()
    val packetLoss = if (samples > 0) (lost.toDouble() / samples.toDouble()) * 100.0 else null
    val reason = when {
      successful.isEmpty() -> "All ping attempts timed out/failed for ${server.name}"
      lost > 0 -> "Packet loss estimated from ping timeouts"
      else -> null
    }
    return PingStats(
      medianMs = median,
      jitterMs = jitter,
      packetLossPct = packetLoss,
      reason = reason
    )
  }

  private suspend fun measureHttpPing(server: SpeedtestServer, timeoutMs: Long): Double? {
    return withContext(Dispatchers.IO) {
      val url = buildUrl(server.baseUrl, server.pingUrl)
      timedHttpRequest(url, "HEAD", timeoutMs) ?: timedHttpRequest(url, "GET", timeoutMs)
    }
  }

  private suspend fun runDownloadPhase(
    server: SpeedtestServer,
    config: SpeedtestConfig,
    onSample: suspend (progress: Float, sample: ThroughputSample) -> Unit
  ): ThroughputPhaseOutcome {
    val size = config.downloadSizesBytes.maxOrNull() ?: 20_000_000
    val downloadPath = resolveDownloadPath(server, size) ?: return ThroughputPhaseOutcome(
      mbps = null,
      reason = "Download path not configured for server ${server.name}"
    )

    return runThroughputPhase(
      phase = SpeedtestPhase.DOWNLOAD,
      durationMs = config.durationMs,
      threads = config.threads,
      onSample = onSample
    ) { bytesCounter ->
      val nonce = System.currentTimeMillis()
      val url = buildUrl(server.baseUrl, downloadPath)
      val urlWithNonce = if (url.contains("?")) "$url&nocache=$nonce" else "$url?nocache=$nonce"
      withTimeoutOrNull(config.timeoutMs) {
        val connection = (URL(urlWithNonce).openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = config.timeoutMs.toInt()
          readTimeout = config.timeoutMs.toInt()
          useCaches = false
          doInput = true
        }
        try {
          trackConnection(connection)
          connection.connect()
          val stream = BufferedInputStream(connection.inputStream)
          stream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
              val read = input.read(buffer)
              if (read <= 0) break
              bytesCounter.addAndGet(read.toLong())
            }
          }
        } finally {
          untrackConnection(connection)
          connection.disconnect()
        }
      }
    }
  }

  private suspend fun runUploadPhase(
    server: SpeedtestServer,
    config: SpeedtestConfig,
    onSample: suspend (progress: Float, sample: ThroughputSample) -> Unit
  ): ThroughputPhaseOutcome {
    val payloadSize = config.uploadSizesBytes.maxOrNull() ?: 10_000_000
    if (server.uploadUrl.isBlank()) {
      return ThroughputPhaseOutcome(
        mbps = null,
        reason = "Upload endpoint not configured for server ${server.name}"
      )
    }
    val uploadUrl = buildUrl(server.baseUrl, server.uploadUrl)

    return runThroughputPhase(
      phase = SpeedtestPhase.UPLOAD,
      durationMs = config.durationMs,
      threads = config.threads,
      onSample = onSample
    ) { bytesCounter ->
      withTimeoutOrNull(config.timeoutMs) {
        val connection = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = config.timeoutMs.toInt()
          readTimeout = config.timeoutMs.toInt()
          doOutput = true
          doInput = true
          useCaches = false
          setRequestProperty("Content-Type", "application/octet-stream")
          setFixedLengthStreamingMode(payloadSize)
        }
        try {
          trackConnection(connection)
          connection.connect()
          connection.outputStream.use { output ->
            val chunk = ByteArray(16 * 1024) { 0x5A.toByte() }
            var remaining = payloadSize
            while (remaining > 0) {
              val writeSize = minOf(remaining, chunk.size)
              output.write(chunk, 0, writeSize)
              bytesCounter.addAndGet(writeSize.toLong())
              remaining -= writeSize
            }
            output.flush()
          }
          connection.inputStream.use { input ->
            drainStream(input)
          }
        } finally {
          untrackConnection(connection)
          connection.disconnect()
        }
      }
    }
  }

  private suspend fun runThroughputPhase(
    phase: SpeedtestPhase,
    durationMs: Long,
    threads: Int,
    onSample: suspend (progress: Float, sample: ThroughputSample) -> Unit,
    worker: suspend (AtomicLong) -> Unit
  ): ThroughputPhaseOutcome = coroutineScope {
    val safeThreads = threads.coerceIn(1, 8)
    val start = System.currentTimeMillis()
    val end = start + durationMs.coerceAtLeast(1_000L)
    val totalBytes = AtomicLong(0)

    val workers = (0 until safeThreads).map {
      launch(Dispatchers.IO) {
        while (isActive && System.currentTimeMillis() < end) {
          worker(totalBytes)
        }
      }
    }

    var lastBytes = 0L
    var lastTick = start
    while (isActive && System.currentTimeMillis() < end) {
      delay(300)
      val now = System.currentTimeMillis()
      val currentBytes = totalBytes.get()
      val deltaBytes = (currentBytes - lastBytes).coerceAtLeast(0L)
      val deltaMs = (now - lastTick).coerceAtLeast(1L)
      val mbps = (deltaBytes.toDouble() * 8.0) / (deltaMs.toDouble() / 1000.0) / 1_000_000.0
      val sample = ThroughputSample(
        phase = phase,
        tMs = now - start,
        mbps = mbps
      )
      onSample(((now - start).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f), sample)
      lastBytes = currentBytes
      lastTick = now
    }

    workers.forEach {
      it.cancel()
      runCatching { it.join() }
    }

    val actualDurationMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
    val finalMbps = if (totalBytes.get() <= 0L) null else {
      (totalBytes.get().toDouble() * 8.0) / (actualDurationMs.toDouble() / 1000.0) / 1_000_000.0
    }
    ThroughputPhaseOutcome(
      mbps = finalMbps,
      reason = if (finalMbps == null) {
        "${phase.name} transferred 0 bytes in ${actualDurationMs}ms. Verify endpoint supports this test."
      } else {
        null
      }
    )
  }

  private fun resolveDownloadPath(server: SpeedtestServer, requestedSize: Int): String? {
    if (server.downloadPaths.isEmpty()) return null
    server.downloadPaths[requestedSize]?.let { return it }
    return server.downloadPaths
      .entries
      .sortedBy { kotlin.math.abs(it.key - requestedSize) }
      .firstOrNull()
      ?.value
  }

  private fun buildUrl(baseUrl: String, path: String): String {
    val trimmedBase = baseUrl.trimEnd('/')
    val trimmedPath = if (path.startsWith("/")) path else "/$path"
    return trimmedBase + trimmedPath
  }

  private suspend fun timedHttpRequest(url: String, method: String, timeoutMs: Long): Double? {
    return runCatching {
      val started = System.nanoTime()
      val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = timeoutMs.toInt()
        readTimeout = timeoutMs.toInt()
        useCaches = false
      }
      try {
        trackConnection(connection)
        connection.connect()
        if (method == "GET") {
          connection.inputStream.use { input -> drainStream(input) }
        } else {
          connection.responseCode
        }
      } finally {
        untrackConnection(connection)
        connection.disconnect()
      }
      (System.nanoTime() - started).toDouble() / 1_000_000.0
    }.getOrNull()
  }

  private fun drainStream(input: InputStream) {
    val buffer = ByteArray(8 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) break
    }
  }

  private fun appendHistory(result: SpeedtestResult) {
    val entry = SpeedtestHistoryEntry(
      id = UUID.randomUUID().toString(),
      timestamp = result.finishedAt,
      serverName = result.serverName,
      pingMs = result.pingMs,
      downMbps = result.downloadMbps,
      upMbps = result.uploadMbps,
      jitterMs = result.jitterMs,
      lossPct = result.packetLossPct
    )
    val updated = (listOf(entry) + _history.value).take(25)
    _history.value = updated
    persistHistory(updated)
  }

  private fun loadHistory(): List<SpeedtestHistoryEntry> {
    val raw = prefs.getString(historyKey, null) ?: return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      (0 until arr.length()).mapNotNull { idx ->
        val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
        SpeedtestHistoryEntry(
          id = obj.optString("id"),
          timestamp = obj.optLong("timestamp"),
          serverName = obj.optString("serverName").ifBlank { null },
          pingMs = obj.optDoubleOrNull("pingMs"),
          downMbps = obj.optDoubleOrNull("downMbps"),
          upMbps = obj.optDoubleOrNull("upMbps"),
          jitterMs = obj.optDoubleOrNull("jitterMs"),
          lossPct = obj.optDoubleOrNull("lossPct")
        )
      }
    }.getOrDefault(emptyList())
  }

  private fun persistHistory(entries: List<SpeedtestHistoryEntry>) {
    val arr = JSONArray()
    entries.forEach { entry ->
      arr.put(
        JSONObject()
          .put("id", entry.id)
          .put("timestamp", entry.timestamp)
          .put("serverName", entry.serverName)
          .put("pingMs", entry.pingMs)
          .put("downMbps", entry.downMbps)
          .put("upMbps", entry.upMbps)
          .put("jitterMs", entry.jitterMs)
          .put("lossPct", entry.lossPct)
      )
    }
    prefs.edit().putString(historyKey, arr.toString()).apply()
  }

  private fun defaultConfig(): SpeedtestConfig {
    return SpeedtestConfig(
      serverMode = "AUTO",
      selectedServerId = null,
      downloadSizesBytes = listOf(5_000_000, 20_000_000),
      uploadSizesBytes = listOf(2_000_000, 10_000_000),
      threads = 4,
      durationMs = 8_000,
      timeoutMs = 5_000
    )
  }

  private fun defaultServers(): List<SpeedtestServer> {
    return listOf(
      SpeedtestServer(
        id = "librespeed_org",
        name = "LibreSpeed Main",
        baseUrl = "https://librespeed.org/backend",
        pingUrl = "/empty.php",
        downloadPaths = mapOf(
          5_000_000 to "/garbage.php?ckSize=5000",
          20_000_000 to "/garbage.php?ckSize=20000"
        ),
        uploadUrl = "/empty.php"
      ),
      SpeedtestServer(
        id = "librespeed_backup",
        name = "LibreSpeed Backup",
        baseUrl = "https://librespeedtest.net/backend",
        pingUrl = "/empty.php",
        downloadPaths = mapOf(
          5_000_000 to "/garbage.php?ckSize=5000",
          20_000_000 to "/garbage.php?ckSize=20000"
        ),
        uploadUrl = "/empty.php"
      )
    )
  }

  private fun SpeedtestConfig.sanitize(): SpeedtestConfig {
    return copy(
      serverMode = if (serverMode.uppercase(Locale.US) == "MANUAL") "MANUAL" else "AUTO",
      threads = threads.coerceIn(1, 8),
      durationMs = durationMs.coerceIn(2_000L, 30_000L),
      timeoutMs = timeoutMs.coerceIn(1_000L, 20_000L),
      downloadSizesBytes = downloadSizesBytes.filter { it > 0 }.ifEmpty { listOf(5_000_000, 20_000_000) },
      uploadSizesBytes = uploadSizesBytes.filter { it > 0 }.ifEmpty { listOf(2_000_000, 10_000_000) }
    )
  }

  private fun List<Double>.median(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    return sorted[sorted.size / 2]
  }

  private fun List<Double>.standardDeviation(): Double? {
    if (size < 2) return null
    val mean = average()
    val variance = map { (it - mean) * (it - mean) }.average()
    return sqrt(variance)
  }

  private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return optDouble(name)
  }

  private suspend fun trackConnection(connection: HttpURLConnection) {
    connectionMutex.withLock { activeConnections += connection }
  }

  private suspend fun untrackConnection(connection: HttpURLConnection) {
    connectionMutex.withLock { activeConnections -= connection }
  }

  private suspend fun closeActiveConnections() {
    connectionMutex.withLock {
      activeConnections.forEach { runCatching { it.disconnect() } }
      activeConnections.clear()
    }
  }

  private data class SelectedServer(
    val server: SpeedtestServer,
    val quickPing: PingStats?
  )

  private data class PingStats(
    val medianMs: Double?,
    val jitterMs: Double?,
    val packetLossPct: Double?,
    val reason: String?
  )

  private data class ThroughputPhaseOutcome(
    val mbps: Double?,
    val reason: String?
  )
}

private class HybridScanService(
  private val scope: CoroutineScope,
  private val lanScanner: RealLanScanner,
  private val sharedDevices: MutableStateFlow<List<Device>>
) : ScanService {
  private val _scanState = MutableStateFlow(ScanState(ScanPhase.IDLE, 0, 0))
  override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()
  private val _results = MutableStateFlow<List<Device>>(emptyList())
  override val results: StateFlow<List<Device>> = _results.asStateFlow()
  private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 128)
  override val events: SharedFlow<ScanEvent> = _events.asSharedFlow()
  private var scanJob: Job? = null
  private val knownDevicesByIp = linkedMapOf<String, Device>()

  override suspend fun startDeepScan() {
    if (scanJob?.isActive == true) return
    scanJob = scope.launch {
      val foundIps = linkedSetOf<String>()
      var targetsPlanned = 0
      try {
        lanScanner.scanHosts(
          onStarted = { planned, warning, metadata ->
            targetsPlanned = planned
            _scanState.value = ScanState(
              phase = ScanPhase.RUNNING,
              scannedHosts = 0,
              discoveredHosts = 0,
              message = warning ?: "Deep scan started"
            )
            _events.tryEmit(ScanEvent.ScanStarted(targetsPlanned = planned, metadata = metadata))
          },
          onProgress = { probesSent, devicesFound, planned ->
            _scanState.value = ScanState(
              phase = ScanPhase.RUNNING,
              scannedHosts = probesSent,
              discoveredHosts = devicesFound,
              message = "Scanning LAN"
            )
            _events.tryEmit(
              ScanEvent.ScanProgress(
                targetsPlanned = planned,
                probesSent = probesSent,
                devicesFound = devicesFound
              )
            )
          },
          onDevice = { device, updated ->
            val now = System.currentTimeMillis()
            val enriched = device.copy(
              online = true,
              reachable = true,
              deviceType = inferDeviceType(device.hostName),
              lastSeenEpochMs = now,
              lastSeen = now
            )
            synchronized(knownDevicesByIp) {
              foundIps += enriched.ip
              knownDevicesByIp[enriched.ip] = enriched
              val snapshot = knownDevicesByIp.values.sortedBy { it.ip }
              _results.value = snapshot
              sharedDevices.value = snapshot
            }
            _events.tryEmit(ScanEvent.ScanDevice(device = enriched, updated = updated))
          }
        ).also { outcome ->
          val postScanUpdates = mutableListOf<Device>()
          val finalRows = synchronized(knownDevicesByIp) {
            knownDevicesByIp.values.toList().forEach { previous ->
              if (!foundIps.contains(previous.ip)) {
                val offline = previous.copy(
                  online = false,
                  reachable = false,
                  latencyMs = null,
                  latencyReason = "Not observed in latest scan",
                  methodUsed = null,
                  reachabilityMethod = "N/A",
                  rssi = if (previous.isGateway) outcome.metadata.wifiRssi else null,
                  rssiDbm = if (previous.isGateway) outcome.metadata.wifiRssi else null,
                  unresolvedReasons = previous.unresolvedReasons + ("online" to "Host not discovered in current scan"),
                  lastSeen = previous.lastSeen,
                  lastSeenEpochMs = previous.lastSeenEpochMs
                )
                knownDevicesByIp[previous.ip] = offline
                postScanUpdates += offline
              } else if (previous.isGateway) {
                val gatewayUpdated = previous.copy(
                  rssi = outcome.metadata.wifiRssi,
                  rssiDbm = outcome.metadata.wifiRssi
                )
                knownDevicesByIp[previous.ip] = gatewayUpdated
                postScanUpdates += gatewayUpdated
              }
            }
            knownDevicesByIp.values.sortedBy { it.ip }
          }
          postScanUpdates.forEach { _events.tryEmit(ScanEvent.ScanDevice(device = it, updated = true)) }
          _results.value = finalRows
          sharedDevices.value = finalRows
          _scanState.value = ScanState(
            phase = ScanPhase.COMPLETE,
            scannedHosts = targetsPlanned,
            discoveredHosts = finalRows.size,
            message = "Scan complete"
          )
          _events.tryEmit(
            ScanEvent.ScanDone(
              targetsPlanned = targetsPlanned,
              probesSent = targetsPlanned,
              devicesFound = finalRows.size,
              metadata = outcome.metadata
            )
          )
        }
      } catch (ce: CancellationException) {
        _scanState.value = ScanState(ScanPhase.IDLE, 0, 0, "Scan stopped")
        throw ce
      } catch (t: Throwable) {
        _scanState.value = ScanState(ScanPhase.ERROR, 0, 0, t.message ?: "Scan error")
        _events.tryEmit(ScanEvent.ScanError(t.message ?: "Scan error"))
      }
    }
  }

  override suspend fun stopScan() {
    scanJob?.cancel()
    scanJob = null
    _scanState.value = ScanState(ScanPhase.IDLE, 0, 0, "Stopped")
  }

  private fun inferDeviceType(hostname: String?): String {
    if (hostname.isNullOrBlank()) return "UNKNOWN"
    val h = hostname.lowercase()
    return when {
      h.contains("iphone") || h.contains("ipad") -> "PHONE"
      h.contains("android") || h.contains("pixel") || h.contains("samsung") -> "PHONE"
      h.contains("macbook") || h.contains("imac") -> "COMPUTER"
      h.contains("windows") || h.contains("desktop") || h.contains("laptop") -> "COMPUTER"
      h.contains("tv") || h.contains("roku") || h.contains("chromecast") -> "MEDIA"
      h.contains("printer") -> "PRINTER"
      h.contains("cam") || h.contains("camera") -> "CAMERA"
      else -> "UNKNOWN"
    }
  }
}

private class HybridDevicesService(
  private val sharedDevices: MutableStateFlow<List<Device>>,
  private val scanService: ScanService
) : DevicesService {
  override val devices: StateFlow<List<Device>> = sharedDevices.asStateFlow()

  override suspend fun refresh() {
    scanService.startDeepScan()
  }
}

private class HybridDeviceControl(
  private val sharedDevices: MutableStateFlow<List<Device>>,
  private val lanScanner: RealLanScanner,
  private val credentialsStore: RouterCredentialsStore
) : DeviceControlService {
  private val _status = MutableStateFlow(
    DeviceControlStatusSnapshot(
      status = ServiceStatus.NO_DATA,
      message = "Router-backed device control is not configured."
    )
  )
  override val status: StateFlow<DeviceControlStatusSnapshot> = _status.asStateFlow()

  override suspend fun refreshStatus(): DeviceControlStatusSnapshot {
    return runCatching { refreshDeviceStatus() }.getOrElse { error ->
      DeviceControlStatusSnapshot(
        status = ServiceStatus.ERROR,
        message = error.message ?: "Device control status refresh failed.",
        backend = DeviceControlBackendState(
          detected = false,
          authenticated = false,
          readable = false,
          writable = false,
          message = error.message ?: "Device control status refresh failed."
        )
      ).also { _status.value = it }
    }
  }

  override suspend fun ping(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(
        ok = false,
        status = ServiceStatus.ERROR,
        code = "DEVICE_NOT_FOUND",
        message = "Device not found",
        errorReason = "No device with id=$deviceId"
      )

    val probe = lanScanner.probeReachability(d.ip)
    return if (probe.reachable) {
      ActionResult(
        ok = true,
        status = ServiceStatus.OK,
        code = "PING_OK",
        message = "Ping completed",
        details = mapOf(
          "ip" to d.ip,
          "latencyMs" to (probe.latencyMs?.toString() ?: "N/A"),
          "methodUsed" to (probe.methodUsed ?: "N/A"),
          "reachable" to "true"
        )
      )
    } else {
      ActionResult(
        ok = false,
        status = ServiceStatus.NO_DATA,
        code = "PING_UNAVAILABLE",
        message = "Reachability unavailable",
        details = mapOf(
          "ip" to d.ip,
          "latencyMs" to "N/A",
          "methodUsed" to "UNAVAILABLE",
          "reachable" to "false"
        ),
        errorReason = "ICMP blocked and TCP fallback ports did not accept connections."
      )
    }
  }

  override suspend fun setBlocked(deviceId: String, blocked: Boolean): ActionResult {
    val device = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, ServiceStatus.ERROR, "DEVICE_NOT_FOUND", "Device not found")
    val routerDevice = routerDevice(device)
    val api = buildRouterApi()
      ?: return deviceUnavailable(
        action = if (blocked) DeviceControlAction.BLOCK else DeviceControlAction.UNBLOCK,
        device = device,
        reason = "Router credentials are not configured or validated."
      )
    val capability = getDeviceCapabilityState(device)
    val actionId = if (blocked) AppActionId.DEVICE_BLOCK else AppActionId.DEVICE_UNBLOCK
    val support = capability.actionSupport[actionId]
    if (support == null || !support.supported) {
      return deviceSupportResult(
        action = if (blocked) DeviceControlAction.BLOCK else DeviceControlAction.UNBLOCK,
        device = device,
        backend = capability.backend,
        reason = support?.reason ?: capability.message
      )
    }

    val result = api.setDeviceBlocked(routerDevice, blocked)
    val mapped = mapDeviceActionResult(
      result = result,
      action = if (blocked) DeviceControlAction.BLOCK else DeviceControlAction.UNBLOCK,
      okCode = if (blocked) "DEVICE_BLOCKED" else "DEVICE_UNBLOCKED",
      device = device
    )
    if (mapped.ok) {
      refreshDeviceState(deviceId)
    } else {
      refreshDeviceStatus()
    }
    return mapped
  }

  override suspend fun setPaused(deviceId: String, paused: Boolean): ActionResult {
    val device = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, ServiceStatus.ERROR, "DEVICE_NOT_FOUND", "Device not found")
    val capability = getDeviceCapabilityState(device)
    val actionId = if (paused) AppActionId.DEVICE_PAUSE else AppActionId.DEVICE_RESUME
    val support = capability.actionSupport[actionId]
    return deviceSupportResult(
      action = if (paused) DeviceControlAction.PAUSE else DeviceControlAction.RESUME,
      device = device,
      backend = capability.backend,
      reason = support?.reason ?: capability.message
    )
  }

  override suspend fun rename(deviceId: String, name: String): ActionResult {
    val device = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, ServiceStatus.ERROR, "DEVICE_NOT_FOUND", "Device not found")
    val api = buildRouterApi()
      ?: return deviceUnavailable(
        action = DeviceControlAction.RENAME,
        device = device,
        reason = "Router credentials are not configured or validated."
      )
    val capability = getDeviceCapabilityState(device)
    val support = capability.actionSupport[AppActionId.DEVICE_RENAME]
    if (support == null || !support.supported) {
      return deviceSupportResult(
        action = DeviceControlAction.RENAME,
        device = device,
        backend = capability.backend,
        reason = support?.reason ?: capability.message
      )
    }
    val result = api.renameDevice(routerDevice(device), name)
    val mapped = mapDeviceActionResult(
      result = result,
      action = DeviceControlAction.RENAME,
      okCode = "DEVICE_RENAMED",
      device = device
    )
    if (mapped.ok) {
      refreshDeviceState(deviceId)
    } else {
      refreshDeviceStatus()
    }
    return mapped
  }

  override suspend fun block(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, ServiceStatus.ERROR, "DEVICE_NOT_FOUND", "Device not found")
    return setBlocked(d.id, true)
  }

  override suspend fun prioritize(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, ServiceStatus.ERROR, "DEVICE_NOT_FOUND", "Device not found")
    val capability = getDeviceCapabilityState(d)
    return deviceSupportResult(
      action = DeviceControlAction.PRIORITIZE,
      device = d,
      backend = capability.backend,
      reason = capability.actionSupport[AppActionId.DEVICE_PRIORITIZE]?.reason ?: capability.message
    )
  }

  override suspend fun deviceDetails(deviceId: String): DeviceDetails? {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId } ?: return null
    val probe = lanScanner.probeReachability(d.ip)
    val capability = getDeviceCapabilityState(d)
    val support = capability.toSupport()
    return DeviceDetails(
      device = capability.device,
      pingMs = probe.latencyMs,
      notes = if (probe.reachable) "Reachable via ${probe.methodUsed}" else "Reachability unavailable",
      support = support,
      actionSupport = capability.actionSupport,
      backend = capability.backend,
      deviceCapabilities = capability.capabilities,
      trafficMessage = "Per-device traffic telemetry is unavailable from the current backend."
    )
  }

  private suspend fun getDeviceCapabilityState(device: Device): DeviceCapabilitySnapshot {
    val api = buildRouterApi()
    if (api == null) {
      val snapshot = DeviceControlStatusSnapshot(
        status = ServiceStatus.NO_DATA,
        message = "Router credentials are not configured.",
        backend = DeviceControlBackendState(
          detected = false,
          authenticated = false,
          readable = false,
          writable = false,
          message = "Router credentials are not configured."
        )
      )
      _status.value = snapshot
      val actionSupport = ActionSupportCatalog.deviceActionSupport(device, snapshot)
      return DeviceCapabilitySnapshot(device, snapshot.backend, emptyMap(), actionSupport, snapshot.message)
    }

    val statusSnapshot = refreshStatus()
    val routerDevice = routerDevice(device)
    val runtime = api.getDeviceRuntimeCapabilities(routerDevice)
    val backend = statusSnapshot.backend
    val capabilities = runtime.actionCapabilities.values.associate { capability ->
      deviceActionIdFor(capability.capability) to DeviceCapabilityState(
        actionId = deviceActionIdFor(capability.capability),
        label = deviceActionLabel(capability.capability),
        supported = capability.supported,
        detected = runtime.detected,
        authenticated = runtime.authenticated,
        readable = capability.readable,
        writable = capability.writable,
        status = when {
          capability.writable -> ServiceStatus.OK
          capability.readable -> ServiceStatus.NO_DATA
          capability.supported -> ServiceStatus.NO_DATA
          else -> ServiceStatus.NOT_SUPPORTED
        },
        reason = capability.reason,
        source = capability.source
      )
    }
    val currentDevice = applyReadback(device, runtime.readback)
    if (currentDevice != device) {
      updateSharedDevice(currentDevice)
    }
    val actionSupport = ActionSupportCatalog.deviceActionSupport(currentDevice, statusSnapshot.copy(deviceCapabilities = capabilities))
    return DeviceCapabilitySnapshot(
      device = currentDevice,
      backend = backend,
      capabilities = capabilities,
      actionSupport = actionSupport,
      message = runtime.message
    )
  }

  private suspend fun refreshDeviceStatus(api: RouterApi? = buildRouterApi()): DeviceControlStatusSnapshot {
    val resolvedApi = api ?: return DeviceControlStatusSnapshot(
      status = ServiceStatus.NO_DATA,
      message = "Router credentials are not configured.",
      backend = DeviceControlBackendState(
        detected = false,
        authenticated = false,
        readable = false,
        writable = false,
        message = "Router credentials are not configured."
      )
    ).also { _status.value = it }
    val runtime = resolvedApi.getRuntimeCapabilities()
    val detected = resolvedApi.detect(connectionInfoFromStore())
    val capabilityStates = linkedMapOf(
      AppActionId.DEVICE_BLOCK to deviceCapabilityState(runtime, AppActionId.DEVICE_BLOCK, RouterDeviceCapability.BLOCK, DeviceControlAction.BLOCK.label),
      AppActionId.DEVICE_UNBLOCK to deviceCapabilityState(runtime, AppActionId.DEVICE_UNBLOCK, RouterDeviceCapability.BLOCK, DeviceControlAction.UNBLOCK.label),
      AppActionId.DEVICE_PAUSE to deviceCapabilityState(runtime, AppActionId.DEVICE_PAUSE, RouterDeviceCapability.PAUSE, DeviceControlAction.PAUSE.label),
      AppActionId.DEVICE_RESUME to deviceCapabilityState(runtime, AppActionId.DEVICE_RESUME, RouterDeviceCapability.PAUSE, DeviceControlAction.RESUME.label),
      AppActionId.DEVICE_RENAME to deviceCapabilityState(runtime, AppActionId.DEVICE_RENAME, RouterDeviceCapability.RENAME, DeviceControlAction.RENAME.label),
      AppActionId.DEVICE_PRIORITIZE to deviceCapabilityState(runtime, AppActionId.DEVICE_PRIORITIZE, RouterDeviceCapability.PRIORITIZE, DeviceControlAction.PRIORITIZE.label)
    )
    val snapshot = DeviceControlStatusSnapshot(
      status = when {
        runtime.authenticated && runtime.readable -> ServiceStatus.OK
        runtime.detected -> ServiceStatus.NO_DATA
        else -> ServiceStatus.ERROR
      },
      message = runtime.message,
      backend = DeviceControlBackendState(
        detected = runtime.detected,
        authenticated = runtime.authenticated,
        readable = runtime.readable,
        writable = capabilityStates.values.any { it.writable },
        vendorName = detected.vendorName,
        modelName = detected.modelName,
        firmwareVersion = detected.firmwareVersion,
        adapterId = runtime.adapterId,
        message = runtime.message
      ),
      deviceCapabilities = capabilityStates
    )
    _status.value = snapshot
    return snapshot
  }

  private suspend fun refreshDeviceState(deviceId: String) {
    val device = sharedDevices.value.firstOrNull { it.id == deviceId } ?: return
    val capability = getDeviceCapabilityState(device)
    updateSharedDevice(capability.device)
  }

  private fun buildRouterApi(): RouterApi? {
    val creds = credentialsStore.read()
    val profile = credentialsStore.readProfile()
    val ip = (profile.routerIp ?: creds.host).trim()
    if (ip.isBlank()) return null
    return RouterApiHttp(connectionInfoFromStore())
  }

  private fun connectionInfoFromStore(): RouterConnectionInfo {
    val creds = credentialsStore.read()
    val profile = credentialsStore.readProfile()
    return RouterConnectionInfo(
      routerIp = profile.routerIp ?: creds.host,
      adminUrl = profile.adminUrl ?: creds.adminUrl,
      username = creds.username.ifBlank { null },
      password = creds.token.ifBlank { null },
      preferredAuthType = profile.authType
    )
  }

  private fun routerDevice(device: Device): RouterManagedDevice {
    return RouterManagedDevice(
      deviceId = device.id,
      macAddress = device.macAddress ?: device.mac,
      ipAddress = device.ip,
      hostName = device.hostName ?: device.hostname,
      displayName = device.nickname ?: device.name
    )
  }

  private fun deviceCapabilityState(
    runtime: RouterRuntimeCapabilities,
    actionId: String,
    capability: RouterDeviceCapability,
    label: String
  ): DeviceCapabilityState {
    val asusDeviceSupport = runtime.adapterId == "asuswrt-app" && runtime.authenticated && runtime.readable
    val authUnavailable = runtime.detected && !runtime.authenticated
    val reason = when (capability) {
      RouterDeviceCapability.BLOCK -> if (authUnavailable) {
        "Router detected, but authenticated device control is unavailable until credentials are validated."
      } else if (asusDeviceSupport) {
        "Per-device internet blocking is available for supported ASUSWRT devices with a stable MAC address."
      } else {
        "Per-device internet blocking is unsupported on the current backend/router."
      }
      RouterDeviceCapability.PAUSE -> if (authUnavailable) {
        "Router detected, but authenticated device control is unavailable until credentials are validated."
      } else {
        "Pause/resume is unsupported on the current backend/router."
      }
      RouterDeviceCapability.RENAME -> if (authUnavailable) {
        "Router detected, but authenticated device control is unavailable until credentials are validated."
      } else if (asusDeviceSupport) {
        "Device rename is available for supported ASUSWRT devices with a stable MAC address."
      } else {
        "Device rename is unsupported on the current backend/router."
      }
      RouterDeviceCapability.PRIORITIZE -> if (authUnavailable) {
        "Router detected, but authenticated device control is unavailable until credentials are validated."
      } else {
        "Device prioritization is unsupported on the current backend/router."
      }
    }
    val writable = when (capability) {
      RouterDeviceCapability.BLOCK, RouterDeviceCapability.RENAME -> asusDeviceSupport
      RouterDeviceCapability.PAUSE, RouterDeviceCapability.PRIORITIZE -> false
    }
    val readable = when (capability) {
      RouterDeviceCapability.BLOCK, RouterDeviceCapability.RENAME -> runtime.readable
      RouterDeviceCapability.PAUSE, RouterDeviceCapability.PRIORITIZE -> false
    }
    return DeviceCapabilityState(
      actionId = actionId,
      label = label,
      supported = writable,
      detected = runtime.detected,
      authenticated = runtime.authenticated,
      readable = readable,
      writable = writable,
      status = when {
        writable -> ServiceStatus.OK
        authUnavailable -> ServiceStatus.NO_DATA
        else -> ServiceStatus.NOT_SUPPORTED
      },
      reason = reason,
      source = runtime.adapterId
    )
  }

  private fun deviceActionIdFor(capability: RouterDeviceCapability): String {
    return when (capability) {
      RouterDeviceCapability.BLOCK -> AppActionId.DEVICE_BLOCK
      RouterDeviceCapability.PAUSE -> AppActionId.DEVICE_PAUSE
      RouterDeviceCapability.RENAME -> AppActionId.DEVICE_RENAME
      RouterDeviceCapability.PRIORITIZE -> AppActionId.DEVICE_PRIORITIZE
    }
  }

  private fun deviceActionLabel(capability: RouterDeviceCapability): String {
    return when (capability) {
      RouterDeviceCapability.BLOCK -> DeviceControlAction.BLOCK.label
      RouterDeviceCapability.PAUSE -> DeviceControlAction.PAUSE.label
      RouterDeviceCapability.RENAME -> DeviceControlAction.RENAME.label
      RouterDeviceCapability.PRIORITIZE -> DeviceControlAction.PRIORITIZE.label
    }
  }

  private fun applyReadback(device: Device, readback: RouterDeviceReadback): Device {
    val renamed = readback.nickname?.takeIf { it.isNotBlank() }
    return device.copy(
      name = renamed ?: device.name,
      nickname = renamed ?: device.nickname,
      isBlocked = readback.blocked ?: device.isBlocked,
      isPaused = readback.paused ?: device.isPaused
    )
  }

  private fun updateSharedDevice(updated: Device) {
    sharedDevices.value = sharedDevices.value.map { device ->
      if (device.id == updated.id) updated else device
    }
  }

  private fun mapDeviceActionResult(
    result: RouterActionResult,
    action: DeviceControlAction,
    okCode: String,
    device: Device
  ): ActionResult {
    return when (result.status) {
      RouterActionStatus.OK -> ActionResult(
        ok = true,
        status = ServiceStatus.OK,
        code = okCode,
        message = result.message,
        details = mapOf("device" to device.name.ifBlank { device.ip })
      )
      RouterActionStatus.NOT_SUPPORTED -> deviceUnsupported(action, device, result.message)
      RouterActionStatus.ERROR -> ActionResult(
        ok = false,
        status = ServiceStatus.ERROR,
        code = result.errorCode ?: "DEVICE_ACTION_ERROR",
        message = result.message,
        details = mapOf("device" to device.name.ifBlank { device.ip }),
        errorReason = result.message
      )
    }
  }

  private fun deviceUnsupported(action: DeviceControlAction, device: Device, reason: String): ActionResult {
    return ActionResult(
      ok = false,
      status = ServiceStatus.NOT_SUPPORTED,
      code = "NOT_SUPPORTED",
      message = "${action.label} is unsupported on the current backend/router.",
      details = mapOf("device" to device.name.ifBlank { device.ip }),
      errorReason = reason
    )
  }

  private fun deviceUnavailable(action: DeviceControlAction, device: Device, reason: String): ActionResult {
    return ActionResult(
      ok = false,
      status = ServiceStatus.NO_DATA,
      code = "DEVICE_CONTROL_UNAVAILABLE",
      message = "${action.label} is unavailable.",
      details = mapOf("device" to device.name.ifBlank { device.ip }),
      errorReason = reason
    )
  }

  private fun deviceSupportResult(
    action: DeviceControlAction,
    device: Device,
    backend: DeviceControlBackendState,
    reason: String
  ): ActionResult {
    return if (backend.detected && !backend.authenticated) {
      deviceUnavailable(action, device, reason)
    } else {
      deviceUnsupported(action, device, reason)
    }
  }

  private fun DeviceCapabilitySnapshot.toSupport(): DeviceActionSupport {
    return DeviceActionSupport(
      canBlock = actionSupport[AppActionId.DEVICE_BLOCK]?.supported == true,
      canUnblock = actionSupport[AppActionId.DEVICE_UNBLOCK]?.supported == true,
      canPause = actionSupport[AppActionId.DEVICE_PAUSE]?.supported == true,
      canResume = actionSupport[AppActionId.DEVICE_RESUME]?.supported == true,
      canRename = actionSupport[AppActionId.DEVICE_RENAME]?.supported == true,
      canPrioritize = actionSupport[AppActionId.DEVICE_PRIORITIZE]?.supported == true
    )
  }

  private data class DeviceCapabilitySnapshot(
    val device: Device,
    val backend: DeviceControlBackendState,
    val capabilities: Map<String, DeviceCapabilityState>,
    val actionSupport: Map<String, ActionSupportState>,
    val message: String
  )
}

private class HybridMapService(
  private val sharedDevices: MutableStateFlow<List<Device>>,
  private val selectedNodeId: MutableStateFlow<String?>
) : MapService {
  private val _nodes = MutableStateFlow<List<MapNode>>(emptyList())
  override val nodes: StateFlow<List<MapNode>> = _nodes.asStateFlow()

  override suspend fun refresh() {
    _nodes.value = sharedDevices.value.map { d ->
      MapNode(
        id = d.id,
        label = d.name,
        strength = toStrength(d),
        ip = d.ip,
        selected = selectedNodeId.value == d.id
      )
    }
  }
}

private class HybridTopologyService(
  private val sharedDevices: MutableStateFlow<List<Device>>,
  private val selectedNodeId: MutableStateFlow<String?>
) : MapTopologyService {
  private val _layout = MutableStateFlow(MapLayoutMode.TOPOLOGY)
  override val layoutMode: StateFlow<MapLayoutMode> = _layout.asStateFlow()
  private val _nodes = MutableStateFlow<List<MapNode>>(emptyList())
  override val nodes: StateFlow<List<MapNode>> = _nodes.asStateFlow()
  private val _links = MutableStateFlow<List<MapLink>>(emptyList())
  override val links: StateFlow<List<MapLink>> = _links.asStateFlow()

  override suspend fun refreshTopology() {
    val devices = sharedDevices.value
    _nodes.value = devices.map {
      MapNode(
        id = it.id,
        label = it.name,
        strength = toStrength(it),
        ip = it.ip,
        selected = selectedNodeId.value == it.id
      )
    }

    val gateway = devices.firstOrNull { it.isGateway || it.deviceType.equals("router", true) || it.name.equals("GATEWAY", true) }
      ?: devices.firstOrNull()
    _links.value = if (gateway == null) {
      emptyList()
    } else {
      devices
        .filter { it.id != gateway.id }
        .map { MapLink(gateway.id, it.id, toStrength(it)) }
    }
  }

  override suspend fun selectNode(id: String?) {
    selectedNodeId.value = id
    refreshTopology()
  }
}

private class HybridAnalytics(
  context: Context,
  private val speedtestService: SpeedtestService,
  private val devicesFlow: StateFlow<List<Device>>,
  private val scanService: ScanService
) : AnalyticsService {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences("nerf_analytics_store", Context.MODE_PRIVATE)

  private val _events = MutableStateFlow<List<String>>(listOf("BOOT: analytics online"))
  override val events: StateFlow<List<String>> = _events.asStateFlow()
  private val _snapshot = MutableStateFlow(
    AnalyticsSnapshot(
      downMbps = null,
      upMbps = null,
      latencyMs = null,
      jitterMs = null,
      packetLossPct = null,
      deviceCount = 0,
      reachableCount = 0,
      avgRttMs = null,
      medianRttMs = null,
      scanDurationMs = null,
      lastScanEpochMs = null,
      status = ServiceStatus.NO_DATA,
      message = "No analytics data yet. Run a scan or speedtest."
    )
  )
  override val snapshot: StateFlow<AnalyticsSnapshot> = _snapshot.asStateFlow()

  private val keySamples = "analytics_samples"
  private val keyReports = "analytics_reports"
  private val keyDeviceStats = "analytics_device_stats"
  private val keyLastScanDevices = "analytics_last_scan_devices"
  private val keyLastExportPath = "analytics_last_export_path"
  private val maxSamples = 200
  private val maxReports = 60
  private val maxDeviceStats = 400
  private val retentionMs = 7L * 24L * 60L * 60L * 1000L

  private val scanStartedAt = MutableStateFlow<Long?>(null)
  private var samples = mutableListOf<AnalyticsPoint>()
  private var reports = mutableListOf<ScanReport>()
  private var deviceStats = linkedMapOf<String, DeviceHistory>()
  private var lastScanDevices = linkedMapOf<String, DeviceSnapshot>()
  private var lastSpeedtestSampleAt = 0L

  init {
    loadPersisted()
    scope.launch {
      scanService.events.collect { event ->
        when (event) {
          is ScanEvent.ScanStarted -> scanStartedAt.value = event.startedAtEpochMs
          is ScanEvent.ScanDone -> {
            onScanCompleted(event)
          }
          else -> Unit
        }
      }
    }

    scope.launch {
      speedtestService.latestResult.collect { result ->
        if (result == null) return@collect
        if (result.phase != SpeedtestPhase.DONE && result.phase != SpeedtestPhase.ERROR) return@collect
        if (result.finishedAt <= lastSpeedtestSampleAt) return@collect
        lastSpeedtestSampleAt = result.finishedAt
        addSpeedtestSample(result)
      }
    }

    scope.launch {
      while (true) {
        recomputeSnapshot()
        delay(1000)
      }
    }
  }

  override suspend fun refresh() {
    recomputeSnapshot()
    val payload = buildPayloadJson()
    val trend = computeTrendSummary(samples)
    val warnings = reports.takeLast(8).flatMap { it.warnings }.distinct().take(6)
    val healthRows = computeHealthRows(deviceStats).take(10)
    val latestReport = reports.lastOrNull()

    _events.value = buildList {
      add("STATUS=${_snapshot.value.status}")
      add("SAMPLES=${samples.size} REPORTS=${reports.size}")
      add("TREND_LATENCY=${trend.latencyDirection}:${"%.2f".format(trend.latencySlope)}")
      add("TREND_ONLINE=${trend.onlineDirection}:${"%.2f".format(trend.onlineSlope)}")
      add("TREND_THROUGHPUT=${trend.throughputDirection}:${"%.2f".format(trend.throughputSlope)}")
      if (healthRows.isNotEmpty()) {
        add("HEALTH_WORST=${healthRows.first().name}(${healthRows.first().score})")
      } else {
        add("HEALTH_WORST=N/A")
      }
      if (latestReport != null) {
        add(
          "REPORT=${latestReport.timestamp}|found=${latestReport.devicesFound}|online=${latestReport.devicesOnline}|new=${latestReport.newDevices.size}|offline=${latestReport.offlineDevices.size}"
        )
      } else {
        add("REPORT=N/A")
      }
      if (warnings.isNotEmpty()) add("WARNINGS=${warnings.joinToString(" | ")}")
      val exportPath = prefs.getString(keyLastExportPath, null)
      if (!exportPath.isNullOrBlank()) add("LAST_EXPORT=$exportPath")
      add("PAYLOAD_JSON=$payload")
    }
  }

  override suspend fun exportJson(): ActionResult {
    return runCatching {
      val path = exportAnalyticsJsonToFile()
      ActionResult(
        ok = true,
        status = ServiceStatus.OK,
        code = "ANALYTICS_EXPORT_OK",
        message = "Analytics export created.",
        details = mapOf("path" to path)
      )
    }.getOrElse { error ->
      ActionResult(
        ok = false,
        status = ServiceStatus.ERROR,
        code = "ANALYTICS_EXPORT_FAILED",
        message = "Analytics export failed.",
        errorReason = error.message ?: "unknown error"
      )
    }
  }

  fun getAnalyticsPayloadJson(): String = buildPayloadJson()

  fun exportAnalyticsJsonToFile(): String {
    val payload = buildPayloadJson()
    if (samples.isEmpty() && reports.isEmpty()) {
      throw IllegalStateException("No analytics data to export yet.")
    }
    val dir = File(appContext.filesDir, "analytics_exports")
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "analytics-${System.currentTimeMillis()}.json")
    file.writeText(payload)
    prefs.edit().putString(keyLastExportPath, file.absolutePath).apply()
    return file.absolutePath
  }

  private suspend fun onScanCompleted(event: ScanEvent.ScanDone) {
    val devices = devicesFlow.value
    val reachable = devices.filter { it.online }
    val latencies = reachable.mapNotNull { it.latencyMs }.sorted()
    val medianLatency = latencies.median()
    val p90Latency = latencies.p90()
    val now = event.completedAtEpochMs
    val scanDuration = event.metadata?.durationMs?.toInt()
      ?: run {
        val started = scanStartedAt.value
        if (started == null) null else ((now - started).coerceAtLeast(0L)).toInt()
      }
    val gateway = devices.firstOrNull { it.isGateway }
    val sample = AnalyticsPoint(
      timestamp = now,
      source = "SCAN",
      deviceCountTotal = devices.size,
      deviceCountOnline = reachable.size,
      medianLatencyOnline = medianLatency,
      p90LatencyOnline = p90Latency,
      scanDurationMs = scanDuration,
      gatewayPresent = gateway != null,
      wifiRssi = gateway?.rssiDbm ?: event.metadata?.wifiRssi,
      pingMs = speedtestService.latestResult.value?.pingMs,
      jitterMs = speedtestService.latestResult.value?.jitterMs,
      packetLossPct = speedtestService.latestResult.value?.packetLossPct,
      downloadMbps = speedtestService.latestResult.value?.downloadMbps,
      uploadMbps = speedtestService.latestResult.value?.uploadMbps
    )

    val report = generateReport(now, scanDuration, devices, latencies, medianLatency)
    updateDeviceHistory(devices)
    lastScanDevices = currentScanSnapshot(devices)
    samples += sample
    reports += report
    pruneAndPersist()
    recomputeSnapshot()
  }

  private suspend fun addSpeedtestSample(result: SpeedtestResult) {
    val devices = devicesFlow.value
    val reachable = devices.count { it.online }
    val latencies = devices.filter { it.online }.mapNotNull { it.latencyMs }.sorted()
    val sample = AnalyticsPoint(
      timestamp = result.finishedAt,
      source = "SPEEDTEST",
      deviceCountTotal = devices.size,
      deviceCountOnline = reachable,
      medianLatencyOnline = latencies.median(),
      p90LatencyOnline = latencies.p90(),
      scanDurationMs = null,
      gatewayPresent = devices.any { it.isGateway },
      wifiRssi = devices.firstOrNull { it.isGateway }?.rssiDbm,
      pingMs = result.pingMs,
      jitterMs = result.jitterMs,
      packetLossPct = result.packetLossPct,
      downloadMbps = result.downloadMbps,
      uploadMbps = result.uploadMbps
    )
    samples += sample
    pruneAndPersist()
    recomputeSnapshot()
  }

  private fun generateReport(
    timestamp: Long,
    durationMs: Int?,
    devices: List<Device>,
    latencies: List<Int>,
    medianLatency: Double?
  ): ScanReport {
    val current = currentScanSnapshot(devices)
    val previous = lastScanDevices
    val newDevices = current.keys.filter { !previous.containsKey(it) }
    val offlineDevices = previous.keys.filter { !current.containsKey(it) || current[it]?.online != true }
    val slow = devices
      .filter { it.online && it.latencyMs != null }
      .sortedByDescending { it.latencyMs ?: 0 }
      .take(5)
      .map { "${it.name.ifBlank { it.ip }}(${it.latencyMs}ms)" }

    val warnings = mutableListOf<String>()
    if (medianLatency != null && medianLatency > 120.0) warnings += "High latency median (${medianLatency.toInt()}ms)"
    if (devices.isNotEmpty() && devices.count { !it.online } >= maxOf(3, (devices.size * 0.4f).toInt())) warnings += "Many devices offline"
    if (latencies.isEmpty()) warnings += "Latency source missing for online devices"

    return ScanReport(
      timestamp = timestamp,
      durationMs = durationMs,
      devicesFound = devices.size,
      devicesOnline = devices.count { it.online },
      newDevices = newDevices,
      offlineDevices = offlineDevices,
      slowDevices = slow,
      warnings = warnings
    )
  }

  private fun currentScanSnapshot(devices: List<Device>): LinkedHashMap<String, DeviceSnapshot> {
    val map = linkedMapOf<String, DeviceSnapshot>()
    devices.forEach { d ->
      val key = deviceKey(d)
      map[key] = DeviceSnapshot(
        key = key,
        name = d.name.ifBlank { d.ip },
        ip = d.ip,
        mac = d.mac.ifBlank { null },
        online = d.online,
        latencyMs = d.latencyMs
      )
    }
    return map
  }

  private fun updateDeviceHistory(devices: List<Device>) {
    val now = System.currentTimeMillis()
    val presentKeys = devices.map { deviceKey(it) }.toSet()

    val existingKeys = deviceStats.keys.toList()
    existingKeys.forEach { key ->
      val old = deviceStats[key] ?: return@forEach
      if (!presentKeys.contains(key)) {
        deviceStats[key] = old.copy(
          scansSeen = old.scansSeen + 1
        )
      }
    }

    devices.forEach { d ->
      val key = deviceKey(d)
      val prev = deviceStats[key]
      val latencies = (prev?.latencySamples.orEmpty() + listOfNotNull(d.latencyMs)).takeLast(24)
      val next = DeviceHistory(
        key = key,
        name = d.name.ifBlank { d.ip },
        ip = d.ip,
        mac = d.mac.ifBlank { null },
        scansSeen = (prev?.scansSeen ?: 0) + 1,
        onlineCount = (prev?.onlineCount ?: 0) + if (d.online) 1 else 0,
        latencySamples = latencies,
        lastSeen = d.lastSeen,
        lastUpdated = now
      )
      deviceStats[key] = next
    }
  }

  private suspend fun recomputeSnapshot() {
    val st = speedtestService.ui.value
    val devices = devicesFlow.value
    val reachable = devices.filter { it.online }
    val rtts = reachable.mapNotNull { it.latencyMs }.sorted()
    val avgRtt = if (rtts.isEmpty()) null else rtts.average()
    val medianRtt = if (rtts.isEmpty()) null else rtts[rtts.size / 2].toDouble()
    val lastScan = samples.lastOrNull { it.source == "SCAN" }
    val status = if (samples.isEmpty() && devices.isEmpty()) ServiceStatus.NO_DATA else ServiceStatus.OK
    val missing = mutableListOf<String>()
    if (samples.none { it.source == "SCAN" }) missing += "scan source missing"
    if (samples.none { it.source == "SPEEDTEST" }) missing += "speedtest source missing"
    if (devices.isEmpty()) missing += "device state missing"
    val message = if (status == ServiceStatus.NO_DATA) {
      "No analytics data yet. Run a scan or speedtest."
    } else if (missing.isNotEmpty()) {
      "Partial analytics: ${missing.joinToString(", ")}"
    } else {
      null
    }

    _snapshot.value = AnalyticsSnapshot(
      downMbps = st.downMbps ?: samples.lastOrNull { it.downloadMbps != null }?.downloadMbps,
      upMbps = st.upMbps ?: samples.lastOrNull { it.uploadMbps != null }?.uploadMbps,
      latencyMs = st.latencyMs?.toDouble() ?: medianRtt,
      jitterMs = st.jitterMs,
      packetLossPct = st.packetLossPct,
      deviceCount = devices.size,
      reachableCount = reachable.size,
      avgRttMs = avgRtt,
      medianRttMs = medianRtt,
      scanDurationMs = lastScan?.scanDurationMs,
      lastScanEpochMs = lastScan?.timestamp,
      status = status,
      message = message
    )
  }

  private fun buildPayloadJson(): String {
    val trend = computeTrendSummary(samples)
    val health = computeHealthRows(deviceStats)
    val latest = _snapshot.value
    val warnings = reports.takeLast(8).flatMap { it.warnings }.distinct()

    val root = JSONObject()
    val latestObj = JSONObject()
      .put("status", latest.status.name)
      .put("downMbps", latest.downMbps)
      .put("upMbps", latest.upMbps)
      .put("latencyMs", latest.latencyMs)
      .put("jitterMs", latest.jitterMs)
      .put("packetLossPct", latest.packetLossPct)
      .put("deviceCount", latest.deviceCount)
      .put("reachableCount", latest.reachableCount)
      .put("medianRttMs", latest.medianRttMs)
      .put("scanDurationMs", latest.scanDurationMs)
      .put("lastScanEpochMs", latest.lastScanEpochMs)
      .put("message", latest.message)

    val trendObj = JSONObject()
      .put("latencyDirection", trend.latencyDirection)
      .put("latencySlope", trend.latencySlope)
      .put("onlineDirection", trend.onlineDirection)
      .put("onlineSlope", trend.onlineSlope)
      .put("throughputDirection", trend.throughputDirection)
      .put("throughputSlope", trend.throughputSlope)

    val seriesArr = JSONArray()
    samples.forEach { seriesArr.put(it.toJson()) }
    val reportsArr = JSONArray()
    reports.takeLast(30).forEach { reportsArr.put(it.toJson()) }
    val healthArr = JSONArray()
    health.forEach { healthArr.put(it.toJson()) }
    val warningsArr = JSONArray()
    warnings.forEach { warningsArr.put(it) }

    root.put("latest", latestObj)
    root.put("trend", trendObj)
    root.put("series", seriesArr)
    root.put("deviceHealth", healthArr)
    root.put("scanReports", reportsArr)
    root.put("warnings", warningsArr)
    root.put("missingSources", missingSourcesArray())
    return root.toString()
  }

  private fun missingSourcesArray(): JSONArray {
    val arr = JSONArray()
    if (samples.none { it.source == "SCAN" }) arr.put("scan source missing")
    if (samples.none { it.source == "SPEEDTEST" }) arr.put("speedtest source missing")
    if (deviceStats.isEmpty()) arr.put("device history missing")
    return arr
  }

  private fun computeTrendSummary(series: List<AnalyticsPoint>): TrendSummary {
    val lastN = series.takeLast(12)
    val latencySlope = slope(lastN.mapNotNull { it.medianLatencyOnline })
    val onlineSlope = slope(lastN.map { it.deviceCountOnline.toDouble() })
    val throughputSlope = slope(lastN.mapNotNull { it.downloadMbps })

    return TrendSummary(
      latencyDirection = when {
        latencySlope < -1.2 -> "IMPROVING"
        latencySlope > 1.2 -> "WORSENING"
        else -> "STABLE"
      },
      latencySlope = latencySlope,
      onlineDirection = when {
        onlineSlope > 0.25 -> "IMPROVING"
        onlineSlope < -0.25 -> "WORSENING"
        else -> "STABLE"
      },
      onlineSlope = onlineSlope,
      throughputDirection = when {
        throughputSlope > 0.7 -> "IMPROVING"
        throughputSlope < -0.7 -> "WORSENING"
        else -> "STABLE"
      },
      throughputSlope = throughputSlope
    )
  }

  private fun computeHealthRows(history: Map<String, DeviceHistory>): List<DeviceHealthRow> {
    return history.values.map { h ->
      val onlineRatio = if (h.scansSeen <= 0) 0.0 else h.onlineCount.toDouble() / h.scansSeen.toDouble()
      val medianLatency = h.latencySamples.sorted().median()
      val recencyMin = ((System.currentTimeMillis() - h.lastSeen).coerceAtLeast(0L) / 60_000.0)
      var score = 100.0
      val reasons = mutableListOf<String>()
      if (onlineRatio < 0.7) {
        score -= (0.7 - onlineRatio) * 45.0
        reasons += "Offline frequently"
      }
      if (medianLatency != null && medianLatency > 120.0) {
        score -= minOf(30.0, (medianLatency - 120.0) * 0.12)
        reasons += "High latency"
      }
      if (recencyMin > 90.0) {
        score -= minOf(25.0, (recencyMin - 90.0) * 0.08)
        reasons += "Not seen recently"
      }
      DeviceHealthRow(
        key = h.key,
        name = h.name,
        ip = h.ip,
        macMasked = h.mac?.let(::maskMac),
        score = score.coerceIn(0.0, 100.0).toInt(),
        onlineRatio = onlineRatio,
        medianLatencyMs = medianLatency,
        reasons = reasons
      )
    }.sortedBy { it.score }
  }

  private fun maskMac(mac: String): String {
    val parts = mac.split(":")
    return if (parts.size < 3) mac else "${parts[0]}:${parts[1]}:xx:xx:xx:${parts.last()}"
  }

  private fun slope(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val n = values.size.toDouble()
    val xMean = (n - 1.0) / 2.0
    val yMean = values.average()
    var num = 0.0
    var den = 0.0
    values.forEachIndexed { idx, y ->
      val x = idx.toDouble()
      num += (x - xMean) * (y - yMean)
      den += (x - xMean) * (x - xMean)
    }
    return if (abs(den) < 1e-9) 0.0 else num / den
  }

  private fun List<Int>.median(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    return sorted[sorted.size / 2].toDouble()
  }

  private fun List<Int>.p90(): Int? {
    if (isEmpty()) return null
    val sorted = sorted()
    val idx = ((sorted.size - 1) * 0.90).toInt().coerceIn(0, sorted.lastIndex)
    return sorted[idx]
  }

  private fun deviceKey(device: Device): String {
    val mac = device.mac.trim()
    return if (mac.isNotBlank()) mac.lowercase(Locale.US) else "ip:${device.ip}"
  }

  private fun pruneAndPersist() {
    val cutoff = System.currentTimeMillis() - retentionMs
    samples = samples.filter { it.timestamp >= cutoff }.takeLast(maxSamples).toMutableList()
    reports = reports.filter { it.timestamp >= cutoff }.takeLast(maxReports).toMutableList()
    if (deviceStats.size > maxDeviceStats) {
      val trimmed = deviceStats.values.sortedByDescending { it.lastUpdated }.take(maxDeviceStats)
      deviceStats = linkedMapOf<String, DeviceHistory>().apply { trimmed.forEach { put(it.key, it) } }
    }
    persistAll()
  }

  private fun persistAll() {
    val sampleArr = JSONArray()
    samples.forEach { sampleArr.put(it.toJson()) }
    val reportArr = JSONArray()
    reports.forEach { reportArr.put(it.toJson()) }
    val deviceObj = JSONObject()
    deviceStats.forEach { (key, value) -> deviceObj.put(key, value.toJson()) }
    val lastScanObj = JSONObject()
    lastScanDevices.forEach { (key, value) -> lastScanObj.put(key, value.toJson()) }
    prefs.edit()
      .putString(keySamples, sampleArr.toString())
      .putString(keyReports, reportArr.toString())
      .putString(keyDeviceStats, deviceObj.toString())
      .putString(keyLastScanDevices, lastScanObj.toString())
      .apply()
  }

  private fun loadPersisted() {
    samples = parsePoints(prefs.getString(keySamples, null)).toMutableList()
    reports = parseReports(prefs.getString(keyReports, null)).toMutableList()
    deviceStats = parseDeviceHistory(prefs.getString(keyDeviceStats, null))
    lastScanDevices = parseScanDevices(prefs.getString(keyLastScanDevices, null))
  }

  private fun parsePoints(raw: String?): List<AnalyticsPoint> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      (0 until arr.length()).mapNotNull { idx ->
        arr.optJSONObject(idx)?.let { AnalyticsPoint.fromJson(it) }
      }
    }.getOrDefault(emptyList())
  }

  private fun parseReports(raw: String?): List<ScanReport> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      (0 until arr.length()).mapNotNull { idx ->
        arr.optJSONObject(idx)?.let { ScanReport.fromJson(it) }
      }
    }.getOrDefault(emptyList())
  }

  private fun parseDeviceHistory(raw: String?): LinkedHashMap<String, DeviceHistory> {
    if (raw.isNullOrBlank()) return linkedMapOf()
    return runCatching {
      val obj = JSONObject(raw)
      val keys = obj.keys()
      val out = linkedMapOf<String, DeviceHistory>()
      while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.optJSONObject(key) ?: continue
        out[key] = DeviceHistory.fromJson(value)
      }
      out
    }.getOrDefault(linkedMapOf())
  }

  private fun parseScanDevices(raw: String?): LinkedHashMap<String, DeviceSnapshot> {
    if (raw.isNullOrBlank()) return linkedMapOf()
    return runCatching {
      val obj = JSONObject(raw)
      val keys = obj.keys()
      val out = linkedMapOf<String, DeviceSnapshot>()
      while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.optJSONObject(key) ?: continue
        out[key] = DeviceSnapshot.fromJson(value)
      }
      out
    }.getOrDefault(linkedMapOf())
  }

  private data class AnalyticsPoint(
    val timestamp: Long,
    val source: String,
    val deviceCountTotal: Int,
    val deviceCountOnline: Int,
    val medianLatencyOnline: Double?,
    val p90LatencyOnline: Int?,
    val scanDurationMs: Int?,
    val gatewayPresent: Boolean,
    val wifiRssi: Int?,
    val pingMs: Double?,
    val jitterMs: Double?,
    val packetLossPct: Double?,
    val downloadMbps: Double?,
    val uploadMbps: Double?
  ) {
    fun toJson(): JSONObject = JSONObject()
      .put("timestamp", timestamp)
      .put("source", source)
      .put("deviceCountTotal", deviceCountTotal)
      .put("deviceCountOnline", deviceCountOnline)
      .put("medianLatencyOnline", medianLatencyOnline)
      .put("p90LatencyOnline", p90LatencyOnline)
      .put("scanDurationMs", scanDurationMs)
      .put("gatewayPresent", gatewayPresent)
      .put("wifiRssi", wifiRssi)
      .put("pingMs", pingMs)
      .put("jitterMs", jitterMs)
      .put("packetLossPct", packetLossPct)
      .put("downloadMbps", downloadMbps)
      .put("uploadMbps", uploadMbps)

    companion object {
      fun fromJson(obj: JSONObject): AnalyticsPoint = AnalyticsPoint(
        timestamp = obj.optLong("timestamp"),
        source = obj.optString("source"),
        deviceCountTotal = obj.optInt("deviceCountTotal"),
        deviceCountOnline = obj.optInt("deviceCountOnline"),
        medianLatencyOnline = obj.optDoubleOrNull("medianLatencyOnline"),
        p90LatencyOnline = obj.optIntOrNull("p90LatencyOnline"),
        scanDurationMs = obj.optIntOrNull("scanDurationMs"),
        gatewayPresent = obj.optBoolean("gatewayPresent"),
        wifiRssi = obj.optIntOrNull("wifiRssi"),
        pingMs = obj.optDoubleOrNull("pingMs"),
        jitterMs = obj.optDoubleOrNull("jitterMs"),
        packetLossPct = obj.optDoubleOrNull("packetLossPct"),
        downloadMbps = obj.optDoubleOrNull("downloadMbps"),
        uploadMbps = obj.optDoubleOrNull("uploadMbps")
      )
    }
  }

  private data class ScanReport(
    val timestamp: Long,
    val durationMs: Int?,
    val devicesFound: Int,
    val devicesOnline: Int,
    val newDevices: List<String>,
    val offlineDevices: List<String>,
    val slowDevices: List<String>,
    val warnings: List<String>
  ) {
    fun toJson(): JSONObject = JSONObject()
      .put("timestamp", timestamp)
      .put("durationMs", durationMs)
      .put("devicesFound", devicesFound)
      .put("devicesOnline", devicesOnline)
      .put("newDevices", JSONArray(newDevices))
      .put("offlineDevices", JSONArray(offlineDevices))
      .put("slowDevices", JSONArray(slowDevices))
      .put("warnings", JSONArray(warnings))

    companion object {
      fun fromJson(obj: JSONObject): ScanReport = ScanReport(
        timestamp = obj.optLong("timestamp"),
        durationMs = obj.optIntOrNull("durationMs"),
        devicesFound = obj.optInt("devicesFound"),
        devicesOnline = obj.optInt("devicesOnline"),
        newDevices = obj.optStringList("newDevices"),
        offlineDevices = obj.optStringList("offlineDevices"),
        slowDevices = obj.optStringList("slowDevices"),
        warnings = obj.optStringList("warnings")
      )
    }
  }

  private data class DeviceHistory(
    val key: String,
    val name: String,
    val ip: String,
    val mac: String?,
    val scansSeen: Int,
    val onlineCount: Int,
    val latencySamples: List<Int>,
    val lastSeen: Long,
    val lastUpdated: Long
  ) {
    fun toJson(): JSONObject = JSONObject()
      .put("key", key)
      .put("name", name)
      .put("ip", ip)
      .put("mac", mac)
      .put("scansSeen", scansSeen)
      .put("onlineCount", onlineCount)
      .put("latencySamples", JSONArray(latencySamples))
      .put("lastSeen", lastSeen)
      .put("lastUpdated", lastUpdated)

    companion object {
      fun fromJson(obj: JSONObject): DeviceHistory = DeviceHistory(
        key = obj.optString("key"),
        name = obj.optString("name"),
        ip = obj.optString("ip"),
        mac = obj.optString("mac").takeIf { it.isNotBlank() },
        scansSeen = obj.optInt("scansSeen"),
        onlineCount = obj.optInt("onlineCount"),
        latencySamples = obj.optIntList("latencySamples"),
        lastSeen = obj.optLong("lastSeen"),
        lastUpdated = obj.optLong("lastUpdated")
      )
    }
  }

  private data class DeviceSnapshot(
    val key: String,
    val name: String,
    val ip: String,
    val mac: String?,
    val online: Boolean,
    val latencyMs: Int?
  ) {
    fun toJson(): JSONObject = JSONObject()
      .put("key", key)
      .put("name", name)
      .put("ip", ip)
      .put("mac", mac)
      .put("online", online)
      .put("latencyMs", latencyMs)

    companion object {
      fun fromJson(obj: JSONObject): DeviceSnapshot = DeviceSnapshot(
        key = obj.optString("key"),
        name = obj.optString("name"),
        ip = obj.optString("ip"),
        mac = obj.optString("mac").takeIf { it.isNotBlank() },
        online = obj.optBoolean("online"),
        latencyMs = obj.optIntOrNull("latencyMs")
      )
    }
  }

  private data class DeviceHealthRow(
    val key: String,
    val name: String,
    val ip: String,
    val macMasked: String?,
    val score: Int,
    val onlineRatio: Double,
    val medianLatencyMs: Double?,
    val reasons: List<String>
  ) {
    fun toJson(): JSONObject = JSONObject()
      .put("key", key)
      .put("name", name)
      .put("ip", ip)
      .put("macMasked", macMasked)
      .put("score", score)
      .put("onlineRatio", onlineRatio)
      .put("medianLatencyMs", medianLatencyMs)
      .put("reasons", JSONArray(reasons))
  }

  private data class TrendSummary(
    val latencyDirection: String,
    val latencySlope: Double,
    val onlineDirection: String,
    val onlineSlope: Double,
    val throughputDirection: String,
    val throughputSlope: Double
  )

  private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return optDouble(name)
  }

  private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
  }

  private fun JSONObject.optStringList(name: String): List<String> {
    val arr = optJSONArray(name) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
  }

  private fun JSONObject.optIntList(name: String): List<Int> {
    val arr = optJSONArray(name) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { idx ->
      if (arr.isNull(idx)) null else arr.optInt(idx)
    }
  }
}

private class HybridRouterControl(
  private val context: Context,
  private val credentialsStore: RouterCredentialsStore
) : RouterControlService {

  private val _status = MutableStateFlow(
    RouterStatusSnapshot(
      status = ServiceStatus.IDLE,
      message = "Router status not loaded yet."
    )
  )
  override val status: StateFlow<RouterStatusSnapshot> = _status.asStateFlow()

  override suspend fun info(): RouterInfoResult {
    val snapshot = refreshStatus()
    return RouterInfoResult(
      status = snapshot.status,
      message = snapshot.message,
      gatewayIp = snapshot.gatewayIp,
      dnsServers = snapshot.dnsServers,
      ssid = snapshot.ssid,
      linkSpeedMbps = snapshot.linkSpeedMbps
    )
  }

  override suspend fun refreshStatus(): RouterStatusSnapshot {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (cm == null) {
      return RouterStatusSnapshot(
        status = ServiceStatus.ERROR,
        message = "ConnectivityManager unavailable"
      ).also { _status.value = it }
    }

    val active = cm.activeNetwork
    if (active == null) {
      return RouterStatusSnapshot(
        status = ServiceStatus.NO_DATA,
        message = "No active network"
      ).also { _status.value = it }
    }

    val link = cm.getLinkProperties(active)
    val netCaps = cm.getNetworkCapabilities(active)
    val dns = link?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()

    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val onWifi = netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val ssid = if (onWifi) wifi?.connectionInfo?.ssid?.trim('"') else null
    val linkSpeed = if (onWifi) wifi?.connectionInfo?.linkSpeed else null
    val gatewayIp = link?.routes?.firstOrNull { it.hasGateway() }?.gateway?.hostAddress
    val publicIp = fetchPublicIp()

    val api = buildRouterApi()
    val snapshot = if (api == null) {
      RouterStatusSnapshot(
        status = ServiceStatus.NO_DATA,
        message = "Router credentials not configured.",
        gatewayIp = gatewayIp,
        publicIp = publicIp,
        dnsServers = dns,
        ssid = ssid,
        linkSpeedMbps = linkSpeed,
        accessMode = "READ_ONLY",
        capabilities = listOf(RouterCapability.READ_INFO.name),
        backend = RouterBackendState(
          detected = false,
          authenticated = false,
          readable = false,
          writable = false,
          message = "Router credentials are not configured."
        ),
        guestWifi = RouterFeatureState(message = "Router credentials are required for guest Wi-Fi state."),
        dnsShield = RouterFeatureState(message = "Router credentials are required for DNS Shield state."),
        firewall = RouterFeatureState(message = "Router credentials are required for firewall state."),
        vpn = RouterFeatureState(message = "Router credentials are required for VPN state.")
      )
    } else {
      val detected = api.detect(connectionInfoFromStore())
      val runtime = api.getRuntimeCapabilities()
      val accessMode = if (runtime.writable) "READ_WRITE" else "READ_ONLY"
      val message = buildString {
        append(if (runtime.detected) "Router detected" else "Router not detected")
        detected.vendorName?.let { append(": ").append(it) }
        detected.modelName?.let { append(" ").append(it) }
        append(" | auth=").append(if (runtime.authenticated) "AUTHENTICATED" else detected.detectedAuthType.name)
        append(" | readable=").append(runtime.readable)
        append(" | writable=").append(runtime.writable)
        append(" | adapter=").append(runtime.adapterId ?: "none")
        append(" | ").append(runtime.message)
      }

      RouterStatusSnapshot(
        status = when {
          runtime.authenticated && runtime.readable -> ServiceStatus.OK
          runtime.detected -> ServiceStatus.NO_DATA
          else -> ServiceStatus.ERROR
        },
        message = message,
        gatewayIp = detected.routerIp.ifBlank { gatewayIp },
        publicIp = publicIp,
        dnsServers = dns,
        ssid = ssid,
        linkSpeedMbps = linkSpeed,
        accessMode = accessMode,
        capabilities = runtime.capabilities.map { it.name }.sorted(),
        backend = RouterBackendState(
          detected = runtime.detected,
          authenticated = runtime.authenticated,
          readable = runtime.readable,
          writable = runtime.writable,
          vendorName = detected.vendorName,
          modelName = detected.modelName,
          firmwareVersion = detected.firmwareVersion,
          adapterId = runtime.adapterId,
          message = runtime.message
        ),
        routerCapabilities = capabilityStates(runtime),
        guestWifi = featureState(
          runtime = runtime,
          capability = RouterCapability.GUEST_WIFI_TOGGLE,
          unavailableMessage = "Guest Wi-Fi readback is unavailable for this router model."
        ),
        dnsShield = featureState(
          runtime = runtime,
          capability = RouterCapability.DNS_SHIELD_TOGGLE,
          unavailableMessage = "DNS Shield readback is unavailable for this router model."
        ),
        firewall = featureState(
          runtime = runtime,
          capability = RouterCapability.FIREWALL_TOGGLE,
          unavailableMessage = "Firewall readback is unavailable for this router model."
        ),
        vpn = featureState(
          runtime = runtime,
          capability = RouterCapability.VPN_TOGGLE,
          unavailableMessage = "VPN readback is unavailable for this router model."
        ),
        qosMode = runtime.actionCapabilities[RouterCapability.QOS_CONFIG]
          ?.takeIf { it.readable }
          ?.let { "READABLE_ONLY" }
      )
    }

    val enrichedSnapshot = snapshot.copy(
      actionSupport = ActionSupportCatalog.routerActionSupport(snapshot)
    )
    _status.value = enrichedSnapshot
    return enrichedSnapshot
  }

  override suspend fun setGuestWifiEnabled(enabled: Boolean): ActionResult {
    return performCapabilityAction(
      action = RouterWriteAction.GUEST_WIFI,
      capability = RouterCapability.GUEST_WIFI_TOGGLE,
      code = if (enabled) "GUEST_WIFI_ON" else "GUEST_WIFI_OFF"
    ) { api -> api.setGuestWifiEnabled(enabled) }
  }

  override suspend fun setDnsShieldEnabled(enabled: Boolean): ActionResult {
    return performCapabilityAction(
      action = RouterWriteAction.DNS_SHIELD,
      capability = RouterCapability.DNS_SHIELD_TOGGLE,
      code = if (enabled) "DNS_SHIELD_ON" else "DNS_SHIELD_OFF"
    ) { api -> api.setDnsShieldEnabled(enabled) }
  }

  override suspend fun toggleGuest(): ActionResult {
    val current = refreshStatus().guestWifi.enabled
    return if (current == null) {
      RouterWriteAction.GUEST_WIFI.unsupportedResult("Guest Wi-Fi state is unavailable on the current router/backend.")
    } else {
      setGuestWifiEnabled(!current)
    }
  }

  override suspend fun setQos(mode: QosMode): ActionResult {
    return performCapabilityAction(
      action = RouterWriteAction.QOS,
      capability = RouterCapability.QOS_CONFIG,
      code = "QOS_${mode.name}"
    ) { api ->
      api.setQosConfig(
        QosConfig(
          mode = mode.name
        )
      )
    }
  }

  override suspend fun renewDhcp(): ActionResult {
    return performCapabilityAction(
      action = RouterWriteAction.RENEW_DHCP,
      capability = RouterCapability.DHCP_LEASES_WRITE,
      code = "DHCP_RENEW"
    ) { api -> api.renewDhcp() }
  }

  override suspend fun flushDns(): ActionResult {
    return performCapabilityAction(
      action = RouterWriteAction.FLUSH_DNS,
      capability = RouterCapability.DNS_FLUSH,
      code = "DNS_FLUSH"
    ) { api -> api.flushDns() }
  }

  override suspend fun rebootRouter(): ActionResult {
    return performCapabilityAction(
      action = RouterWriteAction.REBOOT,
      capability = RouterCapability.REBOOT,
      code = "REBOOT"
    ) { api -> api.reboot() }
  }

  override suspend fun setFirewallEnabled(enabled: Boolean): ActionResult {
    return performCapabilityAction(
      action = RouterWriteAction.FIREWALL,
      capability = RouterCapability.FIREWALL_TOGGLE,
      code = if (enabled) "FIREWALL_ON" else "FIREWALL_OFF"
    ) { api -> api.setFirewallEnabled(enabled) }
  }

  override suspend fun setVpnEnabled(enabled: Boolean): ActionResult {
    return performCapabilityAction(
      action = RouterWriteAction.VPN,
      capability = RouterCapability.VPN_TOGGLE,
      code = if (enabled) "VPN_ON" else "VPN_OFF"
    ) { api -> api.setVpnEnabled(enabled) }
  }

  override suspend fun toggleFirewall(): ActionResult {
    val current = refreshStatus().firewall.enabled
    return if (current == null) {
      RouterWriteAction.FIREWALL.unsupportedResult("Firewall state is unavailable on the current router/backend.")
    } else {
      setFirewallEnabled(!current)
    }
  }

  override suspend fun toggleVpn(): ActionResult {
    val current = refreshStatus().vpn.enabled
    return if (current == null) {
      RouterWriteAction.VPN.unsupportedResult("VPN state is unavailable on the current router/backend.")
    } else {
      setVpnEnabled(!current)
    }
  }

  private suspend fun performCapabilityAction(
    action: RouterWriteAction,
    capability: RouterCapability,
    code: String,
    call: suspend (RouterApi) -> RouterActionResult
  ): ActionResult {
    val api = buildRouterApi() ?: return routerNotConfigured(action)
    val runtime = api.getRuntimeCapabilities()
    val capabilityState = capabilityStates(runtime)[actionIdFor(capability)]
    if (capabilityState == null || !capabilityState.writable) {
      return action.unsupportedResult(
        capabilityState?.reason ?: "No verified API endpoint exists for this router action."
      )
    }
    val result = call(api)
    val mapped = mapRouterActionResult(result, action, code)
    refreshStatus()
    return mapped
  }

  private fun mapRouterActionResult(result: RouterActionResult, action: RouterWriteAction, code: String): ActionResult {
    return when (result.status) {
      RouterActionStatus.OK -> ActionResult(
        ok = true,
        status = ServiceStatus.OK,
        code = code,
        message = result.message
      )
      RouterActionStatus.NOT_SUPPORTED -> action.unsupportedResult(result.message)
      RouterActionStatus.ERROR -> ActionResult(
        ok = false,
        status = ServiceStatus.ERROR,
        code = result.errorCode ?: "ROUTER_ACTION_ERROR",
        message = result.message,
        errorReason = result.message
      )
    }
  }

  private fun routerNotConfigured(action: RouterWriteAction): ActionResult {
    return action.unavailableResult("Router credentials are not configured or validated.")
  }

  private fun connectionInfoFromStore(): RouterConnectionInfo {
    val creds = credentialsStore.read()
    val profile = credentialsStore.readProfile()
    return RouterConnectionInfo(
      routerIp = profile.routerIp ?: creds.host,
      adminUrl = profile.adminUrl ?: creds.adminUrl,
      username = creds.username.ifBlank { null },
      password = creds.token.ifBlank { null },
      preferredAuthType = profile.authType
    )
  }

  private fun buildRouterApi(): RouterApi? {
    val creds = credentialsStore.read()
    val profile = credentialsStore.readProfile()
    val ip = (profile.routerIp ?: creds.host).trim()
    if (ip.isBlank()) return null
    return RouterApiHttp(connectionInfoFromStore())
  }

  private fun featureState(
    runtime: RouterRuntimeCapabilities,
    capability: RouterCapability,
    unavailableMessage: String
  ): RouterFeatureState {
    val readback = runtime.featureReadback[capability]
    return if (readback != null) {
      RouterFeatureState(
        supported = readback.supported,
        readable = readback.readable,
        writable = readback.writable,
        enabled = readback.enabled,
        status = when {
          readback.readable -> ServiceStatus.OK
          readback.supported -> ServiceStatus.NO_DATA
          else -> ServiceStatus.NOT_SUPPORTED
        },
        message = readback.message ?: unavailableMessage
      )
    } else {
      RouterFeatureState(
        supported = false,
        readable = false,
        writable = false,
        enabled = null,
        status = ServiceStatus.NOT_SUPPORTED,
        message = "No verified API endpoint for this router capability."
      )
    }
  }

  private fun capabilityStates(runtime: RouterRuntimeCapabilities): Map<String, RouterCapabilityState> {
    return runtime.actionCapabilities.values.associate { capability ->
      actionIdFor(capability.capability) to RouterCapabilityState(
        actionId = actionIdFor(capability.capability),
        label = routerActionLabel(capability.capability),
        supported = capability.supported,
        detected = runtime.detected,
        authenticated = runtime.authenticated,
        readable = capability.readable,
        writable = capability.writable,
        status = when {
          capability.writable -> ServiceStatus.OK
          capability.readable -> ServiceStatus.NO_DATA
          capability.supported -> ServiceStatus.NO_DATA
          else -> ServiceStatus.NOT_SUPPORTED
        },
        reason = capability.reason,
        source = capability.source
      )
    }
  }

  private fun actionIdFor(capability: RouterCapability): String {
    return when (capability) {
      RouterCapability.GUEST_WIFI_TOGGLE -> AppActionId.ROUTER_GUEST_WIFI
      RouterCapability.DNS_SHIELD_TOGGLE -> AppActionId.ROUTER_DNS_SHIELD
      RouterCapability.FIREWALL_TOGGLE -> AppActionId.ROUTER_FIREWALL
      RouterCapability.VPN_TOGGLE -> AppActionId.ROUTER_VPN
      RouterCapability.QOS_CONFIG -> AppActionId.ROUTER_QOS
      RouterCapability.REBOOT -> AppActionId.ROUTER_REBOOT
      RouterCapability.DNS_FLUSH -> AppActionId.ROUTER_FLUSH_DNS
      RouterCapability.DHCP_LEASES_WRITE -> AppActionId.ROUTER_RENEW_DHCP
      else -> capability.name
    }
  }

  private fun routerActionLabel(capability: RouterCapability): String {
    return when (capability) {
      RouterCapability.GUEST_WIFI_TOGGLE -> RouterWriteAction.GUEST_WIFI.label
      RouterCapability.DNS_SHIELD_TOGGLE -> RouterWriteAction.DNS_SHIELD.label
      RouterCapability.FIREWALL_TOGGLE -> RouterWriteAction.FIREWALL.label
      RouterCapability.VPN_TOGGLE -> RouterWriteAction.VPN.label
      RouterCapability.QOS_CONFIG -> RouterWriteAction.QOS.label
      RouterCapability.REBOOT -> RouterWriteAction.REBOOT.label
      RouterCapability.DNS_FLUSH -> RouterWriteAction.FLUSH_DNS.label
      RouterCapability.DHCP_LEASES_WRITE -> RouterWriteAction.RENEW_DHCP.label
      else -> capability.name
    }
  }

  private suspend fun fetchPublicIp(): String? = withContext(Dispatchers.IO) {
    runCatching {
      val conn = (URL("https://api.ipify.org?format=text").openConnection() as HttpURLConnection).apply {
        connectTimeout = 2500
        readTimeout = 2500
        requestMethod = "GET"
      }
      try {
        conn.inputStream.bufferedReader().use { reader ->
          reader.readText().trim().takeIf { it.isNotBlank() }
        }
      } finally {
        conn.disconnect()
      }
    }.getOrNull()
  }
}

private fun toStrength(device: Device): Int {
  if (device.isGateway) {
    device.rssiDbm?.let { return (it + 100).coerceIn(5, 99) }
  }
  device.latencyMs?.let {
    val score = 100 - it
    return score.coerceIn(8, 95)
  }
  return if (device.online) 55 else 8
}

