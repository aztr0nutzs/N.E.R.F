package com.nerf.netx.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTypeInferenceTest {
  @Test
  fun `infers expected device types from hostname`() {
    assertEquals("PHONE", BackendMath.inferDeviceType("johns-iphone"))
    assertEquals("PHONE", BackendMath.inferDeviceType("pixel-8"))
    assertEquals("COMPUTER", BackendMath.inferDeviceType("my-macbook"))
    assertEquals("MEDIA", BackendMath.inferDeviceType("living-room-roku"))
    assertEquals("PRINTER", BackendMath.inferDeviceType("hp-printer"))
    assertEquals("CAMERA", BackendMath.inferDeviceType("front-camera"))
    assertEquals("UNKNOWN", BackendMath.inferDeviceType("mystery-host"))
    assertEquals("UNKNOWN", BackendMath.inferDeviceType(null))
  }
}

