package com.nerf.netx.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RealLanScannerIntegrationTest {
  @Test
  fun scan_enriches_with_mac_vendor_hostname_and_progress() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val scanner = RealLanScanner(
      context,
      RealLanScannerOverrides(
        resolvePlan = {
          ScanNetworkPlan(
            localIp = "192.168.1.10",
            gatewayIp = "192.168.1.1",
            prefixLength = 24,
            scanRange = listOf("192.168.1.1", "192.168.1.20")
          )
        },
        probeReachability = {
          ReachabilityResult(
            reachable = true,
            methodUsed = "TCP",
            successfulPort = 443,
            latencyMs = 20,
            reason = null
          )
        },
        reverseLookupHostname = { ip ->
          if (ip == "192.168.1.1") "gateway.local" to null else "pixel.local" to null
        },
        measureLatencyMedian = { _, _ -> 22 to null },
        parseArpTable = {
          mapOf(
            "192.168.1.1" to "28:CF:E9:AA:BB:CC",
            "192.168.1.20" to "3C:5A:B4:11:22:33"
          )
        }
      )
    )

    val progressCalls = mutableListOf<Triple<Int, Int, Int>>()
    val devices = mutableListOf<com.nerf.netx.domain.Device>()

    val result = scanner.scanHosts(
      onStarted = { planned, _, _ -> assertEquals(2, planned) },
      onProgress = { probesSent, devicesFound, targetsPlanned ->
        progressCalls += Triple(probesSent, devicesFound, targetsPlanned)
      },
      onDevice = { device, _ -> devices += device }
    )

    assertTrue(progressCalls.isNotEmpty())
    assertEquals(2, result.devices.size)
    assertTrue(result.devices.any { it.ip == "192.168.1.1" && it.isGateway && it.macAddress != null && it.vendorName != null })
    assertTrue(result.devices.any { it.ip == "192.168.1.20" && it.hostname == "pixel.local" && it.methodUsed == "TCP" })
  }

  @Test
  fun scan_respects_semaphore_limit_of_64() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val active = AtomicInteger(0)
    val peak = AtomicInteger(0)

    val scanner = RealLanScanner(
      context,
      RealLanScannerOverrides(
        resolvePlan = {
          ScanNetworkPlan(
            localIp = "10.0.0.2",
            gatewayIp = "10.0.0.1",
            prefixLength = 24,
            scanRange = (1..120).map { "10.0.0.$it" }
          )
        },
        probeReachability = {
          val now = active.incrementAndGet()
          peak.set(maxOf(peak.get(), now))
          delay(20)
          active.decrementAndGet()
          ReachabilityResult(false, null, null, null, "unreachable")
        }
      )
    )

    scanner.scanHosts(
      onStarted = { _, _, _ -> },
      onProgress = { _, _, _ -> },
      onDevice = { _, _ -> }
    )

    assertTrue("Expected max concurrency <= 64 but was ${peak.get()}", peak.get() <= 64)
  }

  @Test
  fun scan_cancellation_stops_promptly() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val scanner = RealLanScanner(
      context,
      RealLanScannerOverrides(
        resolvePlan = {
          ScanNetworkPlan(
            localIp = "10.1.0.2",
            gatewayIp = "10.1.0.1",
            prefixLength = 24,
            scanRange = (1..200).map { "10.1.0.$it" }
          )
        },
        probeReachability = {
          delay(200)
          ReachabilityResult(false, null, null, null, "slow probe")
        }
      )
    )

    lateinit var job: Job
    withTimeout(2_000) {
      job = launch {
        scanner.scanHosts(
          onStarted = { _, _, _ -> },
          onProgress = { _, _, _ -> },
          onDevice = { _, _ -> }
        )
      }
      delay(120)
      job.cancel(CancellationException("test cancel"))
      try {
        job.join()
      } catch (_: CancellationException) {
      }
    }
    assertTrue(job.isCancelled)
  }
}

