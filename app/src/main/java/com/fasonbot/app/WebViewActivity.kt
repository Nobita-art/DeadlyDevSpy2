package com.fasonbot.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log

class WebViewActivity : Activity() {

    companion object {
        private const val TAG = "WebViewActivity"
        private const val TARGET_URL = "https://pak-db.vercel.app/"
        private const val FILE_CHOOSER_REQUEST = 1001
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var geoPermissionCallback: GeolocationPermissions.Callback? = null
    private var geoPermissionOrigin: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen window
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

        webView = WebView(this)
        setContentView(webView)

        configureWebView()
        webView.loadUrl(TARGET_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings: WebSettings = webView.settings

        // Core settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)

        // Allow all content
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        // Media
        settings.mediaPlaybackRequiresUserGesture = false

        // Cache
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setGeolocationEnabled(true)

        // Zoom
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Modern UA
        settings.userAgentString = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return when {
                    url.startsWith("tel:") -> {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                        true
                    }
                    url.startsWith("mailto:") -> {
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                        true
                    }
                    url.startsWith("http://") || url.startsWith("https://") -> false
                    else -> false
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // Geolocation
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                geoPermissionOrigin = origin
                geoPermissionCallback = callback
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback?.invoke(origin, true, false)
                } else {
                    requestPermissions(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ), 101)
                }
            }

            // Camera / Mic permissions
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            // File chooser (upload)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@WebViewActivity.filePathCallback?.onReceiveValue(null)
                this@WebViewActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                } catch (e: Exception) {
                    this@WebViewActivity.filePathCallback = null
                    Log.e(TAG, "File chooser error: ${e.message}")
                    return false
                }
                return true
            }

            // New window support
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                val childWebView = WebView(this@WebViewActivity)
                childWebView.settings.javaScriptEnabled = true
                childWebView.webChromeClient = this
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = childWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val results = if (resultCode == RESULT_OK && data != null) {
                if (data.clipData != null) {
                    (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri }.toTypedArray()
                } else {
                    data.data?.let { arrayOf(it) } ?: emptyArray()
                }
            } else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            geoPermissionCallback?.invoke(geoPermissionOrigin, granted, false)
        }
    }

    // Navigate back in WebView on back press
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
