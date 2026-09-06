package com.example.gochat.ui.chat

import android.content.res.ColorStateList
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
import com.example.gochat.core.wallpaper.BubbleShape
import com.example.gochat.core.wallpaper.ChatBubbleHelper
import com.example.gochat.core.wallpaper.ChatTheme
import com.example.gochat.core.wallpaper.ChatThemeManager
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
    private val onThemeChanged: (ChatTheme) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetChatWallpaperBinding? = null
    private val binding get() = _binding!!

    private lateinit var themeManager: ChatThemeManager
    private lateinit var currentTheme: ChatTheme

    private lateinit var bubbleShapeAdapter: BubbleShapeAdapter
    private lateinit var presetAdapter: PresetAdapter
    private lateinit var solidAdapter: SolidAdapter
    private lateinit var accentAdapter: AccentColorAdapter

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val cachedPath = copyUriToInternalStorage(uri)
                if (cachedPath != null) {
                    currentTheme = currentTheme.copy(
                        id = "custom_${System.currentTimeMillis()}",
                        name = getString(R.string.label_custom_photo),
                        type = WallpaperType.CUSTOM_IMAGE,
                        imageUriOrPath = cachedPath,
                        solidColor = null
                    )
                    presetAdapter.setSelectedId(null)
                    solidAdapter.setSelectedColor(null)
                    updatePreviewUI()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.error_load_image), Toast.LENGTH_SHORT).show()
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

        themeManager = ChatThemeManager(requireContext())
        currentTheme = themeManager.getTheme(conversationId)

        binding.tvTargetChatSubtitle.text = getString(R.string.customizing_for_format, conversationTitle)
        binding.btnCloseSheet.setOnClickListener { dismiss() }

        setupBubbleShapeRecyclerView()
        setupPresetRecyclerView()
        setupSolidColorRecyclerView()
        setupAccentColorRecyclerView()
        setupDoodleControls()
        setupActionButtons()

        updatePreviewUI()
    }

    private fun setupBubbleShapeRecyclerView() {
        bubbleShapeAdapter = BubbleShapeAdapter(
            selectedShape = currentTheme.bubbleShape,
            accentColor = currentTheme.accentColor,
            onShapeClick = { shape ->
                currentTheme = currentTheme.copy(bubbleShape = shape)
                bubbleShapeAdapter.setSelectedShape(shape)
                updatePreviewUI()
            }
        )
        binding.rvBubbleShapes.adapter = bubbleShapeAdapter
    }

    private fun setupPresetRecyclerView() {
        presetAdapter = PresetAdapter(
            presets = ChatTheme.PRESETS,
            selectedId = if (currentTheme.type == WallpaperType.PRESET) currentTheme.id else null,
            onPresetClick = { preset ->
                currentTheme = preset.copy(
                    showDoodle = currentTheme.showDoodle,
                    doodleOpacity = currentTheme.doodleOpacity
                )
                presetAdapter.setSelectedId(preset.id)
                solidAdapter.setSelectedColor(null)
                accentAdapter.setSelectedColor(currentTheme.accentColor)
                bubbleShapeAdapter.setSelectedShape(currentTheme.bubbleShape)
                bubbleShapeAdapter.setAccentColor(currentTheme.accentColor)
                updatePreviewUI()
            }
        )
        binding.rvPresets.adapter = presetAdapter
    }

    private fun setupSolidColorRecyclerView() {
        solidAdapter = SolidAdapter(
            solidColors = ChatTheme.SOLID_COLORS,
            selectedColor = if (currentTheme.type == WallpaperType.SOLID) currentTheme.solidColor else null,
            onSolidColorClick = { solid ->
                currentTheme = currentTheme.copy(
                    id = "solid_${solid.name.lowercase().replace(" ", "_")}",
                    name = solid.name,
                    type = WallpaperType.SOLID,
                    solidColor = solid.color,
                    imageUriOrPath = null
                )
                presetAdapter.setSelectedId(null)
                solidAdapter.setSelectedColor(solid.color)
                updatePreviewUI()
            }
        )
        binding.rvSolidColors.adapter = solidAdapter
    }

    private fun setupAccentColorRecyclerView() {
        accentAdapter = AccentColorAdapter(
            accentColors = ChatTheme.ACCENT_COLORS,
            selectedColor = currentTheme.accentColor,
            onAccentColorClick = { accent ->
                currentTheme = currentTheme.copy(accentColor = accent.color)
                accentAdapter.setSelectedColor(accent.color)
                bubbleShapeAdapter.setAccentColor(accent.color)
                updatePreviewUI()
            }
        )
        binding.rvAccentColors.adapter = accentAdapter
    }

    private fun setupDoodleControls() {
        binding.switchDoodle.isChecked = currentTheme.showDoodle
        binding.switchDoodle.setOnCheckedChangeListener { _, isChecked ->
            currentTheme = currentTheme.copy(showDoodle = isChecked)
            updatePreviewUI()
        }

        binding.sliderDoodleOpacity.value = (currentTheme.doodleOpacity * 100).coerceIn(1f, 25f)
        binding.sliderDoodleOpacity.addOnChangeListener { _, value, _ ->
            val opacity = value / 100f
            currentTheme = currentTheme.copy(doodleOpacity = opacity)
            binding.tvOpacityValue.text = "${value.toInt()}%"
            binding.doodlePreview.setDoodleOpacity(opacity)
        }

        binding.btnPickImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnRemoveImage.setOnClickListener {
            currentTheme = ChatTheme.DEFAULT_EMERALD.copy(
                showDoodle = currentTheme.showDoodle,
                doodleOpacity = currentTheme.doodleOpacity,
                accentColor = currentTheme.accentColor
            )
            presetAdapter.setSelectedId(ChatTheme.DEFAULT_EMERALD.id)
            solidAdapter.setSelectedColor(null)
            updatePreviewUI()
        }
    }

    private fun setupActionButtons() {
        binding.btnApplyThisChat.setOnClickListener {
            themeManager.setThemeForConversation(conversationId, currentTheme)
            onThemeChanged(currentTheme)
            Toast.makeText(requireContext(), getString(R.string.toast_applied_theme_single, currentTheme.name), Toast.LENGTH_SHORT).show()
            dismiss()
        }

        binding.btnApplyAllChats.setOnClickListener {
            themeManager.setGlobalTheme(currentTheme)
            themeManager.setThemeForConversation(conversationId, currentTheme)
            onThemeChanged(currentTheme)
            Toast.makeText(requireContext(), getString(R.string.toast_applied_theme_all, currentTheme.name), Toast.LENGTH_SHORT).show()
            dismiss()
        }

        binding.btnResetDefault.setOnClickListener {
            themeManager.resetTheme(conversationId)
            val defaultTheme = themeManager.getTheme(conversationId)
            currentTheme = defaultTheme
            presetAdapter.setSelectedId(defaultTheme.id)
            solidAdapter.setSelectedColor(null)
            accentAdapter.setSelectedColor(defaultTheme.accentColor)
            bubbleShapeAdapter.setSelectedShape(defaultTheme.bubbleShape)
            bubbleShapeAdapter.setAccentColor(defaultTheme.accentColor)
            updatePreviewUI()
            onThemeChanged(defaultTheme)
            Toast.makeText(requireContext(), getString(R.string.toast_reset_theme), Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun updatePreviewUI() {
        val theme = currentTheme

        if (theme.type == WallpaperType.CUSTOM_IMAGE && !theme.imageUriOrPath.isNullOrBlank()) {
            binding.ivPreviewImage.visibility = View.VISIBLE
            binding.ivPreviewImage.load(File(theme.imageUriOrPath))
            binding.viewPreviewBg.setBackgroundColor(Color.BLACK)
            binding.tvCustomImageStatus.text = getString(R.string.status_custom_photo_selected)
            binding.btnRemoveImage.visibility = View.VISIBLE
        } else {
            binding.ivPreviewImage.visibility = View.GONE
            binding.btnRemoveImage.visibility = View.GONE
            binding.tvCustomImageStatus.text = getString(R.string.choose_from_gallery)

            if (theme.solidColor != null) {
                binding.viewPreviewBg.setBackgroundColor(theme.solidColor)
            } else {
                val drawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    theme.bgGradientColors.toIntArray()
                )
                binding.viewPreviewBg.background = drawable
            }
        }

        binding.doodlePreview.visibility = if (theme.showDoodle) View.VISIBLE else View.GONE
        binding.doodlePreview.setDoodleOpacity(theme.doodleOpacity)

        binding.switchDoodle.isChecked = theme.showDoodle
        binding.layoutDoodleOpacity.visibility = if (theme.showDoodle) View.VISIBLE else View.GONE
        binding.sliderDoodleOpacity.value = (theme.doodleOpacity * 100).coerceIn(1f, 25f)
        binding.tvOpacityValue.text = "${(theme.doodleOpacity * 100).toInt()}%"

        // Apply accent color to preview controls
        val accentColor = theme.accentColor
        binding.btnApplyThisChat.backgroundTintList = ColorStateList.valueOf(accentColor)
        binding.btnPickImage.backgroundTintList = ColorStateList.valueOf(accentColor)
        binding.switchDoodle.thumbTintList = ColorStateList.valueOf(accentColor)
        binding.sliderDoodleOpacity.thumbTintList = ColorStateList.valueOf(accentColor)
        binding.sliderDoodleOpacity.trackActiveTintList = ColorStateList.valueOf(accentColor)
        binding.tvOpacityValue.setTextColor(accentColor)
        
        // Mock chat bubbles styled according to selected bubble shape and accent
        binding.previewBubbleMe.backgroundTintList = null
        binding.previewBubbleOther.backgroundTintList = null
        binding.previewBubbleMe.background = ChatBubbleHelper.getBubbleDrawable(
            requireContext(),
            isMe = true,
            shape = theme.bubbleShape,
            accentColor = accentColor
        )
        binding.previewBubbleOther.background = ChatBubbleHelper.getBubbleDrawable(
            requireContext(),
            isMe = false,
            shape = theme.bubbleShape,
            accentColor = accentColor
        )
        binding.tvPreviewMeMessage.setTextColor(
            ChatBubbleHelper.getMessageTextColor(isMe = true, shape = theme.bubbleShape)
        )
        binding.tvPreviewOtherMessage.setTextColor(
            ChatBubbleHelper.getMessageTextColor(isMe = false, shape = theme.bubbleShape)
        )
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
        private val presets: List<ChatTheme>,
        private var selectedId: String?,
        private val onPresetClick: (ChatTheme) -> Unit
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

            fun bind(preset: ChatTheme) {
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

    // ── Nested Accent Color Adapter ────────────────────────────────────
    private class AccentColorAdapter(
        private val accentColors: List<SolidColorOption>,
        private var selectedColor: Int?,
        private val onAccentColorClick: (SolidColorOption) -> Unit
    ) : RecyclerView.Adapter<AccentColorAdapter.AccentViewHolder>() {

        fun setSelectedColor(color: Int?) {
            val oldPos = accentColors.indexOfFirst { it.color == selectedColor }
            selectedColor = color
            val newPos = accentColors.indexOfFirst { it.color == selectedColor }
            if (oldPos != -1) notifyItemChanged(oldPos)
            if (newPos != -1) notifyItemChanged(newPos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccentViewHolder {
            val binding = ItemWallpaperSolidBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return AccentViewHolder(binding)
        }

        override fun onBindViewHolder(holder: AccentViewHolder, position: Int) {
            holder.bind(accentColors[position])
        }

        override fun getItemCount(): Int = accentColors.size

        inner class AccentViewHolder(private val binding: ItemWallpaperSolidBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(accent: SolidColorOption) {
                binding.tvSolidName.text = accent.name
                binding.viewSolidColor.setBackgroundColor(accent.color)

                val isSelected = accent.color == selectedColor
                binding.cardSolidColor.strokeWidth = if (isSelected) 3 else 0
                binding.ivSolidSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.ivSolidSelected.backgroundTintList = ColorStateList.valueOf(accent.color)

                binding.cardSolidColor.setOnClickListener {
                    onAccentColorClick(accent)
                }
            }
        }
    }
}
