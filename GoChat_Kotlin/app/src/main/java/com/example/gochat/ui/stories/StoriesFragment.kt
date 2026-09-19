package com.example.gochat.ui.stories

import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.databinding.DialogCreateMediaStatusBinding
import com.example.gochat.databinding.DialogCreateTextStatusBinding
import com.example.gochat.databinding.FragmentStoriesBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

@AndroidEntryPoint
class StoriesFragment : Fragment() {

    private var _binding: FragmentStoriesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StoriesViewModel by viewModels()
    private lateinit var storyAdapter: StoryAdapter

    private val bgColors = listOf(
        "#00A884", // WhatsApp Emerald
        "#128C7E", // Dark Teal
        "#075E54", // Deep Green
        "#2C3E50", // Midnight Blue
        "#8E44AD", // Wisteria Purple
        "#C0392B", // Crimson Red
        "#D35400", // Pumpkin Orange
        "#16A085"  // Sea Green
    )
    private var currentColorIndex = 0

    // Photo picker for camera/image status
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleSelectedImage(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSwipeRefresh()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        storyAdapter = StoryAdapter { userStories ->
            val intent = StoryViewerActivity.createIntent(requireContext(), userStories)
            startActivity(intent)
        }

        binding.rvRecentStories.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = storyAdapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshStories.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.gochat_accent)
        )
        binding.swipeRefreshStories.setOnRefreshListener {
            viewModel.loadStories()
        }
    }

    private fun setupClickListeners() {
        binding.btnQuickTextStatus.setOnClickListener {
            showCreateTextStatusDialog()
        }

        binding.fabTextStatus.setOnClickListener {
            showCreateTextStatusDialog()
        }

        binding.btnQuickCameraStatus.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.fabCameraStatus.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.layoutMyStatusTile.setOnClickListener {
            val myStories = viewModel.myStories.value
            if (myStories != null && myStories.stories.isNotEmpty()) {
                val intent = StoryViewerActivity.createIntent(requireContext(), myStories)
                startActivity(intent)
            } else {
                showCreateTextStatusDialog()
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.myStories.collectLatest { myStory ->
                if (myStory == null) return@collectLatest

                val hasStories = myStory.stories.isNotEmpty()
                val avatarToLoad = if (hasStories && myStory.stories.first().mediaUrl.isNotBlank()) {
                    myStory.stories.first().mediaUrl
                } else {
                    myStory.userAvatar
                }

                com.example.gochat.core.media.MediaImageHelper.loadSafeImage(
                    binding.ivMyStatusAvatar,
                    avatarToLoad,
                    isCircle = true,
                    placeholderRes = R.drawable.ic_account,
                    errorRes = R.drawable.ic_account
                )

                binding.viewMyStatusStoryRing.visibility = if (hasStories) View.VISIBLE else View.GONE
                binding.ivMyStatusAddBadge.visibility = if (hasStories) View.GONE else View.VISIBLE

                if (hasStories) {
                    val count = myStory.stories.size
                    val countStr = if (count == 1) getString(R.string.story_count_singular) else getString(R.string.story_count_plural, count)
                    val totalViews = myStory.totalViewCount
                    val viewsStr = if (totalViews > 0) " • 👁️ $totalViews ${if (totalViews == 1) "view" else "views"}" else ""
                    val latestTime = myStory.stories.firstOrNull()?.createdAt?.ifBlank { getString(R.string.time_recently) } ?: getString(R.string.time_recently)
                    binding.tvMyStatusSubtitle.text = "$countStr$viewsStr • $latestTime"
                } else {
                    binding.tvMyStatusSubtitle.text = "Tap to add status update"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recentStories.collectLatest { stories ->
                storyAdapter.submitList(stories)
                if (stories.isEmpty()) {
                    binding.layoutNoStories.visibility = View.VISIBLE
                    binding.rvRecentStories.visibility = View.GONE
                } else {
                    binding.layoutNoStories.visibility = View.GONE
                    binding.rvRecentStories.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isRefreshing.collectLatest { refreshing ->
                binding.swipeRefreshStories.isRefreshing = refreshing
            }
        }
    }

    private fun showCreateTextStatusDialog() {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBinding = DialogCreateTextStatusBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        currentColorIndex = 0
        dialogBinding.layoutTextStatusRoot.setBackgroundColor(Color.parseColor(bgColors[currentColorIndex]))

        var allowReshare = true
        dialogBinding.layoutReshareToggle.setOnClickListener {
            allowReshare = !allowReshare
            if (allowReshare) {
                dialogBinding.ivReshareToggleIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.gochat_emerald_light))
                dialogBinding.tvReshareToggleText.text = "Reshare: Allowed"
                dialogBinding.tvReshareToggleText.setTextColor(Color.WHITE)
            } else {
                dialogBinding.ivReshareToggleIcon.setColorFilter(Color.parseColor("#99FFFFFF"))
                dialogBinding.tvReshareToggleText.text = "Reshare: Off"
                dialogBinding.tvReshareToggleText.setTextColor(Color.parseColor("#99FFFFFF"))
            }
        }

        dialogBinding.btnCloseTextStatus.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnChangeBgColor.setOnClickListener {
            currentColorIndex = (currentColorIndex + 1) % bgColors.size
            val color = Color.parseColor(bgColors[currentColorIndex])
            dialogBinding.layoutTextStatusRoot.setBackgroundColor(color)
        }

        dialogBinding.fabPostStatus.setOnClickListener {
            val text = dialogBinding.etStatusText.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.error_empty_status), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val chosenColor = bgColors[currentColorIndex]
            dialogBinding.fabPostStatus.isEnabled = false

            viewModel.postTextStatus(text, chosenColor, allowReshare) { success, error ->
                dialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    if (success) getString(R.string.toast_status_updated) else (error ?: getString(R.string.error_image_processing)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        dialog.show()
    }

    private fun handleSelectedImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val compressed = withContext(Dispatchers.IO) {
                    com.example.gochat.core.media.ImageCompressor.compressImageUri(
                        context = requireContext().applicationContext,
                        uri = uri,
                        maxDimension = 1280,
                        quality = 80
                    )
                }

                if (compressed != null) {
                    showCreateMediaStatusDialog(compressed)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.error_image_processing), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.error_image_loading), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreateMediaStatusDialog(compressed: com.example.gochat.core.media.CompressedImage) {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBinding = DialogCreateMediaStatusBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        com.example.gochat.core.media.MediaImageHelper.loadSafeImage(
            dialogBinding.ivMediaPreview,
            compressed.dataUri,
            isCircle = false
        )

        var allowReshare = true
        dialogBinding.layoutMediaReshareToggle.setOnClickListener {
            allowReshare = !allowReshare
            if (allowReshare) {
                dialogBinding.ivMediaReshareIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.gochat_emerald_light))
                dialogBinding.tvMediaReshareText.text = "Reshare: Allowed"
                dialogBinding.tvMediaReshareText.setTextColor(Color.WHITE)
            } else {
                dialogBinding.ivMediaReshareIcon.setColorFilter(Color.parseColor("#99FFFFFF"))
                dialogBinding.tvMediaReshareText.text = "Reshare: Off"
                dialogBinding.tvMediaReshareText.setTextColor(Color.parseColor("#99FFFFFF"))
            }
        }

        dialogBinding.btnCloseMediaStatus.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.fabPostMediaStatus.setOnClickListener {
            val caption = dialogBinding.etMediaCaption.text?.toString()?.trim().orEmpty()
            dialogBinding.fabPostMediaStatus.isEnabled = false

            viewModel.postMediaStatus(
                mediaBytes = compressed.bytes,
                mimeType = compressed.mimeType,
                localDataUri = compressed.dataUri,
                caption = caption,
                mediaType = "image",
                allowReshare = allowReshare
            ) { success, error ->
                dialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    if (success) getString(R.string.toast_status_updated) else (error ?: getString(R.string.error_image_processing)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        dialog.show()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            viewModel.loadStories()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
