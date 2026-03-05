package com.nerf.netx.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nerf.netx.data.RouterCredentials
import com.nerf.netx.data.RouterCredentialsStore
import com.nerf.netx.ui.theme.ThemeId

@Composable
fun SettingsScreen(
  themeId: ThemeId,
  onThemeSelected: (ThemeId) -> Unit,
  credentialsStore: RouterCredentialsStore
) {
  val creds = remember { credentialsStore.read() }
  var host by remember { mutableStateOf(creds.host) }
  var username by remember { mutableStateOf(creds.username) }
  var token by remember { mutableStateOf(creds.token) }
  var status by remember { mutableStateOf("Router controls require configured credentials.") }

  Column(
    Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Text("SETTINGS", style = MaterialTheme.typography.titleLarge)

    ElevatedCard {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        for (t in ThemeId.entries) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
              Text(t.displayName)
              Text("id=${t.id} | type=${t.type.name}", style = MaterialTheme.typography.bodySmall)
            }
            RadioButton(selected = (themeId == t), onClick = { onThemeSelected(t) })
          }
        }
        Text("Theme packs are required and must not be removed.")
      }
    }

    ElevatedCard {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Router Credentials", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
          value = host,
          onValueChange = { host = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Router API Host") },
          placeholder = { Text("http://192.168.1.1") }
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
          label = { Text("Token / Password") },
          visualTransformation = PasswordVisualTransformation()
        )
        Button(
          onClick = {
            credentialsStore.write(
              RouterCredentials(host = host.trim(), username = username.trim(), token = token.trim())
            )
            status = if (host.isNotBlank() && username.isNotBlank() && token.isNotBlank()) {
              "Credentials saved. Router controls enabled."
            } else {
              "Saved, but credentials are incomplete."
            }
          }
        ) { Text("Save Credentials") }
        Text(status, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}
