package com.iptv.tvapp.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Toast
import com.iptv.tvapp.R
import com.iptv.tvapp.data.PreferencesHelper
import com.iptv.tvapp.databinding.DialogChannelActionsBinding
import com.iptv.tvapp.model.Channel

class ChannelActionDialog(
    context: Context,
    private val channel: Channel,
    private val onActionCompleted: () -> Unit
) : Dialog(context, R.style.TvDialogTheme) {

    private lateinit var binding: DialogChannelActionsBinding
    private val prefs = PreferencesHelper(context)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogChannelActionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvActionChannelTitle.text = "[${String.format("%02d", channel.number)}] ${channel.name}"

        // Trạng thái nút Yêu thích
        if (channel.isFavorite) {
            binding.btnToggleFavorite.text = "★ Bỏ khỏi Kênh Yêu Thích"
        } else {
            binding.btnToggleFavorite.text = "★ Thêm vào Kênh Yêu Thích"
        }

        binding.btnToggleFavorite.setOnClickListener {
            val isNowFav = prefs.toggleFavorite(channel.streamUrl)
            channel.isFavorite = isNowFav
            Toast.makeText(
                context,
                if (isNowFav) "Đã thêm vào mục Yêu Thích ★" else "Đã xóa khỏi mục Yêu Thích",
                Toast.LENGTH_SHORT
            ).show()
            onActionCompleted()
            dismiss()
        }

        // Đặt làm kênh mở mặc định
        binding.btnSetDefaultStartup.setOnClickListener {
            prefs.defaultStartupChannelUrl = channel.streamUrl
            prefs.defaultStartupChannelName = channel.name
            prefs.startupMode = PreferencesHelper.MODE_FIXED_CHANNEL
            Toast.makeText(context, "Đã đặt [${channel.name}] làm kênh mở khi khởi động!", Toast.LENGTH_SHORT).show()
            onActionCompleted()
            dismiss()
        }

        binding.btnCancelAction.setOnClickListener {
            dismiss()
        }

        binding.btnToggleFavorite.requestFocus()
    }
}
