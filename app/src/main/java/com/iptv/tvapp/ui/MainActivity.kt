package com.iptv.tvapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.iptv.tvapp.R
import com.iptv.tvapp.data.PlaylistManager
import com.iptv.tvapp.data.PreferencesHelper
import com.iptv.tvapp.databinding.ActivityMainBinding
import com.iptv.tvapp.epg.EpgManager
import com.iptv.tvapp.model.Category
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.server.TvWebServer
import com.iptv.tvapp.ui.adapter.CategoryAdapter
import com.iptv.tvapp.ui.adapter.ChannelAdapter
import com.iptv.tvapp.ui.adapter.EpgTimelineAdapter
import com.iptv.tvapp.ui.dialog.ChannelActionDialog
import com.iptv.tvapp.ui.dialog.QuickPlaylistDialog
import com.iptv.tvapp.ui.dialog.SettingsDialog
import com.iptv.tvapp.util.FptLogoHelper
import com.iptv.tvapp.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesHelper
    private lateinit var playlistManager: PlaylistManager
    private lateinit var epgManager: EpgManager

    private var player: ExoPlayer? = null
    private var libVLC: LibVLC? = null
    private var vlcPlayer: VlcMediaPlayer? = null
    private var webServer: TvWebServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var epgTimelineAdapter: EpgTimelineAdapter

    private val allCategories = mutableListOf<Category>()
    private var allChannels = mutableListOf<Channel>()
    private var currentCategoryChannels = mutableListOf<Channel>()
    private var currentPlayingChannel: Channel? = null
    private var currentFocusedChannel: Channel? = null
    private var currentSelectedCategory: Category? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private val hideQuickBannerRunnable = Runnable { binding.quickChannelInfo.visibility = View.GONE }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            binding.tvDigitalClock.text = timeFormat.format(Date())
            clockHandler.postDelayed(this, 10000)
        }
    }

    private var backPressedTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesHelper(this)
        playlistManager = PlaylistManager(this)
        epgManager = EpgManager(this)

        acquireMulticastLock()
        checkStoragePermission()
        initViews()
        initPlayer()
        initWebServer()
        startClock()
        loadPlaylist(forceRefresh = false)
        loadEpgData()
    }

    private fun loadEpgData() {
        lifecycleScope.launch {
            epgManager.loadEpg()
            withContext(Dispatchers.Main) {
                currentFocusedChannel?.let { updateSpotlight(it) }
                    ?: currentPlayingChannel?.let { updateSpotlight(it) }
            }
        }
    }

    private fun startClock() {
        clockHandler.post(clockRunnable)
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

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
            }
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
            },
            onChannelFocused = { channel ->
                updateSpotlight(channel)
            }
        )
        binding.rvChannels.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelAdapter
        }

        // Khởi tạo Timeline EPG dạng list
        epgTimelineAdapter = EpgTimelineAdapter()
        binding.rvSpotlightEpg.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = epgTimelineAdapter
        }

        // Spotlight Action Buttons
        binding.btnSpotlightPlay.setOnClickListener {
            currentFocusedChannel?.let { ch ->
                playChannel(ch)
                hideHandler.postDelayed({ hideOverlay() }, 800)
            }
        }

        binding.btnSpotlightFav.setOnClickListener {
            currentFocusedChannel?.let { ch ->
                val isFav = prefs.toggleFavorite(ch.streamUrl)
                ch.isFavorite = isFav
                channelAdapter.updateChannelItem(ch)
                updateSpotlight(ch)
                Toast.makeText(this, if (isFav) "Đã thêm [${ch.name}] vào Yêu Thích ★" else "Đã xóa khỏi Yêu Thích", Toast.LENGTH_SHORT).show()
                refreshChannelsState()
            }
        }

        binding.btnSpotlightDefault.setOnClickListener {
            currentFocusedChannel?.let { ch ->
                if (ch.isDefaultStartup) {
                    prefs.defaultStartupChannelUrl = null
                    ch.isDefaultStartup = false
                    Toast.makeText(this, "Đã hủy kênh mặc định khởi động", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.defaultStartupChannelUrl = ch.streamUrl
                    prefs.startupMode = PreferencesHelper.MODE_FIXED_CHANNEL
                    allChannels.forEach { it.isDefaultStartup = (it.streamUrl == ch.streamUrl) }
                    Toast.makeText(this, "Đã đặt [${ch.name}] làm kênh mặc định khi mở app", Toast.LENGTH_SHORT).show()
                }
                channelAdapter.notifyDataSetChanged()
                updateSpotlight(ch)
            }
        }

        // Sidebar Navigation Buttons
        binding.navFavorites.setOnClickListener {
            val favCat = allCategories.firstOrNull { it.name.contains("Yêu thích", ignoreCase = true) }
            if (favCat != null) {
                currentSelectedCategory = favCat
                categoryAdapter.setSelectedCategory(favCat)
                displayCategoryChannels(favCat)
                binding.rvChannels.requestFocus()
            } else {
                Toast.makeText(this, "Chưa có kênh yêu thích nào. Hãy bấm phím Yêu thích ★ ở kênh bạn muốn lưu!", Toast.LENGTH_LONG).show()
            }
        }

        binding.navPlaylists.setOnClickListener {
            showQuickPlaylistDialog()
        }

        binding.navSidebarSettings.setOnClickListener {
            showSettingsDialog()
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
            context = this,
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
        initVlcPlayer()
        initExoPlayer()
        applyPlayerVisibility()
    }

    private fun applyPlayerVisibility() {
        if (prefs.playerEngine == PreferencesHelper.ENGINE_VLC) {
            binding.vlcVideoLayout.visibility = View.VISIBLE
            binding.playerView.visibility = View.GONE
        } else {
            binding.vlcVideoLayout.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
        }
    }

    private fun initVlcPlayer() {
        try {
            val options = arrayListOf(
                "-vv",
                "--deinterlace=1",
                "--deinterlace-mode=blend",
                "--video-filter=deinterlace",
                // Bắt buộc luôn phát độ phân giải 1080/cao nhất trong HLS/DASH dù màn hình là 720p
                "--adaptive-logic=highest",
                "--adaptive-maxwidth=0",
                "--adaptive-maxheight=0",
                "--network-caching=2000",
                "--live-caching=2000",
                "--clock-jitter=0",
                "--clock-synchro=0",
                "--drop-late-frames",
                "--skip-frames"
            )
            libVLC = LibVLC(this, options)
            vlcPlayer = VlcMediaPlayer(libVLC).apply {
                attachViews(binding.vlcVideoLayout, null, false, false)

                setEventListener { event ->
                    runOnUiThread {
                        when (event.type) {
                            VlcMediaPlayer.Event.Buffering -> {
                                if (event.buffering < 100f) {
                                    binding.layoutBuffering.visibility = View.VISIBLE
                                } else {
                                    binding.layoutBuffering.visibility = View.GONE
                                }
                            }
                            VlcMediaPlayer.Event.Playing,
                            VlcMediaPlayer.Event.Vout,
                            VlcMediaPlayer.Event.ESSelected -> {
                                binding.layoutBuffering.visibility = View.GONE
                                binding.tvErrorMessage.visibility = View.GONE
                                currentVideoTrack?.let { track ->
                                    if (track.width > 0 && track.height > 0) {
                                        updateDynamicResolutionBadge(track.width, track.height)
                                    }
                                }
                            }
                            VlcMediaPlayer.Event.EncounteredError -> {
                                binding.layoutBuffering.visibility = View.GONE
                                binding.tvErrorMessage.visibility = View.VISIBLE
                                binding.tvErrorDetail.text = "Lỗi phát kênh qua VLC. Bấm ▲ ▼ để đổi kênh."
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initExoPlayer() {
        // 1. Tối ưu bộ giải mã: Bật Deinterlace & Dự phòng Decoder
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true) // Cho phép chuyển codec dự phòng nếu codec phần cứng lỗi 1080i
            setAllowedVideoJoiningTimeMs(5000L)
        }

        // 2. Tối ưu bộ đệm chống mất gói (UDP packet loss) và giật khung hình
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, // Đệm tối thiểu 15 giây
                60000, // Đệm tối đa 60 giây
                1500,  // Bắt đầu phát sau 1.5s đệm mượt
                3000   // Phục hồi sau 3s nếu nghẽn mạng
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 3. Tối ưu cấu hình phân tích luồng MPEG-TS từ đài truyền hình
        val extractorsFactory = DefaultExtractorsFactory().apply {
            setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
        }

        // 4. Bắt buộc ExoPlayer luôn chọn độ phân giải 1080/cao nhất trong HLS dù màn hình là 720p
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setForceHighestSupportedBitrate(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setMaxVideoSize(3840, 2160)
                .build()
        }

        // 5. Hỗ trợ đầy đủ HTTP / HTTPS và giao thức luồng mạng UDP multicast
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
            .setTrackSelector(trackSelector)
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

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            runOnUiThread {
                                updateDynamicResolutionBadge(videoSize.width, videoSize.height)
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        binding.layoutBuffering.visibility = View.GONE
                        binding.tvErrorMessage.visibility = View.VISIBLE
                        binding.tvErrorDetail.text = "Lỗi phát kênh: ${error.errorCodeName}\nBấm ▲ ▼ để đổi kênh khác"
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
                    // Tự động phát kênh mặc định ngay, ẩn giao diện cài đặt/danh mục để xem toàn màn hình
                    hideOverlay()
                } else {
                    showOverlay()
                }

            } catch (e: Exception) {
                binding.layoutBuffering.visibility = View.GONE
                binding.tvErrorMessage.visibility = View.VISIBLE
                binding.tvErrorDetail.text = getString(R.string.load_failed) + "\n" + (e.localizedMessage ?: "")
                showOverlay()
            }
        }
    }

    private fun displayCategoryChannels(category: Category, focusChannel: Channel? = null) {
        currentCategoryChannels.clear()
        currentCategoryChannels.addAll(category.channels)

        binding.tvCategoryHeader.text = category.name
        binding.tvChannelCount.text = getString(R.string.channels_count, category.channels.size)
        channelAdapter.submitList(currentCategoryChannels)

        val targetToHighlight = focusChannel ?: currentPlayingChannel?.takeIf { ch ->
            currentCategoryChannels.any { it.streamUrl == ch.streamUrl }
        } ?: currentCategoryChannels.firstOrNull()

        if (targetToHighlight != null) {
            updateSpotlight(targetToHighlight)
            val index = currentCategoryChannels.indexOfFirst { it.streamUrl == targetToHighlight.streamUrl }
            if (index != -1) {
                binding.rvChannels.scrollToPosition(index)
                if (focusChannel != null) {
                    binding.rvChannels.post {
                        val vh = binding.rvChannels.findViewHolderForAdapterPosition(index)
                        if (vh != null) {
                            vh.itemView.requestFocus()
                        } else {
                            binding.rvChannels.postDelayed({
                                binding.rvChannels.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                            }, 60)
                        }
                    }
                }
            }
        }
    }

    private fun updateSpotlight(channel: Channel) {
        currentFocusedChannel = channel
        binding.tvSpotlightName.text = channel.name

        // Logo chính hãng FPT CDN hoặc M3U
        val resolvedLogo = FptLogoHelper.getResolvedLogoUrl(channel.name, channel.logoUrl)
        if (resolvedLogo.isNotBlank()) {
            Glide.with(this)
                .load(resolvedLogo)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_tv_placeholder)
                .error(R.drawable.ic_tv_placeholder)
                .into(binding.ivSpotlightLogo)
        } else {
            binding.ivSpotlightLogo.setImageResource(R.drawable.ic_tv_placeholder)
        }

        // Độ phân giải hiển thị: Ưu tiên FHD 1080 (hoặc 4K), các kênh HD tại Việt Nam là 1080i/1080p
        val upperName = channel.name.uppercase()
        binding.tvSpotlightResolution.text = when {
            upperName.contains("4K") -> "4K UHD"
            upperName.contains("720") -> "HD 720p"
            upperName.contains("FHD") || upperName.contains("1080") || upperName.contains("HD") -> "FHD 1080"
            else -> "FHD 1080"
        }

        binding.tvSpotlightCategory.text = currentSelectedCategory?.name ?: "IPTV"

        val isPlaying = channel.streamUrl == currentPlayingChannel?.streamUrl
        binding.tvSpotlightStatus.visibility = if (isPlaying) View.VISIBLE else View.GONE

        // Giao thức
        val udpxy = prefs.udpxyServer.trim()
        val protocol = when {
            channel.streamUrl.contains("/udp/", ignoreCase = true) || (udpxy.isNotBlank() && channel.streamUrl.startsWith("udp", ignoreCase = true)) -> "udpxy ($udpxy)"
            channel.streamUrl.contains("/rtp/", ignoreCase = true) || (udpxy.isNotBlank() && channel.streamUrl.startsWith("rtp", ignoreCase = true)) -> "udpxy ($udpxy)"
            channel.streamUrl.startsWith("udp", ignoreCase = true) -> "UDP Multicast"
            channel.streamUrl.startsWith("rtp", ignoreCase = true) -> "RTP Multicast"
            channel.streamUrl.contains(".m3u8", ignoreCase = true) -> "HLS / CDN"
            else -> "HTTP Stream"
        }
        val engineName = if (prefs.playerEngine == PreferencesHelper.ENGINE_VLC) "VLC (Khử xé hình)" else "ExoPlayer"
        binding.tvSpotlightStreamInfo.text = "Giao thức: $protocol • $engineName"

        // Lịch phát sóng EPG dạng list time-line từ lichphatsong.io.vn (Có thể cuộn xem cả ngày)
        val timeline = epgManager.getTimelineForChannel(channel.id, channel.name)
        if (timeline.isNotEmpty()) {
            binding.rvSpotlightEpg.visibility = View.VISIBLE
            binding.tvEpgEmpty.visibility = View.GONE
            epgTimelineAdapter.submitList(timeline)

            val now = System.currentTimeMillis()
            val currentIndex = timeline.indexOfFirst { it.isCurrent(now) }
            if (currentIndex != -1) {
                binding.rvSpotlightEpg.scrollToPosition(currentIndex)
            } else {
                binding.rvSpotlightEpg.scrollToPosition(0)
            }
        } else {
            binding.rvSpotlightEpg.visibility = View.GONE
            binding.tvEpgEmpty.visibility = View.VISIBLE
            binding.tvEpgEmpty.text = if (epgManager.isLoaded) "Chưa có lịch phát sóng" else "Đang tải lịch phát sóng..."
        }

        // Nút trạng thái
        binding.btnSpotlightFav.text = if (channel.isFavorite) "★ BỎ THÍCH" else "☆ YÊU THÍCH"
        binding.btnSpotlightDefault.text = if (channel.isDefaultStartup) "✓ ĐÃ ĐẶT" else "⚡ MẶC ĐỊNH"
    }

    private fun playChannel(channel: Channel) {
        currentPlayingChannel = channel
        prefs.lastChannelUrl = channel.streamUrl

        channelAdapter.setPlayingChannel(channel)
        updateSpotlight(channel)

        showQuickChannelInfo(channel)

        binding.tvErrorMessage.visibility = View.GONE
        binding.layoutBuffering.visibility = View.VISIBLE
        binding.tvBufferingText.text = getString(R.string.stream_buffering)

        val isVlc = prefs.playerEngine == PreferencesHelper.ENGINE_VLC
        val streamUriString = normalizeStreamUrl(channel.streamUrl, isVlc)

        if (isVlc) {
            playWithVlc(streamUriString)
        } else {
            playWithExoPlayer(streamUriString)
        }
    }

    private fun normalizeStreamUrl(rawUrl: String, isVlc: Boolean): String {
        val trimmed = rawUrl.trim()
        val udpxy = prefs.udpxyServer.trim()

        // Nếu đã cấu hình máy chủ udpxy (server:port), tự động chuyển đổi luồng multicast sang HTTP proxy
        val effectiveUrl = if (udpxy.isNotBlank()) {
            when {
                trimmed.startsWith("udp://@", ignoreCase = true) -> "http://$udpxy/udp/" + trimmed.substring(7)
                trimmed.startsWith("udp://", ignoreCase = true) -> "http://$udpxy/udp/" + trimmed.substring(6)
                trimmed.startsWith("rtp://@", ignoreCase = true) -> "http://$udpxy/rtp/" + trimmed.substring(7)
                trimmed.startsWith("rtp://", ignoreCase = true) -> "http://$udpxy/rtp/" + trimmed.substring(6)
                trimmed.matches(Regex("(?i)^http://[^/]+/(udp|rtp)/.*")) -> {
                    trimmed.replaceFirst(Regex("(?i)^http://[^/]+/(udp|rtp)/"), "http://$udpxy/$1/")
                }
                else -> trimmed
            }
        } else {
            trimmed
        }

        return if (isVlc) {
            // LibVLC chuẩn Multicast: BẮT BUỘC phải có '@' trước IP Multicast (ví dụ: udp://@225.1.2.249:30120)
            when {
                effectiveUrl.startsWith("udp://@", ignoreCase = true) -> effectiveUrl
                effectiveUrl.startsWith("udp://", ignoreCase = true) -> "udp://@" + effectiveUrl.substring(6)
                effectiveUrl.startsWith("rtp://@", ignoreCase = true) -> effectiveUrl
                effectiveUrl.startsWith("rtp://", ignoreCase = true) -> "rtp://@" + effectiveUrl.substring(6)
                else -> effectiveUrl
            }
        } else {
            // ExoPlayer: Chuẩn Java URI không chấp nhận '@' trong host name
            when {
                effectiveUrl.startsWith("udp://@", ignoreCase = true) -> "udp://" + effectiveUrl.substring(7)
                effectiveUrl.startsWith("rtp://@", ignoreCase = true) -> "udp://" + effectiveUrl.substring(7)
                effectiveUrl.startsWith("rtp://", ignoreCase = true) -> "udp://" + effectiveUrl.substring(6)
                else -> effectiveUrl
            }
        }
    }

    private fun playWithVlc(streamUriString: String) {
        try {
            player?.pause()
            binding.playerView.visibility = View.GONE
            binding.vlcVideoLayout.visibility = View.VISIBLE

            val vlc = libVLC ?: return
            val vp = vlcPlayer ?: return

            vp.stop()
            val media = Media(vlc, Uri.parse(streamUriString)).apply {
                addOption(":deinterlace=1")
                addOption(":deinterlace-mode=blend")
                addOption(":video-filter=deinterlace")
                // BẮT BUỘC LUÔN PHÁT ĐỘ PHÂN GIẢI 1080/CAO NHẤT DÙ MÀN HÌNH MÁY LÀ 720P
                addOption(":adaptive-logic=highest")
                addOption(":adaptive-maxwidth=0")
                addOption(":adaptive-maxheight=0")
                addOption(":network-caching=2000")
                addOption(":live-caching=2000")
            }
            vp.media = media
            media.release()
            vp.play()
        } catch (e: Exception) {
            binding.layoutBuffering.visibility = View.GONE
            binding.tvErrorMessage.visibility = View.VISIBLE
            binding.tvErrorDetail.text = "Không thể phát qua VLC: ${e.localizedMessage}"
        }
    }

    private fun updateDynamicResolutionBadge(width: Int, height: Int) {
        val badgeText = when {
            height >= 2000 || width >= 3800 -> "4K UHD"
            height >= 900 || width >= 1400 -> "FHD 1080"
            height >= 700 || width >= 1200 -> "HD 720p"
            height > 0 -> "SD ${height}p"
            else -> "FHD 1080"
        }
        binding.tvSpotlightResolution.text = badgeText
        val streamInfo = binding.tvSpotlightStreamInfo.text.toString()
        binding.tvQuickResolution.text = "$badgeText • $streamInfo"
    }

    private fun playWithExoPlayer(streamUriString: String) {
        try {
            vlcPlayer?.stop()
            binding.vlcVideoLayout.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE

            val mediaItem = MediaItem.fromUri(Uri.parse(streamUriString))
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            binding.layoutBuffering.visibility = View.GONE
            binding.tvErrorMessage.visibility = View.VISIBLE
            binding.tvErrorDetail.text = "Không thể phát qua ExoPlayer: ${e.localizedMessage}"
        }
    }

    private fun showQuickChannelInfo(channel: Channel) {
        binding.tvQuickNumber.text = String.format("%02d", channel.number)
        binding.tvQuickName.text = channel.name

        // Lấy tên chương trình EPG đang phát thời gian thực (hiển thị xong rồi tự tắt đi)
        val currentProg = epgManager.getCurrentProgram(channel.id, channel.name)
        if (currentProg != null) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val startStr = timeFormat.format(Date(currentProg.startTime))
            val endStr = timeFormat.format(Date(currentProg.endTime))
            binding.tvQuickProgramTitle.text = "● Đang phát: ${currentProg.title} ($startStr - $endStr)"
            binding.tvQuickProgramTitle.visibility = View.VISIBLE
        } else {
            binding.tvQuickProgramTitle.text = "● Đang phát: Chương trình trực tiếp"
            binding.tvQuickProgramTitle.visibility = View.VISIBLE
        }

        val resolvedLogo = FptLogoHelper.getResolvedLogoUrl(channel.name, channel.logoUrl)
        if (resolvedLogo.isNotBlank()) {
            Glide.with(this)
                .load(resolvedLogo)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_tv_placeholder)
                .error(R.drawable.ic_tv_placeholder)
                .into(binding.ivQuickLogo)
        } else {
            binding.ivQuickLogo.setImageResource(R.drawable.ic_tv_placeholder)
        }

        val upperName = channel.name.uppercase()
        val defaultRes = when {
            upperName.contains("4K") -> "4K UHD"
            upperName.contains("720") -> "HD 720p"
            upperName.contains("FHD") || upperName.contains("1080") || upperName.contains("HD") -> "FHD 1080"
            else -> "FHD 1080"
        }
        binding.tvQuickResolution.text = "$defaultRes • TV Play Live"

        binding.quickChannelInfo.visibility = View.VISIBLE

        hideHandler.removeCallbacks(hideQuickBannerRunnable)
        hideHandler.postDelayed(hideQuickBannerRunnable, 10000L)
    }

    private fun showChannelActionDialog(channel: Channel) {
        resetOverlayTimer()
        val dialog = ChannelActionDialog(this, channel) {
            refreshChannelsState()
        }
        dialog.setOnDismissListener {
            if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                currentPlayingChannel?.let { showQuickChannelInfo(it) }
            }
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
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                    // Phím Trái khi xem toàn màn hình: Mở nhanh danh sách Yêu Thích
                    val favCat = allCategories.firstOrNull { it.name.contains("Yêu thích", ignoreCase = true) }
                    if (favCat != null) {
                        currentSelectedCategory = favCat
                        categoryAdapter.setSelectedCategory(favCat)
                        displayCategoryChannels(favCat)
                    }
                    showOverlay()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                    // Phím Phải khi xem toàn màn hình: Mở menu chọn danh sách phát nhanh
                    showQuickPlaylistDialog()
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                showSettingsDialog()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    val focused = currentFocus
                    if (focused != null && (focused.id == R.id.rvSpotlightEpg || focused.id == R.id.rvCategories ||
                                focused.id == R.id.navFavorites || focused.id == R.id.navPlaylists ||
                                focused.id == R.id.navSidebarSettings || focused.id == R.id.btnSpotlightPlay ||
                                focused.id == R.id.btnSpotlightFav || focused.id == R.id.btnSpotlightDefault)) {
                        focusPlayingChannel()
                        return true
                    }
                    hideOverlay()
                    return true
                } else {
                    if (System.currentTimeMillis() - backPressedTime < 2000) {
                        finish()
                    } else {
                        backPressedTime = System.currentTimeMillis()
                        currentPlayingChannel?.let { showQuickChannelInfo(it) }
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
        resetOverlayTimer()

        val playing = currentPlayingChannel
        if (playing != null) {
            // Đảm bảo danh mục đang chọn có chứa kênh đang phát
            val inCurrentCat = currentCategoryChannels.any { it.streamUrl == playing.streamUrl }
            if (!inCurrentCat) {
                val matchedCat = allCategories.firstOrNull { cat ->
                    cat.channels.any { it.streamUrl == playing.streamUrl }
                } ?: allCategories.firstOrNull { it.name == "Tất cả" }

                if (matchedCat != null) {
                    currentSelectedCategory = matchedCat
                    categoryAdapter.setSelectedCategory(matchedCat)
                    displayCategoryChannels(matchedCat, focusChannel = playing)
                    return
                }
            }

            focusPlayingChannel()
            return
        }

        binding.rvChannels.requestFocus()
    }

    private fun focusPlayingChannel() {
        val playing = currentPlayingChannel ?: return
        val playingIndex = currentCategoryChannels.indexOfFirst { it.streamUrl == playing.streamUrl }
        if (playingIndex != -1) {
            binding.rvChannels.scrollToPosition(playingIndex)
            binding.rvChannels.post {
                val vh = binding.rvChannels.findViewHolderForAdapterPosition(playingIndex)
                if (vh != null) {
                    vh.itemView.requestFocus()
                } else {
                    binding.rvChannels.postDelayed({
                        binding.rvChannels.findViewHolderForAdapterPosition(playingIndex)?.itemView?.requestFocus()
                    }, 60)
                }
            }
            updateSpotlight(playing)
        } else {
            binding.rvChannels.requestFocus()
        }
    }

    private fun showQuickPlaylistDialog() {
        resetOverlayTimer()
        val dialog = QuickPlaylistDialog(
            context = this,
            onPlaylistSelected = {
                loadPlaylist(forceRefresh = false)
                loadEpgData()
            },
            onOpenFullSettings = {
                showSettingsDialog()
            }
        )
        dialog.setOnDismissListener {
            if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                currentPlayingChannel?.let { showQuickChannelInfo(it) }
            }
        }
        dialog.show()
    }

    private fun hideOverlay() {
        binding.channelGuideOverlay.visibility = View.GONE
        hideHandler.removeCallbacks(hideOverlayRunnable)
        currentPlayingChannel?.let { showQuickChannelInfo(it) }
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
        val dialog = SettingsDialog(this) { playlistChanged, engineChanged ->
            if (playlistChanged) {
                loadPlaylist(forceRefresh = true)
                loadEpgData()
            }
            if (engineChanged) {
                applyPlayerVisibility()
                currentPlayingChannel?.let { playChannel(it) }
            }
        }
        dialog.setOnDismissListener {
            if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                currentPlayingChannel?.let { showQuickChannelInfo(it) }
            }
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.playerEngine == PreferencesHelper.ENGINE_VLC) {
            vlcPlayer?.play()
        } else {
            player?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        vlcPlayer?.pause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        vlcPlayer?.stop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
        webServer?.stop()
        webServer = null
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {}
        multicastLock = null

        try {
            vlcPlayer?.apply {
                stop()
                detachViews()
                release()
            }
            vlcPlayer = null
            libVLC?.release()
            libVLC = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        player?.release()
        player = null
    }
}
