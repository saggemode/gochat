package com.example.gochat.ui.marketplace

import com.example.gochat.R

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.data.repository.MarketplaceRepository
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dedicated "My Products" page: the full list of the seller's products with a
 * top Add Product button and per-item edit/delete. Add/edit reuse the shared
 * [ProductEditor] (same bottom sheet as the Seller Hub), so there's one editor
 * implementation. The list is reactive off MarketplaceViewModel.myProducts.
 */
@AndroidEntryPoint
class MyProductsActivity : AppCompatActivity() {

    @Inject
    lateinit var repository: MarketplaceRepository

    private val viewModel: MarketplaceViewModel by viewModels()

    private lateinit var adapter: StoreProductAdapter
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar

    // Bridges the multi-image picker to the shared ProductEditor.
    private var onProductImagesPicked: ((List<Uri>) -> Unit)? = null
    private val pickProductImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> -> onProductImagesPicked?.invoke(uris) }

    private val productEditor: ProductEditor by lazy {
        ProductEditor(object : ProductEditor.Host {
            override val ctx = this@MyProductsActivity
            override val inflater: LayoutInflater get() = layoutInflater
            override val scope: CoroutineScope get() = lifecycleScope
            override val vm: MarketplaceViewModel get() = viewModel
            override var onProductImagesPicked: ((List<Uri>) -> Unit)?
                get() = this@MyProductsActivity.onProductImagesPicked
                set(value) { this@MyProductsActivity.onProductImagesPicked = value }
            override fun launchProductImagePicker() { pickProductImagesLauncher.launch("image/*") }
        })
    }

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.my_products_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }

        root.addView(MaterialButton(this).apply {
            text = getString(R.string.btn_add_product)
            isAllCaps = false
            setOnClickListener { productEditor.showAdd() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        })

        progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) }
        }
        root.addView(progress)

        emptyView = TextView(this).apply {
            text = getString(R.string.my_products_empty)
            setTextColor(resources.getColor(R.color.gochat_text_muted, theme))
            textSize = 14f
            visibility = View.GONE
        }
        root.addView(emptyView)

        adapter = StoreProductAdapter(
            onClick = { product ->
                startActivity(Intent(this, ProductDetailsActivity::class.java).apply {
                    putExtra("product_id", product.id)
                })
            },
            onEdit = { product -> productEditor.showEdit(product) },
            onDelete = { product -> confirmDelete(product) }
        )
        root.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MyProductsActivity)
            adapter = this@MyProductsActivity.adapter
            isNestedScrollingEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        setContentView(root)

        lifecycleScope.launch {
            viewModel.myProducts.collectLatest { products ->
                progress.visibility = View.GONE
                adapter.submitList(products)
                emptyView.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        viewModel.loadMyStore()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun confirmDelete(product: com.example.gochat.data.model.Product) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.coupon_action_delete))
            .setMessage(getString(R.string.my_products_delete_confirm, product.name))
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.coupon_action_delete)) { _, _ ->
                // deleteProduct already reloads the store list on completion.
                viewModel.deleteProduct(product.id)
                Toast.makeText(this, getString(R.string.my_products_deleted), Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
