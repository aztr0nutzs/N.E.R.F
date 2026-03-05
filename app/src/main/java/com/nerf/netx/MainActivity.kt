package com.nerf.netx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nerf.netx.data.FakeServices
import com.nerf.netx.data.ThemeRepositoryImpl
import com.nerf.netx.ui.AppRoot

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val services = FakeServices()
    val themeRepo = ThemeRepositoryImpl(applicationContext)
    setContent {
      AppRoot(services = services, themeRepository = themeRepo)
    }
  }
}
