package com.nerf.netx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OuiLookupTest {
  @Test
  fun `returns known vendor for known OUI`() {
    assertEquals("Apple, Inc.", OuiLookup.lookup("28:CF:E9:00:11:22"))
    assertEquals("Google, Inc.", OuiLookup.lookup("3c:5a:b4:AA:BB:CC"))
  }

  @Test
  fun `returns null for unknown or invalid mac`() {
    assertNull(OuiLookup.lookup("12:34:56:78:90:AB"))
    assertNull(OuiLookup.lookup("bad-mac"))
    assertNull(OuiLookup.lookup(null))
  }
}

