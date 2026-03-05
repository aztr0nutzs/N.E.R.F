package com.nerf.netx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nerf.netx.data.HybridBackendGateway
import com.nerf.netx.data.RouterCredentialsStore
import com.nerf.netx.data.ThemeRepositoryImpl
import com.nerf.netx.ui.AppRoot

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    val credentialsStore = RouterCredentialsStore(applicationContext)
    val services = HybridBackendGateway(applicationContext, credentialsStore)
    val themeRepo = ThemeRepositoryImpl(applicationContext)
    setContent {
      AppRoot(services = services, themeRepository = themeRepo, credentialsStore = credentialsStore)
    }
  }
}
