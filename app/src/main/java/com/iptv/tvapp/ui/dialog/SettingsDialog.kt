package com.iptv.tvapp.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
        initDeinterlaceSettings()
        initUserAgentSettings()
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

        val existingBackup = prefs.getAvailableBackupFile()
        if (existingBackup != null) {
            binding.tvBackupStatus.text = "Bản sao lưu hiện có: ${existingBackup.name} (${existingBackup.parent})"
        }

        binding.btnBackupConfig.setOnClickListener {
            val file = prefs.saveAutoBackup()
            if (file != null) {
                binding.tvBackupStatus.text = "✓ Đã sao lưu thành công: ${file.name}\n${file.absolutePath}"
                binding.tvBackupStatus.setTextColor(Color.parseColor("#34D399"))
                Toast.makeText(context, "Đã sao lưu cấu hình: ${file.name}", Toast.LENGTH_LONG).show()
            } else {
                binding.tvBackupStatus.text = "✗ Lỗi khi sao lưu dữ liệu!"
                binding.tvBackupStatus.setTextColor(Color.parseColor("#EF4444"))
                Toast.makeText(context, "Lỗi khi sao lưu dữ liệu!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRestoreConfig.setOnClickListener {
            val picker = LocalFilePickerDialog(
                context = context,
                onFileSelected = { file ->
                    try {
                        val content = file.readText(Charsets.UTF_8)
                        if (prefs.restoreFromBackupJson(content)) {
                            hasChangedPlaylist = true
                            initPlaylistsList()
                            initPlayerEngineSettings()
                            initDeinterlaceSettings()
                            initUserAgentSettings()
                            initBootSettings()
                            initUdpxySettings()
                            binding.tvBackupStatus.text = "✓ Đã khôi phục thành công từ file: ${file.name}"
                            binding.tvBackupStatus.setTextColor(Color.parseColor("#34D399"))
                            Toast.makeText(context, "Đã khôi phục thành công từ: ${file.name}", Toast.LENGTH_LONG).show()
                        } else {
                            binding.tvBackupStatus.text = "✗ File sao lưu không đúng định dạng!"
                            binding.tvBackupStatus.setTextColor(Color.parseColor("#EF4444"))
                            Toast.makeText(context, "File sao lưu không đúng định dạng!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        binding.tvBackupStatus.text = "✗ Lỗi đọc file: ${e.message}"
                        binding.tvBackupStatus.setTextColor(Color.parseColor("#EF4444"))
                        Toast.makeText(context, "Lỗi đọc file: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            picker.show()
        }

        binding.btnCloseSettings.setOnClickListener {
            saveSettings()
            onSettingsChanged(hasChangedPlaylist, hasChangedEngine)
            dismiss()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_HELP) {
            saveSettings()
            onSettingsChanged(hasChangedPlaylist, hasChangedEngine)
            dismiss()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawableResource(R.color.fpt_bg_dark)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun showSoftKeyboard(view: View) {
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_FORCED)
    }

    private fun initFocusNavigation() {
        // Khắc phục bẫy focus của bàn phím TV trên trường nhập văn bản
        binding.etNewPlaylistName.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        showSoftKeyboard(v)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.etNewPlaylistUrl.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.rvPlaylists.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        binding.etUdpxyServer.requestFocus()
                        true
                    }
                    else -> false
                }
            } else false
        }
        binding.etNewPlaylistName.setOnClickListener { v ->
            showSoftKeyboard(v)
        }

        binding.etNewPlaylistUrl.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        showSoftKeyboard(v)
                        true
                    }
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
        binding.etNewPlaylistUrl.setOnClickListener { v ->
            showSoftKeyboard(v)
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

        binding.etUdpxyServer.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        showSoftKeyboard(v)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.rbPlayerVlc.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        binding.etNewPlaylistName.requestFocus()
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun initUdpxySettings() {
        binding.etUdpxyServer.setText(prefs.udpxyServer)

        // Tự động cuộn lên vị trí đầu trang khi focus hoặc mở bàn phím ảo
        binding.etUdpxyServer.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.scrollViewSettingsRight.post {
                    binding.scrollViewSettingsRight.smoothScrollTo(0, 0)
                }
            }
        }
        binding.etUdpxyServer.setOnClickListener { v ->
            showSoftKeyboard(v)
            binding.scrollViewSettingsRight.post {
                binding.scrollViewSettingsRight.smoothScrollTo(0, 0)
            }
        }
        binding.etUdpxyServer.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                binding.rbPlayerVlc.requestFocus()
                true
            } else false
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

    private fun initDeinterlaceSettings() {
        when (prefs.deinterlaceMode) {
            PreferencesHelper.DEINTERLACE_LINEAR -> binding.rbDeinterlaceLinear.isChecked = true
            PreferencesHelper.DEINTERLACE_BLEND -> binding.rbDeinterlaceBlend.isChecked = true
            PreferencesHelper.DEINTERLACE_OFF -> binding.rbDeinterlaceOff.isChecked = true
            else -> binding.rbDeinterlaceYadif.isChecked = true
        }
    }

    private fun initUserAgentSettings() {
        binding.etHttpUserAgent.setText(prefs.httpUserAgent)
        binding.btnResetUserAgent.setOnClickListener {
            binding.etHttpUserAgent.setText(PreferencesHelper.DEFAULT_USER_AGENT)
            Toast.makeText(context, "Đã khôi phục User-Agent FPT Play Box!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initBootSettings() {
        when (prefs.startupMode) {
            PreferencesHelper.MODE_FIXED_CHANNEL -> binding.rbFixedChannel.isChecked = true
            PreferencesHelper.MODE_LAST_WATCHED -> binding.rbLastWatched.isChecked = true
            else -> binding.rbFirstChannel.isChecked = true
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

        val selectedDeinterlace = when {
            binding.rbDeinterlaceLinear.isChecked -> PreferencesHelper.DEINTERLACE_LINEAR
            binding.rbDeinterlaceBlend.isChecked -> PreferencesHelper.DEINTERLACE_BLEND
            binding.rbDeinterlaceOff.isChecked -> PreferencesHelper.DEINTERLACE_OFF
            else -> PreferencesHelper.DEINTERLACE_YADIF
        }

        if (selectedDeinterlace != prefs.deinterlaceMode) {
            prefs.deinterlaceMode = selectedDeinterlace
            hasChangedEngine = true
        }

        val newUa = binding.etHttpUserAgent.text.toString().trim()
        if (newUa.isNotBlank() && newUa != prefs.httpUserAgent) {
            prefs.httpUserAgent = newUa
            hasChangedEngine = true
            hasChangedPlaylist = true
        }

        prefs.startupMode = when {
            binding.rbFixedChannel.isChecked -> PreferencesHelper.MODE_FIXED_CHANNEL
            binding.rbLastWatched.isChecked -> PreferencesHelper.MODE_LAST_WATCHED
            else -> PreferencesHelper.MODE_FIRST_CHANNEL
        }

        val newUdpxy = binding.etUdpxyServer.text.toString().trim()
        if (newUdpxy != prefs.udpxyServer) {
            prefs.udpxyServer = newUdpxy
            hasChangedPlaylist = true
        }
    }
}
