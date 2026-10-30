package com.iptv.tvapp.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.tvapp.R
import com.iptv.tvapp.data.PreferencesHelper
import com.iptv.tvapp.databinding.DialogSettingsBinding
import com.iptv.tvapp.model.PlaylistSource
import com.iptv.tvapp.ui.adapter.PlaylistSourceAdapter
import java.util.UUID

class SettingsDialog(
    context: Context,
    private val onPlaylistChanged: () -> Unit
) : Dialog(context, R.style.TvDialogTheme) {

    private lateinit var binding: DialogSettingsBinding
    private val prefs = PreferencesHelper(context)
    private lateinit var adapter: PlaylistSourceAdapter
    private var playlistList = mutableListOf<PlaylistSource>()
    private var hasChangedPlaylist = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initPlaylistsList()
        initBootSettings()

        binding.btnAddPlaylist.setOnClickListener {
            val name = binding.etNewPlaylistName.text.toString().trim()
            val url = binding.etNewPlaylistUrl.text.toString().trim()

            if (name.isBlank() || url.isBlank()) {
                Toast.makeText(context, "Vui lòng nhập tên và đường link M3U!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newSource = PlaylistSource(
                id = UUID.randomUUID().toString(),
                name = name,
                url = url,
                isSelected = false
            )
            playlistList.add(newSource)
            prefs.savePlaylists(playlistList)
            adapter.submitList(playlistList.toList())

            binding.etNewPlaylistName.text?.clear()
            binding.etNewPlaylistUrl.text?.clear()
            Toast.makeText(context, "Đã thêm danh sách thành công!", Toast.LENGTH_SHORT).show()
        }

        binding.btnCloseSettings.setOnClickListener {
            saveBootSettings()
            if (hasChangedPlaylist) {
                onPlaylistChanged()
            }
            dismiss()
        }
    }

    private fun initPlaylistsList() {
        playlistList = prefs.getPlaylists()
        adapter = PlaylistSourceAdapter(
            onSelect = { selectedSource ->
                prefs.setActivePlaylist(selectedSource.id)
                playlistList = prefs.getPlaylists()
                adapter.submitList(playlistList.toList())
                hasChangedPlaylist = true
                Toast.makeText(context, "Đã chuyển sang: ${selectedSource.name}", Toast.LENGTH_SHORT).show()
            },
            onDelete = { sourceToDelete ->
                if (playlistList.size <= 1) {
                    Toast.makeText(context, "Cần giữ lại ít nhất 1 danh sách!", Toast.LENGTH_SHORT).show()
                    return@PlaylistSourceAdapter
                }
                playlistList.removeAll { it.id == sourceToDelete.id }
                if (sourceToDelete.isSelected) {
                    playlistList[0].isSelected = true
                    hasChangedPlaylist = true
                }
                prefs.savePlaylists(playlistList)
                adapter.submitList(playlistList.toList())
                Toast.makeText(context, "Đã xóa danh sách", Toast.LENGTH_SHORT).show()
            }
        )

        binding.rvPlaylists.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SettingsDialog.adapter
        }
        adapter.submitList(playlistList.toList())
    }

    private fun initBootSettings() {
        binding.cbAutoStartOnBoot.isChecked = prefs.autoStartOnBoot

        if (prefs.startupMode == PreferencesHelper.MODE_FIXED_CHANNEL) {
            binding.rbFixedChannel.isChecked = true
        } else {
            binding.rbLastWatched.isChecked = true
        }

        val fixedName = prefs.defaultStartupChannelName
        if (!fixedName.isNullOrBlank()) {
            binding.tvCurrentFixedChannel.text = "Kênh đang chọn: $fixedName"
        } else {
            binding.tvCurrentFixedChannel.text = "Chưa thiết lập (Giữ phím OK trên kênh để chọn)"
        }
    }

    private fun saveBootSettings() {
        prefs.autoStartOnBoot = binding.cbAutoStartOnBoot.isChecked
        prefs.startupMode = if (binding.rbFixedChannel.isChecked) {
            PreferencesHelper.MODE_FIXED_CHANNEL
        } else {
            PreferencesHelper.MODE_LAST_WATCHED
        }
    }
}
