package com.nerf.netx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsTrendComputationTest {
  @Test
  fun `positive slope indicates increasing trend`() {
    val slope = BackendMath.slope(listOf(10.0, 15.0, 20.0, 25.0))
    assertTrue(slope > 0.0)
  }

  @Test
  fun `negative slope indicates decreasing trend`() {
    val slope = BackendMath.slope(listOf(25.0, 20.0, 15.0, 10.0))
    assertTrue(slope < 0.0)
  }

  @Test
  fun `flat values yield stable slope`() {
    val slope = BackendMath.slope(listOf(5.0, 5.0, 5.0, 5.0))
    assertEquals(0.0, slope, 0.000001)
  }
}

