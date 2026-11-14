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

    var isSportsMode: Boolean = false
    var channelMatchFinder: ((Channel) -> SportsLiveManager.MatchGroup?)? = null

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
        if (playingMatchKey != null) {
            val mg = channelMatchFinder?.invoke(channel)
            if (mg != null && mg.key == playingMatchKey) return true
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
            if (matchKey != null) {
                val mg = channelMatchFinder?.invoke(ch)
                if (mg != null && mg.key == matchKey) return@indexOfFirst true
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
            channel.streamUrl.startsWith("match://") || isSportsMode -> VIEW_TYPE_MATCH_CARD
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
                val mg = if (channel.streamUrl.startsWith("match://")) {
                    val matchKey = channel.streamUrl.removePrefix("match://")
                    matchGroupProvider?.invoke(matchKey)
                } else {
                    channelMatchFinder?.invoke(channel)
                }
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

    fun getChannelAt(position: Int): Channel? {
        return if (position in 0 until channels.size) channels[position] else null
    }

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
                val rawName = channel.name
                val timeMatch = Regex("""\b(\d{1,2}:\d{2})\b""").find(rawName)
                val timeStr = timeMatch?.groupValues?.get(1) ?: "LIVE"
                val dateMatch = Regex("""\b(\d{1,2}[/-]\d{1,2})\b""").find(rawName)
                val dateStr = dateMatch?.groupValues?.get(1) ?: ""

                // Làm sạch tên 2 đội bóng
                val clean = rawName
                    .replace(Regex("""[\u2600-\u26ff\u2700-\u27bf\U0001f300-\U0001f9ff]"""), " ")
                    .replace(Regex("""\[[^\]]*\]"""), " ")
                    .replace(Regex("""\([^\)]*\)"""), " ")
                    .replace(Regex("""(?i)(?:-|\s)?\s*(?:Chuối Chiên|Gà Vàng|Xôi Lạc|Cà Khịa|TQM|Cola|Bia Ôm|Phá Làng|Vua Sân Cỏ|S8|Khô Gà|Giờ Vàng|Sao Kê|Xôi Gấc|Tiếu Lâm|Xôi Chè|Film4k|TV360|Antsports)(?:\s+TV)?\b"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()

                val parts = clean.split(Regex("""\s+(?:vs|Vs|VS|-)\s+"""))
                val homeTeam = if (parts.isNotEmpty()) parts[0].trim() else clean
                val awayTeam = if (parts.size >= 2) parts[1].trim() else ""

                val (sportIcon, sportName) = when {
                    rawName.contains("F1", ignoreCase = true) || rawName.contains("Formula", ignoreCase = true) -> "🏎️" to "Đua xe F1"
                    rawName.contains("Tennis", ignoreCase = true) -> "🎾" to "Quần vợt"
                    rawName.contains("NBA", ignoreCase = true) -> "🏀" to "Bóng rổ"
                    rawName.contains("Rugby", ignoreCase = true) || rawName.contains("🏉") -> "🏉" to "Bóng bầu dục"
                    rawName.contains("NFL", ignoreCase = true) || rawName.contains("NCAAF", ignoreCase = true) || rawName.contains("American Football", ignoreCase = true) || rawName.contains("🏈") ||
                        (rawName.contains("NCAA", ignoreCase = true) && !rawName.contains("soccer", ignoreCase = true) && !rawName.contains("bóng đá", ignoreCase = true)) -> "🏈" to "Bóng bầu dục Mỹ"
                    rawName.contains("UFC", ignoreCase = true) || rawName.contains("Boxing", ignoreCase = true) || rawName.contains("MMA", ignoreCase = true) || rawName.contains("🥊") -> "🥊" to "Võ thuật"
                    else -> "⚽" to "Bóng đá"
                }
                binding.tvSportBadge.text = "$sportIcon $sportName"
                val detectedLg = SportsLiveManager.detectLeague(rawName, homeTeam, awayTeam)
                val cleanLgName = if (detectedLg != "OTHER") {
                    SportsLiveManager.LEAGUE_LABELS[detectedLg] ?: ""
                } else {
                    val rawGrp = channel.groupTitle.replace(Regex("""(?i)(?:LIVE EVENTS|Film4k|F4K Sport|F4K|Xôi Chè|Gà Vàng|Tiếu Lâm|Chuối Chiên|Trực tiếp thể thao|Trực tiếp)\b"""), "").trim()
                    rawGrp.ifBlank { "Bóng đá Quốc tế" }
                }
                binding.tvLeagueName.text = cleanLgName.ifBlank { "Bóng đá Quốc tế" }

                val timeDisplay = if (dateStr.isNotBlank()) "⏰ $timeStr ($dateStr)" else "⏰ $timeStr"
                binding.tvMatchTime.text = timeDisplay
                binding.tvMatchTime.visibility = if (timeStr.isNotBlank() && timeStr != "LIVE") View.VISIBLE else View.GONE

                val isOngoing = rawName.contains("LIVE", ignoreCase = true) || rawName.contains("ĐANG PHÁT", ignoreCase = true) || timeStr == "LIVE"
                if (isOngoing) {
                    binding.tvMatchStatus.text = "🔴 LIVE"
                    binding.tvMatchStatus.setBackgroundResource(R.drawable.bg_fpt_badge_live)
                } else {
                    binding.tvMatchStatus.text = "SẮP ĐẤU"
                    binding.tvMatchStatus.setBackgroundResource(R.drawable.bg_fpt_badge)
                }

                binding.tvHomeName.text = homeTeam
                binding.tvAwayName.text = awayTeam
                binding.tvVsBadge.visibility = if (awayTeam.isNotBlank()) View.VISIBLE else View.GONE
                binding.tvAwayName.visibility = if (awayTeam.isNotBlank()) View.VISIBLE else View.GONE

                val homeLogoUrl = TeamLogoHelper.getTeamLogoUrl(homeTeam, channel.logoUrl)
                val homeInitial = TeamLogoHelper.getInitial(homeTeam)
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

                val awayLogoUrl = if (awayTeam.isNotBlank()) TeamLogoHelper.getTeamLogoUrl(awayTeam, null) else null
                val awayInitial = if (awayTeam.isNotBlank()) TeamLogoHelper.getInitial(awayTeam) else ""
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
                    binding.tvAwayInitial.visibility = if (awayTeam.isNotBlank()) View.VISIBLE else View.GONE
                }

                binding.tvSourcesCount.text = "● Nguồn: ${channel.groupTitle}"
                binding.tvWatchAction.text = if (channel.streamUrl.startsWith("match://")) "Xem luồng ▶" else "Phát ngay ▶"
                return
            }

            // 1. Sport badge icon & text
            val (sportIcon, sportName) = when {
                mg.league.equals("F1", true) -> "🏎️" to "Đua xe F1"
                mg.league.equals("TENNIS", true) -> "🎾" to "Quần vợt"
                mg.league.equals("NBA", true) -> "🏀" to "Bóng rổ"
                mg.league.equals("RUGBY", true) -> "🏉" to "Bóng bầu dục"
                mg.league.equals("NFL", true) || mg.league.equals("NCAAF", true) -> "🏈" to "Bóng bầu dục Mỹ"
                mg.league.uppercase() in listOf("UFC", "BOXING", "MMA") -> "🥊" to "Võ thuật"
                mg.sources.any { it.name.contains("🏈") } || mg.leagueName.contains("NCAA", ignoreCase = true) || mg.leagueName.contains("Bầu dục", ignoreCase = true) || mg.homeTeam.contains("🏈") || mg.awayTeam.contains("🏈") -> "🏈" to "Bóng bầu dục Mỹ"
                mg.sources.any { it.name.contains("🏉") } || mg.leagueName.contains("Rugby", ignoreCase = true) -> "🏉" to "Bóng bầu dục"
                else -> "⚽" to "Bóng đá"
            }
            binding.tvSportBadge.text = "$sportIcon $sportName"

            // 2. League name
            val cleanLg = mg.leagueName
                .replace("🌐 Trực tiếp", "")
                .replace("Trực tiếp", "")
                .trim()
            val finalLgName = when {
                cleanLg.isNotBlank() && !cleanLg.equals("OTHER", true) -> cleanLg
                mg.league.equals("NCAAF", true) -> "NCAA Football"
                mg.league.equals("NFL", true) -> "Bóng bầu dục Mỹ"
                else -> SportsLiveManager.LEAGUE_LABELS[mg.league] ?: "Bóng đá Quốc tế"
            }
            binding.tvLeagueName.text = finalLgName

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
            binding.tvVsBadge.visibility = if (mg.awayTeam.isNotBlank()) View.VISIBLE else View.GONE
            binding.tvAwayName.visibility = if (mg.awayTeam.isNotBlank()) View.VISIBLE else View.GONE

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
            val awayLogoUrl = if (mg.awayTeam.isNotBlank()) TeamLogoHelper.getTeamLogoUrl(mg.awayTeam, mg.awayLogo) else null
            val awayInitial = if (mg.awayTeam.isNotBlank()) TeamLogoHelper.getInitial(mg.awayTeam) else ""
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
                binding.tvAwayInitial.visibility = if (mg.awayTeam.isNotBlank()) View.VISIBLE else View.GONE
            }

            // 8. Sources Count & Action
            val sourceCount = mg.sources.size
            binding.tvSourcesCount.text = if (channel.streamUrl.startsWith("match://")) {
                "● $sourceCount luồng phát - Nhấn để chọn kênh"
            } else {
                "● $sourceCount luồng phát • ${channel.groupTitle}"
            }
            binding.tvWatchAction.text = if (channel.streamUrl.startsWith("match://")) "Xem luồng ▶" else "Phát ngay ▶"
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

            binding.tvResolutionBadge.visibility = View.GONE

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
