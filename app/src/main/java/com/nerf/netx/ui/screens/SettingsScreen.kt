package com.nerf.netx.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.nerf.netx.data.RouterCredentials
import com.nerf.netx.data.RouterCredentialsStore
import com.nerf.netx.ui.theme.ThemeId
import com.nerf.netx.ui.theme.ThemeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Base64

private enum class RouterValidationState {
  VALIDATED,
  REACHABLE,
  UNREACHABLE,
  INVALID_INPUT,
  ERROR
}

private data class RouterValidationResult(
  val state: RouterValidationState,
  val message: String
)

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
  credentialsStore: RouterCredentialsStore
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
  var saveStatus by remember {
    mutableStateOf("Router write-actions remain NOT_SUPPORTED until vendor API integration exists.")
  }
  var validationResult by remember { mutableStateOf<RouterValidationResult?>(null) }
  var validating by remember { mutableStateOf(false) }

  val appliedTheme = themeId
  val previewUrl = htmlAssetUrlProvider(previewTheme)
  val palette = themePalette(previewTheme)

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
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Text("Applied: ${appliedTheme.displayName}", style = MaterialTheme.typography.bodySmall)
        Text("Previewing: ${previewTheme.displayName}", style = MaterialTheme.typography.bodySmall)

        ThemeId.entries.forEach { t ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
              Text(t.displayName)
              Text("id=${t.id} | type=${t.type.name}", style = MaterialTheme.typography.bodySmall)
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
      }
    }

    ElevatedCard {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Theme Preview", style = MaterialTheme.typography.titleMedium)

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
          onValueChange = {
            host = it
            validationResult = null
          },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Router IP / URL") },
          placeholder = { Text("192.168.1.1 or http://192.168.1.1") }
        )
        OutlinedTextField(
          value = username,
          onValueChange = {
            username = it
            validationResult = null
          },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Username") }
        )
        OutlinedTextField(
          value = token,
          onValueChange = {
            token = it
            validationResult = null
          },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Token / Password") },
          visualTransformation = PasswordVisualTransformation()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = {
            credentialsStore.write(
              RouterCredentials(host = host.trim(), username = username.trim(), token = token.trim())
            )
            saveStatus = if (host.isNotBlank() && username.isNotBlank() && token.isNotBlank()) {
              "Credentials saved. Read-only checks can run; write-actions remain NOT_SUPPORTED."
            } else {
              "Saved, but credentials are incomplete."
            }
          }) {
            Text("Save Credentials")
          }

          OutlinedButton(
            enabled = !validating,
            onClick = {
              scope.launch {
                validating = true
                validationResult = validateRouterCredentials(host.trim(), username.trim(), token.trim())
                validating = false
              }
            }
          ) {
            Text(if (validating) "Validating..." else "Validate Credentials")
          }
        }

        Text(saveStatus, style = MaterialTheme.typography.bodySmall)
        validationResult?.let {
          Text("Validation: ${it.state} - ${it.message}", style = MaterialTheme.typography.bodySmall)
        }
      }
    }
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

private suspend fun validateRouterCredentials(
  hostInput: String,
  username: String,
  token: String
): RouterValidationResult = withContext(Dispatchers.IO) {
  if (hostInput.isBlank() || username.isBlank() || token.isBlank()) {
    return@withContext RouterValidationResult(
      RouterValidationState.INVALID_INPUT,
      "Host, username, and password/token are required."
    )
  }

  val target = parseHostAndPorts(hostInput)
    ?: return@withContext RouterValidationResult(
      RouterValidationState.INVALID_INPUT,
      "Host format invalid. Use IP, host, or http(s)://host[:port][/path]."
    )

  val reachablePort = target.ports.firstOrNull { port ->
    runCatching {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(target.host, port), 700)
      }
      true
    }.getOrDefault(false)
  }

  if (reachablePort == null) {
    return@withContext RouterValidationResult(
      RouterValidationState.UNREACHABLE,
      "Router not reachable on ports 443/80 within timeout."
    )
  }

  val urlForAuth = target.urlForAuth
  if (urlForAuth == null) {
    return@withContext RouterValidationResult(
      RouterValidationState.REACHABLE,
      "Host reachable on port $reachablePort; auth not verifiable without vendor-specific endpoint."
    )
  }

  runCatching {
    val url = URL(urlForAuth)
    val conn = (url.openConnection() as HttpURLConnection)
    conn.connectTimeout = 1200
    conn.readTimeout = 1200
    conn.requestMethod = "GET"
    val basic = Base64.getEncoder().encodeToString("$username:$token".toByteArray())
    conn.setRequestProperty("Authorization", "Basic $basic")
    conn.instanceFollowRedirects = false

    val code = conn.responseCode
    conn.disconnect()

    when {
      code in 200..299 -> RouterValidationResult(
        RouterValidationState.VALIDATED,
        "Credentials validated against endpoint response $code."
      )

      code == 401 || code == 403 -> RouterValidationResult(
        RouterValidationState.REACHABLE,
        "Router reachable but credentials not verified (HTTP $code)."
      )

      else -> RouterValidationResult(
        RouterValidationState.REACHABLE,
        "Router reachable; auth endpoint returned HTTP $code (not verifiable)."
      )
    }
  }.getOrElse { err ->
    RouterValidationResult(
      RouterValidationState.ERROR,
      "Validation error: ${err.message ?: "unknown"}"
    )
  }
}

private data class ValidationTarget(
  val host: String,
  val ports: List<Int>,
  val urlForAuth: String?
)

private fun parseHostAndPorts(raw: String): ValidationTarget? {
  val trimmed = raw.trim()
  if (trimmed.isBlank()) return null

  return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
    val uri = Uri.parse(trimmed)
    val host = uri.host ?: return null
    val uriPort = if (uri.port > 0) uri.port else null
    val ports = listOfNotNull(uriPort, 443, 80).distinct().take(3)
    val path = uri.path ?: ""
    val hasEndpointPath = path.isNotBlank() && path != "/"
    ValidationTarget(
      host = host,
      ports = ports,
      urlForAuth = if (hasEndpointPath) trimmed else null
    )
  } else {
    val withScheme = if (":" in trimmed) "http://$trimmed" else "http://$trimmed"
    val uri = Uri.parse(withScheme)
    val host = uri.host ?: return null
    val uriPort = if (uri.port > 0) uri.port else null
    val ports = listOfNotNull(uriPort, 443, 80).distinct().take(3)
    ValidationTarget(
      host = host,
      ports = ports,
      urlForAuth = null
    )
  }
}
