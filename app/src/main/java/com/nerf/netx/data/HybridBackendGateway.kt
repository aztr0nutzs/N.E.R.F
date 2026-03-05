package com.nerf.netx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.nerf.netx.domain.ActionResult
import com.nerf.netx.domain.AnalyticsService
import com.nerf.netx.domain.AnalyticsSnapshot
import com.nerf.netx.domain.AppServices
import com.nerf.netx.domain.Device
import com.nerf.netx.domain.DeviceControlService
import com.nerf.netx.domain.DeviceDetails
import com.nerf.netx.domain.DevicesService
import com.nerf.netx.domain.MapLayoutMode
import com.nerf.netx.domain.MapLink
import com.nerf.netx.domain.MapNode
import com.nerf.netx.domain.MapService
import com.nerf.netx.domain.MapTopologyService
import com.nerf.netx.domain.MeasurementMode
import com.nerf.netx.domain.QosMode
import com.nerf.netx.domain.RouterControlService
import com.nerf.netx.domain.RouterInfoResult
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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
  override val deviceControl: DeviceControlService = HybridDeviceControl(sharedDevices, lanScanner)
  override val map: MapService = HybridMapService(sharedDevices, selectedNodeId)
  override val topology: MapTopologyService = HybridTopologyService(sharedDevices, selectedNodeId)
  override val analytics: AnalyticsService = HybridAnalytics(speedtest, sharedDevices, scan)
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
  private val lanScanner: RealLanScanner
) : DeviceControlService {
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

  override suspend fun block(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, ServiceStatus.ERROR, "DEVICE_NOT_FOUND", "Device not found")
    return ActionResult(
      ok = false,
      status = ServiceStatus.NOT_SUPPORTED,
      code = "ROUTER_API_REQUIRED",
      message = "Block action is NOT_SUPPORTED",
      details = mapOf("device" to d.name),
      errorReason = "Blocking devices requires router vendor API integration and authenticated credentials."
    )
  }

  override suspend fun prioritize(deviceId: String): ActionResult {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId }
      ?: return ActionResult(false, ServiceStatus.ERROR, "DEVICE_NOT_FOUND", "Device not found")
    return ActionResult(
      ok = false,
      status = ServiceStatus.NOT_SUPPORTED,
      code = "ROUTER_API_REQUIRED",
      message = "Prioritize action is NOT_SUPPORTED",
      details = mapOf("device" to d.name),
      errorReason = "QoS prioritization requires router vendor API integration and authenticated credentials."
    )
  }

  override suspend fun deviceDetails(deviceId: String): DeviceDetails? {
    val d = sharedDevices.value.firstOrNull { it.id == deviceId } ?: return null
    val probe = lanScanner.probeReachability(d.ip)
    return DeviceDetails(
      device = d,
      pingMs = probe.latencyMs,
      notes = if (probe.reachable) "Reachable via ${probe.methodUsed}" else "Reachability unavailable"
    )
  }
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
  speedtestService: SpeedtestService,
  devicesFlow: StateFlow<List<Device>>,
  scanService: ScanService
) : AnalyticsService {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val _events = MutableStateFlow<List<String>>(listOf("BOOT: backend online"))
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
      message = "No scan data available yet."
    )
  )
  override val snapshot: StateFlow<AnalyticsSnapshot> = _snapshot.asStateFlow()

  private val scanStartedAt = MutableStateFlow<Long?>(null)
  private val lastScanDuration = MutableStateFlow<Int?>(null)
  private val lastScanTimestamp = MutableStateFlow<Long?>(null)

  init {
    scope.launch {
      scanService.events.collect { event ->
        when (event) {
          is ScanEvent.ScanStarted -> scanStartedAt.value = event.startedAtEpochMs
          is ScanEvent.ScanDone -> {
            lastScanTimestamp.value = event.completedAtEpochMs
            val started = scanStartedAt.value
            lastScanDuration.value = if (started == null) null else ((event.completedAtEpochMs - started).coerceAtLeast(0L)).toInt()
          }
          else -> Unit
        }
      }
    }

    scope.launch {
      while (true) {
        val st = speedtestService.ui.value
        val devices = devicesFlow.value
        val reachable = devices.filter { it.online }
        val rtts = reachable.mapNotNull { it.latencyMs }.sorted()
        val avgRtt = if (rtts.isEmpty()) null else rtts.average()
        val medianRtt = if (rtts.isEmpty()) null else rtts[rtts.size / 2].toDouble()

        val status = if (devices.isEmpty()) ServiceStatus.NO_DATA else ServiceStatus.OK
        val message = if (devices.isEmpty()) "No scan data available yet." else null

        _snapshot.value = AnalyticsSnapshot(
          downMbps = st.downMbps,
          upMbps = st.upMbps,
          latencyMs = st.latencyMs?.toDouble(),
          jitterMs = null,
          packetLossPct = null,
          deviceCount = devices.size,
          reachableCount = reachable.size,
          avgRttMs = avgRtt,
          medianRttMs = medianRtt,
          scanDurationMs = lastScanDuration.value,
          lastScanEpochMs = lastScanTimestamp.value,
          status = status,
          message = message
        )
        delay(1000)
      }
    }
  }

  override suspend fun refresh() {
    val s = snapshot.value
    _events.value = listOf(
      "STATUS=${s.status}",
      "DEVICES=${s.deviceCount} REACHABLE=${s.reachableCount}",
      "RTT_AVG=${s.avgRttMs?.let { "%.1f".format(it) } ?: "N/A"}ms RTT_MED=${s.medianRttMs?.let { "%.1f".format(it) } ?: "N/A"}ms",
      "LAST_SCAN=${s.lastScanEpochMs ?: 0} DURATION_MS=${s.scanDurationMs ?: -1}"
    )
  }
}

