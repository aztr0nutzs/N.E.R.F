package com.nerf.netx.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkQualityScoreTest {
  @Test
  fun `gateway quality uses rssi`() {
    assertEquals(45, BackendMath.linkQualityScore(isOnline = true, latencyMs = 20, isGateway = true, gatewayRssiDbm = -55))
  }

  @Test
  fun `non gateway quality uses latency`() {
    assertEquals(80, BackendMath.linkQualityScore(isOnline = true, latencyMs = 20, isGateway = false, gatewayRssiDbm = null))
    assertEquals(8, BackendMath.linkQualityScore(isOnline = true, latencyMs = 400, isGateway = false, gatewayRssiDbm = null))
  }

  @Test
  fun `offline quality is lowest`() {
    assertEquals(8, BackendMath.linkQualityScore(isOnline = false, latencyMs = 5, isGateway = false, gatewayRssiDbm = null))
  }
}

