package io.github.raphaelbleier.glasshouse

import android.graphics.Color
import android.service.dreams.DreamService
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class WallboardDream : DreamService() {
    private var web: WebView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SystemSetup.apply(this)
        val url = Prefs(this).screensaverUrl
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                    // Server unreachable (e.g. right after boot): retry instead of showing an error page forever
                    if (req.isForMainFrame) v.postDelayed({ v.loadUrl(url) }, 30_000)
                }
            }
            loadUrl(url)
        }
        setContentView(web)
    }

    override fun onDetachedFromWindow() {
        web?.destroy()
        super.onDetachedFromWindow()
    }
}