private class HybridRouterControl(
  private val context: Context,
  private val credentialsStore: RouterCredentialsStore
) : RouterControlService {

  override suspend fun info(): RouterInfoResult {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return RouterInfoResult(ServiceStatus.ERROR, "ConnectivityManager unavailable")

    val active = cm.activeNetwork ?: return RouterInfoResult(ServiceStatus.NO_DATA, "No active network")
    val link = cm.getLinkProperties(active)
    val netCaps = cm.getNetworkCapabilities(active)
    val dns = link?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()

    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val onWifi = netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val ssid = if (onWifi) wifi?.connectionInfo?.ssid?.trim('"') else null
    val linkSpeed = if (onWifi) wifi?.connectionInfo?.linkSpeed else null

    val api = buildRouterApi()
    if (api == null) {
      val gatewayIp = link?.routes?.firstOrNull { it.hasGateway() }?.gateway?.hostAddress
      return RouterInfoResult(
        status = ServiceStatus.NO_DATA,
        message = "Router credentials not configured.",
        gatewayIp = gatewayIp,
        dnsServers = dns,
        ssid = ssid,
        linkSpeedMbps = linkSpeed
      )
    }

    val detected = api.detect(connectionInfoFromStore())
    val capabilities = api.getCapabilities()
    val mode = if (capabilities.any { it != RouterCapability.READ_INFO && it != RouterCapability.DHCP_LEASES_READ }) {
      "READ_WRITE"
    } else {
      "READ_ONLY"
    }
    val message = buildString {
      append("Router detected")
      detected.vendorName?.let { append(": ").append(it) }
      detected.modelName?.let { append(" ").append(it) }
      append(" | auth=").append(detected.detectedAuthType.name)
      append(" | mode=").append(mode)
      append(" | capabilities=").append(capabilities.joinToString(",") { it.name })
    }

    return RouterInfoResult(
      status = ServiceStatus.OK,
      message = message,
      gatewayIp = detected.routerIp,
      dnsServers = dns,
      ssid = ssid,
      linkSpeedMbps = linkSpeed
    )
  }

  override suspend fun toggleGuest(): ActionResult {
    return performCapabilityAction(
      capability = RouterCapability.GUEST_WIFI_TOGGLE,
      code = "GUEST_WIFI"
    ) { api -> api.setGuestWifiEnabled(true) }
  }

  override suspend fun setQos(mode: QosMode): ActionResult {
    return performCapabilityAction(
      capability = RouterCapability.QOS_CONFIG,
      code = "QOS"
    ) { api ->
      api.setQosConfig(
        QosConfig(
          mode = mode.name
        )
      )
    }
  }

  override suspend fun renewDhcp(): ActionResult {
    val api = buildRouterApi() ?: return routerNotConfigured("renewDhcp")
    val caps = api.getCapabilities()
    if (!caps.contains(RouterCapability.DHCP_LEASES_WRITE)) {
      return notSupportedAction("renewDhcp", "No verified API endpoint for DHCP lease write/renew. Read-only mode.")
    }
    return notSupportedAction("renewDhcp", "Renew DHCP endpoint is not verified for this router model. Read-only mode.")
  }

  override suspend fun flushDns(): ActionResult {
    return performCapabilityAction(
      capability = RouterCapability.DNS_FLUSH,
      code = "DNS_FLUSH"
    ) { api -> api.flushDns() }
  }

  override suspend fun rebootRouter(): ActionResult {
    return performCapabilityAction(
      capability = RouterCapability.REBOOT,
      code = "REBOOT"
    ) { api -> api.reboot() }
  }

  override suspend fun toggleFirewall(): ActionResult {
    return performCapabilityAction(
      capability = RouterCapability.FIREWALL_TOGGLE,
      code = "FIREWALL"
    ) { api -> api.setFirewallEnabled(true) }
  }

  override suspend fun toggleVpn(): ActionResult {
    return performCapabilityAction(
      capability = RouterCapability.VPN_TOGGLE,
      code = "VPN"
    ) { api -> api.setVpnEnabled(true) }
  }

  private suspend fun performCapabilityAction(
    capability: RouterCapability,
    code: String,
    call: suspend (RouterApi) -> RouterActionResult
  ): ActionResult {
    val api = buildRouterApi() ?: return routerNotConfigured(code)
    val caps = api.getCapabilities()
    if (!caps.contains(capability)) {
      return notSupportedAction(code, "No verified API endpoint for this action. Read-only mode.")
    }
    val result = call(api)
    return mapRouterActionResult(result, code)
  }

  private fun mapRouterActionResult(result: RouterActionResult, code: String): ActionResult {
    return when (result.status) {
      RouterActionStatus.OK -> ActionResult(
        ok = true,
        status = ServiceStatus.OK,
        code = code,
        message = result.message
      )
      RouterActionStatus.NOT_SUPPORTED -> ActionResult(
        ok = false,
        status = ServiceStatus.NOT_SUPPORTED,
        code = "NOT_SUPPORTED",
        message = "$code is NOT_SUPPORTED",
        errorReason = result.message
      )
      RouterActionStatus.ERROR -> ActionResult(
        ok = false,
        status = ServiceStatus.ERROR,
        code = result.errorCode ?: "ROUTER_ACTION_ERROR",
        message = result.message,
        errorReason = result.message
      )
    }
  }

  private fun routerNotConfigured(action: String): ActionResult {
    return ActionResult(
      ok = false,
      status = ServiceStatus.NO_DATA,
      code = "ROUTER_NOT_CONFIGURED",
      message = "$action unavailable",
      errorReason = "Router credentials are not configured or validated."
    )
  }

  private fun notSupportedAction(action: String, reason: String): ActionResult {
    return ActionResult(
      ok = false,
      status = ServiceStatus.NOT_SUPPORTED,
      code = "NOT_SUPPORTED",
      message = "$action is NOT_SUPPORTED",
      errorReason = reason
    )
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

