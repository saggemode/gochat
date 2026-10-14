package com.example.gochat.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Product
import com.example.gochat.data.model.ProductVariant
import com.example.gochat.databinding.BottomSheetQuickCheckoutBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import com.example.gochat.core.currency.CurrencyManager
import java.util.Locale

@AndroidEntryPoint
class QuickAddBottomSheet(
    private val product: Product,
    private val onAddToCart: (product: Product, quantity: Int, selectedVariant: ProductVariant?) -> Unit,
    private val onDirectCheckout: (product: Product, quantity: Int, selectedVariant: ProductVariant?) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetQuickCheckoutBinding? = null
    private val binding get() = _binding!!

    private var quantity: Int = 1
    private var selectedVariant: ProductVariant? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetQuickCheckoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindProductData()
        setupListeners()
    }

    private fun bindProductData() {
        binding.tvQuickProductName.text = product.displayTitle
        binding.tvQuickProductPrice.text = CurrencyManager.formatPrice(product.price, product.currency)
        binding.tvQuickStockBadge.text = if (product.stock > 0) "In Stock (${product.stock} available)" else "Out of Stock"

        MediaImageHelper.loadSafeImage(
            imageView = binding.ivQuickProductImage,
            url = product.primaryImage,
            cornerRadiusDp = 8f
        )

        // Setup variants if present
        val variants = product.variants
        if (variants.isNotEmpty()) {
            binding.layoutVariantSection.visibility = View.VISIBLE
            binding.chipGroupVariants.removeAllViews()

            variants.forEachIndexed { index, variant ->
                val chip = Chip(requireContext()).apply {
                    text = variant.title.ifBlank { variant.sku.ifBlank { "Option ${index + 1}" } }
                    isCheckable = true
                    id = View.generateViewId()
                }
                binding.chipGroupVariants.addView(chip)

                if (index == 0) {
                    chip.isChecked = true
                    selectedVariant = variant
                }
            }

            binding.chipGroupVariants.setOnCheckedStateChangeListener { group, checkedIds ->
                if (checkedIds.isNotEmpty()) {
                    val checkedIndex = group.indexOfChild(group.findViewById(checkedIds[0]))
                    if (checkedIndex in variants.indices) {
                        selectedVariant = variants[checkedIndex]
                    }
                }
            }
        } else {
            binding.layoutVariantSection.visibility = View.GONE
        }

        updateQuantityDisplay()
    }

    private fun setupListeners() {
        binding.btnCloseQuickCheckout.setOnClickListener {
            dismiss()
        }

        binding.btnDecreaseQty.setOnClickListener {
            if (quantity > 1) {
                quantity--
                updateQuantityDisplay()
            }
        }

        binding.btnIncreaseQty.setOnClickListener {
            val maxStock = if (product.stock > 0) product.stock else 99
            if (quantity < maxStock) {
                quantity++
                updateQuantityDisplay()
            }
        }

        binding.btnSheetAddToCart.setOnClickListener {
            onAddToCart(product, quantity, selectedVariant)
            dismiss()
        }

        binding.btnSheetDirectCheckout.setOnClickListener {
            onDirectCheckout(product, quantity, selectedVariant)
            dismiss()
        }
    }

    private fun updateQuantityDisplay() {
        binding.tvQuantityCount.text = quantity.toString()
        binding.btnDecreaseQty.isEnabled = quantity > 1
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
