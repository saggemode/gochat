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
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.load
import com.example.gochat.R
import com.example.gochat.data.model.GifItem
import com.example.gochat.data.repository.MediaRepository
import com.example.gochat.databinding.BottomSheetGifPickerBinding
import com.example.gochat.databinding.ItemGifBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GifPickerBottomSheet(
    private val onGifSelected: (String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetGifPickerBinding? = null
    private val binding get() = _binding!!
    private lateinit var gifAdapter: GifAdapter
    private lateinit var mediaRepository: MediaRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetGifPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        mediaRepository = MediaRepository(requireContext())
        gifAdapter = GifAdapter { onGifSelected(it); dismiss() }
        binding.rvGifs.adapter = gifAdapter
        
        binding.etSearchGif.doAfterTextChanged {
            searchGifs(it?.toString().orEmpty())
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> searchGifs(binding.etSearchGif.text.toString())
                    1 -> loadStickers()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        
        searchGifs("") // Initial trending
    }

    private fun searchGifs(query: String) {
        lifecycleScope.launch {
            val gifs = withContext(Dispatchers.IO) {
                if (query.isBlank()) mediaRepository.getTrending() else mediaRepository.searchGifs(query)
            }
            gifAdapter.submitList(gifs.map { it.fullUrl })
        }
    }

    private fun loadStickers() {
        val stickers = mediaRepository.getStickerPacks().flatMap { it.stickers }
        gifAdapter.submitList(stickers.map { it.url })
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
