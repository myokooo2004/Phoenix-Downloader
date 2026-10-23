package com.phoenix.downloader.ui.search

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.phoenix.downloader.MainActivity
import com.phoenix.downloader.R
import com.phoenix.downloader.core.SearchHistoryStore
import com.phoenix.downloader.core.YouTubeApiClient
import com.phoenix.downloader.databinding.FragmentSearchEntryBinding
import com.phoenix.downloader.databinding.ItemSuggestionBinding
import com.phoenix.downloader.ui.download.DownloadDialogFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search entry screen (reference UI, user 2026-10-20): compact box with an
 * ENTER/SEARCH action; with an empty query it shows Search History and
 * Hot Searches chips; while typing it shows live YouTube suggestions
 * (one letter is enough). Rounded Padauk font throughout, per the
 * reference screenshots.
 */
class SearchEntryFragment : Fragment() {
    private var _binding: FragmentSearchEntryBinding? = null
    private val binding get() = _binding!!
    private var suggestJob: Job? = null
    private lateinit var suggestAdapter: SuggestAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        suggestAdapter = SuggestAdapter(
            onPick = { submit(it) },
            onFill = { fillQuery(it) }
        )
        binding.rvSuggestions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSuggestions.adapter = suggestAdapter

        bindHistoryChips()
        bindHotChips()

