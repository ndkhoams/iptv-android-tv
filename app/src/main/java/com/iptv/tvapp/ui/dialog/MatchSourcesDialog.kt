package com.iptv.tvapp.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptv.tvapp.R
import com.iptv.tvapp.data.SportsLiveManager
import com.iptv.tvapp.databinding.DialogMatchSourcesBinding
import com.iptv.tvapp.databinding.ItemMatchSourceBinding
import com.iptv.tvapp.model.Channel

class MatchSourcesDialog(
    context: Context,
    private val matchGroup: SportsLiveManager.MatchGroup,
    private val currentPlayingChannel: Channel? = null,
    private val onSourceSelected: (Channel) -> Unit
) : Dialog(context, R.style.TvDialogTheme) {

    private lateinit var binding: DialogMatchSourcesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogMatchSourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvMatchTitle.text = "${matchGroup.homeTeam} vs ${matchGroup.awayTeam}"
        val leagueStr = if (matchGroup.leagueName.isNotBlank()) " • ${matchGroup.leagueName}" else ""
        val timeStr = if (matchGroup.date.isNotBlank()) "${matchGroup.time} ${matchGroup.date}" else "${matchGroup.time} Hôm nay"
        binding.tvMatchSubtitle.text = "$timeStr$leagueStr • ${matchGroup.sources.size} nguồn phát trực tiếp"

        val adapter = MatchSourcesAdapter(matchGroup.sources, currentPlayingChannel) { selectedChannel ->
            dismiss()
            onSourceSelected(selectedChannel)
        }

        binding.rvMatchSources.layoutManager = LinearLayoutManager(context)
        binding.rvMatchSources.adapter = adapter

        // Tìm vị trí của luồng đang phát để tự động cuộn tới và đặt D-pad focus
        val playingIndex = matchGroup.sources.indexOfFirst { src ->
            isSourcePlaying(src, currentPlayingChannel)
        }
        val targetIndex = if (playingIndex >= 0) playingIndex else 0

        binding.rvMatchSources.scrollToPosition(targetIndex)
        binding.rvMatchSources.post {
            val vh = binding.rvMatchSources.findViewHolderForAdapterPosition(targetIndex)
            if (vh != null) {
                vh.itemView.requestFocus()
            } else {
                binding.rvMatchSources.layoutManager?.findViewByPosition(targetIndex)?.requestFocus()
                    ?: binding.rvMatchSources.requestFocus()
            }
        }
    }

    private class MatchSourcesAdapter(
        private val sources: List<Channel>,
        private val currentPlayingChannel: Channel?,
        private val onClick: (Channel) -> Unit
    ) : RecyclerView.Adapter<MatchSourcesAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemMatchSourceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val channel = sources[position]
            val isPlaying = isSourcePlaying(channel, currentPlayingChannel)
            holder.bind(channel, position + 1, isPlaying)
        }

        override fun getItemCount(): Int = sources.size

        inner class ViewHolder(private val binding: ItemMatchSourceBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                binding.root.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onClick(sources[pos])
                    }
                }
                binding.root.setOnFocusChangeListener { v, hasFocus ->
                    v.elevation = if (hasFocus) 8f else 0f
                    if (hasFocus) {
                        v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    }
                }
            }

            fun bind(channel: Channel, index: Int, isPlaying: Boolean) {
                itemView.isSelected = isPlaying

                val orangeColor = ContextCompat.getColor(itemView.context, R.color.fpt_orange)
                val numColorStateList = ContextCompat.getColorStateList(itemView.context, R.color.selector_fpt_channel_num)
                val textColorStateList = ContextCompat.getColorStateList(itemView.context, R.color.selector_fpt_channel_text)

                if (isPlaying) {
                    binding.tvSourceIndex.text = "▶"
                    binding.tvSourceIndex.setTextColor(orangeColor)
                    binding.tvSourceName.text = "▶ " + formatSourceTitle(channel)
                    binding.tvSourceName.setTextColor(orangeColor)
                    binding.tvSourceBadge.visibility = View.VISIBLE
                    binding.tvSourceBadge.text = "ĐANG PHÁT"
                    binding.tvSourceBadge.setBackgroundResource(R.drawable.bg_fpt_badge)
                    binding.tvSourceBadge.setTextColor(orangeColor)
                } else {
                    binding.tvSourceIndex.text = String.format("%02d", index)
                    binding.tvSourceIndex.setTextColor(numColorStateList)
                    binding.tvSourceName.text = formatSourceTitle(channel)
                    binding.tvSourceName.setTextColor(textColorStateList)
                    binding.tvSourceBadge.visibility = View.GONE
                }

                if (!channel.logoUrl.isNullOrBlank()) {
                    Glide.with(itemView.context)
                        .load(channel.logoUrl)
                        .placeholder(R.drawable.ic_tv_placeholder)
                        .error(R.drawable.ic_tv_placeholder)
                        .into(binding.ivSourceLogo)
                } else {
                    binding.ivSourceLogo.setImageResource(R.drawable.ic_tv_placeholder)
                }
            }

            private fun formatSourceTitle(channel: Channel): String {
                val name = channel.name
                val group = channel.groupTitle
                val blvMatch = Regex("""\((?:BLV\s+)?([^\)]+)\)""", RegexOption.IGNORE_CASE).find(name)
                val blv = blvMatch?.groupValues?.get(1)?.trim()
                    ?.replace(Regex("""(?i)\b(hls|fhd|hd|sd|geo)\b"""), "")?.trim()

                val isNhaDai = blv?.equals("Nhà đài", ignoreCase = true) == true ||
                        name.contains("Nhà đài", ignoreCase = true) ||
                        group.contains("Nhà đài", ignoreCase = true)

                val sourcePrefix = when {
                    isNhaDai -> "📺 Nguồn Nhà Đài"
                    group.contains("Chuối Chiên") || name.contains("Chuối Chiên") -> "🍌 Chuối Chiên"
                    group.contains("Gà Vàng") || name.contains("Gà Vàng") -> "🐔 Gà Vàng"
                    group.contains("Xôi Lạc") || name.contains("Xôi Lạc") -> "🍚 Xôi Lạc"
                    group.contains("Cà Khịa") || name.contains("Cà Khịa") -> "🌶️ Cà Khịa"
                    group.contains("Bia Ôm") || name.contains("Bia Ôm") -> "🍺 Bia Ôm"
                    group.contains("S8") || name.contains("S8") -> "🎱 S8 TV"
                    group.contains("Cola") || name.contains("Cola") -> "🥤 Cola TV"
                    group.contains("Phá Làng") || name.contains("Phá Làng") -> "💥 Phá Làng"
                    group.contains("Vua Sân Cỏ") || name.contains("Vua Sân Cỏ") -> "👑 Vua Sân Cỏ"
                    group.contains("Khô Gà") || name.contains("Khô Gà") -> "🍗 Khô Gà"
                    group.contains("Giờ Vàng") || name.contains("Giờ Vàng") -> "⏰ Giờ Vàng"
                    group.contains("Sao Kê") || name.contains("Sao Kê") -> "📜 Sao Kê"
                    group.contains("Xôi Gấc") || name.contains("Xôi Gấc") -> "🍧 Xôi Gấc"
                    group.contains("Xôi Chè") || name.contains("Xôi Chè") -> "🍧 Xôi Chè"
                    group.contains("SocoLive", ignoreCase = true) || name.contains("SocoLive", ignoreCase = true) -> "🐿️ SocoLive"
                    group.contains("F4K Sport", ignoreCase = true) || group.contains("Film4k", ignoreCase = true) || name.contains("F4K Sport", ignoreCase = true) || name.contains("Film4k", ignoreCase = true) -> "🎬 F4K Sport"
                    else -> group.ifBlank { "⚽ Trực tiếp" }
                }

                return if (isNhaDai) {
                    "📺 Nguồn Nhà Đài"
                } else if (!blv.isNullOrBlank() && !blv.equals("hls", ignoreCase = true)) {
                    val blvText = if (blv.startsWith("BLV", ignoreCase = true) || sourcePrefix.contains("F4K Sport") || sourcePrefix.contains("Film4k") || isNhaDai) blv else "BLV $blv"
                    "$sourcePrefix • $blvText"
                } else {
                    sourcePrefix
                }
            }
        }
    }

    companion object {
        fun isSourcePlaying(source: Channel, playing: Channel?): Boolean {
            if (playing == null) return false
            if (source.id == playing.id) return true
            val srcUrl = source.streamUrl.substringBefore('|').trim()
            val playUrl = playing.streamUrl.substringBefore('|').trim()
            if (srcUrl.isNotBlank() && srcUrl == playUrl) return true

            // Khớp theo tên hoặc kênh TV360 cụ thể
            val sName = source.name.lowercase().trim()
            val pName = playing.name.lowercase().trim()
            if (sName.isNotBlank() && pName.isNotBlank()) {
                if (sName == pName) return true
                val tv360Regex = Regex("""tv360\+\s*\d+""")
                val sTv360 = tv360Regex.find(sName)?.value
                val pTv360 = tv360Regex.find(pName)?.value
                if (sTv360 != null && pTv360 != null && sTv360 == pTv360) return true
            }
            return false
        }
    }
}

