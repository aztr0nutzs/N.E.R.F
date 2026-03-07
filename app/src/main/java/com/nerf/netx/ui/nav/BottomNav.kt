package com.nerf.netx.ui.nav

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

@Composable
fun BottomNav(currentRoute: String?, onNavigate: (String) -> Unit) {
  NavigationBar {
    NavigationBarItem(selected = currentRoute == Routes.SPEED, onClick = { onNavigate(Routes.SPEED) },
      icon = { Icon(Icons.Filled.Speed, null) }, label = { Text("Speed") })
    NavigationBarItem(selected = currentRoute == Routes.MAP, onClick = { onNavigate(Routes.MAP) },
      icon = { Icon(Icons.Filled.Public, null) }, label = { Text("Map") })
    NavigationBarItem(selected = currentRoute == Routes.DEVICES, onClick = { onNavigate(Routes.DEVICES) },
      icon = { Icon(Icons.Filled.Devices, null) }, label = { Text("Devices") })
    NavigationBarItem(selected = currentRoute == Routes.ANALYTICS, onClick = { onNavigate(Routes.ANALYTICS) },
      icon = { Icon(Icons.Filled.QueryStats, null) }, label = { Text("Analytics") })
    NavigationBarItem(selected = currentRoute == Routes.SETTINGS, onClick = { onNavigate(Routes.SETTINGS) },
      icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
  }
}
