package com.iptv.tvapp.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.tvapp.R
import com.iptv.tvapp.data.PreferencesHelper
import com.iptv.tvapp.databinding.DialogSettingsBinding
import com.iptv.tvapp.model.PlaylistSource
import com.iptv.tvapp.ui.adapter.PlaylistSourceAdapter
import java.util.UUID

class SettingsDialog(
    context: Context,
    private val onSettingsChanged: (playlistChanged: Boolean, engineChanged: Boolean) -> Unit
) : Dialog(context, R.style.TvDialogTheme) {

    private lateinit var binding: DialogSettingsBinding
    private val prefs = PreferencesHelper(context)
    private lateinit var adapter: PlaylistSourceAdapter
    private var playlistList = mutableListOf<PlaylistSource>()
    private var hasChangedPlaylist = false
    private var hasChangedEngine = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initPlaylistsList()
        initPlayerEngineSettings()
        initBootSettings()
        initUdpxySettings()
        initFocusNavigation()

        binding.btnBrowseLocalFile.setOnClickListener {
            val picker = LocalFilePickerDialog(
                context = context,
                onFileSelected = { file ->
                    if (binding.etNewPlaylistName.text.isNullOrBlank()) {
                        binding.etNewPlaylistName.setText(file.nameWithoutExtension)
                    }
                    binding.etNewPlaylistUrl.setText(file.absolutePath)
                    binding.btnAddPlaylist.requestFocus()
                    Toast.makeText(context, "Đã chọn file: ${file.name}", Toast.LENGTH_SHORT).show()
                }
            )
            picker.show()
        }

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
            saveSettings()
            onSettingsChanged(hasChangedPlaylist, hasChangedEngine)
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawableResource(R.color.fpt_bg_dark)
        }
    }

    private fun initFocusNavigation() {
        // Khắc phục bẫy focus của bàn phím TV trên trường nhập văn bản
        binding.etNewPlaylistName.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.etNewPlaylistUrl.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.rvPlaylists.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        binding.rbPlayerVlc.requestFocus()
                        true
                    }
                    else -> false
                }
            } else false
        }

        binding.etNewPlaylistUrl.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.btnBrowseLocalFile.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.etNewPlaylistName.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        binding.rbPlayerVlc.requestFocus()
                        true
                    }
                    else -> false
                }
            } else false
        }

        binding.btnBrowseLocalFile.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        binding.btnAddPlaylist.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.etNewPlaylistUrl.requestFocus()
                        true
                    }
                    else -> false
                }
            } else false
        }

        binding.etUdpxyServer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.btnCloseSettings.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.rbFixedChannel.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        binding.btnAddPlaylist.requestFocus()
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun initUdpxySettings() {
        binding.etUdpxyServer.setText(prefs.udpxyServer)
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
                AlertDialog.Builder(context, R.style.TvDialogTheme)
                    .setTitle("Xác nhận xóa")
                    .setMessage("Bạn có chắc chắn muốn xóa danh sách \"${sourceToDelete.name}\"?")
                    .setPositiveButton("Xóa") { _, _ ->
                        playlistList.removeAll { it.id == sourceToDelete.id }
                        if (sourceToDelete.isSelected) {
                            playlistList[0].isSelected = true
                            hasChangedPlaylist = true
                        }
                        prefs.savePlaylists(playlistList)
                        adapter.submitList(playlistList.toList())
                        Toast.makeText(context, "Đã xóa danh sách", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        )

        binding.rvPlaylists.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SettingsDialog.adapter
        }
        adapter.submitList(playlistList.toList())
    }

    private fun initPlayerEngineSettings() {
        if (prefs.playerEngine == PreferencesHelper.ENGINE_EXOPLAYER) {
            binding.rbPlayerExo.isChecked = true
        } else {
            binding.rbPlayerVlc.isChecked = true
        }
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

    private fun saveSettings() {
        val selectedEngine = if (binding.rbPlayerExo.isChecked) {
            PreferencesHelper.ENGINE_EXOPLAYER
        } else {
            PreferencesHelper.ENGINE_VLC
        }

        if (selectedEngine != prefs.playerEngine) {
            prefs.playerEngine = selectedEngine
            hasChangedEngine = true
        }

        prefs.autoStartOnBoot = binding.cbAutoStartOnBoot.isChecked
        prefs.startupMode = if (binding.rbFixedChannel.isChecked) {
            PreferencesHelper.MODE_FIXED_CHANNEL
        } else {
            PreferencesHelper.MODE_LAST_WATCHED
        }

        val newUdpxy = binding.etUdpxyServer.text.toString().trim()
        if (newUdpxy != prefs.udpxyServer) {
            prefs.udpxyServer = newUdpxy
            hasChangedPlaylist = true
        }
    }
}
