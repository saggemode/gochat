package com.example.gochat.ui.stories

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.example.gochat.R
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.model.StoryItem
import com.example.gochat.data.model.UserStories
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.data.repository.StoryRepository
import com.example.gochat.databinding.ActivityStoryViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StoryViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStoryViewerBinding
    private lateinit var storyRepository: StoryRepository
    private lateinit var chatRepository: ChatRepository

    private var userStories: UserStories? = null
    private val progressBars = mutableListOf<ProgressBar>()

    private var currentIndex = 0
    private var currentAnimator: ValueAnimator? = null
    private var isPaused = false
    private var isShowingBottomSheet = false

    companion object {
        const val EXTRA_USER_STORIES_JSON = "extra_user_stories_json"
        const val EXTRA_START_INDEX = "extra_start_index"
        private const val STORY_DURATION_MS = 5000L

        fun createIntent(context: Context, userStories: UserStories, startIndex: Int = 0): Intent {
            return Intent(context, StoryViewerActivity::class.java).apply {
                val jsonString = NetworkModule.json.encodeToString(UserStories.serializer(), userStories)
                putExtra(EXTRA_USER_STORIES_JSON, jsonString)
                putExtra(EXTRA_START_INDEX, startIndex)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoryViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storyRepository = StoryRepository(this)
        chatRepository = ChatRepository(this)

        val jsonStr = intent.getStringExtra(EXTRA_USER_STORIES_JSON)
        if (jsonStr.isNullOrBlank()) {
            finish()
            return
        }

        try {
            userStories = NetworkModule.json.decodeFromString(UserStories.serializer(), jsonStr)
        } catch (e: Exception) {
            finish()
            return
        }

        val stories = userStories?.stories.orEmpty()
        if (stories.isEmpty()) {
            finish()
            return
        }

        currentIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, stories.size - 1)

        setupProgressBars(stories.size)
        setupHeader()
        setupTouchZones()
        setupFooter()

        displayStory(currentIndex)
    }

    private fun setupProgressBars(count: Int) {
        binding.layoutSegmentedProgress.removeAllViews()
        progressBars.clear()

        val marginPx = (2 * resources.displayMetrics.density).toInt()

        for (i in 0 until count) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    if (i < count - 1) {
                        marginEnd = marginPx
                    }
                }
                max = 1000
                progress = 0
                progressDrawable = ContextCompat.getDrawable(
                    this@StoryViewerActivity,
                    R.drawable.bg_story_progress_bar
                )
            }
            binding.layoutSegmentedProgress.addView(pb)
            progressBars.add(pb)
        }
    }

    private fun setupHeader() {
        val user = userStories ?: return
        binding.tvStoryHeaderName.text = user.userName

        if (user.userAvatar.isNotBlank()) {
            binding.ivStoryHeaderAvatar.load(user.userAvatar) {
                crossfade(true)
                placeholder(R.drawable.ic_account)
                error(R.drawable.ic_account)
                transformations(CircleCropTransformation())
            }
        } else {
            binding.ivStoryHeaderAvatar.setImageResource(R.drawable.ic_account)
        }

        binding.btnBackStory.setOnClickListener { finish() }
        binding.btnCloseStory.setOnClickListener { finish() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchZones() {
        var downTime = 0L

        val touchListener = View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    pauseStory()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - downTime
                    if (duration < 300) {
                        // Quick tap navigation
                        if (view.id == R.id.touchZonePrevious) {
                            showPreviousStory()
                        } else {
                            showNextStory()
                        }
                    } else {
                        // Resume after long hold
                        resumeStory()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    resumeStory()
                    true
                }
                else -> false
            }
        }

        binding.touchZonePrevious.setOnTouchListener(touchListener)
        binding.touchZoneNext.setOnTouchListener(touchListener)
    }

    private fun setupFooter() {
        val user = userStories ?: return
        if (user.isMe) {
            binding.layoutOwnStoryFooter.visibility = View.VISIBLE
            binding.layoutReplyStoryFooter.visibility = View.GONE

            binding.layoutOwnStoryFooter.setOnClickListener {
                val stories = user.stories
                if (currentIndex in stories.indices) {
                    val currentStory = stories[currentIndex]
                    showStoryViewers(currentStory)
                }
            }
        } else {
            binding.layoutOwnStoryFooter.visibility = View.GONE
            binding.layoutReplyStoryFooter.visibility = View.VISIBLE

            binding.etStoryReplyInput.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) pauseStory()
            }

            binding.btnSendStoryReply.setOnClickListener {
                sendStoryReply()
            }
        }
    }

    private fun displayStory(index: Int) {
        val user = userStories ?: return
        val stories = user.stories
        if (index !in stories.indices) {
            finish()
            return
        }

        currentAnimator?.cancel()

        for (i in 0 until index) {
            if (i < progressBars.size) progressBars[i].progress = 1000
        }
        if (index < progressBars.size) {
            progressBars[index].progress = 0
        }
        for (i in (index + 1) until progressBars.size) {
            progressBars[i].progress = 0
        }

        val story = stories[index]
        binding.tvStoryHeaderTime.text = story.createdAt.ifBlank { "Recently" }

        // Text status vs Media status
        val isTextStory = story.mediaType == "text" || story.mediaUrl.isBlank()
        if (isTextStory) {
            binding.layoutTextStory.visibility = View.VISIBLE
            binding.ivMediaStory.visibility = View.GONE
            binding.tvImageStoryCaption.visibility = View.GONE

            val color = try {
                if (!story.backgroundColor.isNullOrBlank()) {
                    Color.parseColor(story.backgroundColor)
                } else {
                    Color.parseColor("#00A884")
                }
            } catch (_: Exception) {
                Color.parseColor("#00A884")
            }
            binding.layoutTextStory.setBackgroundColor(color)
            binding.tvTextStoryContent.text = story.caption.ifBlank { story.mediaUrl }
        } else {
            binding.layoutTextStory.visibility = View.GONE
            binding.ivMediaStory.visibility = View.VISIBLE

            binding.ivMediaStory.load(story.mediaUrl) {
                crossfade(true)
                error(R.drawable.ic_tab_status)
            }

            if (story.caption.isNotBlank()) {
                binding.tvImageStoryCaption.visibility = View.VISIBLE
                binding.tvImageStoryCaption.text = story.caption
            } else {
                binding.tvImageStoryCaption.visibility = View.GONE
            }
        }

        if (user.isMe) {
            val count = story.viewCount.coerceAtLeast(story.viewers.size)
            binding.tvOwnStoryViewCount.text = if (count == 1) "1 view" else "$count views"
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                storyRepository.viewStory(story.id)
            }
        }

        startProgressAnimation(index)
    }

    private fun startProgressAnimation(index: Int) {
        if (index !in progressBars.indices) return

        val progressBar = progressBars[index]
        currentAnimator = ValueAnimator.ofInt(0, 1000).apply {
            duration = STORY_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                progressBar.progress = anim.animatedValue as Int
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isFinishing && !isDestroyed && progressBar.progress >= 1000) {
                        showNextStory()
                    }
                }
            })
            start()
        }
        isPaused = false
    }

    private fun showNextStory() {
        val user = userStories ?: return
        if (currentIndex < user.stories.size - 1) {
            currentIndex++
            displayStory(currentIndex)
        } else {
            finish()
        }
    }

    private fun showPreviousStory() {
        if (currentIndex > 0) {
            currentIndex--
            displayStory(currentIndex)
        } else {
            displayStory(0)
        }
    }

    private fun pauseStory() {
        if (!isPaused) {
            currentAnimator?.pause()
            isPaused = true
        }
    }

    private fun resumeStory() {
        if (isPaused && !isShowingBottomSheet && !binding.etStoryReplyInput.hasFocus()) {
            currentAnimator?.resume()
            isPaused = false
        }
    }

    private fun showStoryViewers(story: StoryItem) {
        pauseStory()
        isShowingBottomSheet = true

        lifecycleScope.launch {
            val viewersResult = withContext(Dispatchers.IO) {
                storyRepository.getStoryViewers(story.id)
            }
            val viewers = viewersResult.getOrNull() ?: story.viewers

            if (!isFinishing && !isDestroyed) {
                val sheet = StoryViewersBottomSheet.newInstance(viewers) {
                    isShowingBottomSheet = false
                    resumeStory()
                }
                sheet.show(supportFragmentManager, "StoryViewersBottomSheet")
            }
        }
    }

    private fun sendStoryReply() {
        val replyText = binding.etStoryReplyInput.text?.toString()?.trim().orEmpty()
        if (replyText.isEmpty()) return

        val user = userStories ?: return
        val currentStory = user.stories.getOrNull(currentIndex)
        val storyCaption = currentStory?.caption?.ifBlank { "Status update" } ?: "Status update"

        binding.etStoryReplyInput.text?.clear()
        binding.etStoryReplyInput.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etStoryReplyInput.windowToken, 0)

        lifecycleScope.launch {
            val convResult = withContext(Dispatchers.IO) {
                chatRepository.createConversation(
                    name = user.userName,
                    memberIds = listOf(user.userId),
                    isGroup = false
                )
            }

            convResult.onSuccess { conv ->
                withContext(Dispatchers.IO) {
                    chatRepository.sendMessage(
                        conversationId = conv.id,
                        content = replyText,
                        replyToText = storyCaption,
                        replyToSenderName = user.userName
                    )
                }
                Toast.makeText(this@StoryViewerActivity, "Reply sent", Toast.LENGTH_SHORT).show()
                resumeStory()
            }.onFailure {
                Toast.makeText(this@StoryViewerActivity, "Failed to send reply", Toast.LENGTH_SHORT).show()
                resumeStory()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pauseStory()
    }

    override fun onResume() {
        super.onResume()
        if (!isShowingBottomSheet && !binding.etStoryReplyInput.hasFocus()) {
            resumeStory()
        }
    }

    override fun onDestroy() {
        currentAnimator?.cancel()
        currentAnimator = null
        super.onDestroy()
    }
}
