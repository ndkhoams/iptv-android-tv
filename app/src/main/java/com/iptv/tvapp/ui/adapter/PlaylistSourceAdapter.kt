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
            // D-pad OK để chọn Playlist
            binding.layoutPlaylistSelect.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onSelect(playlists[pos])
                }
            }

            // Hỗ trợ giữ phím OK trực tiếp trên playlist để xóa
            binding.layoutPlaylistSelect.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDelete(playlists[pos])
                }
                true
            }

            // D-pad Sang Phải -> Bấm OK trên nút Thùng Rác để xóa
            binding.btnDeletePlaylist.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDelete(playlists[pos])
                }
            }

            // Ràng buộc điều hướng D-pad 4 nút
            binding.layoutPlaylistSelect.nextFocusRightId = binding.btnDeletePlaylist.id
            binding.btnDeletePlaylist.nextFocusLeftId = binding.layoutPlaylistSelect.id
        }

        fun bind(source: PlaylistSource) {
            binding.tvPlaylistName.text = source.name
            binding.tvPlaylistUrl.text = source.url

            binding.ivSelectedCheck.visibility = if (source.isSelected) View.VISIBLE else View.INVISIBLE
            binding.layoutPlaylistSelect.isSelected = source.isSelected
        }
    }
}
