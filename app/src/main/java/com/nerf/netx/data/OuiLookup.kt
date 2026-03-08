package com.nerf.netx.data

import android.content.Context

object OuiLookup {
  private const val assetPath = "data/oui_vendors.tsv"

  private val bootstrapVendors = mapOf(
    "28:CF:E9" to "Apple",
    "3C:5A:B4" to "Google",
    "10:9A:DD" to "Samsung",
    "B8:27:EB" to "Raspberry Pi",
    "00:1A:79" to "Cisco",
    "F0:9F:C2" to "Ubiquiti",
    "44:65:0D" to "Amazon",
    "3C:84:6A" to "Roku",
    "AC:63:BE" to "LG",
    "00:0C:29" to "VMware"
  )

  @Volatile
  private var cachedAssetMap: Map<String, String>? = null

  fun lookup(mac: String?, context: Context? = null): String? {
    if (mac.isNullOrBlank()) return null
    val normalized = normalizeMac(mac) ?: return null
    val prefix = normalized.take(8)
    val vendors = if (context == null) bootstrapVendors else vendors(context)
    return vendors[prefix] ?: bootstrapVendors[prefix]
  }

  internal fun parseTsv(lines: Sequence<String>): Map<String, String> {
    return lines
      .mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@mapNotNull null
        val parts = trimmed.split('\t', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val prefix = normalizePrefix(parts[0]) ?: return@mapNotNull null
        val vendor = canonicalVendorName(parts[1])
        if (vendor.isBlank()) return@mapNotNull null
        prefix to vendor
      }
      .toMap(LinkedHashMap())
  }

  internal fun canonicalVendorName(raw: String): String {
    val cleaned = raw
      .trim()
      .replace(Regex("\\s+"), " ")
      .trim(',', '.', ' ')
    if (cleaned.isBlank()) return ""

    val cleanedKey = cleaned.uppercase()
    val exact = vendorAliases[cleanedKey]
    if (exact != null) return exact

    val withoutCorporateNoise = cleaned
      .replace(Regex(",?\\s+(INCORPORATED|INC|LLC|LTD|LIMITED|CORPORATION|CORP|CO\\.?|COMPANY|GMBH|S\\.A\\.?|BV|B\\.V\\.?|PLC|PTE|PTY|AG|AB|S\\.R\\.L\\.?|S\\.P\\.A\\.?)$", RegexOption.IGNORE_CASE), "")
      .trim(',', '.', ' ')

    return vendorAliases[withoutCorporateNoise.uppercase()]
      ?: withoutCorporateNoise
      .replace("  ", " ")
  }

  private fun vendors(context: Context): Map<String, String> {
    cachedAssetMap?.let { return it }
    synchronized(this) {
      cachedAssetMap?.let { return it }
      val loaded = runCatching {
        context.applicationContext.assets.open(assetPath).bufferedReader().use { reader ->
          parseTsv(reader.lineSequence())
        }
      }.getOrDefault(emptyMap())
      return (loaded.ifEmpty { bootstrapVendors }).also { cachedAssetMap = it }
    }
  }

  private fun normalizeMac(raw: String): String? {
    val normalized = raw.replace('-', ':').uppercase()
    return if (macRegex.matches(normalized)) normalized else null
  }

  private fun normalizePrefix(raw: String): String? {
    val compact = raw.replace(':', '-').replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
    if (compact.length < 6) return null
    return "${compact.substring(0, 2)}:${compact.substring(2, 4)}:${compact.substring(4, 6)}"
  }

  private val macRegex = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

  private val vendorAliases = mapOf(
    "APPLE, INC" to "Apple",
    "APPLE" to "Apple",
    "GOOGLE, INC" to "Google",
    "GOOGLE" to "Google",
    "GOOGLE FIBER INC" to "Google Fiber",
    "NEST LABS INC" to "Google Nest",
    "SAMSUNG ELECTRONICS" to "Samsung",
    "SAMSUNG ELECTRONICS CO.,LTD" to "Samsung",
    "SAMSUNG ELECTRONICS CO LTD" to "Samsung",
    "SAMSUNG" to "Samsung",
    "ASUSTEK COMPUTER INC" to "ASUS",
    "ASUSTEK COMPUTER" to "ASUS",
    "ASUS" to "ASUS",
    "MICROSOFT" to "Microsoft",
    "HEWLETT PACKARD" to "HP",
    "HEWLETT PACKARD ENTERPRISE" to "HPE",
    "HP INC" to "HP",
    "HPE" to "HPE",
    "DELL" to "Dell",
    "DELL INC" to "Dell",
    "LENOVO" to "Lenovo",
    "MOTOROLA MOBILITY LLC, A LENOVO COMPANY" to "Motorola",
    "INTEL CORPORATE" to "Intel",
    "INTEL" to "Intel",
    "UBIQUITI INC" to "Ubiquiti",
    "CISCO SYSTEMS" to "Cisco",
    "CISCO SYSTEMS, INC" to "Cisco",
    "TP-LINK TECHNOLOGIES CO., LTD." to "TP-Link",
    "TP-LINK TECHNOLOGIES CO LTD" to "TP-Link",
    "HUAWEI TECHNOLOGIES CO.,LTD" to "Huawei",
    "HUAWEI DEVICE CO., LTD." to "Huawei",
    "XIAOMI COMMUNICATIONS CO LTD" to "Xiaomi",
    "XIAOMI COMMUNICATIONS CO., LTD" to "Xiaomi",
    "ONEPLUS TECH (SHENZHEN) LTD" to "OnePlus",
    "AMAZON TECHNOLOGIES INC" to "Amazon",
    "AMAZON TECHNOLOGIES, INC." to "Amazon",
    "RING LLC" to "Ring",
    "RING" to "Ring",
    "ROKU, INC" to "Roku",
    "ROKU" to "Roku",
    "LG ELECTRONICS" to "LG",
    "SONY GROUP CORPORATION" to "Sony",
    "SONY INTERACTIVE ENTERTAINMENT INC." to "Sony PlayStation",
    "NINTENDO CO., LTD." to "Nintendo",
    "NINTENDO" to "Nintendo",
    "ESPRESSIF INC." to "Espressif",
    "ESPRESSIF INC" to "Espressif",
    "RASPBERRY PI TRADING LTD" to "Raspberry Pi",
    "VMWARE, INC." to "VMware",
    "VMWARE" to "VMware",
    "ARLO TECHNOLOGIES, INC." to "Arlo",
    "ARLO" to "Arlo",
    "CANON INC." to "Canon",
    "BROTHER INDUSTRIES, LTD." to "Brother",
    "EPSON" to "Epson",
    "SEIKO EPSON CORPORATION" to "Epson",
    "WYZE LABS INC." to "Wyze",
    "EERO INC." to "eero",
    "SONOS, INC." to "Sonos",
    "ECOBEE INC." to "ecobee",
    "PHILIPS LIGHTING BV" to "Philips Hue",
    "SIGNIFY NETHERLANDS B.V." to "Philips Hue",
    "BELKIN INTERNATIONAL INC." to "Belkin",
    "HON HAI PRECISION IND. CO.,LTD." to "Foxconn",
    "FOXCONN INTERCONNECT TECHNOLOGY LIMITED" to "Foxconn"
  )
}
