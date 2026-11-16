package com.example.gochat.ui.miniapps

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gochat.data.model.MiniApp
import com.example.gochat.data.repository.MiniAppRepository
import com.example.gochat.databinding.ActivityMiniappsBinding
import com.example.gochat.databinding.ItemMiniappBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import javax.inject.Inject

/** Browse mini-apps and launch one in a WebView host. */
@AndroidEntryPoint
class MiniAppsActivity : AppCompatActivity() {

    @Inject
    lateinit var miniAppRepository: MiniAppRepository

    private lateinit var binding: ActivityMiniappsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMiniappsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackMiniApps.setOnClickListener { finish() }
        binding.swipeMiniApps.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        binding.swipeMiniApps.isRefreshing = true
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { miniAppRepository.list().getOrNull() ?: emptyList() }
            render(apps)
            binding.swipeMiniApps.isRefreshing = false
        }
    }

    private fun render(apps: List<MiniApp>) {
        binding.containerMiniApps.removeAllViews()
        if (apps.isEmpty()) {
            binding.containerMiniApps.addView(binding.tvMiniAppsEmpty)
            binding.tvMiniAppsEmpty.visibility = View.VISIBLE
            return
        }
        val nf = NumberFormat.getIntegerInstance()
        apps.forEach { app ->
            val item = ItemMiniappBinding.inflate(layoutInflater, binding.containerMiniApps, false)
            item.tvMiniAppName.text = app.name.ifBlank { "Mini-app" }
            if (app.description.isNotBlank()) item.tvMiniAppDesc.text = app.description else item.tvMiniAppDesc.visibility = View.GONE
            val cat = app.category.ifBlank { "App" }
            item.tvMiniAppMeta.text = "$cat · ${nf.format(app.installCount)} installs"
            item.root.setOnClickListener { launch(app) }
            binding.containerMiniApps.addView(item.root)
        }
    }

    private fun launch(app: MiniApp) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { miniAppRepository.launch(app.id) }
            result.onSuccess { session ->
                val url = session.manifestUrl.ifBlank { app.manifestUrl }
                if (url.isBlank()) {
                    Toast.makeText(this@MiniAppsActivity, "This mini-app has no entry URL", Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                startActivity(Intent(this@MiniAppsActivity, MiniAppHostActivity::class.java).apply {
                    putExtra(MiniAppHostActivity.EXTRA_URL, url)
                    putExtra(MiniAppHostActivity.EXTRA_NAME, app.name)
                })
            }.onFailure {
                Toast.makeText(this@MiniAppsActivity, it.message ?: "Failed to launch", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
