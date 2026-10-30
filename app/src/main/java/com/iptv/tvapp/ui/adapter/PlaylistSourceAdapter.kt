package com.iptv.tvapp.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvapp.databinding.ItemPlaylistSourceBinding
import com.iptv.tvapp.model.PlaylistSource

class PlaylistSourceAdapter(
    private val onSelect: (PlaylistSource) -> Unit,
    private val onDelete: (PlaylistSource) -> Unit
) : RecyclerView.Adapter<PlaylistSourceAdapter.ViewHolder>() {

    private val playlists = mutableListOf<PlaylistSource>()

    fun submitList(list: List<PlaylistSource>) {
        playlists.clear()
        playlists.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaylistSourceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(playlists[position])
    }

    override fun getItemCount(): Int = playlists.size

    inner class ViewHolder(
        private val binding: ItemPlaylistSourceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onSelect(playlists[pos])
                }
            }

            binding.btnDeletePlaylist.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDelete(playlists[pos])
                }
            }
        }

        fun bind(source: PlaylistSource) {
            binding.tvPlaylistName.text = source.name
            binding.tvPlaylistUrl.text = source.url

            binding.ivSelectedCheck.visibility = if (source.isSelected) View.VISIBLE else View.INVISIBLE
            binding.root.isSelected = source.isSelected
        }
    }
}
