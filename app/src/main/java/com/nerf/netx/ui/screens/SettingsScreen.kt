package com.nerf.netx.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nerf.netx.ui.theme.ThemeId

@Composable
fun SettingsScreen(themeId: ThemeId, onThemeSelected: (ThemeId) -> Unit) {
  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("SETTINGS", style = MaterialTheme.typography.titleLarge)

    ElevatedCard {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        for (t in ThemeId.entries) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
              Text(t.displayName)
              Text("id=" + t.id + " | type=" + t.type.name, style = MaterialTheme.typography.bodySmall)
            }
            RadioButton(selected = (themeId == t), onClick = { onThemeSelected(t) })
          }
        }
        Text("Theme packs are required and must not be removed.")
      }
    }
  }
}
