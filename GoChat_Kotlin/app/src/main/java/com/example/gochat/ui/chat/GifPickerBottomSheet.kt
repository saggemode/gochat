package com.example.gochat.ui.chat

import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.load
import com.example.gochat.R
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.databinding.BottomSheetGifPickerBinding
import com.example.gochat.databinding.ItemGifBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request

class GifPickerBottomSheet(
    private val onGifSelected: (String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetGifPickerBinding? = null
    private val binding get() = _binding!!
    private lateinit var gifAdapter: GifAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetGifPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        gifAdapter = GifAdapter { onGifSelected(it); dismiss() }
        binding.rvGifs.adapter = gifAdapter
        
        binding.etSearchGif.doAfterTextChanged {
            searchGifs(it?.toString().orEmpty())
        }
        
        searchGifs("") // Initial trending
    }

    private fun searchGifs(query: String) {
        lifecycleScope.launch {
            val gifs = withContext(Dispatchers.IO) {
                fetchGifsFromGiphy(query)
            }
            gifAdapter.submitList(gifs)
        }
    }

    private fun fetchGifsFromGiphy(query: String): List<String> {
        return try {
            val apiKey = "dc6zaTOxFJmzC" // Public Beta Key
            val url = if (query.isBlank()) {
                "https://api.giphy.com/v1/gifs/trending?api_key=$apiKey&limit=20"
            } else {
                "https://api.giphy.com/v1/gifs/search?api_key=$apiKey&q=$query&limit=20"
            }
            
            val response = NetworkModule.getOkHttpClient(requireContext()).newCall(
                Request.Builder().url(url).build()
            ).execute()
            
            val body = response.body?.string() ?: return emptyList()
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
            val data = json["data"]?.jsonArray ?: return emptyList()
            
            data.map { 
                it.jsonObject["images"]?.jsonObject?.get("fixed_height")?.jsonObject?.get("url")?.jsonPrimitive?.content ?: ""
            }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class GifAdapter(private val onClick: (String) -> Unit) : ListAdapter<String, GifAdapter.GifViewHolder>(DiffCallback) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifViewHolder {
            return GifViewHolder(ItemGifBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: GifViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
        inner class GifViewHolder(private val itemBinding: ItemGifBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(url: String) {
                itemBinding.ivGif.load(url) {
                    crossfade(true)
                    decoderFactory(if (SDK_INT >= 28) ImageDecoderDecoder.Factory() else GifDecoder.Factory())
                }
                itemBinding.root.setOnClickListener { onClick(url) }
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }
}
