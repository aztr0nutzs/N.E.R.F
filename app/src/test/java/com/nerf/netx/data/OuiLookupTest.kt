package com.nerf.netx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OuiLookupTest {
  @Test
  fun `returns known vendor for known OUI`() {
    assertEquals("Apple", OuiLookup.lookup("28:CF:E9:00:11:22"))
    assertEquals("Google", OuiLookup.lookup("3c:5a:b4:AA:BB:CC"))
  }

  @Test
  fun `parses bundled tsv rows safely`() {
    val parsed = OuiLookup.parseTsv(
      sequenceOf(
        "28CFE9\tApple, Inc.",
        "3C:5A:B4\tGoogle, Inc.",
        "# comment",
        "bad-row"
      )
    )

    assertEquals("Apple", parsed["28:CF:E9"])
    assertEquals("Google", parsed["3C:5A:B4"])
  }

  @Test
  fun `returns null for unknown or invalid mac`() {
    assertNull(OuiLookup.lookup("12:34:56:78:90:AB"))
    assertNull(OuiLookup.lookup("bad-mac"))
    assertNull(OuiLookup.lookup(null))
  }
}
