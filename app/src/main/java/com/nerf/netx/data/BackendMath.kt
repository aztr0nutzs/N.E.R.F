package com.nerf.netx.data

import kotlin.math.abs

object BackendMath {
  fun inferDeviceType(hostname: String?): String {
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

  fun median(values: List<Int>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[sorted.size / 2].toDouble()
  }

  fun linkQualityScore(isOnline: Boolean, latencyMs: Int?, isGateway: Boolean, gatewayRssiDbm: Int?): Int {
    if (!isOnline) return 8
    if (isGateway && gatewayRssiDbm != null) {
      return (gatewayRssiDbm + 100).coerceIn(5, 99)
    }
    if (latencyMs != null) {
      return (100 - latencyMs).coerceIn(8, 95)
    }
    return 55
  }

  fun slope(values: List<Double>): Double {
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
}

