package com.nerf.netx.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.nerf.netx.data.RouterCredentialsStore
import androidx.navigation.compose.*
import com.nerf.netx.data.ThemeRepository
import com.nerf.netx.domain.AppServices
import com.nerf.netx.ui.nav.BottomNav
import com.nerf.netx.ui.nav.Routes
import com.nerf.netx.ui.screens.*
import com.nerf.netx.ui.theme.NerfTheme

@Composable
fun AppRoot(
  services: AppServices,
  themeRepository: ThemeRepository,
  credentialsStore: RouterCredentialsStore
) {
  val themeId by themeRepository.selected.collectAsState()

  NerfTheme(themeId) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
      bottomBar = { BottomNav(currentRoute = route, onNavigate = { r ->
        nav.navigate(r) {
          popUpTo(nav.graph.startDestinationId) { saveState = true }
          launchSingleTop = true
          restoreState = true
        }
      }) }
    ) { padding ->
      NavHost(navController = nav, startDestination = Routes.SPEED, modifier = Modifier.padding(padding)) {
        composable(Routes.SPEED) { SpeedtestScreen(services.speedtest) }
        composable(Routes.MAP) { MapScreen(services.topology, services.deviceControl) }
        composable(Routes.DEVICES) { DevicesScreen(services.devices, services.deviceControl) }
        composable(Routes.ANALYTICS) { AnalyticsScreen(services.analytics) }
        composable(Routes.SETTINGS) {
          SettingsScreen(
            themeId = themeId,
            onThemeSelected = themeRepository::set,
            htmlAssetUrlProvider = themeRepository::htmlAssetUrl,
            credentialsStore = credentialsStore
          )
        }
        composable(Routes.PREVIEW) { PreviewScreen(themeId, themeRepository, services) }
      }
    }
  }
}
