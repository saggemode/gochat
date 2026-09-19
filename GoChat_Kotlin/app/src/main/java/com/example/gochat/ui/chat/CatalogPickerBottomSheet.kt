package com.example.gochat.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.core.media.MediaImageHelper
import com.example.gochat.data.model.Product
import com.example.gochat.data.repository.MarketplaceRepository
import com.example.gochat.databinding.BottomSheetCatalogPickerBinding
import com.example.gochat.databinding.ItemCatalogPickerSelectableBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class CatalogPickerBottomSheet(
    private val onCatalogPicked: (List<Product>) -> Unit
) : BottomSheetDialogFragment() {

    @Inject
    lateinit var repository: MarketplaceRepository

    private var _binding: BottomSheetCatalogPickerBinding? = null
    private val binding get() = _binding!!

    private var allProducts: List<Product> = emptyList()
    private var displayedProducts: List<Product> = emptyList()
    private val selectedProductIds = mutableSetOf<String>()
    private lateinit var adapter: SelectableProductAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCatalogPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        loadProducts()
    }

    private fun setupUI() {
        adapter = SelectableProductAdapter()
        binding.rvCatalogPickerProducts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCatalogPickerProducts.adapter = adapter

        binding.btnCloseCatalogPicker.setOnClickListener {
            dismiss()
        }

        binding.etSearchCatalogProducts.doAfterTextChanged { text ->
            filterProducts(text?.toString().orEmpty())
        }

        binding.btnQuickSelectAll.setOnClickListener {
            selectedProductIds.clear()
            val toSelect = displayedProducts.take(6)
            toSelect.forEach { selectedProductIds.add(it.id) }
            adapter.notifyDataSetChanged()
            updateSendButton()
        }

        binding.btnSendCatalogGrid.setOnClickListener {
            val count = selectedProductIds.size
            if (count < 2) {
                Toast.makeText(requireContext(), "Please select at least 4 products (or at least 2) for a catalog grid", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedList = allProducts.filter { selectedProductIds.contains(it.id) }
            dismiss()
            onCatalogPicked(selectedList)
        }

        updateSendButton()
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            binding.progressBarCatalog.visibility = View.VISIBLE
            // Only load the current user's own products — never show other merchants' products
            val myResult = repository.getMyProducts()
            val myProducts = myResult.getOrNull().orEmpty()
            binding.progressBarCatalog.visibility = View.GONE

            if (myProducts.isNotEmpty()) {
                allProducts = myProducts
                displayedProducts = myProducts
                // Auto-select first 4-6 by default for instant convenience
                myProducts.take(6).forEach { selectedProductIds.add(it.id) }
                adapter.notifyDataSetChanged()
                updateSendButton()
                binding.tvEmptyCatalog.visibility = View.GONE
            } else {
                allProducts = emptyList()
                displayedProducts = emptyList()
                adapter.notifyDataSetChanged()
                updateSendButton()
                binding.tvEmptyCatalog.text = "You don't have any products yet.\nAdd products to your store first."
                binding.tvEmptyCatalog.visibility = View.VISIBLE
            }
        }
    }

    private fun filterProducts(query: String) {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        displayedProducts = if (trimmed.isEmpty()) {
            allProducts
        } else {
            allProducts.filter {
                it.displayTitle.lowercase(Locale.ROOT).contains(trimmed) ||
                        it.category.lowercase(Locale.ROOT).contains(trimmed)
            }
        }
        adapter.notifyDataSetChanged()
        binding.tvEmptyCatalog.visibility = if (displayedProducts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateSendButton() {
        val count = selectedProductIds.size
        binding.btnSendCatalogGrid.text = if (count > 0) {
            "Send Catalog ($count selected)"
        } else {
            "Select 4 to 8 Products"
        }
        binding.btnSendCatalogGrid.isEnabled = count in 1..8
        binding.tvSelectionSubtitle.text = "Selected $count of 8 products (ideal: 4–8)"
    }

    inner class SelectableProductAdapter : RecyclerView.Adapter<SelectableProductAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemCatalogPickerSelectableBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemCatalogPickerSelectableBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val product = displayedProducts[position]
            val isSelected = selectedProductIds.contains(product.id)

            holder.itemBinding.tvProductTitle.text = product.displayTitle
            holder.itemBinding.tvProductPrice.text = String.format(Locale.US, "$%.2f", product.price)
            holder.itemBinding.tvProductCategory.text = "• ${product.category.ifBlank { "General" }}"
            holder.itemBinding.cbSelectProduct.isChecked = isSelected

            MediaImageHelper.loadSafeImage(
                imageView = holder.itemBinding.ivProductThumb,
                url = product.primaryImage,
                cornerRadiusDp = 6f
            )

            holder.itemBinding.root.setOnClickListener {
                if (isSelected) {
                    selectedProductIds.remove(product.id)
                } else {
                    if (selectedProductIds.size >= 8) {
                        Toast.makeText(requireContext(), "Maximum 8 products per catalog grid", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    selectedProductIds.add(product.id)
                }
                notifyItemChanged(position)
                updateSendButton()
            }
        }

        override fun getItemCount(): Int = displayedProducts.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
