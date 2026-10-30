package com.iptv.tvapp.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvapp.R
import com.iptv.tvapp.data.PreferencesHelper
import com.iptv.tvapp.databinding.DialogQuickPlaylistBinding
import com.iptv.tvapp.databinding.ItemQuickPlaylistBinding
import com.iptv.tvapp.model.PlaylistSource

class QuickPlaylistDialog(
    context: Context,
    private val onPlaylistSelected: (PlaylistSource) -> Unit,
    private val onOpenFullSettings: () -> Unit
) : Dialog(context, R.style.TvDialogTheme) {

    private lateinit var binding: DialogQuickPlaylistBinding
    private val prefs = PreferencesHelper(context)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogQuickPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val playlists = prefs.getPlaylists()
        val adapter = QuickPlaylistAdapter(playlists) { selected ->
            prefs.setActivePlaylist(selected.id)
            Toast.makeText(context, "Đã chuyển sang: ${selected.name}", Toast.LENGTH_SHORT).show()
            onPlaylistSelected(selected)
            dismiss()
        }

        binding.rvQuickPlaylists.layoutManager = LinearLayoutManager(context)
        binding.rvQuickPlaylists.adapter = adapter

        val selectedIndex = playlists.indexOfFirst { it.isSelected }
        if (selectedIndex != -1) {
            binding.rvQuickPlaylists.scrollToPosition(selectedIndex)
            binding.rvQuickPlaylists.post {
                binding.rvQuickPlaylists.findViewHolderForAdapterPosition(selectedIndex)?.itemView?.requestFocus()
            }
        }

        binding.btnManagePlaylists.setOnClickListener {
            dismiss()
            onOpenFullSettings()
        }

        binding.btnCloseQuickDialog.setOnClickListener {
            dismiss()
        }
    }

    private class QuickPlaylistAdapter(
        private val items: List<PlaylistSource>,
        private val onClick: (PlaylistSource) -> Unit
    ) : RecyclerView.Adapter<QuickPlaylistAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemQuickPlaylistBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemQuickPlaylistBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                binding.root.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onClick(items[pos])
                    }
                }
                binding.root.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100).start()
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    }
                }
            }

            fun bind(item: PlaylistSource) {
                binding.tvPlaylistName.text = item.name
                if (item.isSelected) {
                    binding.tvRadioIcon.text = "◉"
                    binding.tvRadioIcon.setTextColor(Color.parseColor("#FF6500"))
                    binding.tvActiveBadge.visibility = View.VISIBLE
                } else {
                    binding.tvRadioIcon.text = "○"
                    binding.tvRadioIcon.setTextColor(Color.parseColor("#64748B"))
                    binding.tvActiveBadge.visibility = View.GONE
                }
            }
        }
    }
}

