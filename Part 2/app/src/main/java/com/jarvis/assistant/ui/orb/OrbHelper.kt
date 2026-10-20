package com.jarvis.assistant.ui.orb

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jarvis.assistant.data.model.ConversationState

class OrbHelper(private val webView: WebView) {

    private var isLoaded = false
    private var pendingState: ConversationState? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoaded = true
                pendingState?.let {
                    updateState(it, 0.2f)
                    pendingState = null
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("OrbWebView", "${message?.message()} -- line ${message?.lineNumber()}")
                return true
            }
        }

        webView.loadUrl("file:///android_asset/orb/index.html")
    }

    fun updateState(state: ConversationState, audioLevel: Float = 0f) {
        if (!isLoaded) {
            pendingState = state
            return
        }
        val orbKey = state.orbKey
        val level = audioLevel.coerceIn(0f, 1f)
        val js = "javascript:if(window.setOrbState){window.setOrbState('$orbKey', $level);}"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    fun updateAudioLevel(level: Float) {
        if (!isLoaded) return
        val clamped = level.coerceIn(0f, 1f)
        val js = "javascript:if(window.setAudioLevel){window.setAudioLevel($clamped);}"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }
}
