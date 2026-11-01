package com.iptv.tvapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.TrafficStats
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
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvapp.ui.widget.TvLinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Tracks
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
import com.iptv.tvapp.data.SportsLiveManager
import com.iptv.tvapp.databinding.ActivityMainBinding
import com.iptv.tvapp.epg.EpgManager
import com.iptv.tvapp.model.Category
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.model.EpgProgram
import com.iptv.tvapp.server.TvWebServer
import com.iptv.tvapp.ui.adapter.CategoryAdapter
import com.iptv.tvapp.ui.adapter.ChannelAdapter
import com.iptv.tvapp.ui.adapter.EpgTimelineAdapter
import com.iptv.tvapp.ui.dialog.ChannelActionDialog
import com.iptv.tvapp.ui.dialog.QuickPlaylistDialog
import com.iptv.tvapp.ui.dialog.SettingsDialog
import com.iptv.tvapp.update.AppUpdateManager
import com.iptv.tvapp.util.FptLogoHelper
import com.iptv.tvapp.util.NetworkUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesHelper
    private lateinit var playlistManager: PlaylistManager
    private lateinit var epgManager: EpgManager
    private lateinit var appUpdateManager: AppUpdateManager

    private var player: ExoPlayer? = null
    private var libVLC: LibVLC? = null
    private var vlcPlayer: VlcMediaPlayer? = null
    private var webServer: TvWebServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val extractorsFactory = DefaultExtractorsFactory().apply {
        setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
    }

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var epgTimelineAdapter: EpgTimelineAdapter

    private val allCategories = mutableListOf<Category>()
    private var allChannels = mutableListOf<Channel>()
    private var currentCategoryChannels = mutableListOf<Channel>()
    private var currentPlayingChannel: Channel? = null
    private var currentFocusedChannel: Channel? = null
    private var currentSelectedCategory: Category? = null
    private var isInitialAppStart = true

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private val hideQuickBannerRunnable = Runnable {
        binding.quickChannelInfo.visibility = View.GONE
        binding.layoutQuickBuffering.visibility = View.GONE
        if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
            stopTelemetryUpdates()
        }
    }

    private val bottomNotificationHandler = Handler(Looper.getMainLooper())
    private val hideBottomNotificationRunnable = Runnable {
        binding.layoutBottomNotification.animate()
            .alpha(0f)
            .translationY(30f)
            .setDuration(300)
            .withEndAction {
                binding.layoutBottomNotification.visibility = View.GONE
            }
            .start()
    }
    private var sportsFetchJob: Job? = null

    fun showBottomNotification(message: String) {
        bottomNotificationHandler.removeCallbacks(hideBottomNotificationRunnable)
        // Ẩn bảng thông tin kênh nhanh nếu đang mở để tránh hai thông báo bị đè lên nhau
        hideHandler.removeCallbacks(hideQuickBannerRunnable)
        binding.quickChannelInfo.visibility = View.GONE
        binding.layoutQuickBuffering.visibility = View.GONE

        binding.tvBottomNotificationText.text = message
        binding.layoutBottomNotification.alpha = 0f
        binding.layoutBottomNotification.translationY = 30f
        binding.layoutBottomNotification.visibility = View.VISIBLE
        binding.layoutBottomNotification.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .start()
        bottomNotificationHandler.postDelayed(hideBottomNotificationRunnable, 3500)
    }

    private var lastRxBytes: Long = 0L
    private var lastRxTime: Long = 0L
    private var currentMeasuredBitrateMbps: Float = 0f
    private var currentVideoWidth: Int = 0
    private var currentVideoHeight: Int = 0
    private var currentVideoFps: Int = 0
    private val telemetryHandler = Handler(Looper.getMainLooper())
    private val telemetryRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            measureRealtimeBitrate()
            updateDynamicTelemetry()
            if (binding.quickChannelInfo.visibility == View.VISIBLE || binding.channelGuideOverlay.visibility == View.VISIBLE) {
                telemetryHandler.postDelayed(this, 1500L)
            }
        }
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            binding.tvDigitalClock.text = timeFormat.format(Date())
            clockHandler.postDelayed(this, 10000)
        }
    }

    private val spotlightHandler = Handler(Looper.getMainLooper())
    private var pendingSpotlightChannel: Channel? = null
    private val spotlightRunnable = Runnable {
        pendingSpotlightChannel?.let { channel ->
            performHeavySpotlightUpdate(channel)
        }
    }

    private var backPressedTime = 0L
    private var activeSettingsDialog: SettingsDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesHelper(this)
        playlistManager = PlaylistManager(this)
        epgManager = EpgManager(this)
        appUpdateManager = AppUpdateManager(this)

        acquireMulticastLock()
        checkStoragePermission()
        initViews()
        initPlayer()
        initWebServer()
        startClock()
        loadPlaylist(forceRefresh = false, isStartup = true)
        loadEpgData()
        startPeriodicSportsRefresh()
        checkAppUpdate(isManual = false)
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
            val perms = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            if (perms.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1001)
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
            layoutManager = TvLinearLayoutManager(this@MainActivity) { binding.rvCategories }
            adapter = categoryAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            itemAnimator = null
        }

        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                if (channel.id == SportsLiveManager.ACTION_REFRESH_SPORTS || channel.streamUrl == "action://refresh_sports") {
                    refreshSportsMatchesManually()
                } else {
                    playChannel(channel)
                    hideHandler.postDelayed({ hideOverlay() }, 800)
                }
            },
            onChannelLongClick = { channel ->
                if (channel.id != SportsLiveManager.ACTION_REFRESH_SPORTS && !channel.streamUrl.startsWith("action://")) {
                    showChannelActionDialog(channel)
                }
            },
            onChannelFocused = { channel ->
                updateSpotlight(channel, immediate = false)
            }
        )
        binding.rvChannels.apply {
            layoutManager = TvLinearLayoutManager(this@MainActivity) { binding.rvChannels }
            adapter = channelAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            itemAnimator = null
        }
        binding.rvChannels.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // Nếu bản thân container RecyclerView nhận focus ngoài ý muốn, chuyển ngay focus vào item
                val lm = binding.rvChannels.layoutManager as? LinearLayoutManager
                val targetView = currentFocusedChannel?.let { ch ->
                    val idx = currentCategoryChannels.indexOfFirst { it.streamUrl == ch.streamUrl }
                    if (idx != -1) binding.rvChannels.findViewHolderForAdapterPosition(idx)?.itemView else null
                } ?: run {
                    val first = lm?.findFirstVisibleItemPosition() ?: 0
                    if (first != RecyclerView.NO_POSITION) {
                        binding.rvChannels.findViewHolderForAdapterPosition(first)?.itemView
                    } else null
                } ?: binding.rvChannels.layoutManager?.getChildAt(0)
                targetView?.requestFocus()
            }
        }

        // Lưới an toàn bảo toàn focus: Nếu item có focus bị detach/recycle khi cuộn nhanh
        binding.rvChannels.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {}
            override fun onChildViewDetachedFromWindow(view: View) {
                if (view.hasFocus() && binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    binding.rvChannels.post {
                        if (!binding.rvChannels.hasFocus() && binding.channelGuideOverlay.visibility == View.VISIBLE) {
                            val lm = binding.rvChannels.layoutManager as? LinearLayoutManager
                            val first = lm?.findFirstVisibleItemPosition() ?: 0
                            if (first != RecyclerView.NO_POSITION) {
                                binding.rvChannels.findViewHolderForAdapterPosition(first)?.itemView?.requestFocus()
                            }
                        }
                    }
                }
            }
        })

        // Tạm dừng tải ảnh Glide khi cuộn nhanh để giải phóng 100% CPU cho luồng UI 60fps
        binding.rvChannels.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING || newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    Glide.with(this@MainActivity).pauseRequests()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    Glide.with(this@MainActivity).resumeRequests()
                }
            }
        })

        // Khởi tạo Timeline EPG dạng list (Bấm OK để phát kênh, giữ OK để mở Menu tác vụ)
        epgTimelineAdapter = EpgTimelineAdapter(
            onItemClick = {
                currentFocusedChannel?.let { ch ->
                    playChannel(ch)
                    hideHandler.postDelayed({ hideOverlay() }, 800)
                }
            },
            onItemLongClick = {
                currentFocusedChannel?.let { ch ->
                    showChannelActionDialog(ch)
                }
            }
        )
        binding.rvSpotlightEpg.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = epgTimelineAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            itemAnimator = null
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

        binding.navRefreshPlaylists.setOnClickListener {
            refreshAllPlaylists()
        }

        binding.navCheckUpdate.setOnClickListener {
            checkAppUpdate(isManual = true)
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
                loadPlaylist(forceRefresh = true)
                activeSettingsDialog?.refreshPlaylists()
            },
            onChannelsUpdated = {
                refreshChannelsState()
            },
            onEngineChanged = {
                if (prefs.playerEngine != PreferencesHelper.ENGINE_EXOPLAYER) {
                    initVlcPlayer()
                } else {
                    initExoPlayer()
                }
                applyPlayerVisibility()
                currentPlayingChannel?.let { playChannel(it) }
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
        if (prefs.playerEngine != PreferencesHelper.ENGINE_EXOPLAYER) {
            binding.vlcVideoLayout.visibility = View.VISIBLE
            binding.playerView.visibility = View.GONE
        } else {
            binding.vlcVideoLayout.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
        }
    }

    private fun initVlcPlayer() {
        try {
            try {
                vlcPlayer?.apply {
                    stop()
                    try {
                        if (vlcVout.areViewsAttached()) {
                            detachViews()
                        }
                    } catch (e: Exception) {}
                    release()
                }
                vlcPlayer = null
                libVLC?.release()
                libVLC = null
            } catch (e: Exception) {}

            val deinterlaceVal = if (prefs.deinterlaceMode != PreferencesHelper.DEINTERLACE_OFF) "1" else "0"
            val options = arrayListOf(
                "-vv",
                // 1. Tối ưu bộ giải mã phần cứng cho Amlogic / Mali-450: Ưu tiên MediaCodec NDK
                "--codec=mediacodec_ndk,mediacodec_jni,all",
                "--avcodec-hw=any",
                "--http-user-agent=${prefs.httpUserAgent}",
                // 2. Chống xé hình/răng cưa phần cứng với thuật toán chọn lọc (Mặc định Yadif sắc nét như PC)
                "--deinterlace=$deinterlaceVal",
                "--deinterlace-mode=${prefs.deinterlaceMode}",
                // 3. Tối ưu bộ nhớ đệm (1.5s cân bằng giữa khởi động nhanh và chống tràn đệm/khựng hình)
                "--network-caching=1500",
                "--live-caching=1500",
                "--file-caching=1500",
                // 4. Giữ toàn vẹn khung hình và đồng bộ chuẩn A/V Master Clock (loại bỏ desync và noise)
                "--no-audio-time-stretch",
                // 5. Bật thống kê lưu lượng để đo chính xác bitrate thời gian thực
                "--stats",
                "--no-video-title-show",
                // 6. Luôn ưu tiên luồng chất lượng 1080p cao nhất trong adaptive streaming
                "--adaptive-logic=highest",
                "--adaptive-maxwidth=0",
                "--adaptive-maxheight=0"
            )
            libVLC = LibVLC(this, options)
            vlcPlayer = VlcMediaPlayer(libVLC).apply {
                setEventListener { event ->
                    runOnUiThread {
                        when (event.type) {
                            VlcMediaPlayer.Event.Buffering -> {
                                // Tuyệt đối không hiện vòng xoay giữa màn hình khi đang phát luồng
                                if (vlcPlayer?.isPlaying != true) {
                                    binding.layoutQuickBuffering.visibility = View.VISIBLE
                                }
                            }
                            VlcMediaPlayer.Event.Vout -> {
                                if (event.voutCount > 0) {
                                    // Khi video của kênh mới đã thực sự giải mã và xuất hình lên Surface
                                    binding.vlcVideoLayout.visibility = View.VISIBLE
                                    binding.layoutBuffering.visibility = View.GONE
                                    binding.layoutQuickBuffering.visibility = View.GONE
                                    binding.tvErrorMessage.visibility = View.GONE
                                    updateDynamicTelemetry()
                                }
                            }
                            VlcMediaPlayer.Event.Playing -> {
                                binding.tvErrorMessage.visibility = View.GONE
                                binding.layoutBuffering.visibility = View.GONE
                                binding.layoutQuickBuffering.visibility = View.GONE
                                updateDynamicTelemetry()
                                // Dự phòng cho kênh chỉ có tiếng (Radio / Audio-only) không có track video
                                binding.rootView.postDelayed({
                                    if (vlcPlayer?.isPlaying == true) {
                                        binding.vlcVideoLayout.visibility = View.VISIBLE
                                        binding.layoutBuffering.visibility = View.GONE
                                        binding.layoutQuickBuffering.visibility = View.GONE
                                    }
                                }, 1200L)
                            }
                            VlcMediaPlayer.Event.EncounteredError -> {
                                binding.layoutBuffering.visibility = View.GONE
                                binding.layoutQuickBuffering.visibility = View.GONE
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

        // 2. Tối ưu bộ đệm RAM cho Mi Box 3c (1GB RAM): Khởi động nhanh 0.8s, không đầy RAM
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                3000,  // Đệm tối thiểu 3 giây
                12000, // Đệm tối đa 12 giây (tiết kiệm RAM tối đa cho box 1GB)
                800,   // Bắt đầu phát tức thì sau 0.8s
                2000   // Phục hồi sau 2s nếu nghẽn mạng
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 3. Bắt buộc ExoPlayer luôn chọn độ phân giải 1080/cao nhất trong HLS dù màn hình là 720p
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
            .setUserAgent(prefs.httpUserAgent)
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
                                if (player?.isPlaying != true) {
                                    binding.layoutQuickBuffering.visibility = View.VISIBLE
                                }
                                binding.tvErrorMessage.visibility = View.GONE
                            }
                            Player.STATE_READY -> {
                                binding.layoutBuffering.visibility = View.GONE
                                binding.layoutQuickBuffering.visibility = View.GONE
                                binding.tvErrorMessage.visibility = View.GONE
                            }
                            Player.STATE_ENDED -> {
                                binding.layoutBuffering.visibility = View.GONE
                                binding.layoutQuickBuffering.visibility = View.GONE
                            }
                            Player.STATE_IDLE -> {
                                binding.layoutBuffering.visibility = View.GONE
                                binding.layoutQuickBuffering.visibility = View.GONE
                            }
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        runOnUiThread {
                            updateDynamicTelemetry()
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        runOnUiThread {
                            updateDynamicTelemetry()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        binding.layoutBuffering.visibility = View.GONE
                        binding.layoutQuickBuffering.visibility = View.GONE
                        binding.tvErrorMessage.visibility = View.VISIBLE
                        binding.tvErrorDetail.text = "Lỗi phát kênh: ${error.errorCodeName}\nBấm ▲ ▼ để đổi kênh khác"
                    }
                })
            }
    }

    private fun loadPlaylist(forceRefresh: Boolean, isStartup: Boolean = false) {
        val activeSource = prefs.getActivePlaylist()
        binding.tvActivePlaylistBadge.text = "Danh sách: ${activeSource.name}"

        binding.layoutBuffering.visibility = View.VISIBLE
        binding.tvBufferingText.text = getString(R.string.loading_playlist)
        binding.tvErrorMessage.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val categories = playlistManager.loadPlaylist(
                    activeSource.id,
                    activeSource.url,
                    forceRefresh = forceRefresh,
                    includeSports = !isStartup
                )
                allCategories.clear()
                allCategories.addAll(categories)

                allChannels.clear()
                val allCat = categories.firstOrNull { it.name == "Tất cả" }
                if (allCat != null) {
                    allChannels.addAll(allCat.channels)
                } else if (categories.isNotEmpty()) {
                    allChannels.addAll(categories.flatMap { it.channels })
                }

                categoryAdapter.submitList(allCategories.toList())

                if (allCategories.isNotEmpty()) {
                    currentSelectedCategory = allCategories[0]
                    displayCategoryChannels(allCategories[0])
                }

                binding.layoutBuffering.visibility = View.GONE

                val playableChannels = allChannels.filter { it.id != SportsLiveManager.ACTION_REFRESH_SPORTS && !it.streamUrl.startsWith("action://") }
                val targetChannel = when (prefs.startupMode) {
                    PreferencesHelper.MODE_FIRST_CHANNEL -> {
                        playableChannels.firstOrNull()
                    }
                    PreferencesHelper.MODE_FIXED_CHANNEL -> {
                        if (!prefs.defaultStartupChannelUrl.isNullOrBlank()) {
                            playableChannels.firstOrNull { it.streamUrl == prefs.defaultStartupChannelUrl }
                                ?: playableChannels.firstOrNull()
                        } else {
                            playableChannels.firstOrNull()
                        }
                    }
                    PreferencesHelper.MODE_LAST_WATCHED -> {
                        if (!prefs.lastChannelUrl.isNullOrBlank()) {
                            playableChannels.firstOrNull { it.streamUrl == prefs.lastChannelUrl }
                                ?: playableChannels.firstOrNull()
                        } else {
                            playableChannels.firstOrNull()
                        }
                    }
                    else -> playableChannels.firstOrNull()
                }

                if (targetChannel != null) {
                    binding.vlcVideoLayout.post {
                        if (!isFinishing && !isDestroyed) {
                            playChannel(targetChannel)
                            // Tự động phát kênh mặc định ngay, ẩn giao diện cài đặt/danh mục để xem toàn màn hình
                            hideOverlay()
                        }
                    }
                } else {
                    showOverlay()
                }

                // Nếu là khởi động app, quét luồng thể thao ngầm trong khi TV đã đang phát mượt mà
                if (isStartup) {
                    fetchSportsInBackground(forceRefresh = false, showNotification = true)
                }

            } catch (e: Exception) {
                binding.layoutBuffering.visibility = View.GONE
                binding.tvErrorMessage.visibility = View.VISIBLE
                binding.tvErrorDetail.text = getString(R.string.load_failed) + "\n" + (e.localizedMessage ?: "")
                showOverlay()
            }
        }
    }

    private fun fetchSportsInBackground(forceRefresh: Boolean = false, showNotification: Boolean = true) {
        sportsFetchJob?.cancel()
        sportsFetchJob = lifecycleScope.launch {
            // Cho kênh TV phát ổn định 2.5s trước khi quét luồng thể thao ngầm
            delay(2500)
            try {
                val sportsCategories = withContext(Dispatchers.IO) {
                    playlistManager.sportsLiveManager.getSportsCategories(forceRefresh = forceRefresh)
                }
                if (!isActive) return@launch
                if (sportsCategories.isNotEmpty()) {
                    applyUpdatedSportsCategories(sportsCategories)
                    val count = sportsCategories.firstOrNull()?.channels?.count { it.id != SportsLiveManager.ACTION_REFRESH_SPORTS } ?: 0
                    if (showNotification) {
                        showBottomNotification("✅ Đã cập nhật xong! Tìm thấy $count sự kiện thể thao hôm nay.")
                    }
                } else if (showNotification && forceRefresh) {
                    showBottomNotification("Không tìm thấy thêm trận đấu mới nào.")
                }
            } catch (e: Exception) {
                // Giữ trải nghiệm phát kênh truyền hình liền mạch, không làm gián đoạn
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

    private fun updateSpotlight(channel: Channel, immediate: Boolean = true) {
        if (channel.id == SportsLiveManager.ACTION_REFRESH_SPORTS || channel.streamUrl == "action://refresh_sports") {
            currentFocusedChannel = channel
            binding.tvSpotlightName.text = "CẬP NHẬT TRẬN ĐẤU THỂ THAO"
            binding.tvSpotlightResolution.text = "HÔM NAY"
            binding.tvSpotlightCategory.text = "Trực Tiếp Bóng Đá"
            binding.tvSpotlightStatus.visibility = View.GONE
            spotlightHandler.removeCallbacks(spotlightRunnable)
            performHeavySpotlightUpdate(channel)
            return
        }

        currentFocusedChannel = channel
        binding.tvSpotlightName.text = channel.name

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

        spotlightHandler.removeCallbacks(spotlightRunnable)
        pendingSpotlightChannel = channel

        if (immediate) {
            performHeavySpotlightUpdate(channel)
        } else {
            // Debounce 120ms khi duyệt nhanh phím D-pad: Giữ luồng UI 60fps mượt mà, triệt tiêu nghẽn phím
            spotlightHandler.postDelayed(spotlightRunnable, 120L)
        }
    }

    private fun performHeavySpotlightUpdate(channel: Channel) {
        if (isFinishing || isDestroyed) return

        if (channel.id == SportsLiveManager.ACTION_REFRESH_SPORTS || channel.streamUrl == "action://refresh_sports") {
            binding.ivSpotlightLogo.setImageResource(R.drawable.ic_tv_placeholder)
            binding.tvSpotlightStreamInfo.text = "Tác vụ: Cập nhật dữ liệu trận đấu mới nhất hôm nay"
            binding.rvSpotlightEpg.visibility = View.GONE
            binding.tvEpgEmpty.visibility = View.VISIBLE
            binding.tvEpgEmpty.text = "Nhấn OK trên điều khiển để tải lại trận đấu"
            return
        }

        // Logo chính hãng FPT CDN hoặc M3U (Khống chế kích thước và RGB_565 tiết kiệm RAM)
        val resolvedLogo = FptLogoHelper.getResolvedLogoUrl(channel.name, channel.logoUrl)
        if (resolvedLogo.isNotBlank()) {
            Glide.with(this)
                .load(resolvedLogo)
                .override(160, 100)
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_tv_placeholder)
                .error(R.drawable.ic_tv_placeholder)
                .into(binding.ivSpotlightLogo)
        } else {
            binding.ivSpotlightLogo.setImageResource(R.drawable.ic_tv_placeholder)
        }

        // Giao thức
        val udpxy = prefs.udpxyServer.trim()
        val protocol = when {
            channel.streamUrl.contains("/truc-tiep/") || channel.streamUrl.startsWith("streamfree://") -> "Web Live Stream (HLS)"
            channel.streamUrl.contains("/udp/", ignoreCase = true) || (udpxy.isNotBlank() && channel.streamUrl.startsWith("udp", ignoreCase = true)) -> "udpxy ($udpxy)"
            channel.streamUrl.contains("/rtp/", ignoreCase = true) || (udpxy.isNotBlank() && channel.streamUrl.startsWith("rtp", ignoreCase = true)) -> "udpxy ($udpxy)"
            channel.streamUrl.startsWith("udp", ignoreCase = true) -> "UDP Multicast"
            channel.streamUrl.startsWith("rtp", ignoreCase = true) -> "RTP Multicast"
            channel.streamUrl.contains(".m3u8", ignoreCase = true) -> "HLS / CDN"
            else -> "HTTP Stream"
        }
        val engineName = if (prefs.playerEngine == PreferencesHelper.ENGINE_EXOPLAYER) "ExoPlayer" else "LibVLC (MediaCodec)"
        binding.tvSpotlightStreamInfo.text = "Giao thức: $protocol • $engineName"

        // Lịch phát sóng EPG: Nếu là trận bóng đá trực tiếp, hiển thị lịch thi đấu chuyên biệt
        val timeline = if (isSportsLiveMatch(channel)) {
            buildSportsTimeline(channel)
        } else {
            epgManager.getTimelineForChannel(channel.id, channel.name)
        }

        if (timeline.isNotEmpty()) {
            binding.rvSpotlightEpg.visibility = View.VISIBLE
            binding.tvEpgEmpty.visibility = View.GONE
            epgTimelineAdapter.submitList(timeline)

            val currentIndex = epgTimelineAdapter.getCurrentPlayingPosition()
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
    }

    private fun playChannel(channel: Channel) {
        if (channel.id == SportsLiveManager.ACTION_REFRESH_SPORTS || channel.streamUrl == "action://refresh_sports") {
            refreshSportsMatchesManually()
            return
        }

        currentPlayingChannel = channel
        prefs.lastChannelUrl = channel.streamUrl

        currentMeasuredBitrateMbps = 0f
        currentVideoWidth = 0
        currentVideoHeight = 0
        currentVideoFps = 0
        lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
        lastRxTime = System.currentTimeMillis()
        startTelemetryUpdates()

        channelAdapter.setPlayingChannel(channel)
        updateSpotlight(channel)

        showQuickChannelInfo(channel)

        binding.tvErrorMessage.visibility = View.GONE
        binding.tvQuickBufferingText.text = "Đang tải luồng..."
        binding.layoutQuickBuffering.visibility = View.VISIBLE

        val isVlc = prefs.playerEngine != PreferencesHelper.ENGINE_EXOPLAYER

        // Nếu là trận đấu thể thao trực tiếp từ web (Xôi Lạc, Cà Khịa, StreamFree), giải mã link m3u8 động
        if (channel.streamUrl.contains("/truc-tiep/") || channel.streamUrl.startsWith("streamfree://")) {
            binding.tvQuickBufferingText.text = "Đang kết nối luồng trực tiếp..."
            binding.layoutQuickBuffering.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.Main) {
                val resolved = withContext(Dispatchers.IO) {
                    playlistManager.sportsLiveManager.resolveLiveStreamUrl(channel.streamUrl)
                }
                if (currentPlayingChannel?.id == channel.id) {
                    val streamUriString = normalizeStreamUrl(resolved, isVlc)
                    if (isVlc) {
                        playWithVlc(streamUriString)
                    } else {
                        playWithExoPlayer(streamUriString)
                    }
                }
            }
            return
        }

        val streamUriString = normalizeStreamUrl(channel.streamUrl, isVlc)

        if (isVlc) {
            playWithVlc(streamUriString)
        } else {
            playWithExoPlayer(streamUriString)
        }
    }

    private fun parseStreamUrlAndHeaders(rawUrl: String): Pair<String, Map<String, String>> {
        val trimmed = rawUrl.trim()
        if (!trimmed.contains("|")) {
            return Pair(trimmed, emptyMap())
        }
        val cleanUrl = trimmed.substringBefore('|').trim()
        val headersStr = trimmed.substringAfter('|').trim()
        val headersMap = mutableMapOf<String, String>()
        headersStr.split("&").forEach { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                headersMap[parts[0].trim()] = parts[1].trim()
            }
        }
        return Pair(cleanUrl, headersMap)
    }

    private fun normalizeStreamUrl(rawUrl: String, isVlc: Boolean): String {
        val (cleanUrl, headers) = parseStreamUrlAndHeaders(rawUrl)
        val trimmed = cleanUrl.trim()
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

        val finalClean = if (isVlc) {
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

        return if (headers.isNotEmpty()) {
            val hStr = headers.entries.joinToString("&") { "${it.key}=${it.value}" }
            "$finalClean|$hStr"
        } else {
            finalClean
        }
    }

    private fun playWithVlc(streamUriString: String) {
        try {
            player?.pause()
            binding.playerView.visibility = View.GONE

            // Che ngay lập tức khung hình tĩnh của kênh cũ để triệt tiêu hiện tượng đơ hình kênh cũ
            binding.vlcVideoLayout.visibility = View.INVISIBLE
            binding.layoutQuickBuffering.visibility = View.VISIBLE

            val vlc = libVLC ?: return
            val vp = vlcPlayer ?: return

            try {
                if (!vp.vlcVout.areViewsAttached()) {
                    vp.attachViews(binding.vlcVideoLayout, null, false, false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val (cleanUrl, headers) = parseStreamUrlAndHeaders(streamUriString)
            vp.stop()
            val media = Media(vlc, Uri.parse(cleanUrl)).apply {
                // Tối ưu giải mã phần cứng cho Amlogic Mi Box 3c (MediaCodec NDK)
                setHWDecoderEnabled(true, false)
                addOption(":codec=mediacodec_ndk,mediacodec_jni,all")
                addOption(":avcodec-hw=any")

                val userAgent = headers["User-Agent"] ?: headers["user-agent"] ?: prefs.httpUserAgent
                addOption(":http-user-agent=$userAgent")

                val referer = headers["Referer"] ?: headers["referer"]
                if (!referer.isNullOrBlank()) {
                    addOption(":http-referrer=$referer")
                }

                if (prefs.deinterlaceMode != PreferencesHelper.DEINTERLACE_OFF) {
                    addOption(":deinterlace=1")
                    addOption(":deinterlace-mode=${prefs.deinterlaceMode}")
                } else {
                    addOption(":deinterlace=0")
                }
                addOption(":network-caching=1500")
                addOption(":live-caching=1500")
                addOption(":file-caching=1500")
                addOption(":no-audio-time-stretch")
                // BẮT BUỘC LUÔN PHÁT ĐỘ PHÂN GIẢI 1080/CAO NHẤT DÙ MÀN HÌNH MÁY LÀ 720P
                addOption(":adaptive-logic=highest")
                addOption(":adaptive-maxwidth=0")
                addOption(":adaptive-maxheight=0")
            }
            vp.media = media
            media.release()
            vp.play()
        } catch (e: Exception) {
            binding.layoutBuffering.visibility = View.GONE
            binding.layoutQuickBuffering.visibility = View.GONE
            binding.tvErrorMessage.visibility = View.VISIBLE
            binding.tvErrorDetail.text = "Không thể phát qua VLC: ${e.localizedMessage}"
        }
    }

    private fun startTelemetryUpdates() {
        telemetryHandler.removeCallbacks(telemetryRunnable)
        telemetryHandler.postDelayed(telemetryRunnable, 800L)
    }

    private fun stopTelemetryUpdates() {
        telemetryHandler.removeCallbacks(telemetryRunnable)
    }

    private fun measureRealtimeBitrate() {
        val now = System.currentTimeMillis()
        val currentBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())

        var sampledMbps = 0f

        // 1. Đo lường qua TrafficStats của Linux Kernel (Độ chính xác cao, chuẩn xác cho cả HLS, UDP multicast, udpxy)
        if (currentBytes > 0 && lastRxBytes > 0 && now > lastRxTime) {
            val deltaBytes = currentBytes - lastRxBytes
            val deltaTimeMs = now - lastRxTime
            if (deltaTimeMs in 500..5000 && deltaBytes > 0) {
                val bps = (deltaBytes * 8f * 1000f) / deltaTimeMs
                val mbps = bps / 1_000_000f
                if (mbps in 0.1f..150.0f) {
                    sampledMbps = mbps
                }
            }
        }

        lastRxBytes = currentBytes
        lastRxTime = now

        // 2. Dự phòng: Kiểm tra thống kê trực tiếp từ LibVLC Media Stats nếu có
        if (sampledMbps == 0f && prefs.playerEngine != PreferencesHelper.ENGINE_EXOPLAYER) {
            try {
                vlcPlayer?.media?.let { media ->
                    val stats = media.stats
                    if (stats != null) {
                        val vlcMbps = (stats.inputBitrate * 8f) / 1_000_000f
                        if (vlcMbps in 0.1f..150.0f) {
                            sampledMbps = vlcMbps
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Dự phòng: Kiểm tra định dạng ExoPlayer
        if (sampledMbps == 0f && prefs.playerEngine == PreferencesHelper.ENGINE_EXOPLAYER) {
            val vf = player?.videoFormat
            if (vf != null && vf.bitrate > 0) {
                val exoMbps = vf.bitrate / 1_000_000f
                if (exoMbps in 0.1f..150.0f) {
                    sampledMbps = exoMbps
                }
            }
        }

        // 4. Cập nhật bitrate với bộ lọc làm mượt (Exponential Moving Average)
        if (sampledMbps > 0.1f) {
            currentMeasuredBitrateMbps = if (currentMeasuredBitrateMbps > 0.1f) {
                currentMeasuredBitrateMbps * 0.6f + sampledMbps * 0.4f
            } else {
                sampledMbps
            }
        }
    }

    private fun updateDynamicTelemetry() {
        val currentCh = currentPlayingChannel ?: return

        var width = currentVideoWidth
        var height = currentVideoHeight
        var fps = currentVideoFps
        var bitrateBps = 0
        var vCodec = ""
        var aCodec = ""
        var aChannels = ""
        var aSampleRate = ""

        try {
            if (prefs.playerEngine != PreferencesHelper.ENGINE_EXOPLAYER) {
                vlcPlayer?.currentVideoTrack?.let { vt ->
                    if (vt.width > 0) width = vt.width
                    if (vt.height > 0) height = vt.height
                    if (vt.frameRateDen > 0 && vt.frameRateNum > 0) {
                        fps = Math.round(vt.frameRateNum.toFloat() / vt.frameRateDen.toFloat())
                    }
                    bitrateBps = vt.bitrate
                    vCodec = when (vt.codec?.lowercase()) {
                        "h264", "avc1" -> "H.264"
                        "hevc", "h265" -> "H.265"
                        "mpgv", "mp2v" -> "MPEG-2"
                        else -> vt.codec?.uppercase() ?: "H.264"
                    }
                }
                vlcPlayer?.media?.let { media ->
                    val trackCount = media.trackCount
                    val curAudioId = vlcPlayer?.audioTrack ?: -1
                    for (i in 0 until trackCount) {
                        val track = media.getTrack(i)
                        if (track is IMedia.VideoTrack) {
                            if (width <= 0 && track.width > 0) width = track.width
                            if (height <= 0 && track.height > 0) height = track.height
                            if (fps <= 0 && track.frameRateDen > 0 && track.frameRateNum > 0) {
                                fps = Math.round(track.frameRateNum.toFloat() / track.frameRateDen.toFloat())
                            }
                            if (bitrateBps <= 0 && track.bitrate > 0) bitrateBps = track.bitrate
                            if (vCodec.isEmpty()) {
                                vCodec = when (track.codec?.lowercase()) {
                                    "h264", "avc1" -> "H.264"
                                    "hevc", "h265" -> "H.265"
                                    "mpgv", "mp2v" -> "MPEG-2"
                                    else -> track.codec?.uppercase() ?: "H.264"
                                }
                            }
                        }
                        if (track is IMedia.AudioTrack) {
                            if (curAudioId == -1 || track.id == curAudioId || aChannels.isEmpty()) {
                                aChannels = when (track.channels) {
                                    2 -> "Stereo 2.0"
                                    6 -> "5.1 Surround"
                                    1 -> "Mono"
                                    else -> if (track.channels > 0) "${track.channels}.0" else "Stereo 2.0"
                                }
                                if (track.rate > 0) {
                                    aSampleRate = "${track.rate / 1000}kHz"
                                }
                                aCodec = when (track.codec?.lowercase()) {
                                    "mp4a", "aac" -> "AAC"
                                    "ac3" -> "Dolby AC3"
                                    "eac3" -> "Dolby Digital+"
                                    "mpga" -> "MPEG Audio"
                                    else -> track.codec?.uppercase() ?: "AAC"
                                }
                                if (track.id == curAudioId) break
                            }
                        }
                    }
                }
            } else {
                // ExoPlayer
                player?.videoFormat?.let { vf ->
                    if (vf.width > 0) width = vf.width
                    if (vf.height > 0) height = vf.height
                    if (vf.frameRate > 0) {
                        fps = Math.round(vf.frameRate)
                    }
                    bitrateBps = if (vf.bitrate > 0) vf.bitrate else vf.peakBitrate
                    vCodec = when {
                        vf.sampleMimeType?.contains("hevc", ignoreCase = true) == true -> "H.265"
                        vf.sampleMimeType?.contains("avc", ignoreCase = true) == true -> "H.264"
                        else -> "H.264"
                    }
                }
                player?.videoSize?.let { vs ->
                    if (vs.width > 0 && width <= 0) width = vs.width
                    if (vs.height > 0 && height <= 0) height = vs.height
                }
                player?.audioFormat?.let { af ->
                    aChannels = when (af.channelCount) {
                        2 -> "Stereo 2.0"
                        6 -> "5.1 Surround"
                        1 -> "Mono"
                        else -> if (af.channelCount > 0) "${af.channelCount}.0" else "Stereo 2.0"
                    }
                    if (af.sampleRate > 0) {
                        aSampleRate = "${af.sampleRate / 1000}kHz"
                    }
                    aCodec = when {
                        af.sampleMimeType?.contains("mp4a", ignoreCase = true) == true -> "AAC"
                        af.sampleMimeType?.contains("ac3", ignoreCase = true) == true -> "Dolby AC3"
                        af.sampleMimeType?.contains("eac3", ignoreCase = true) == true -> "Dolby Digital+"
                        else -> "AAC"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Nếu decoder chưa bóc tách được kích thước (ví dụ luồng raw MPEG-TS qua UDP/HTTP không có manifest)
        // thì nhận diện qua tên kênh để người dùng không bị kẹt ở "Đang nhận diện video..."
        if (width <= 0 || height <= 0) {
            val upper = currentCh.name.uppercase()
            when {
                upper.contains("4K") || upper.contains("2160") -> { width = 3840; height = 2160 }
                upper.contains("720") -> { width = 1280; height = 720 }
                upper.contains("SD") || upper.contains("576") -> { width = 720; height = 576 }
                else -> { width = 1920; height = 1080 }
            }
        }

        if (width > 0 && height > 0) {
            currentVideoWidth = width
            currentVideoHeight = height
            if (fps > 0) currentVideoFps = fps
        }

        // Định dạng Bitrate thực tế đo đạc
        val bitrateStr = if (currentMeasuredBitrateMbps > 0.1f) {
            String.format(Locale.US, "%.1f Mbps", currentMeasuredBitrateMbps)
        } else if (bitrateBps > 100_000) {
            String.format(Locale.US, "%.1f Mbps", bitrateBps / 1_000_000f)
        } else {
            val match = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*mbps").find(currentCh.name)
            if (match != null) {
                "${match.groupValues[1]} Mbps"
            } else {
                when {
                    height >= 2000 || width >= 3800 -> "15.0 Mbps"
                    height >= 900 || width >= 1400 -> "6.5 Mbps"
                    height >= 700 || width >= 1200 -> "3.5 Mbps"
                    height > 0 -> "1.8 Mbps"
                    else -> "--- Mbps"
                }
            }
        }

        // Nhãn phân giải chuẩn
        val resBadge = when {
            height >= 2000 || width >= 3800 -> "4K UHD"
            height >= 900 || width >= 1400 -> "FHD 1080"
            height >= 700 || width >= 1200 -> "HD 720p"
            height > 0 -> "SD ${height}p"
            else -> "Đang nhận diện"
        }

        // Chi tiết Audio: Codec Kênh âm thanh (Tần số lấy mẫu)
        val audioDetails = if (aCodec.isNotBlank()) {
            listOfNotNull(
                aCodec.takeIf { it.isNotBlank() },
                aChannels.takeIf { it.isNotBlank() },
                aSampleRate.takeIf { it.isNotBlank() }?.let { "($it)" }
            ).joinToString(" ")
        } else {
            "AAC Stereo 2.0 (48kHz)"
        }

        val displayFps = when {
            fps == 25 || fps == 50 -> if (height >= 1000) 25 else fps
            fps > 0 -> fps
            height >= 1000 -> 25
            height in 1..600 -> 25
            else -> 50
        }
        val fpsStr = " @ ${displayFps}fps"
        val resDim = if (width > 0 && height > 0) {
            "${width}x${height}$fpsStr"
        } else {
            "Đang nhận diện video..."
        }

        // Bố cục Dòng 3 thẻ OSD góc dưới (Màu xanh Cyan):
        // Kích thước độ phân giải @ Tần số quét • Bitrate • Thông tin Audio chi tiết
        // Ví dụ: 1920x1080 @ 50fps • 8.0 Mbps • AAC Stereo 2.0 (48kHz)
        val quickOsdLine3 = if (width > 0 && height > 0) {
            "$resDim • $bitrateStr • $audioDetails"
        } else {
            "Đang nhận diện video... • $bitrateStr • $audioDetails"
        }
        binding.tvQuickResolution.text = quickOsdLine3
        binding.tvQuickAudioBitrate.text = "Âm thanh: $audioDetails"

        binding.tvSpotlightResolution.text = resBadge
        val engineName = if (prefs.playerEngine == PreferencesHelper.ENGINE_EXOPLAYER) "ExoPlayer" else "LibVLC (MediaCodec)"
        binding.tvSpotlightStreamInfo.text = "$resDim • $bitrateStr • $resBadge | Âm thanh: $audioDetails | $engineName"
    }

    private fun playWithExoPlayer(streamUriString: String) {
        try {
            vlcPlayer?.apply {
                stop()
                try {
                    if (vlcVout.areViewsAttached()) {
                        detachViews()
                    }
                } catch (e: Exception) {}
            }
            binding.vlcVideoLayout.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE

            val (cleanUrl, headers) = parseStreamUrlAndHeaders(streamUriString)
            val mediaItem = MediaItem.fromUri(Uri.parse(cleanUrl))

            if (headers.isNotEmpty()) {
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(headers["User-Agent"] ?: headers["user-agent"] ?: prefs.httpUserAgent)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(20000)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers)
                val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
                val mediaSourceFactory = DefaultMediaSourceFactory(defaultDataSourceFactory, extractorsFactory)
                val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
                player?.setMediaSource(mediaSource)
            } else {
                player?.setMediaItem(mediaItem)
            }

            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            binding.layoutBuffering.visibility = View.GONE
            binding.layoutQuickBuffering.visibility = View.GONE
            binding.tvErrorMessage.visibility = View.VISIBLE
            binding.tvErrorDetail.text = "Không thể phát qua ExoPlayer: ${e.localizedMessage}"
        }
    }

    private fun showQuickChannelInfo(channel: Channel) {
        // Ẩn thông báo cạnh dưới nếu đang mở để tránh hai bảng đè lên nhau
        bottomNotificationHandler.removeCallbacks(hideBottomNotificationRunnable)
        binding.layoutBottomNotification.visibility = View.GONE

        binding.tvQuickNumber.text = String.format("%02d", channel.number)
        binding.tvQuickName.text = channel.name

        // Lấy tên chương trình EPG đang phát thời gian thực (hiển thị xong rồi tự tắt đi)
        val currentProg = if (isSportsLiveMatch(channel)) {
            val sportsTimeline = buildSportsTimeline(channel)
            val now = System.currentTimeMillis()
            sportsTimeline.firstOrNull { it.isCurrent(now) } ?: sportsTimeline.getOrNull(1)
        } else {
            epgManager.getCurrentProgram(channel.id, channel.name)
        }
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
                .override(120, 80)
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_tv_placeholder)
                .error(R.drawable.ic_tv_placeholder)
                .into(binding.ivQuickLogo)
        } else {
            binding.ivQuickLogo.setImageResource(R.drawable.ic_tv_placeholder)
        }

        val upperName = channel.name.uppercase()
        val initialResDim = if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            val displayFps = when {
                currentVideoFps == 25 || currentVideoFps == 50 -> if (currentVideoHeight >= 1000) 25 else currentVideoFps
                currentVideoFps > 0 -> currentVideoFps
                currentVideoHeight >= 1000 -> 25
                currentVideoHeight in 1..600 -> 25
                else -> 50
            }
            "${currentVideoWidth}x${currentVideoHeight} @ ${displayFps}fps"
        } else {
            when {
                upperName.contains("4K") || upperName.contains("2160") -> "3840x2160 @ 50fps"
                upperName.contains("720") -> "1280x720 @ 50fps"
                upperName.contains("SD") || upperName.contains("576") -> "720x576 @ 25fps"
                else -> "1920x1080 @ 25fps"
            }
        }
        val initialBitrate = if (currentMeasuredBitrateMbps > 0.1f) {
            String.format(Locale.US, "%.1f Mbps", currentMeasuredBitrateMbps)
        } else {
            val match = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*mbps").find(channel.name)
            if (match != null) {
                "${match.groupValues[1]} Mbps"
            } else {
                "--- Mbps"
            }
        }
        // Dòng 3 (Màu xanh Cyan): Kích thước độ phân giải @ FPS • Bitrate • Thông tin Audio chi tiết
        binding.tvQuickResolution.text = "$initialResDim • $initialBitrate • AAC Stereo 2.0 (48kHz)"
        binding.tvQuickAudioBitrate.text = "Âm thanh: AAC Stereo 2.0 (48kHz)"
        updateDynamicTelemetry()
        startTelemetryUpdates()

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
        // Lấy danh sách kênh trong danh mục/list hiện hành (loại bỏ các nút chức năng như nút refresh thể thao)
        val activeChannels = (if (currentCategoryChannels.any { it.streamUrl == currentPlayingChannel?.streamUrl }) {
            currentCategoryChannels
        } else {
            allCategories.firstOrNull { cat ->
                cat.name != "★ KÊNH YÊU THÍCH" && cat.channels.any { it.streamUrl == currentPlayingChannel?.streamUrl }
            }?.channels ?: allChannels
        }).filter { it.id != SportsLiveManager.ACTION_REFRESH_SPORTS && !it.streamUrl.startsWith("action://") }

        if (activeChannels.isEmpty()) return

        val currentIndex = activeChannels.indexOfFirst { it.streamUrl == currentPlayingChannel?.streamUrl }
        val newIndex = if (next) {
            // Ch+ hoặc Dpad Down: Số thứ tự lớn hơn (kênh kế tiếp)
            if (currentIndex == -1 || currentIndex >= activeChannels.size - 1) 0 else currentIndex + 1
        } else {
            // Ch- hoặc Dpad Up: Số thứ tự nhỏ hơn (kênh phía trước)
            if (currentIndex <= 0) activeChannels.size - 1 else currentIndex - 1
        }

        val targetChannel = activeChannels[newIndex]
        playChannel(targetChannel)

        // Nếu bảng danh sách kênh đang hiển thị, tự động cuộn và trỏ focus tới kênh mới
        if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
            val idxInView = currentCategoryChannels.indexOfFirst { it.streamUrl == targetChannel.streamUrl }
            if (idxInView != -1) {
                binding.rvChannels.scrollToPosition(idxInView)
                binding.rvChannels.post {
                    binding.rvChannels.findViewHolderForAdapterPosition(idxInView)?.itemView?.requestFocus()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Phím Channel + (Ch+) trên điều khiển TV -> Kênh có số thứ tự lớn hơn
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (activeSettingsDialog?.isShowing != true) {
                        switchChannel(next = true)
                        resetOverlayTimer()
                        return true
                    }
                }
                // Phím Channel - (Ch-) trên điều khiển TV -> Kênh có số thứ tự nhỏ hơn
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (activeSettingsDialog?.isShowing != true) {
                        switchChannel(next = false)
                        resetOverlayTimer()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_HELP -> {
                    showSettingsDialog()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetOverlayTimer()

        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                switchChannel(next = true)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                switchChannel(next = false)
                return true
            }
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
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_HELP -> {
                showSettingsDialog()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    val focused = currentFocus
                    if (focused != null && (focused.id == R.id.rvSpotlightEpg || focused.id == R.id.rvCategories ||
                                focused.id == R.id.navFavorites || focused.id == R.id.navPlaylists ||
                                focused.id == R.id.navSidebarSettings || focused.id == R.id.navRefreshPlaylists ||
                                focused.id == R.id.navCheckUpdate)) {
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
                        // Chỉ hiển thị thông báo xác nhận thoát, không gọi showQuickChannelInfo để tránh đè 2 bảng lên nhau
                        showBottomNotification("Bấm QUAY LẠI lần nữa để thoát ứng dụng")
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
        startTelemetryUpdates()

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
        if (activeSettingsDialog?.isShowing == true) {
            activeSettingsDialog?.dismiss()
            return
        }
        resetOverlayTimer()
        val dialog = SettingsDialog(this) { playlistChanged, engineChanged ->
            if (playlistChanged) {
                loadPlaylist(forceRefresh = true)
                loadEpgData()
            }
            if (engineChanged) {
                if (prefs.playerEngine != PreferencesHelper.ENGINE_EXOPLAYER) {
                    initVlcPlayer()
                }
                applyPlayerVisibility()
                currentPlayingChannel?.let { playChannel(it) }
            }
        }
        dialog.setOnDismissListener {
            activeSettingsDialog = null
            if (binding.channelGuideOverlay.visibility != View.VISIBLE) {
                currentPlayingChannel?.let { showQuickChannelInfo(it) }
            }
        }
        activeSettingsDialog = dialog
        dialog.show()
    }

    /**
     * Nút 1: Làm mới toàn bộ playlist (cả các sự kiện trực tiếp thể thao và các playlist M3U người dùng nhập vào)
     */
    private fun refreshAllPlaylists() {
        showBottomNotification("Đang làm mới toàn bộ danh sách kênh và trực tiếp thể thao...")
        binding.layoutBuffering.visibility = View.VISIBLE
        binding.tvBufferingText.text = "Đang cập nhật danh sách..."

        lifecycleScope.launch {
            try {
                // 1. Xóa sạch file cache của toàn bộ danh sách M3U và thể thao
                withContext(Dispatchers.IO) {
                    playlistManager.refreshAllPlaylistCaches()
                }

                // 2. Tải lại active playlist và các luồng thể thao trực tiếp mới nhất
                val activeSource = prefs.getActivePlaylist()
                val categories = withContext(Dispatchers.IO) {
                    playlistManager.loadPlaylist(activeSource.id, activeSource.url, forceRefresh = true)
                }

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

                // Cập nhật lại danh mục đang chọn (nếu có)
                val targetCat = allCategories.firstOrNull { it.name == currentSelectedCategory?.name } ?: allCategories.firstOrNull()
                if (targetCat != null) {
                    currentSelectedCategory = targetCat
                    categoryAdapter.setSelectedCategory(targetCat)
                    displayCategoryChannels(targetCat)
                }

                // Tải lại EPG
                loadEpgData()

                binding.layoutBuffering.visibility = View.GONE
                showBottomNotification("Đã cập nhật ${allChannels.size} kênh & các luồng thể thao mới nhất!")
            } catch (e: Exception) {
                binding.layoutBuffering.visibility = View.GONE
                showBottomNotification("Lỗi cập nhật: ${e.message}")
            }
        }
    }

    /**
     * Nút 2: Kiểm tra bản cập nhật ứng dụng từ GitHub Releases
     * isManual = false: Tự động chạy ẩn khi mở app, chỉ hiện thông báo khi CÓ bản mới.
     * isManual = true: Người dùng bấm nút "Check for update", hiện tiến trình và thông báo cụ thể.
     */
    private fun checkAppUpdate(isManual: Boolean) {
        if (isManual) {
            Toast.makeText(this, "Đang kiểm tra phiên bản mới từ GitHub...", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            if (!isManual) {
                // Chờ app khởi động và phát kênh ổn định trước khi check ngầm
                delay(3000)
            }

            val release = appUpdateManager.fetchLatestRelease()
            if (release != null && appUpdateManager.isNewerVersion(release.tagName)) {
                // Có phiên bản mới -> Hiện dialog cập nhật
                appUpdateManager.showUpdateDialog(this@MainActivity, lifecycleScope, release)
            } else {
                if (isManual) {
                    val currentVer = appUpdateManager.getAppVersion(this@MainActivity)
                    Toast.makeText(this@MainActivity, "Bạn đang sử dụng phiên bản mới nhất (v$currentVer)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (prefs.playerEngine != PreferencesHelper.ENGINE_EXOPLAYER) {
            vlcPlayer?.let { vp ->
                try {
                    if (!vp.vlcVout.areViewsAttached()) {
                        vp.attachViews(binding.vlcVideoLayout, null, false, false)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.playerEngine != PreferencesHelper.ENGINE_EXOPLAYER) {
            if (currentPlayingChannel != null && vlcPlayer?.isPlaying == false) {
                vlcPlayer?.play()
            }
        } else {
            if (currentPlayingChannel != null && player?.isPlaying == false) {
                player?.play()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        vlcPlayer?.pause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        stopTelemetryUpdates()
        if (prefs.playerEngine != PreferencesHelper.ENGINE_EXOPLAYER) {
            vlcPlayer?.let { vp ->
                try {
                    vp.stop()
                    if (vp.vlcVout.areViewsAttached()) {
                        vp.detachViews()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        player?.pause()
    }

    private fun isSportsLiveMatch(channel: Channel): Boolean {
        return channel.streamUrl.contains("/truc-tiep/") ||
                channel.streamUrl.startsWith("streamfree://") ||
                channel.id.startsWith("streamfree_") ||
                channel.id.startsWith("intl_sports_") ||
                channel.id.startsWith("chuoichien_") ||
                channel.id.startsWith("gavang_") ||
                channel.id.startsWith("xoilac_") ||
                channel.id.startsWith("cakhia_") ||
                channel.groupTitle.contains("Chuối Chiên") ||
                channel.groupTitle.contains("Gà Vàng") ||
                channel.groupTitle.contains("Xôi Lạc") ||
                channel.groupTitle.contains("Cà Khịa") ||
                channel.groupTitle.contains("StreamFree") ||
                channel.groupTitle.contains("THỂ THAO QUỐC TẾ") ||
                channel.groupTitle.contains("TRỰC TIẾP") ||
                channel.name.startsWith("⚽") ||
                channel.name.startsWith("🏎️") ||
                channel.name.startsWith("🎾") ||
                channel.name.startsWith("🏀") ||
                channel.name.startsWith("🏆") ||
                channel.name.startsWith("🚴")
    }

    private fun buildSportsTimeline(channel: Channel): List<EpgProgram> {
        val name = channel.name
        val timeMatch = Regex("\\[(\\d{1,2}:\\d{2})\\]").find(name)
        val matchTimeStr = timeMatch?.groupValues?.get(1)

        val cal = Calendar.getInstance()
        var matchStartTime: Long = cal.timeInMillis

        if (!matchTimeStr.isNullOrBlank()) {
            try {
                val parts = matchTimeStr.split(":")
                cal.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                cal.set(Calendar.MINUTE, parts[1].toInt())
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                matchStartTime = cal.timeInMillis
            } catch (e: Exception) {}
        }

        val matchDurationMs = 105 * 60 * 1000L
        val matchEndTime = matchStartTime + matchDurationMs

        var cleanTitle = name.replace("⚽", "").trim()
        cleanTitle = cleanTitle.replace(Regex("\\[[^\\]]+\\]"), "").trim()
        var blvInfo = ""
        val blvMatch = Regex("\\((BLV[^\\)]+)\\)").find(cleanTitle)
        if (blvMatch != null) {
            blvInfo = blvMatch.groupValues[1]
            cleanTitle = cleanTitle.replace(blvMatch.value, "").trim()
        }
        val sourceInfo = if (cleanTitle.contains("-")) {
            val src = cleanTitle.substringAfterLast("-").trim()
            cleanTitle = cleanTitle.substringBeforeLast("-").trim()
            src
        } else channel.groupTitle

        val list = mutableListOf<EpgProgram>()

        list.add(
            EpgProgram(
                channelId = channel.id,
                startTime = matchStartTime - 30 * 60 * 1000L,
                endTime = matchStartTime,
                title = "Trước trận đấu: Nhận định & Đội hình",
                desc = "Phân tích chiến thuật trước giờ bóng lăn: $cleanTitle"
            )
        )

        val liveDesc = if (blvInfo.isNotBlank()) "Bình luận: $blvInfo • Tiếp sóng: $sourceInfo" else "Tiếp sóng: $sourceInfo (Trực tiếp HD/FHD)"
        list.add(
            EpgProgram(
                channelId = channel.id,
                startTime = matchStartTime,
                endTime = matchEndTime,
                title = "⚽ TRỰC TIẾP: $cleanTitle",
                desc = liveDesc
            )
        )

        list.add(
            EpgProgram(
                channelId = channel.id,
                startTime = matchEndTime,
                endTime = matchEndTime + 30 * 60 * 1000L,
                title = "Sau trận đấu: Phỏng vấn & Điểm nhấn",
                desc = "Tổng kết diễn biến và kết quả trận đấu: $cleanTitle"
            )
        )

        return list
    }

    private fun refreshSportsMatchesManually() {
        showBottomNotification("🔄 Đang làm mới danh sách trận đấu thể thao...")
        binding.tvQuickBufferingText.text = "Đang quét trận đấu..."
        binding.layoutQuickBuffering.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.Main) {
            val sportsCategories = withContext(Dispatchers.IO) {
                playlistManager.sportsLiveManager.getSportsCategories(forceRefresh = true)
            }
            binding.layoutQuickBuffering.visibility = View.GONE
            if (sportsCategories.isNotEmpty()) {
                applyUpdatedSportsCategories(sportsCategories)
                val count = sportsCategories.firstOrNull()?.channels?.count { it.id != SportsLiveManager.ACTION_REFRESH_SPORTS } ?: 0
                showBottomNotification("✅ Đã cập nhật xong! Tìm thấy $count trận đấu trực tiếp hôm nay.")
            } else {
                showBottomNotification("Không tìm thấy thêm trận đấu mới nào.")
            }
        }
    }

    private fun startPeriodicSportsRefresh() {
        lifecycleScope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L) // Chu kỳ quét tự động 30 phút 1 lần
                try {
                    val sportsCategories = withContext(Dispatchers.IO) {
                        playlistManager.sportsLiveManager.getSportsCategories(forceRefresh = true)
                    }
                    if (sportsCategories.isNotEmpty()) {
                        applyUpdatedSportsCategories(sportsCategories)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun applyUpdatedSportsCategories(sportsCategories: List<Category>) {
        val sportsNames = sportsCategories.map { it.name }.toSet() + setOf(
            "⚽ TRỰC TIẾP BÓNG ĐÁ (HÔM NAY)",
            "🌐 THỂ THAO QUỐC TẾ (F1 / TENNIS)",
            "🌐 KÊNH THỂ THAO QUỐC TẾ (F1 / TENNIS)",
            "🍌 Chuối Chiên TV",
            "🐔 Gà Vàng / Tiếu Lâm",
            "🍚 Xôi Lạc TV",
            "🌶️ Cà Khịa TV"
        )
        val nonSports = allCategories.filter { it.name !in sportsNames }
        allCategories.clear()
        val favCat = nonSports.firstOrNull { it.name == "★ KÊNH YÊU THÍCH" }
        if (favCat != null) allCategories.add(favCat)
        allCategories.addAll(sportsCategories)
        nonSports.filter { it.name != "★ KÊNH YÊU THÍCH" }.forEach { allCategories.add(it) }

        categoryAdapter.submitList(allCategories.toList())

        // Giữ nguyên danh mục đang duyệt nếu có
        val currentCatName = currentSelectedCategory?.name ?: ""
        val updatedCat = allCategories.firstOrNull { it.name == currentCatName } ?: allCategories.firstOrNull()
        if (updatedCat != null) {
            currentSelectedCategory = updatedCat
            if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                displayCategoryChannels(updatedCat)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTelemetryUpdates()
        telemetryHandler.removeCallbacksAndMessages(null)
        bottomNotificationHandler.removeCallbacksAndMessages(null)
        sportsFetchJob?.cancel()
        hideHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
        spotlightHandler.removeCallbacksAndMessages(null)
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
                try {
                    if (vlcVout.areViewsAttached()) {
                        detachViews()
                    }
                } catch (e: Exception) {}
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