        binding.btnClearHistory.setOnClickListener {
            SearchHistoryStore.clear(requireContext())
            bindHistoryChips()
        }
        binding.btnEntryClear.setOnClickListener { binding.etEntryQuery.text?.clear() }
        binding.btnEntrySearch.setOnClickListener { submit(binding.etEntryQuery.text.toString()) }
        binding.etEntryQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submit(binding.etEntryQuery.text.toString())
                true
            } else false
        }
        // Mirror the current YouTube search into the field (user
        // spec 2026-10-23): the place the user searched always holds
        // the current search, selected so typing replaces it and one
        // clear leaves an empty field for the next search. Runs
        // before the text watcher is attached so no suggestion fetch
        // fires for the mirrored text.
        val currentQuery = (activity as? MainActivity)?.currentSearchQuery()
        if (!currentQuery.isNullOrEmpty()) {
            binding.etEntryQuery.setText(currentQuery)
            binding.etEntryQuery.selectAll()
            binding.btnEntryClear.visibility = View.VISIBLE
        }
        binding.etEntryQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                onQueryChanged(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Keyboard up right away, like the reference UI.
        binding.etEntryQuery.requestFocus()
        binding.etEntryQuery.postDelayed({
            if (_binding == null) return@postDelayed
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etEntryQuery, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun onQueryChanged(raw: String) {
        val q = raw.trim()
        binding.btnEntryClear.visibility = if (raw.isEmpty()) View.GONE else View.VISIBLE
        suggestJob?.cancel()
        if (q.isEmpty()) {
            showSections()
            return
        }
        suggestJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(250)
            val list = YouTubeApiClient.fetchSuggestions(q)
            if (_binding == null) return@launch
            if (binding.etEntryQuery.text.toString().trim() != q) return@launch
            if (list.isEmpty()) {
                showSections()
            } else {
                binding.sectionsContainer.visibility = View.GONE
                binding.rvSuggestions.visibility = View.VISIBLE
                suggestAdapter.submit(list, q)
            }
        }
    }

    private fun showSections() {
        binding.rvSuggestions.visibility = View.GONE
        binding.sectionsContainer.visibility = View.VISIBLE
    }

    private fun fillQuery(s: String) {
        binding.etEntryQuery.setText(s)
        binding.etEntryQuery.setSelection(binding.etEntryQuery.text.length)
    }

    private fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (looksLikeUrl(text)) {
            val url = if (text.startsWith("http")) text else "https://$text"
            if (isVideoSiteUrl(url)) {
                // Pasted video link -> download dialog (the existing URL flow).
                DownloadDialogFragment.newInstance(url, "Download")
                    .show(requireActivity().supportFragmentManager, "DownloadDialog")
            } else {
                // Any other address -> open it in the home browser.
                (activity as? MainActivity)?.showHomeWithUrl(url)
            }
        } else {
            SearchHistoryStore.add(requireContext(), text)
            // YouTube search rides YouTube's OWN results page in the
            // home browser, under our overlay (user decision
            // 2026-10-23, reversing the 2026-10-22 native cover after
            // the native fetch chain froze in the field): the query
            // becomes the results URL, the videos below are YouTube's
            // page with our download buttons, and re-searching is
            // just another navigation - it cannot wedge.
            val resultsUrl = "https://m.youtube.com/results?search_query=" +
                java.net.URLEncoder.encode(text, "UTF-8")
            (activity as? MainActivity)?.showHomeWithUrl(resultsUrl)
        }
    }

    /** True for the video sites whose links go straight to the dialog. */
    private fun isVideoSiteUrl(url: String): Boolean =
        url.contains("youtube.com") || url.contains("youtu.be") ||
            url.contains("facebook.com") || url.contains("fb.watch") ||
            url.contains("tiktok.com")

    private fun looksLikeUrl(text: String): Boolean =
        text.startsWith("http://") || text.startsWith("https://") ||
            text.contains("youtube.com") || text.contains("youtu.be") ||
            text.contains("facebook.com") || text.contains("fb.watch") ||
            text.contains("tiktok.com")

    private fun bindHistoryChips() {
        val history = SearchHistoryStore.all(requireContext())
        binding.historyHeader.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        binding.chipsHistory.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        binding.chipsHistory.removeAllViews()
        for (term in history) {
            binding.chipsHistory.addView(makeChip(term) { submit(term) })
        }
    }

    private fun bindHotChips() {
        // Contents come from YouTube itself (Worker-composed from the
        // suggestion rankings; see YouTubeApiClient.fetchHotSearches).
        // Cached terms render instantly; a fresh fetch replaces them.
        // Nothing cached + fetch failed -> the section hides rather than
        // showing anything that did not come from YouTube.
        showHotChips(com.phoenix.downloader.core.HotSearchCache.load(requireContext()))
        viewLifecycleOwner.lifecycleScope.launch {
            val fresh = YouTubeApiClient.fetchHotSearches()
            if (_binding == null || fresh.isEmpty()) return@launch
            com.phoenix.downloader.core.HotSearchCache.save(requireContext(), fresh)
            showHotChips(fresh)
        }
    }

    private fun showHotChips(terms: List<String>) {
        val has = terms.isNotEmpty()
        binding.hotTitle.visibility = if (has) View.VISIBLE else View.GONE
        binding.chipsHot.visibility = if (has) View.VISIBLE else View.GONE
        binding.chipsHot.removeAllViews()
        for (term in terms) {
            binding.chipsHot.addView(makeChip(term) { submit(term) })
        }
    }

    private fun makeChip(label: String, onClick: () -> Unit): TextView {
        val ctx = requireContext()
        val tv = TextView(ctx)
        tv.text = label
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        tv.background = ContextCompat.getDrawable(ctx, R.drawable.bg_chip)
        tv.typeface = ResourcesCompat.getFont(ctx, R.font.padauk)
        tv.setPadding(dp(13f), dp(6f), dp(13f), dp(6f))
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { onClick() }
        val lp = ChipGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, dp(8f), dp(8f))
        tv.layoutParams = lp
        return tv
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        suggestJob?.cancel()
        _binding = null
    }

    private class SuggestAdapter(
        private val onPick: (String) -> Unit,
        private val onFill: (String) -> Unit
    ) : RecyclerView.Adapter<SuggestAdapter.VH>() {
        private var items: List<String> = emptyList()
        private var query: String = ""

        fun submit(list: List<String>, q: String) {
            items = list
            query = q
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            val span = SpannableString(s)
            if (query.isNotEmpty() && s.startsWith(query, ignoreCase = true)) {
                span.setSpan(
                    ForegroundColorSpan(0xFF1E88E5.toInt()), 0, query.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                span.setSpan(
                    StyleSpan(Typeface.BOLD), 0, query.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            holder.binding.tvSuggestion.text = span
            holder.binding.root.setOnClickListener { onPick(s) }
            holder.binding.btnFill.setOnClickListener { onFill(s) }
        }

        override fun getItemCount() = items.size

        class VH(val binding: ItemSuggestionBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
