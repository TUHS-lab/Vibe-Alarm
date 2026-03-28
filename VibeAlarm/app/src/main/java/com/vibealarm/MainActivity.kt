package com.vibealarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.gson.Gson
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val gson = Gson()
    private var pageLoaded = false
    private var pendingAlarmId: String? = null
    private var hydratedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        setContentView(R.layout.activity_main)
        setupContentInsetsNoPadding()
        webView = findViewById(R.id.webview)
        setupWebView()
        captureIntentExtras(intent)
        requestNonNotificationRuntimeSettings()
        loadAssetPage()
    }

    override fun onResume() {
        super.onResume()
        if (pageLoaded) {
            webView.evaluateJavascript(
                "try{if(typeof S!=='undefined'&&S.screen==='dashboard'&&typeof rerender==='function')rerender('dashboard');}catch(e){}",
                null,
            )
        }
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }

    /** Не добавляем padding на контейнер — safe-area только в CSS WebView (env). */
    private fun setupContentInsetsNoPadding() {
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            view.setPadding(0, 0, 0, 0)
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureIntentExtras(intent)
        if (pageLoaded) {
            injectAlarmJavascript()
        }
    }

    private fun captureIntentExtras(intent: Intent?) {
        val id = intent?.getStringExtra(AlarmConstants.EXTRA_ALARM_ID)
        if (id != null) {
            pendingAlarmId = id
        }
    }

    private fun setupWebView() {
        val bridge = AlarmBridge(this) {
            runOnUiThread { }
        }
        webView.addJavascriptInterface(bridge, "Android")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                pageLoaded = true
                injectPersistedStateFromNative()
                injectAlarmJavascript()
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            // Slightly larger default text on phones where UI felt small
            textZoom = 108
        }
    }

    private fun loadAssetPage() {
        hydratedOnce = false
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun injectPersistedStateFromNative() {
        if (hydratedOnce) return
        val json = AlarmPreferences(this).getLastPayloadJson()
        if (json.isNullOrBlank()) return
        hydratedOnce = true
        val quoted = JSONObject.quote(json)
        webView.evaluateJavascript(
            "(function(j){try{var p=JSON.parse(j);if(typeof S==='undefined')return;" +
                "if(p.alarms)S.alarms=p.alarms;if(p.history)S.history=p.history;" +
                "if(p.settings)Object.assign(S.settings,p.settings||{});" +
                "if(typeof p.onboarded==='boolean')S.onboarded=p.onboarded;" +
                "if(p.permissions)S.permissions=Object.assign(S.permissions||{},p.permissions||{});" +
                "if(typeof hydrateNotificationPermission==='function')hydrateNotificationPermission();" +
                "if(typeof render==='function')render(S.screen);" +
                "if(typeof startDashboardClockTimer==='function')startDashboardClockTimer();" +
                "}catch(e){}})($quoted)",
            null,
        )
    }

    private fun injectAlarmJavascript() {
        val id = pendingAlarmId ?: return
        pendingAlarmId = null
        val quoted = JSONObject.quote(id)
        webView.evaluateJavascript("showNativeAlarm($quoted)", null)

        val prefs = AlarmPreferences(this)
        val payload = try {
            gson.fromJson(prefs.getLastPayloadJson(), AlarmPayload::class.java)
        } catch (_: Exception) {
            null
        }
        val alarm = payload?.alarms?.find { it.id == id }
        if (alarm?.scheduleType == "once") {
            webView.evaluateJavascript("syncFromNativeOnce($quoted)", null)
        }
    }

    /** Exact alarm + full-screen intent only — POST_NOTIFICATIONS is requested from Configuration (JS → bridge). */
    private fun requestNonNotificationRuntimeSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(AlarmManager::class.java)
            if (am != null && !am.canScheduleExactAlarms()) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:$packageName")
                        },
                    )
                } catch (_: Exception) {
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && !nm.canUseFullScreenIntent()) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = Uri.parse("package:$packageName")
                        },
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != AlarmConstants.REQ_POST_NOTIFICATIONS || grantResults.isEmpty()) return
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            AlarmPreferences(this).setPostNotificationsGranted(true)
            notifyWebPermissionResult(granted = true)
        } else {
            notifyWebPermissionResult(granted = false)
        }
    }

    /** Called from [AlarmBridge] when permission is already granted (API &lt; 33 or pre-granted). */
    fun notifyWebPermissionResult(granted: Boolean) {
        val fn = if (granted) "onPermissionGranted" else "onPermissionDenied"
        webView.evaluateJavascript(
            "try{if(typeof $fn==='function')$fn();}catch(e){}",
            null,
        )
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

}
