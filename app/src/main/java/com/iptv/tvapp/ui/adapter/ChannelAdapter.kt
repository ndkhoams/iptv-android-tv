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
    private val onChannelLongClick: ((Channel) -> Unit)? = null
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
        holder.bind(channels[position])
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

            // Hiệu ứng phóng to nhẹ khi Remote focus vào item trên Android TV
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.03f).scaleY(1.03f).setDuration(150).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
        }

        fun bind(channel: Channel) {
            binding.tvChannelNumber.text = String.format("%02d", channel.number)
            binding.tvChannelName.text = channel.name

            val isPlaying = channel.streamUrl == playingChannel?.streamUrl
            binding.tvNowPlaying.visibility = if (isPlaying) View.VISIBLE else View.GONE
            binding.tvDefaultBadge.visibility = if (channel.isDefaultStartup) View.VISIBLE else View.GONE
            binding.ivFavorite.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            binding.root.isSelected = isPlaying

            // Load logo kênh bằng Glide
            if (!channel.logoUrl.isNullOrBlank()) {
                Glide.with(binding.ivChannelLogo.context)
                    .load(channel.logoUrl)
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
