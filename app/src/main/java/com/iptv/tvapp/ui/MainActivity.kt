package com.iptv.tvapp.ui

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.iptv.tvapp.R
import com.iptv.tvapp.data.PlaylistManager
import com.iptv.tvapp.data.PreferencesHelper
import com.iptv.tvapp.databinding.ActivityMainBinding
import com.iptv.tvapp.model.Category
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.server.TvWebServer
import com.iptv.tvapp.ui.adapter.CategoryAdapter
import com.iptv.tvapp.ui.adapter.ChannelAdapter
import com.iptv.tvapp.ui.dialog.ChannelActionDialog
import com.iptv.tvapp.ui.dialog.SettingsDialog
import com.iptv.tvapp.util.NetworkUtils
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesHelper
    private lateinit var playlistManager: PlaylistManager

    private var player: ExoPlayer? = null
    private var webServer: TvWebServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private val allCategories = mutableListOf<Category>()
    private var allChannels = mutableListOf<Channel>()
    private var currentCategoryChannels = mutableListOf<Channel>()
    private var currentPlayingChannel: Channel? = null
    private var currentSelectedCategory: Category? = null

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

        acquireMulticastLock()
        initViews()
        initPlayer()
        initWebServer()
        loadPlaylist(forceRefresh = false)
    }

    /**
     * Mở khóa Multicast Wi-Fi trên Android TV để nhận luồng UDP/RTP từ nhà mạng (FPT, VNPT)
     * mà không bị chip Wi-Fi lọc bỏ gói tin dẫn đến sọc ngang và vỡ hạt hình.
     */
    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("iptv_udp_multicast_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initViews() {
        categoryAdapter = CategoryAdapter { category ->
            resetOverlayTimer()
            currentSelectedCategory = category
            displayCategoryChannels(category)
        }
        binding.rvCategories.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = categoryAdapter
        }

        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                playChannel(channel)
                hideHandler.postDelayed({ hideOverlay() }, 800)
            },
            onChannelLongClick = { channel ->
                showChannelActionDialog(channel)
            }
        )
        binding.rvChannels.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelAdapter
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnReload.setOnClickListener {
            loadPlaylist(forceRefresh = true)
        }

        binding.rootView.setOnClickListener {
            toggleOverlay()
        }
    }

    private fun initWebServer() {
        val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
        binding.tvWebRemoteBadge.text = "📱 Web Remote: http://$ip:8080"

        webServer = TvWebServer(
            port = 8080,
            prefs = prefs,
            channelProvider = { allChannels },
            currentPlayingProvider = { currentPlayingChannel },
            onPlayChannel = { ch ->
                playChannel(ch)
                hideHandler.postDelayed({ hideOverlay() }, 800)
            },
            onSendKey = { action ->
                handleRemoteAction(action)
            },
            onPlaylistChanged = {
                loadPlaylist(forceRefresh = false)
            },
            onChannelsUpdated = {
                refreshChannelsState()
            }
        )
        webServer?.start()
    }

    private fun handleRemoteAction(action: String) {
        resetOverlayTimer()
        when (action) {
            "up" -> {
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
                } else {
                    switchChannel(next = false)
                }
            }
            "down" -> {
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
                } else {
                    switchChannel(next = true)
                }
            }
            "left" -> {
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
            }
            "right" -> {
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
            }
            "center", "ok" -> {
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                } else {
                    showOverlay()
                }
            }
            "back" -> {
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    hideOverlay()
                }
            }
            "menu" -> {
                showSettingsDialog()
            }
            "ch_prev" -> switchChannel(next = false)
            "ch_next" -> switchChannel(next = true)
        }
    }

    private fun initPlayer() {
        // 1. Tối ưu bộ giải mã: Bật Deinterlace & Dự phòng Decoder chống lỗi sọc ngang răng cưa
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true) // Cho phép chuyển codec dự phòng nếu codec phần cứng lỗi 1080i
            setAllowedVideoJoiningTimeMs(5000)
        }

        // 2. Tối ưu bộ đệm chống mất gói (UDP packet loss) và giật khung hình
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // Đệm tối thiểu 15 giây
                60_000, // Đệm tối đa 60 giây
                1_500,  // Bắt đầu phát sau 1.5s đệm mượt
                3_000   // Phục hồi sau 3s nếu nghẽn mạng
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 3. Tối ưu cấu hình phân tích luồng MPEG-TS từ đài truyền hình
        val extractorsFactory = DefaultExtractorsFactory().apply {
            setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO
            )
        }

        // 4. Hỗ trợ đầy đủ HTTP / HTTPS và giao thức luồng mạng UDP multicast
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (SmartTV; Android 5.0) AppleWebKit/537.36 (KHTML, like Gecko) ExoPlayer")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)

        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(defaultDataSourceFactory, extractorsFactory)

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
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
        val activeSource = prefs.getActivePlaylist()
        binding.tvActivePlaylistBadge.text = "Danh sách: ${activeSource.name}"

        binding.layoutBuffering.visibility = View.VISIBLE
        binding.tvBufferingText.text = getString(R.string.loading_playlist)
        binding.tvErrorMessage.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val categories = playlistManager.loadPlaylist(activeSource.id, activeSource.url, forceRefresh)
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
                    currentSelectedCategory = allCategories[0]
                    displayCategoryChannels(allCategories[0])
                }

                binding.layoutBuffering.visibility = View.GONE

                val targetChannel = if (prefs.startupMode == PreferencesHelper.MODE_FIXED_CHANNEL && !prefs.defaultStartupChannelUrl.isNullOrBlank()) {
                    allChannels.firstOrNull { it.streamUrl == prefs.defaultStartupChannelUrl }
                        ?: allChannels.firstOrNull { it.streamUrl == prefs.lastChannelUrl }
                        ?: allChannels.firstOrNull()
                } else {
                    allChannels.firstOrNull { it.streamUrl == prefs.lastChannelUrl }
                        ?: allChannels.firstOrNull()
                }

                if (targetChannel != null) {
                    playChannel(targetChannel)
                }

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

        showQuickChannelInfo(channel)

        binding.tvErrorMessage.visibility = View.GONE
        binding.layoutBuffering.visibility = View.VISIBLE
        binding.tvBufferingText.text = getString(R.string.stream_buffering)

        try {
            // Chuẩn hóa đường dẫn stream: Hỗ trợ link UDP / RTP multicast từ FPT (@225.x.x.x)
            var streamUriString = channel.streamUrl.trim()
            if (streamUriString.startsWith("udp://@", ignoreCase = true)) {
                streamUriString = "udp://" + streamUriString.substring(7)
            } else if (streamUriString.startsWith("rtp://@", ignoreCase = true)) {
                streamUriString = "udp://" + streamUriString.substring(7)
            } else if (streamUriString.startsWith("rtp://", ignoreCase = true)) {
                streamUriString = "udp://" + streamUriString.substring(6)
            }

            val mediaItem = MediaItem.fromUri(Uri.parse(streamUriString))
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

    private fun showChannelActionDialog(channel: Channel) {
        resetOverlayTimer()
        val dialog = ChannelActionDialog(this, channel) {
            refreshChannelsState()
        }
        dialog.show()
    }

    private fun refreshChannelsState() {
        val favUrls = prefs.getFavoriteUrls()
        val defaultUrl = prefs.defaultStartupChannelUrl

        allChannels.forEach { ch ->
            ch.isFavorite = favUrls.contains(ch.streamUrl)
            ch.isDefaultStartup = (ch.streamUrl == defaultUrl)
        }

        allCategories.clear()
        allCategories.addAll(playlistManager.groupChannels(allChannels))
        categoryAdapter.submitList(allCategories)

        val activeCat = currentSelectedCategory?.let { sel ->
            allCategories.firstOrNull { it.name == sel.name }
        } ?: allCategories.firstOrNull()

        if (activeCat != null) {
            currentSelectedCategory = activeCat
            displayCategoryChannels(activeCat)
        }
    }

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
                if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                    switchChannel(next = false)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                    switchChannel(next = true)
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                showSettingsDialog()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    hideOverlay()
                    return true
                } else {
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
            hideHandler.postDelayed(hideOverlayRunnable, 7000)
        }
    }

    private fun showSettingsDialog() {
        resetOverlayTimer()
        val dialog = SettingsDialog(this) {
            loadPlaylist(forceRefresh = false)
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
        webServer?.stop()
        webServer = null
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {}
        multicastLock = null
        player?.release()
        player = null
    }
}
