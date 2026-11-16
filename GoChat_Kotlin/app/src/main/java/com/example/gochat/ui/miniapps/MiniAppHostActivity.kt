package com.example.gochat.ui.miniapps

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.gochat.databinding.ActivityMiniappHostBinding

/** Hosts a mini-app's web content in a sandboxed WebView. */
class MiniAppHostActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_miniapp_url"
        const val EXTRA_NAME = "extra_miniapp_name"
    }

    private lateinit var binding: ActivityMiniappHostBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMiniappHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvMiniAppHostTitle.text = intent.getStringExtra(EXTRA_NAME) ?: "Mini-app"
        binding.btnCloseMiniApp.setOnClickListener { finish() }

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) { finish(); return }

        with(binding.webMiniApp.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Mini-apps are untrusted third-party content: keep file access off.
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        binding.webMiniApp.webViewClient = WebViewClient()
        binding.webMiniApp.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressMiniApp.progress = newProgress
                binding.progressMiniApp.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
        binding.webMiniApp.loadUrl(url)

        // In-app back navigates the WebView first.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webMiniApp.canGoBack()) binding.webMiniApp.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        binding.webMiniApp.destroy()
        super.onDestroy()
    }
}
