package com.example.gochat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.gochat.R
import com.example.gochat.data.model.Message
import com.example.gochat.data.model.MessageType
import com.example.gochat.data.repository.ChatRepository
import com.example.gochat.databinding.ActivitySharedMediaBinding
import com.example.gochat.databinding.FragmentSharedMediaTabBinding
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.URI
import javax.inject.Inject

@AndroidEntryPoint
class SharedMediaActivity : AppCompatActivity() {

    @Inject
    lateinit var chatRepository: ChatRepository

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONVERSATION_TITLE = "extra_conversation_title"
    }

    private lateinit var binding: ActivitySharedMediaBinding
    private val conversationId: String by lazy { intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty() }
    private val conversationTitle: String by lazy {
        intent.getStringExtra(EXTRA_CONVERSATION_TITLE)
            ?: intent.getStringExtra(EXTRA_TITLE)
            ?: "Chat"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySharedMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupViewPager()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.title = conversationTitle
        binding.toolbar.subtitle = getString(R.string.media_links_and_docs)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupViewPager() {
        val adapter = SharedMediaPagerAdapter(this, conversationId)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_media)
                1 -> getString(R.string.tab_docs)
                2 -> getString(R.string.tab_links)
                else -> ""
            }
        }.attach()
    }

    private class SharedMediaPagerAdapter(
        activity: AppCompatActivity,
        private val convId: String
    ) : FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> SharedMediaTabFragment.newInstance(convId)
                1 -> SharedDocsTabFragment.newInstance(convId)
                2 -> SharedLinksTabFragment.newInstance(convId)
                else -> throw IllegalArgumentException("Invalid tab position $position")
            }
        }
    }
}

/**
 * Tab 0: Photos & Videos 3-column Grid
 */
@AndroidEntryPoint
class SharedMediaTabFragment : Fragment() {

    @Inject
    lateinit var chatRepository: ChatRepository

    private var _binding: FragmentSharedMediaTabBinding? = null
    private val binding get() = _binding!!
    private val convId: String by lazy { requireArguments().getString(ARG_CONV_ID).orEmpty() }

    private lateinit var adapter: SharedMediaGridAdapter

    companion object {
        private const val ARG_CONV_ID = "arg_conv_id"
        fun newInstance(convId: String) = SharedMediaTabFragment().apply {
            arguments = Bundle().apply { putString(ARG_CONV_ID, convId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSharedMediaTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        adapter = SharedMediaGridAdapter { message ->
            val intent = Intent(requireContext(), MediaViewerActivity::class.java).apply {
                putExtra(MediaViewerActivity.EXTRA_MEDIA_URL, message.mediaUrl)
                putExtra(MediaViewerActivity.EXTRA_IS_VIDEO, message.type == MessageType.VIDEO)
                putExtra(MediaViewerActivity.EXTRA_TITLE, message.senderName)
            }
            startActivity(intent)
        }
        binding.recyclerView.adapter = adapter

        binding.ivEmptyIcon.setImageResource(R.drawable.ic_gallery)
        binding.tvEmptyTitle.text = getString(R.string.no_media_title)
        binding.tvEmptyDescription.text = getString(R.string.no_media_desc)

        viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.getMediaMessages(convId).collectLatest { list ->
                adapter.submitList(list)
                if (list.isEmpty()) {
                    binding.layoutEmpty.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.layoutEmpty.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * Tab 1: Shared Documents List
 */
@AndroidEntryPoint
class SharedDocsTabFragment : Fragment() {

    @Inject
    lateinit var chatRepository: ChatRepository

    private var _binding: FragmentSharedMediaTabBinding? = null
    private val binding get() = _binding!!
    private val convId: String by lazy { requireArguments().getString(ARG_CONV_ID).orEmpty() }

    private lateinit var adapter: SharedDocsAdapter

    companion object {
        private const val ARG_CONV_ID = "arg_conv_id"
        fun newInstance(convId: String) = SharedDocsTabFragment().apply {
            arguments = Bundle().apply { putString(ARG_CONV_ID, convId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSharedMediaTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SharedDocsAdapter { message ->
            openDocument(message)
        }
        binding.recyclerView.adapter = adapter

        binding.ivEmptyIcon.setImageResource(R.drawable.ic_attach_file)
        binding.tvEmptyTitle.text = getString(R.string.no_docs_title)
        binding.tvEmptyDescription.text = getString(R.string.no_docs_desc)

        viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.getDocumentMessages(convId).collectLatest { list ->
                adapter.submitList(list)
                if (list.isEmpty()) {
                    binding.layoutEmpty.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.layoutEmpty.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun openDocument(message: Message) {
        val url = message.mediaUrl ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Open Document"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No application found to open this document", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * Tab 2: Shared Links List
 */
@AndroidEntryPoint
class SharedLinksTabFragment : Fragment() {

    @Inject
    lateinit var chatRepository: ChatRepository

    private var _binding: FragmentSharedMediaTabBinding? = null
    private val binding get() = _binding!!
    private val convId: String by lazy { requireArguments().getString(ARG_CONV_ID).orEmpty() }

    private lateinit var adapter: SharedLinksAdapter
    private val urlRegex = Regex("""(https?://[^\s]+|www\.[^\s]+)""")

    companion object {
        private const val ARG_CONV_ID = "arg_conv_id"
        fun newInstance(convId: String) = SharedLinksTabFragment().apply {
            arguments = Bundle().apply { putString(ARG_CONV_ID, convId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSharedMediaTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SharedLinksAdapter(
            onLinkClick = { rawUrl ->
                val fullUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    "https://$rawUrl"
                } else {
                    rawUrl
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Cannot open link", Toast.LENGTH_SHORT).show()
                }
            },
            onCopyClick = { url ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("URL", url)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), getString(R.string.toast_link_copied), Toast.LENGTH_SHORT).show()
            }
        )
        binding.recyclerView.adapter = adapter

        binding.ivEmptyIcon.setImageResource(R.drawable.ic_link)
        binding.tvEmptyTitle.text = getString(R.string.no_links_title)
        binding.tvEmptyDescription.text = getString(R.string.no_links_desc)

        viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.getLinkMessages(convId).collectLatest { messages ->
                val linkItems = mutableListOf<SharedLinkItem>()
                for (msg in messages) {
                    val matches = urlRegex.findAll(msg.content)
                    for (m in matches) {
                        val u = m.value
                        val host = try {
                            val uri = if (u.startsWith("http")) URI(u) else URI("https://$u")
                            uri.host ?: u
                        } catch (_: Exception) {
                            u
                        }
                        linkItems.add(
                            SharedLinkItem(
                                messageId = "${msg.id}_${u.hashCode()}",
                                url = u,
                                domain = host,
                                timestamp = msg.createdAt
                            )
                        )
                    }
                }
                adapter.submitList(linkItems)
                if (linkItems.isEmpty()) {
                    binding.layoutEmpty.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.layoutEmpty.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
