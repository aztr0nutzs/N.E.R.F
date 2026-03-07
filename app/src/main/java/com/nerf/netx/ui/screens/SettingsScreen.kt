package com.nerf.netx.ui.screens

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nerf.netx.data.RouterAccessMode
import com.nerf.netx.data.RouterCapability
import com.nerf.netx.data.RouterCredentials
import com.nerf.netx.data.RouterCredentialsStore
import com.nerf.netx.ui.theme.ThemeId
import com.nerf.netx.ui.theme.ThemeType
import kotlinx.coroutines.launch

private data class ThemePalette(
  val primary: Color,
  val accent: Color,
  val highlight: Color,
  val panel: Color,
  val text: Color
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SettingsScreen(
  themeId: ThemeId,
  onThemeSelected: (ThemeId) -> Unit,
  htmlAssetUrlProvider: (ThemeId) -> String?,
  credentialsStore: RouterCredentialsStore,
  onOpenAssistant: () -> Unit,
  onOpenDoctor: () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var previewTheme by remember { mutableStateOf(themeId) }
  LaunchedEffect(themeId) {
    previewTheme = themeId
  }

  val detectedHtmlThemeFolders = remember {
    runCatching {
      context.assets.list("themes")?.toList()?.sorted().orEmpty()
    }.getOrDefault(emptyList())
  }

  val creds = remember { credentialsStore.read() }
  var host by remember { mutableStateOf(creds.host) }
  var username by remember { mutableStateOf(creds.username) }
  var token by remember { mutableStateOf(creds.token) }
  var saveStatus by remember { mutableStateOf("Credentials not validated yet.") }
  var validating by remember { mutableStateOf(false) }
  var profile by remember { mutableStateOf(credentialsStore.readProfile()) }

  val appliedTheme = themeId
  val previewUrl = htmlAssetUrlProvider(previewTheme)
  val palette = themePalette(previewTheme)
  val themeDescriptions = remember {
    mapOf(
      ThemeId.NERF_MAIN_DASH_HTML to "Default dashboard - High-contrast HUD, dense telemetry cards, orange/cyan emphasis.",
      ThemeId.NERF_HUD_ALT_HTML to "Alternative HUD - Brighter accents, identical controls to main dashboard.",
      ThemeId.NERF_DASH_NEW_HTML to "Jarvis-styled command dashboard with native bridge wired live telemetry and controls."
    )
  }
  val screenshotAssetPath = remember {
    mapOf(
      ThemeId.NERF_MAIN_DASH_HTML to "themes/nerf_main_dash/screenshot.png",
      ThemeId.NERF_HUD_ALT_HTML to "themes/nerf_hud_alt/screenshot.png",
      ThemeId.NERF_DASH_NEW_HTML to "themes/nerf_dash_new/screenshot.png"
    )
  }
  val previewScreenshotPath = screenshotAssetPath[previewTheme]
  val previewScreenshotExists = remember(previewTheme) {
    val path = screenshotAssetPath[previewTheme]
    if (path == null) {
      false
    } else {
      runCatching { context.assets.open(path).close() }.isSuccess
    }
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(16.dp)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Text("SETTINGS", style = MaterialTheme.typography.titleLarge)

    ElevatedCard {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Expert Surfaces", style = MaterialTheme.typography.titleMedium)
        Text("Use Assistant for conversational guidance, or Network Doctor for a direct expert readout.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(modifier = Modifier.weight(1f), onClick = onOpenAssistant) { Text("Assistant") }
          OutlinedButton(modifier = Modifier.weight(1f), onClick = onOpenDoctor) { Text("Network Doctor") }
        }
      }
    }

    ElevatedCard {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Text("Applied: ${appliedTheme.displayName}", style = MaterialTheme.typography.bodySmall)
        Text("Previewing: ${previewTheme.displayName}", style = MaterialTheme.typography.bodySmall)

        ThemeId.entries.forEach { t ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
              Text(t.displayName)
              Text("id=${t.id} | type=${t.type.name}", style = MaterialTheme.typography.bodySmall)
              Text(themeDescriptions[t] ?: "No description.", style = MaterialTheme.typography.bodySmall)
            }
            RadioButton(selected = (previewTheme == t), onClick = { previewTheme = t })
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = {
            onThemeSelected(previewTheme)
          }) {
            Text("Apply Theme")
          }
          OutlinedButton(onClick = {
            previewTheme = appliedTheme
          }) {
            Text("Cancel / Revert")
          }
        }

        Text("Selection above only previews until Apply Theme is tapped.", style = MaterialTheme.typography.bodySmall)
        Text("Apply updates runtime theme without app restart.", style = MaterialTheme.typography.bodySmall)
      }
    }

    ElevatedCard {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Theme Preview", style = MaterialTheme.typography.titleMedium)
        Text(themeDescriptions[previewTheme] ?: "No description.", style = MaterialTheme.typography.bodySmall)

        if (previewScreenshotExists && previewScreenshotPath != null) {
          AndroidView(
            modifier = Modifier
              .fillMaxWidth()
              .height(140.dp)
              .border(1.dp, Color(0x334A6478), RoundedCornerShape(8.dp)),
            factory = { ctx ->
              ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
              }
            },
            update = { view ->
              runCatching {
                context.assets.open(previewScreenshotPath).use { stream ->
                  val bmp = BitmapFactory.decodeStream(stream)
                  view.setImageBitmap(bmp)
                }
              }
            }
          )
        } else {
          Box(
            Modifier
              .fillMaxWidth()
              .height(90.dp)
              .background(Color(0x1A6E8BA1), RoundedCornerShape(8.dp))
              .border(1.dp, Color(0x334A6478), RoundedCornerShape(8.dp))
              .padding(10.dp)
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("No screenshot asset found.", style = MaterialTheme.typography.bodySmall)
              Text("Missing: ${previewScreenshotPath ?: "themes/<id>/screenshot.png"}", style = MaterialTheme.typography.bodySmall)
            }
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          colorSwatch("Primary", palette.primary)
          colorSwatch("Accent", palette.accent)
          colorSwatch("Highlight", palette.highlight)
        }

        ElevatedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp)
        ) {
          Column(
            Modifier
              .fillMaxWidth()
              .background(palette.panel)
              .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text("${previewTheme.displayName} Mock Card", color = palette.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Box(
                Modifier
                  .height(36.dp)
                  .weight(1f)
                  .background(palette.primary, RoundedCornerShape(8.dp))
              )
              Box(
                Modifier
                  .height(36.dp)
                  .weight(1f)
                  .background(palette.accent, RoundedCornerShape(8.dp))
              )
            }
          }
        }

        if (previewTheme.type == ThemeType.HTML) {
          Text("HTML Theme Packs in assets/themes/", style = MaterialTheme.typography.bodySmall)
          Text(
            if (detectedHtmlThemeFolders.isEmpty()) "(No folders detected at runtime)"
            else detectedHtmlThemeFolders.joinToString(", "),
            style = MaterialTheme.typography.bodySmall
          )

          if (previewUrl != null) {
            AndroidView(
              modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
              factory = { ctx ->
                WebView(ctx).apply {
                  settings.javaScriptEnabled = true
                  settings.domStorageEnabled = true
                  settings.allowFileAccess = true
                  settings.cacheMode = WebSettings.LOAD_NO_CACHE
                  loadUrl(previewUrl)
                }
              },
              update = { it.loadUrl(previewUrl) }
            )
          } else {
            Text("Preview unavailable: theme entry HTML not found.", style = MaterialTheme.typography.bodySmall)
          }
        } else {
          Text("Native-style theme preview shown in mock card.", style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    ElevatedCard {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Router Credentials", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
          value = host,
          onValueChange = { host = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Router IP / URL") },
          placeholder = { Text("192.168.1.1 or https://192.168.1.1") }
        )
        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Username") }
        )
        OutlinedTextField(
          value = token,
          onValueChange = { token = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Password") },
          visualTransformation = PasswordVisualTransformation()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            enabled = !validating,
            onClick = {
              scope.launch {
                validating = true
                val check = credentialsStore.testConnection(
                  RouterCredentials(
                    host = host.trim(),
                    username = username.trim(),
                    token = token
                  )
                )
                profile = check.profile ?: profile
                saveStatus = check.message
                validating = false
              }
            }
          ) {
            Text(if (validating) "Testing..." else "Test Connection")
          }

          Button(
            enabled = !validating,
            onClick = {
              scope.launch {
                validating = true
                val check = credentialsStore.validateAndSave(
                  RouterCredentials(
                    host = host.trim(),
                    username = username.trim(),
                    token = token
                  )
                )
                profile = check.profile ?: profile
                saveStatus = check.message
                validating = false
              }
            }
          ) {
            Text(if (validating) "Validating..." else "Validate & Save")
          }
        }

        Text(saveStatus, style = MaterialTheme.typography.bodySmall)

        Text("Router Profile", style = MaterialTheme.typography.titleSmall)
        Text("Vendor: ${profile.vendorName ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
        Text("Model: ${profile.modelName ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
        Text("Firmware: ${profile.firmwareVersion ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
        Text("Admin URL: ${profile.adminUrl ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
        Text("Auth: ${profile.authType.name}", style = MaterialTheme.typography.bodySmall)
        Text("Mode: ${profile.mode.name}", style = MaterialTheme.typography.bodySmall)
        Text(
          "Last Validated: ${profile.lastValidatedEpochMs?.toString() ?: "Never"}",
          style = MaterialTheme.typography.bodySmall
        )

        Text("Capabilities", style = MaterialTheme.typography.titleSmall)
        RouterCapability.entries.forEach { cap ->
          val enabled = profile.capabilities.contains(cap)
          Text(
            (if (enabled) "[x] " else "[ ] ") + cap.name,
            style = MaterialTheme.typography.bodySmall
          )
        }

        Text("Router Actions (gated)", style = MaterialTheme.typography.titleSmall)
        CapabilityActionRow(
          label = "Flush DNS",
          enabled = profile.capabilities.contains(RouterCapability.DNS_FLUSH),
          mode = profile.mode
        )
        CapabilityActionRow(
          label = "Firewall Toggle",
          enabled = profile.capabilities.contains(RouterCapability.FIREWALL_TOGGLE),
          mode = profile.mode
        )
        CapabilityActionRow(
          label = "VPN Toggle",
          enabled = profile.capabilities.contains(RouterCapability.VPN_TOGGLE),
          mode = profile.mode
        )
        CapabilityActionRow(
          label = "QoS Configure",
          enabled = profile.capabilities.contains(RouterCapability.QOS_CONFIG),
          mode = profile.mode
        )
        CapabilityActionRow(
          label = "Guest Wi-Fi",
          enabled = profile.capabilities.contains(RouterCapability.GUEST_WIFI_TOGGLE),
          mode = profile.mode
        )
        CapabilityActionRow(
          label = "Reboot Router",
          enabled = profile.capabilities.contains(RouterCapability.REBOOT),
          mode = profile.mode
        )
      }
    }
  }
}

@Composable
private fun CapabilityActionRow(label: String, enabled: Boolean, mode: RouterAccessMode) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    OutlinedButton(onClick = {}, enabled = enabled && mode == RouterAccessMode.READ_WRITE) {
      Text(label)
    }
    val reason = if (enabled && mode == RouterAccessMode.READ_WRITE) {
      "Supported"
    } else {
      "No verified endpoint (READ_ONLY)"
    }
    Text(reason, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun colorSwatch(label: String, color: Color) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Box(
      modifier = Modifier
        .size(30.dp)
        .background(color, RoundedCornerShape(6.dp))
    )
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
}

private fun themePalette(themeId: ThemeId): ThemePalette {
  return when (themeId) {
    ThemeId.NERF_MAIN_DASH_HTML -> ThemePalette(
      primary = Color(0xFFFF6A00),
      accent = Color(0xFF00C2FF),
      highlight = Color(0xFFFFD400),
      panel = Color(0xFF10151A),
      text = Color(0xFFEAF2F8)
    )

    ThemeId.NERF_HUD_ALT_HTML -> ThemePalette(
      primary = Color(0xFFFFE600),
      accent = Color(0xFF00A3FF),
      highlight = Color(0xFFFF8F00),
      panel = Color(0xFF10151A),
      text = Color(0xFFEAF2F8)
    )
  }
}
