package com.iptv.tvapp.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.iptv.tvapp.R
import com.iptv.tvapp.data.PlaylistManager
import com.iptv.tvapp.data.PreferencesHelper
import com.iptv.tvapp.databinding.ActivityMainBinding
import com.iptv.tvapp.model.Category
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.ui.adapter.CategoryAdapter
import com.iptv.tvapp.ui.adapter.ChannelAdapter
import com.iptv.tvapp.ui.dialog.PlaylistDialog
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesHelper
    private lateinit var playlistManager: PlaylistManager

    private var player: ExoPlayer? = null
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private val allCategories = mutableListOf<Category>()
    private var allChannels = mutableListOf<Channel>()
    private var currentCategoryChannels = mutableListOf<Channel>()
    private var currentPlayingChannel: Channel? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private val hideQuickBannerRunnable = Runnable { binding.quickChannelInfo.visibility = View.GONE }

    private var backPressedTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesHelper(this)
        playlistManager = PlaylistManager(this)

        initViews()
        initPlayer()
        loadPlaylist(forceRefresh = false)
    }

    private fun initViews() {
        // Cấu hình danh sách Danh mục (Category)
        categoryAdapter = CategoryAdapter { category ->
            resetOverlayTimer()
            displayCategoryChannels(category)
        }
        binding.rvCategories.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = categoryAdapter
        }

        // Cấu hình danh sách Kênh (Channel)
        channelAdapter = ChannelAdapter { channel ->
            playChannel(channel)
            // Ẩn menu sau khi chọn kênh để xem toàn màn hình
            hideHandler.postDelayed({ hideOverlay() }, 800)
        }
        binding.rvChannels.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelAdapter
        }

        // Nút Cài đặt Playlist
        binding.btnSettings.setOnClickListener {
            showPlaylistDialog()
        }

        // Nút Tải lại Playlist
        binding.btnReload.setOnClickListener {
            loadPlaylist(forceRefresh = true)
        }

        // Lắng nghe tương tác để reset bộ đếm ẩn menu tự động
        binding.rootView.setOnClickListener {
            toggleOverlay()
        }
    }

    private fun initPlayer() {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (SmartTV; Android 5.0) AppleWebKit/537.36 (KHTML, like Gecko) ExoPlayer")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                binding.playerView.player = this
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                binding.layoutBuffering.visibility = View.VISIBLE
                                binding.tvErrorMessage.visibility = View.GONE
                            }
                            Player.STATE_READY -> {
                                binding.layoutBuffering.visibility = View.GONE
                                binding.tvErrorMessage.visibility = View.GONE
                            }
                            Player.STATE_ENDED -> {
                                binding.layoutBuffering.visibility = View.GONE
                            }
                            Player.STATE_IDLE -> {
                                binding.layoutBuffering.visibility = View.GONE
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        binding.layoutBuffering.visibility = View.GONE
                        binding.tvErrorMessage.visibility = View.VISIBLE
                        binding.tvErrorMessage.text = "Lỗi phát kênh: ${error.errorCodeName}\nBấm ▲ ▼ để đổi kênh khác"
                    }
                })
            }
    }

    private fun loadPlaylist(forceRefresh: Boolean) {
        binding.layoutBuffering.visibility = View.VISIBLE
        binding.tvBufferingText.text = getString(R.string.loading_playlist)
        binding.tvErrorMessage.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val categories = playlistManager.loadPlaylist(prefs.playlistUrl, forceRefresh)
                allCategories.clear()
                allCategories.addAll(categories)

                allChannels.clear()
                val allCat = categories.firstOrNull { it.name == "Tất cả" }
                if (allCat != null) {
                    allChannels.addAll(allCat.channels)
                } else if (categories.isNotEmpty()) {
                    allChannels.addAll(categories.flatMap { it.channels })
                }

                categoryAdapter.submitList(allCategories)

                if (allCategories.isNotEmpty()) {
                    displayCategoryChannels(allCategories[0])
                }

                binding.layoutBuffering.visibility = View.GONE

                // Tự động phát kênh đã xem gần nhất hoặc kênh đầu tiên
                val lastUrl = prefs.lastChannelUrl
                val channelToPlay = allChannels.firstOrNull { it.streamUrl == lastUrl }
                    ?: allChannels.firstOrNull()

                if (channelToPlay != null) {
                    playChannel(channelToPlay)
                }

                // Cho focus vào danh mục đầu tiên
                binding.rvCategories.requestFocus()
                resetOverlayTimer()

            } catch (e: Exception) {
                binding.layoutBuffering.visibility = View.GONE
                binding.tvErrorMessage.visibility = View.VISIBLE
                binding.tvErrorMessage.text = getString(R.string.load_failed) + "\n" + (e.localizedMessage ?: "")
            }
        }
    }

    private fun displayCategoryChannels(category: Category) {
        currentCategoryChannels.clear()
        currentCategoryChannels.addAll(category.channels)

        binding.tvCategoryHeader.text = category.name
        binding.tvChannelCount.text = getString(R.string.channels_count, category.channels.size)
        channelAdapter.submitList(currentCategoryChannels)
    }

    private fun playChannel(channel: Channel) {
        currentPlayingChannel = channel
        prefs.lastChannelUrl = channel.streamUrl

        channelAdapter.setPlayingChannel(channel)
        binding.tvCurrentPlayingTitle.text = "Đang phát: [${String.format("%02d", channel.number)}] ${channel.name}"

        // Hiển thị banner nhanh thông tin kênh góc dưới
        showQuickChannelInfo(channel)

        binding.tvErrorMessage.visibility = View.GONE
        binding.layoutBuffering.visibility = View.VISIBLE
        binding.tvBufferingText.text = getString(R.string.stream_buffering)

        try {
            val mediaItem = MediaItem.fromUri(Uri.parse(channel.streamUrl))
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            binding.layoutBuffering.visibility = View.GONE
            binding.tvErrorMessage.visibility = View.VISIBLE
            binding.tvErrorMessage.text = "Không thể phát luồng này: ${e.localizedMessage}"
        }
    }

    private fun showQuickChannelInfo(channel: Channel) {
        binding.tvQuickNumber.text = String.format("%02d", channel.number)
        binding.tvQuickName.text = channel.name
        binding.quickChannelInfo.visibility = View.VISIBLE

        hideHandler.removeCallbacks(hideQuickBannerRunnable)
        hideHandler.postDelayed(hideQuickBannerRunnable, 3500)
    }

    // Chuyển kênh kế tiếp hoặc trước đó (như TV truyền thống)
    private fun switchChannel(next: Boolean) {
        if (allChannels.isEmpty()) return

        val currentIndex = allChannels.indexOfFirst { it.streamUrl == currentPlayingChannel?.streamUrl }
        val newIndex = if (next) {
            if (currentIndex == -1 || currentIndex >= allChannels.size - 1) 0 else currentIndex + 1
        } else {
            if (currentIndex <= 0) allChannels.size - 1 else currentIndex - 1
        }

        val targetChannel = allChannels[newIndex]
        playChannel(targetChannel)
    }

    // Điều khiển remote Android TV
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetOverlayTimer()

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                    showOverlay()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Nếu đang xem toàn màn hình (menu đang ẩn), bấm Lên để chuyển kênh trước
                if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                    switchChannel(next = false)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Nếu đang xem toàn màn hình, bấm Xuống để chuyển kênh tiếp theo
                if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                    switchChannel(next = true)
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                showPlaylistDialog()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    hideOverlay()
                    return true
                } else {
                    // Nhấn BACK 2 lần để thoát
                    if (System.currentTimeMillis() - backPressedTime < 2000) {
                        finish()
                    } else {
                        backPressedTime = System.currentTimeMillis()
                        Toast.makeText(this, "Bấm QUAY LẠI lần nữa để thoát ứng dụng", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun showOverlay() {
        binding.channelGuideOverlay.visibility = View.VISIBLE
        binding.rvChannels.requestFocus()
        resetOverlayTimer()
    }

    private fun hideOverlay() {
        binding.channelGuideOverlay.visibility = View.GONE
        hideHandler.removeCallbacks(hideOverlayRunnable)
    }

    private fun toggleOverlay() {
        if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
            hideOverlay()
        } else {
            showOverlay()
        }
    }

    private fun resetOverlayTimer() {
        hideHandler.removeCallbacks(hideOverlayRunnable)
        if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
            // Tự động ẩn danh sách sau 7 giây không thao tác
            hideHandler.postDelayed(hideOverlayRunnable, 7000)
        }
    }

    private fun showPlaylistDialog() {
        val dialog = PlaylistDialog(this, prefs.playlistUrl) { newUrl ->
            prefs.playlistUrl = newUrl
            loadPlaylist(forceRefresh = true)
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
