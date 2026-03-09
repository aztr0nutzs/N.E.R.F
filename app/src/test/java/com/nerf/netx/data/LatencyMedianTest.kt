package com.nerf.netx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyMedianTest {
  @Test
  fun `median is deterministic for odd sized list`() {
    assertEquals(24.0, requireNotNull(BackendMath.median(listOf(31, 24, 18))), 0.0)
  }

  @Test
  fun `median handles empty list`() {
    assertNull(BackendMath.median(emptyList()))
  }
}

