package com.nerf.netx.data

object OuiLookup {
  private val ouiVendors = mapOf(
    "28:CF:E9" to "Apple, Inc.",
    "D8:96:95" to "Apple, Inc.",
    "BC:92:6B" to "Apple, Inc.",
    "3C:5A:B4" to "Google, Inc.",
    "F4:F5:D8" to "Google, Inc.",
    "A4:77:33" to "Google, Inc.",
    "10:9A:DD" to "Samsung Electronics Co.,Ltd",
    "40:4E:36" to "Samsung Electronics Co.,Ltd",
    "CC:F9:E4" to "Samsung Electronics Co.,Ltd",
    "B8:27:EB" to "Raspberry Pi Trading Ltd",
    "DC:A6:32" to "Raspberry Pi Trading Ltd",
    "00:1A:79" to "Cisco Systems, Inc",
    "F0:9F:C2" to "Ubiquiti Inc",
    "24:A4:3C" to "Ubiquiti Inc",
    "44:65:0D" to "Amazon Technologies Inc.",
    "FC:A1:83" to "Amazon Technologies Inc.",
    "00:17:88" to "Philips Lighting BV",
    "3C:84:6A" to "Roku, Inc",
    "AC:63:BE" to "LG Electronics",
    "00:0C:29" to "VMware, Inc."
  )

  fun lookup(mac: String?): String? {
    if (mac.isNullOrBlank()) return null
    val normalized = mac.replace('-', ':').uppercase()
    val valid = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(normalized)
    if (!valid) return null
    return ouiVendors[normalized.take(8)]
  }
}

