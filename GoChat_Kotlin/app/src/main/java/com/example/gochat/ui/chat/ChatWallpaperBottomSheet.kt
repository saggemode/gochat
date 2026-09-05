package com.example.gochat.ui.chat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.gochat.R
import com.example.gochat.core.wallpaper.ChatWallpaper
import com.example.gochat.core.wallpaper.ChatWallpaperManager
import com.example.gochat.core.wallpaper.SolidColorOption
import com.example.gochat.core.wallpaper.WallpaperType
import com.example.gochat.databinding.BottomSheetChatWallpaperBinding
import com.example.gochat.databinding.ItemWallpaperPresetBinding
import com.example.gochat.databinding.ItemWallpaperSolidBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File

class ChatWallpaperBottomSheet(
    private val conversationId: String,
    private val conversationTitle: String,
    private val onWallpaperChanged: (ChatWallpaper) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetChatWallpaperBinding? = null
    private val binding get() = _binding!!

    private lateinit var wallpaperManager: ChatWallpaperManager
    private lateinit var currentWallpaper: ChatWallpaper

    private lateinit var presetAdapter: PresetAdapter
    private lateinit var solidAdapter: SolidAdapter

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val cachedPath = copyUriToInternalStorage(uri)
                if (cachedPath != null) {
                    currentWallpaper = currentWallpaper.copy(
                        id = "custom_${System.currentTimeMillis()}",
                        name = "Custom Photo",
                        type = WallpaperType.CUSTOM_IMAGE,
                        imageUriOrPath = cachedPath,
                        solidColor = null
                    )
                    presetAdapter.setSelectedId(null)
                    solidAdapter.setSelectedColor(null)
                    updatePreviewUI()
                } else {
                    Toast.makeText(requireContext(), "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetChatWallpaperBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        wallpaperManager = ChatWallpaperManager(requireContext())
        currentWallpaper = wallpaperManager.getWallpaper(conversationId)

        binding.tvTargetChatSubtitle.text = "Customizing for $conversationTitle"
        binding.btnCloseSheet.setOnClickListener { dismiss() }

        setupPresetRecyclerView()
        setupSolidColorRecyclerView()
        setupDoodleControls()
        setupActionButtons()

        updatePreviewUI()
    }

    private fun setupPresetRecyclerView() {
        presetAdapter = PresetAdapter(
            presets = ChatWallpaper.PRESETS,
            selectedId = if (currentWallpaper.type == WallpaperType.PRESET) currentWallpaper.id else null,
            onPresetClick = { preset ->
                currentWallpaper = preset.copy(
                    showDoodle = currentWallpaper.showDoodle,
                    doodleOpacity = currentWallpaper.doodleOpacity
                )
                presetAdapter.setSelectedId(preset.id)
                solidAdapter.setSelectedColor(null)
                updatePreviewUI()
            }
        )
        binding.rvPresets.adapter = presetAdapter
    }

    private fun setupSolidColorRecyclerView() {
        solidAdapter = SolidAdapter(
            solidColors = ChatWallpaper.SOLID_COLORS,
            selectedColor = if (currentWallpaper.type == WallpaperType.SOLID) currentWallpaper.solidColor else null,
            onSolidColorClick = { solid ->
                currentWallpaper = ChatWallpaper(
                    id = "solid_${solid.name.lowercase().replace(" ", "_")}",
                    name = solid.name,
                    type = WallpaperType.SOLID,
                    solidColor = solid.color,
                    showDoodle = currentWallpaper.showDoodle,
                    doodleOpacity = currentWallpaper.doodleOpacity
                )
                presetAdapter.setSelectedId(null)
                solidAdapter.setSelectedColor(solid.color)
                updatePreviewUI()
            }
        )
        binding.rvSolidColors.adapter = solidAdapter
    }

    private fun setupDoodleControls() {
        binding.switchDoodle.isChecked = currentWallpaper.showDoodle
        binding.switchDoodle.setOnCheckedChangeListener { _, isChecked ->
            currentWallpaper = currentWallpaper.copy(showDoodle = isChecked)
            updatePreviewUI()
        }

        binding.sliderDoodleOpacity.value = (currentWallpaper.doodleOpacity * 100).coerceIn(1f, 25f)
        binding.sliderDoodleOpacity.addOnChangeListener { _, value, _ ->
            val opacity = value / 100f
            currentWallpaper = currentWallpaper.copy(doodleOpacity = opacity)
            binding.tvOpacityValue.text = "${value.toInt()}%"
            binding.doodlePreview.setDoodleOpacity(opacity)
        }

        binding.btnPickImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnRemoveImage.setOnClickListener {
            currentWallpaper = ChatWallpaper.DEFAULT_EMERALD.copy(
                showDoodle = currentWallpaper.showDoodle,
                doodleOpacity = currentWallpaper.doodleOpacity
            )
            presetAdapter.setSelectedId(ChatWallpaper.DEFAULT_EMERALD.id)
            solidAdapter.setSelectedColor(null)
            updatePreviewUI()
        }
    }

    private fun setupActionButtons() {
        binding.btnApplyThisChat.setOnClickListener {
            wallpaperManager.setWallpaperForConversation(conversationId, currentWallpaper)
            onWallpaperChanged(currentWallpaper)
            Toast.makeText(requireContext(), "✨ Applied \"${currentWallpaper.name}\" to this chat", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        binding.btnApplyAllChats.setOnClickListener {
            wallpaperManager.setGlobalWallpaper(currentWallpaper)
            wallpaperManager.setWallpaperForConversation(conversationId, currentWallpaper)
            onWallpaperChanged(currentWallpaper)
            Toast.makeText(requireContext(), "✨ Applied \"${currentWallpaper.name}\" to all chats", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        binding.btnResetDefault.setOnClickListener {
            wallpaperManager.resetWallpaper(conversationId)
            val defaultWp = wallpaperManager.getWallpaper(conversationId)
            onWallpaperChanged(defaultWp)
            Toast.makeText(requireContext(), "🔄 Reset to default wallpaper", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun updatePreviewUI() {
        val wp = currentWallpaper

        if (wp.type == WallpaperType.CUSTOM_IMAGE && !wp.imageUriOrPath.isNullOrBlank()) {
            binding.ivPreviewImage.visibility = View.VISIBLE
            binding.ivPreviewImage.load(File(wp.imageUriOrPath))
            binding.viewPreviewBg.setBackgroundColor(Color.BLACK)
            binding.tvCustomImageStatus.text = "Custom photo selected"
            binding.btnRemoveImage.visibility = View.VISIBLE
        } else {
            binding.ivPreviewImage.visibility = View.GONE
            binding.btnRemoveImage.visibility = View.GONE
            binding.tvCustomImageStatus.text = "Choose from device gallery"

            if (wp.solidColor != null) {
                binding.viewPreviewBg.setBackgroundColor(wp.solidColor)
            } else {
                val drawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    wp.bgGradientColors.toIntArray()
                )
                binding.viewPreviewBg.background = drawable
            }
        }

        binding.doodlePreview.visibility = if (wp.showDoodle) View.VISIBLE else View.GONE
        binding.doodlePreview.setDoodleOpacity(wp.doodleOpacity)

        binding.switchDoodle.isChecked = wp.showDoodle
        binding.layoutDoodleOpacity.visibility = if (wp.showDoodle) View.VISIBLE else View.GONE
        binding.sliderDoodleOpacity.value = (wp.doodleOpacity * 100).coerceIn(1f, 25f)
        binding.tvOpacityValue.text = "${(wp.doodleOpacity * 100).toInt()}%"
    }

    private fun copyUriToInternalStorage(uri: Uri): String? {
        return try {
            val resolver = requireContext().contentResolver
            val dir = File(requireContext().filesDir, "chat_wallpapers").apply { mkdirs() }
            val destFile = File(dir, "wallpaper_${System.currentTimeMillis()}.jpg")
            resolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error caching custom wallpaper: ${e.message}")
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ChatWallpaperBottomSheet"
    }

    // ── Nested Preset Adapter ──────────────────────────────────────────
    private class PresetAdapter(
        private val presets: List<ChatWallpaper>,
        private var selectedId: String?,
        private val onPresetClick: (ChatWallpaper) -> Unit
    ) : RecyclerView.Adapter<PresetAdapter.PresetViewHolder>() {

        fun setSelectedId(id: String?) {
            val oldPos = presets.indexOfFirst { it.id == selectedId }
            selectedId = id
            val newPos = presets.indexOfFirst { it.id == selectedId }
            if (oldPos != -1) notifyItemChanged(oldPos)
            if (newPos != -1) notifyItemChanged(newPos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
            val binding = ItemWallpaperPresetBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PresetViewHolder(binding)
        }

        override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
            holder.bind(presets[position])
        }

        override fun getItemCount(): Int = presets.size

        inner class PresetViewHolder(private val binding: ItemWallpaperPresetBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(preset: ChatWallpaper) {
                binding.tvPresetName.text = preset.name.replace("GoChat ", "")

                val gradient = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    preset.bgGradientColors.toIntArray()
                )
                binding.viewSwatchGradient.background = gradient

                binding.doodlePresetPreview.visibility =
                    if (preset.showDoodle) View.VISIBLE else View.GONE
                binding.doodlePresetPreview.setDoodleOpacity(preset.doodleOpacity)

                val isSelected = preset.id == selectedId
                binding.cardPreset.strokeWidth = if (isSelected) 3 else 0
                binding.ivPresetSelected.visibility = if (isSelected) View.VISIBLE else View.GONE

                binding.cardPreset.setOnClickListener {
                    onPresetClick(preset)
                }
            }
        }
    }

    // ── Nested Solid Color Adapter ─────────────────────────────────────
    private class SolidAdapter(
        private val solidColors: List<SolidColorOption>,
        private var selectedColor: Int?,
        private val onSolidColorClick: (SolidColorOption) -> Unit
    ) : RecyclerView.Adapter<SolidAdapter.SolidViewHolder>() {

        fun setSelectedColor(color: Int?) {
            val oldPos = solidColors.indexOfFirst { it.color == selectedColor }
            selectedColor = color
            val newPos = solidColors.indexOfFirst { it.color == selectedColor }
            if (oldPos != -1) notifyItemChanged(oldPos)
            if (newPos != -1) notifyItemChanged(newPos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SolidViewHolder {
            val binding = ItemWallpaperSolidBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return SolidViewHolder(binding)
        }

        override fun onBindViewHolder(holder: SolidViewHolder, position: Int) {
            holder.bind(solidColors[position])
        }

        override fun getItemCount(): Int = solidColors.size

        inner class SolidViewHolder(private val binding: ItemWallpaperSolidBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(solid: SolidColorOption) {
                binding.tvSolidName.text = solid.name
                binding.viewSolidColor.setBackgroundColor(solid.color)

                val isSelected = solid.color == selectedColor
                binding.cardSolidColor.strokeWidth = if (isSelected) 3 else 0
                binding.ivSolidSelected.visibility = if (isSelected) View.VISIBLE else View.GONE

                binding.cardSolidColor.setOnClickListener {
                    onSolidColorClick(solid)
                }
            }
        }
    }
}
