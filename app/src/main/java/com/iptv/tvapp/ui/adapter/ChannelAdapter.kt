package com.iptv.tvapp.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.iptv.tvapp.R
import com.iptv.tvapp.databinding.ItemChannelBinding
import com.iptv.tvapp.model.Channel

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelLongClick: ((Channel) -> Unit)? = null,
    private val onChannelFocused: ((Channel) -> Unit)? = null
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    companion object {
        private const val PAYLOAD_PLAY_STATE = 1
        private const val VIEW_TYPE_EVEN = 0
        private const val VIEW_TYPE_ODD = 1
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
        return if (position % 2 == 1) VIEW_TYPE_ODD else VIEW_TYPE_EVEN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        // Gán Drawable background 1 lần duy nhất lúc tạo View, triệt tiêu re-inflate XML Drawable khi cuộn
        if (viewType == VIEW_TYPE_ODD) {
            binding.root.setBackgroundResource(R.drawable.selector_channel_item_odd)
        } else {
            binding.root.setBackgroundResource(R.drawable.selector_channel_item_even)
        }
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(channels[position], position)
    }

    override fun onBindViewHolder(
        holder: ChannelViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            holder.updatePlayState(channels[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = channels.size

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

            // TV Focus nhẹ mượt trên Mi Box 3c
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
            if (channel.id == "action_refresh_sports" || channel.streamUrl == "action://refresh_sports") {
                binding.tvChannelNumber.text = "🔄"
                binding.ivChannelLogo.setImageResource(R.drawable.ic_tv_placeholder)
                binding.tvChannelName.text = channel.name
                binding.tvResolutionBadge.visibility = View.GONE
                binding.tvNowPlaying.visibility = View.GONE
                binding.tvDefaultBadge.visibility = View.GONE
                binding.ivFavorite.visibility = View.GONE
                binding.root.isSelected = false
                return
            }

            binding.tvChannelNumber.text = String.format("%02d", channel.number)
            binding.tvChannelName.text = channel.name

            val isPlaying = isChannelPlaying(channel)
            binding.tvNowPlaying.visibility = if (isPlaying) View.VISIBLE else View.GONE
            binding.tvDefaultBadge.visibility = if (channel.isDefaultStartup) View.VISIBLE else View.GONE
            binding.ivFavorite.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            binding.root.isSelected = isPlaying

            // Sử dụng dữ liệu độ phân giải đã được tiền tính toán (Pre-computed) - 0 allocations
            val res = channel.getOrComputeResolution()
            if (res.isNotEmpty()) {
                binding.tvResolutionBadge.visibility = View.VISIBLE
                binding.tvResolutionBadge.text = res
            } else {
                binding.tvResolutionBadge.visibility = View.GONE
            }

            // Tự động phân giải CDN Logo FPT Play (đã cache URL, định dạng RGB_565 tiết kiệm 80% RAM)
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
