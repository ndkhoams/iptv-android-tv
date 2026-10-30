package com.iptv.tvapp.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Toast
import com.iptv.tvapp.R
import com.iptv.tvapp.databinding.DialogPlaylistBinding

class PlaylistDialog(
    context: Context,
    private val currentUrl: String,
    private val onSaveUrl: (String) -> Unit
) : Dialog(context, R.style.TvDialogTheme) {

    private lateinit var binding: DialogPlaylistBinding

    companion object {
        const val SAMPLE_VN_URL = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/vn.m3u"
        const val SAMPLE_NEWS_URL = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/news.m3u"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etPlaylistUrl.setText(currentUrl)

        // Phím mẫu Kênh Việt Nam
        binding.btnSampleVn.setOnClickListener {
            binding.etPlaylistUrl.setText(SAMPLE_VN_URL)
        }

        // Phím mẫu Tin Tức Quốc Tế
        binding.btnSampleGlobal.setOnClickListener {
            binding.etPlaylistUrl.setText(SAMPLE_NEWS_URL)
        }

        // Phím Hủy
        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        // Phím Lưu & Tải
        binding.btnSave.setOnClickListener {
            val url = binding.etPlaylistUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(context, "Vui lòng nhập đường link URL!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onSaveUrl(url)
            dismiss()
        }

        // Đặt focus ban đầu vào ô nhập URL
        binding.etPlaylistUrl.requestFocus()
    }
}
