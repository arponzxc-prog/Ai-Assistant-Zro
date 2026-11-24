package com.arponzxc.novaai.ui.youtube

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.R
import com.arponzxc.novaai.automation.youtube.YouTubeVideoItem
import com.arponzxc.novaai.databinding.ActivityYoutubeResultsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class YouTubeResultsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUERY = "extra_query"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var binding: ActivityYoutubeResultsBinding
    private val preferences by lazy { JarvisApp.instance.youTubePreferences }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.arponzxc.novaai.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityYoutubeResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val query = intent.getStringExtra(EXTRA_QUERY) ?: "popular"
        val customTitle = intent.getStringExtra(EXTRA_TITLE) ?: "YouTube Results"
        binding.tvHeaderTitle.text = customTitle

        binding.rvYouTubeResults.layoutManager = LinearLayoutManager(this)

        loadResults(query)
    }

    private fun loadResults(query: String) {
        val apiKey = preferences.youtubeApiKey
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                if (query.equals("popular_bd", ignoreCase = true) || query.isBlank()) {
                    com.arponzxc.novaai.automation.youtube.YouTubeDataRepository.getPopularVideos(apiKey, regionCode = "BD")
                } else {
                    com.arponzxc.novaai.automation.youtube.YouTubeDataRepository.searchVideos(apiKey, query)
                }
            }

            if (results.isEmpty()) {
                binding.tvEmptyState.visibility = View.VISIBLE
                if (apiKey.isBlank()) {
                    binding.tvEmptyState.text = "YouTube API key not configured.\nPlease set your API key in Settings -> API & Connection."
                } else {
                    binding.tvEmptyState.text = "No videos found for '$query'."
                }
            } else {
                binding.tvEmptyState.visibility = View.GONE
                binding.rvYouTubeResults.adapter = YouTubeVideoAdapter(results) { video ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${video.videoId}"))
                    startActivity(intent)
                }
            }
        }
    }
}

class YouTubeVideoAdapter(
    private val items: List<YouTubeVideoItem>,
    private val onClick: (YouTubeVideoItem) -> Unit
) : RecyclerView.Adapter<YouTubeVideoAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvChannelTitle: TextView = view.findViewById(R.id.tvChannelTitle)
        val tvStats: TextView = view.findViewById(R.id.tvStats)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val btnOpenYouTube: MaterialButton = view.findViewById(R.id.btnOpenYouTube)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_youtube_video, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvChannelTitle.text = item.channelTitle
        holder.tvStats.text = "${formatViews(item.viewCount)} views • ${item.publishedAt.take(10)}"
        holder.tvDuration.visibility = View.GONE
        holder.ivThumbnail.load(item.thumbnailUrl) {
            crossfade(true)
            placeholder(R.drawable.bg_jarvis_card)
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.btnOpenYouTube.setOnClickListener { onClick(item) }
    }

    private fun formatViews(count: Long): String {
        if (count <= 0) return "0"
        return when {
            count >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1fB", count / 1_000_000_000.0)
            count >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
