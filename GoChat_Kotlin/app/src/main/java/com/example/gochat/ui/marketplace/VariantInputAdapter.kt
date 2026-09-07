package com.example.gochat.ui.marketplace

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gochat.data.model.ProductVariant
import com.example.gochat.databinding.ItemVariantInputBinding
import org.json.JSONObject

class VariantInputAdapter : RecyclerView.Adapter<VariantInputAdapter.VariantViewHolder>() {

    private val variants = mutableListOf<ProductVariant>()

    fun addVariant() {
        variants.add(ProductVariant())
        notifyItemInserted(variants.size - 1)
    }

    fun getVariants(): List<ProductVariant> = variants

    fun setVariants(newVariants: List<ProductVariant>) {
        variants.clear()
        variants.addAll(newVariants)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VariantViewHolder {
        val binding = ItemVariantInputBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VariantViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VariantViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun getItemCount(): Int = variants.size

    inner class VariantViewHolder(private val binding: ItemVariantInputBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(position: Int) {
            val variant = variants[position]
            
            // Parse attributes from JSON if existing
            val attrs = try { JSONObject(variant.attributesJson) } catch(_: Exception) { JSONObject() }
            
            binding.etVariantSize.setText(attrs.optString("size"))
            binding.etVariantColor.setText(attrs.optString("color"))
            binding.etVariantStock.setText(variant.stockQuantity.toString())

            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val currentPos = adapterPosition
                    if (currentPos != RecyclerView.NO_POSITION) {
                        val size = binding.etVariantSize.text.toString()
                        val color = binding.etVariantColor.text.toString()
                        val stock = binding.etVariantStock.text.toString().toIntOrNull() ?: 0
                        
                        val newAttrs = JSONObject().apply {
                            put("size", size)
                            put("color", color)
                        }
                        
                        variants[currentPos] = variants[currentPos].copy(
                            attributesJson = newAttrs.toString(),
                            stockQuantity = stock,
                            title = "${if (size.isNotBlank()) size else ""} ${if (color.isNotBlank()) color else ""}".trim()
                        )
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            }

            binding.etVariantSize.addTextChangedListener(textWatcher)
            binding.etVariantColor.addTextChangedListener(textWatcher)
            binding.etVariantStock.addTextChangedListener(textWatcher)

            binding.btnRemoveVariant.setOnClickListener {
                val currentPos = adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    variants.removeAt(currentPos)
                    notifyItemRemoved(currentPos)
                }
            }
        }
    }
}
