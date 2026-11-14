package com.iptv.tvapp.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.iptv.tvapp.R
import com.iptv.tvapp.data.SportsLiveManager
import com.iptv.tvapp.databinding.ItemChannelBinding
import com.iptv.tvapp.databinding.ItemMatchCardBinding
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.util.TeamLogoHelper

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelLongClick: ((Channel) -> Unit)? = null,
    private val onChannelFocused: ((Channel) -> Unit)? = null,
    var matchGroupProvider: ((String) -> SportsLiveManager.MatchGroup?)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val PAYLOAD_PLAY_STATE = 1
        private const val VIEW_TYPE_EVEN = 0
        private const val VIEW_TYPE_ODD = 1
        private const val VIEW_TYPE_MATCH_CARD = 2
        private const val VIEW_TYPE_REFRESH = 3
    }

    private val channels = mutableListOf<Channel>()
    private var playingChannel: Channel? = null
    private var playingMatchKey: String? = null

    fun submitList(list: List<Channel>) {
        channels.clear()
        channels.addAll(list)
        notifyDataSetChanged()
    }

    private fun isChannelPlaying(channel: Channel): Boolean {
        if (playingChannel == null && playingMatchKey == null) return false
        if (channel.id == playingChannel?.id) return true
        if (channel.streamUrl.startsWith("match://")) {
            val key = channel.streamUrl.removePrefix("match://")
            return key == playingMatchKey
        }
        if (channel.streamUrl == playingChannel?.streamUrl) {
            return channel.name == playingChannel?.name || channel.id == playingChannel?.id
        }
        return false
    }

    private fun findPlayingIndex(targetChannel: Channel?, matchKey: String?): Int {
        if (targetChannel == null && matchKey == null) return -1
        return channels.indexOfFirst { ch ->
            if (ch.id == targetChannel?.id) return@indexOfFirst true
            if (ch.streamUrl.startsWith("match://") && matchKey != null) {
                return@indexOfFirst ch.streamUrl.removePrefix("match://") == matchKey
            }
            if (targetChannel != null && ch.streamUrl == targetChannel.streamUrl) {
                return@indexOfFirst ch.name == targetChannel.name || ch.id == targetChannel.id
            }
            false
        }
    }

    fun setPlayingChannel(channel: Channel?, matchKey: String? = null) {
        val oldIndex = findPlayingIndex(playingChannel, playingMatchKey)
        playingChannel = channel
        playingMatchKey = matchKey
        val newIndex = findPlayingIndex(channel, matchKey)

        if (oldIndex != -1 && newIndex != -1 && oldIndex != newIndex) {
            notifyItemChanged(oldIndex, PAYLOAD_PLAY_STATE)
            notifyItemChanged(newIndex, PAYLOAD_PLAY_STATE)
        } else if (oldIndex != -1 && newIndex == -1) {
            notifyItemChanged(oldIndex, PAYLOAD_PLAY_STATE)
        } else if (oldIndex == -1 && newIndex != -1) {
            notifyItemChanged(newIndex, PAYLOAD_PLAY_STATE)
        } else if (oldIndex != newIndex) {
            notifyItemRangeChanged(0, channels.size, PAYLOAD_PLAY_STATE)
        }
    }

    fun updateChannelItem(channel: Channel) {
        val index = channels.indexOfFirst { it.streamUrl == channel.streamUrl }
        if (index != -1) {
            channels[index] = channel
            notifyItemChanged(index)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val channel = channels[position]
        return when {
            channel.id == SportsLiveManager.ACTION_REFRESH_SPORTS || channel.streamUrl == "action://refresh_sports" -> VIEW_TYPE_REFRESH
            channel.streamUrl.startsWith("match://") -> VIEW_TYPE_MATCH_CARD
            position % 2 == 1 -> VIEW_TYPE_ODD
            else -> VIEW_TYPE_EVEN
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_MATCH_CARD -> {
                val binding = ItemMatchCardBinding.inflate(inflater, parent, false)
                MatchCardViewHolder(binding)
            }
            VIEW_TYPE_REFRESH -> {
                val binding = ItemChannelBinding.inflate(inflater, parent, false)
                binding.root.setBackgroundResource(R.drawable.selector_channel_item_even)
                RefreshViewHolder(binding)
            }
            VIEW_TYPE_ODD -> {
                val binding = ItemChannelBinding.inflate(inflater, parent, false)
                binding.root.setBackgroundResource(R.drawable.selector_channel_item_odd)
                ChannelViewHolder(binding)
            }
            else -> {
                val binding = ItemChannelBinding.inflate(inflater, parent, false)
                binding.root.setBackgroundResource(R.drawable.selector_channel_item_even)
                ChannelViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val channel = channels[position]
        when (holder) {
            is MatchCardViewHolder -> {
                val matchKey = channel.streamUrl.removePrefix("match://")
                val mg = matchGroupProvider?.invoke(matchKey)
                holder.bind(channel, mg, position)
            }
            is ChannelViewHolder -> {
                holder.bind(channel, position)
            }
            is RefreshViewHolder -> {
                holder.bind(channel)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            val channel = channels[position]
            when (holder) {
                is MatchCardViewHolder -> holder.updatePlayState(channel)
                is ChannelViewHolder -> holder.updatePlayState(channel)
                is RefreshViewHolder -> {}
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = channels.size

    // ================= 1. VIEW HOLDER CHO THẺ TRẬN ĐẤU LIVE EVENTS (GIAO DIỆN WEB ĐỒNG BỘ) =================
    inner class MatchCardViewHolder(
        private val binding: ItemMatchCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelClick(channels[pos])
                }
            }

            binding.root.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && onChannelLongClick != null) {
                    onChannelLongClick.invoke(channels[pos])
                    true
                } else {
                    false
                }
            }

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < channels.size) {
                        onChannelFocused?.invoke(channels[pos])
                    }
                }
            }
        }

        fun updatePlayState(channel: Channel) {
            val isPlaying = isChannelPlaying(channel)
            binding.root.isSelected = isPlaying
        }

        fun bind(channel: Channel, mg: SportsLiveManager.MatchGroup?, position: Int) {
            val isPlaying = isChannelPlaying(channel)
            binding.root.isSelected = isPlaying

            if (mg == null) {
                binding.tvSportBadge.text = "⚽ Bóng đá"
                binding.tvLeagueName.text = channel.groupTitle
                binding.tvMatchTime.visibility = View.GONE
                binding.tvMatchStatus.text = "TRỰC TIẾP"
                binding.tvHomeName.text = channel.name
                binding.tvAwayName.text = ""
                binding.tvSourcesCount.text = "● Nhấn OK để xem trực tiếp"
                return
            }

            // 1. Sport badge icon & text
            val (sportIcon, sportName) = when (mg.league.uppercase()) {
                "F1" -> "🏎️" to "Đua xe F1"
                "TENNIS" -> "🎾" to "Quần vợt"
                "NBA" -> "🏀" to "Bóng rổ"
                "RUGBY" -> "🏉" to "Bóng bầu dục"
                "UFC", "BOXING", "MMA" -> "🥊" to "Võ thuật"
                else -> "⚽" to "Bóng đá"
            }
            binding.tvSportBadge.text = "$sportIcon $sportName"

            // 2. League name
            binding.tvLeagueName.text = mg.leagueName.ifBlank { "Trực tiếp thể thao" }

            // 3. Time & Date
            val timeStr = if (mg.date.isNotBlank()) "⏰ ${mg.time} (${mg.date})" else "⏰ ${mg.time}"
            binding.tvMatchTime.text = timeStr
            binding.tvMatchTime.visibility = if (mg.time.isNotBlank() && mg.time != "LIVE") View.VISIBLE else View.GONE

            // 4. Status (LIVE / Sắp đấu)
            if (mg.isOngoing) {
                binding.tvMatchStatus.text = "🔴 LIVE"
                binding.tvMatchStatus.setBackgroundResource(R.drawable.bg_fpt_badge_live)
            } else {
                binding.tvMatchStatus.text = "SẮP ĐẤU"
                binding.tvMatchStatus.setBackgroundResource(R.drawable.bg_fpt_badge)
            }

            // 5. Teams
            binding.tvHomeName.text = mg.homeTeam
            binding.tvAwayName.text = mg.awayTeam

            // 6. Home Logo & Fallback Initials
            val homeLogoUrl = TeamLogoHelper.getTeamLogoUrl(mg.homeTeam, mg.homeLogo)
            val homeInitial = TeamLogoHelper.getInitial(mg.homeTeam)
            binding.tvHomeInitial.text = homeInitial

            if (!homeLogoUrl.isNullOrBlank()) {
                binding.ivHomeLogo.visibility = View.VISIBLE
                binding.tvHomeInitial.visibility = View.GONE
                Glide.with(binding.ivHomeLogo.context)
                    .load(homeLogoUrl)
                    .override(80, 80)
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_tv_placeholder)
                    .error(R.drawable.ic_tv_placeholder)
                    .into(binding.ivHomeLogo)
            } else {
                binding.ivHomeLogo.setImageDrawable(null)
                binding.ivHomeLogo.visibility = View.GONE
                binding.tvHomeInitial.visibility = View.VISIBLE
            }

            // 7. Away Logo & Fallback Initials
            val awayLogoUrl = TeamLogoHelper.getTeamLogoUrl(mg.awayTeam, mg.awayLogo)
            val awayInitial = TeamLogoHelper.getInitial(mg.awayTeam)
            binding.tvAwayInitial.text = awayInitial

            if (!awayLogoUrl.isNullOrBlank()) {
                binding.ivAwayLogo.visibility = View.VISIBLE
                binding.tvAwayInitial.visibility = View.GONE
                Glide.with(binding.ivAwayLogo.context)
                    .load(awayLogoUrl)
                    .override(80, 80)
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_tv_placeholder)
                    .error(R.drawable.ic_tv_placeholder)
                    .into(binding.ivAwayLogo)
            } else {
                binding.ivAwayLogo.setImageDrawable(null)
                binding.ivAwayLogo.visibility = View.GONE
                binding.tvAwayInitial.visibility = View.VISIBLE
            }

            // 8. Sources Count
            val sourceCount = mg.sources.size
            binding.tvSourcesCount.text = "● $sourceCount luồng phát - Nhấn để chọn kênh"
        }
    }

    // ================= 2. VIEW HOLDER CHO NÚT CẬP NHẬT TRẬN ĐẤU =================
    inner class RefreshViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelClick(channels[pos])
                }
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < channels.size) {
                        onChannelFocused?.invoke(channels[pos])
                    }
                }
            }
        }

        fun bind(channel: Channel) {
            binding.tvChannelNumber.text = "🔄"
            binding.ivChannelLogo.setImageResource(R.drawable.ic_refresh)
            binding.tvChannelName.text = channel.name
            binding.tvResolutionBadge.visibility = View.GONE
            binding.tvNowPlaying.visibility = View.GONE
            binding.tvDefaultBadge.visibility = View.GONE
            binding.ivFavorite.visibility = View.GONE
            binding.root.isSelected = false
        }
    }

    // ================= 3. VIEW HOLDER CHO KÊNH TRUYỀN HÌNH BÌNH THƯỜNG =================
    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelClick(channels[pos])
                }
            }

            binding.root.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && onChannelLongClick != null) {
                    onChannelLongClick.invoke(channels[pos])
                    true
                } else {
                    false
                }
            }

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < channels.size) {
                        onChannelFocused?.invoke(channels[pos])
                    }
                }
            }
        }

        fun updatePlayState(channel: Channel) {
            val isPlaying = isChannelPlaying(channel)
            binding.tvNowPlaying.visibility = if (isPlaying) View.VISIBLE else View.GONE
            binding.root.isSelected = isPlaying
        }

        fun bind(channel: Channel, position: Int) {
            binding.tvChannelNumber.text = String.format("%02d", channel.number)
            binding.tvChannelName.text = channel.name

            val isPlaying = isChannelPlaying(channel)
            binding.tvNowPlaying.visibility = if (isPlaying) View.VISIBLE else View.GONE
            binding.tvDefaultBadge.visibility = if (channel.isDefaultStartup) View.VISIBLE else View.GONE
            binding.ivFavorite.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            binding.root.isSelected = isPlaying

            val res = channel.getOrComputeResolution()
            if (res.isNotEmpty()) {
                binding.tvResolutionBadge.visibility = View.VISIBLE
                binding.tvResolutionBadge.text = res
            } else {
                binding.tvResolutionBadge.visibility = View.GONE
            }

            val resolvedLogo = channel.getOrResolveLogo()
            if (resolvedLogo.isNotBlank()) {
                Glide.with(binding.ivChannelLogo.context)
                    .load(resolvedLogo)
                    .override(100, 60)
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_tv_placeholder)
                    .error(R.drawable.ic_tv_placeholder)
                    .into(binding.ivChannelLogo)
            } else {
                binding.ivChannelLogo.setImageResource(R.drawable.ic_tv_placeholder)
            }
        }
    }
}
