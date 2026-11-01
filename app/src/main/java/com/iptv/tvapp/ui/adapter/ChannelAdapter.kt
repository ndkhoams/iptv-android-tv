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
import com.iptv.tvapp.util.FptLogoHelper

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelLongClick: ((Channel) -> Unit)? = null,
    private val onChannelFocused: ((Channel) -> Unit)? = null
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private val channels = mutableListOf<Channel>()
    private var playingChannel: Channel? = null

    fun submitList(list: List<Channel>) {
        channels.clear()
        channels.addAll(list)
        notifyDataSetChanged()
    }

    fun setPlayingChannel(channel: Channel?) {
        val oldPos = channels.indexOfFirst { it.streamUrl == playingChannel?.streamUrl }
        val newPos = channels.indexOfFirst { it.streamUrl == channel?.streamUrl }
        playingChannel = channel

        if (oldPos != -1) notifyItemChanged(oldPos)
        if (newPos != -1) notifyItemChanged(newPos)
    }

    fun updateChannelItem(channel: Channel) {
        val index = channels.indexOfFirst { it.streamUrl == channel.streamUrl }
        if (index != -1) {
            channels[index] = channel
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(channels[position], position)
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

            // TV Focus nhẹ mượt trên Mi Box 3c (Không chạy animation nặng, dùng selector drawable)
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < channels.size) {
                        onChannelFocused?.invoke(channels[pos])
                    }
                }
            }
        }

        fun bind(channel: Channel, position: Int) {
            if (position % 2 == 1) {
                binding.root.setBackgroundResource(R.drawable.selector_channel_item_odd)
            } else {
                binding.root.setBackgroundResource(R.drawable.selector_channel_item_even)
            }

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

            val isPlaying = channel.streamUrl == playingChannel?.streamUrl
            binding.tvNowPlaying.visibility = if (isPlaying) View.VISIBLE else View.GONE
            binding.tvDefaultBadge.visibility = if (channel.isDefaultStartup) View.VISIBLE else View.GONE
            binding.ivFavorite.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            binding.root.isSelected = isPlaying

            // Phân giải chất lượng từ tên kênh: Kênh HD tại VN là FHD 1080i/1080p
            val upperName = channel.name.uppercase()
            when {
                upperName.contains("4K") -> {
                    binding.tvResolutionBadge.visibility = View.VISIBLE
                    binding.tvResolutionBadge.text = "4K"
                }
                upperName.contains("720") -> {
                    binding.tvResolutionBadge.visibility = View.VISIBLE
                    binding.tvResolutionBadge.text = "720p"
                }
                upperName.contains("FHD") || upperName.contains("1080") || upperName.contains("HD") -> {
                    binding.tvResolutionBadge.visibility = View.VISIBLE
                    binding.tvResolutionBadge.text = "FHD"
                }
                else -> {
                    binding.tvResolutionBadge.visibility = View.GONE
                }
            }

            // Tự động phân giải CDN Logo FPT Play (Khống chế kích thước 100x60 và định dạng RGB_565 tiết kiệm 80% RAM)
            val resolvedLogo = FptLogoHelper.getResolvedLogoUrl(channel.name, channel.logoUrl)
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
