package com.example.gochat.ui.marketplace

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts the Paystack checkout in a WebView. We don't trust the WebView to decide
 * success — the caller re-verifies the reference server-side on return. This
 * screen just lets the user complete the Paystack flow and detects when the
 * checkout reaches our callback URL (or the user backs out).
 */
class PaystackPaymentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTHORIZATION_URL = "authorization_url"
        const val EXTRA_REFERENCE = "reference"
        const val RESULT_REFERENCE = "result_reference"
        private const val CALLBACK_PREFIX = "https://gochat.app/paystack/callback"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authUrl = intent.getStringExtra(EXTRA_AUTHORIZATION_URL)
        val reference = intent.getStringExtra(EXTRA_REFERENCE).orEmpty()
        if (authUrl.isNullOrBlank()) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val progress = ProgressBar(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.CENTER
            layoutParams = lp
        }
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        root.addView(webView)
        root.addView(progress)
        setContentView(root)

        fun finishWithReference() {
            setResult(Activity.RESULT_OK, intent.putExtra(RESULT_REFERENCE, reference))
            finish()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (url.startsWith(CALLBACK_PREFIX)) {
                    finishWithReference()
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = android.view.View.GONE
                if (url != null && url.startsWith(CALLBACK_PREFIX)) finishWithReference()
            }
        }

        webView.loadUrl(authUrl)
    }

    override fun onBackPressed() {
        // Backing out still returns the reference so the caller verifies whether
        // the payment went through before the user left.
        setResult(Activity.RESULT_OK, intent.putExtra(RESULT_REFERENCE, intent.getStringExtra(EXTRA_REFERENCE).orEmpty()))
        super.onBackPressed()
    }
}
