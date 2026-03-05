# Keep JavascriptInterface entry points used by WebView bridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep bridge class and method names used from JavaScript.
-keep class com.nerf.netx.ui.screens.NerfWebBridge { *; }

# Keep domain contracts referenced by bridge payload wiring.
-keep class com.nerf.netx.domain.ActionResult { *; }
-keep class com.nerf.netx.domain.RouterInfoResult { *; }
