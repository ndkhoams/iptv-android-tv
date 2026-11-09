package com.iptv.tvapp.ui

import android.Manifest
import android.app.ActivityManager
import android.app.Dialog
import android.media.AudioManager
import android.view.ViewParent
import java.io.File
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
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer
import com.google.android.exoplayer2.video.VideoRendererEventListener
import com.google.android.exoplayer2.video.VideoSize
import java.util.ArrayList
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
import com.iptv.tvapp.ui.adapter.StandingsAdapter
import com.iptv.tvapp.data.SportsStandingsManager
import com.iptv.tvapp.ui.dialog.ChannelActionDialog
import com.iptv.tvapp.ui.dialog.QuickPlaylistDialog
import com.iptv.tvapp.ui.dialog.MatchSourcesDialog
import com.iptv.tvapp.ui.dialog.SettingsDialog
import com.iptv.tvapp.update.AppUpdateManager
import com.iptv.tvapp.util.FptLogoHelper
import com.iptv.tvapp.util.NetworkUtils
import com.iptv.tvapp.util.DeviceUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var standingsManager: SportsStandingsManager
    private lateinit var standingsAdapter: StandingsAdapter

    private var player: ExoPlayer? = null
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

    enum class AppMode {
        TV,
        SPORTS
    }

    private var currentAppMode = AppMode.TV
    private var currentDisplayingLeague: String? = null
    private var currentMatchFallbackCount = 0
    private val tvCategories = mutableListOf<Category>()
    private val sportsCategoriesList = mutableListOf<Category>()
    private val allCategories = mutableListOf<Category>()
    private var allChannels = mutableListOf<Channel>()
    private var currentCategoryChannels = mutableListOf<Channel>()
    private var currentPlayingChannel: Channel? = null
    private var currentPlayingMatchGroup: SportsLiveManager.MatchGroup? = null
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
    private var hasLoadedSportsOnce: Boolean = false

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
            if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                updateSystemStatsBadge()
            }
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
    private var backHandledOnDown = false
    private var lastBackPressTimestamp = 0L
    private var exitPromptActiveUntil = 0L
    private var backKeyDownTimestamp = 0L
    private var pendingSingleBackRunnable: Runnable? = null
    private var okHandledOnDown = false
    private var rightHandledOnDown = false
    private var leftHandledOnDown = false
    private var upDownHandledOnDown = false
    private var activeSettingsDialog: SettingsDialog? = null
    private var activeCustomDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesHelper(this)
        playlistManager = PlaylistManager(this)
        epgManager = EpgManager(this)
        appUpdateManager = AppUpdateManager(this)

        acquireMulticastLock()
        // Loại bỏ checkStoragePermission() tại onCreate() để tránh crash ANR/dialog trên Android 6.0 Marshmallow (Mi Box 3c)
        initViews()
        initPlayer()
        initWebServer()
        startClock()
        loadPlaylist(forceRefresh = false, isStartup = true)
        loadEpgData()
        // Không chạy startPeriodicSportsRefresh() lúc khởi động để giảm tải RAM/CPU tối đa cho thiết bị 1GB RAM
        checkAppUpdate(isManual = false)
    }

    private fun loadEpgData() {
        lifecycleScope.launch {
            try {
                // Trì hoãn 10s sau khởi động để phát luồng TV mượt mà trước, tránh giật lag hay tràn RAM (OOM) trên Mi Box 1GB
                delay(10000L)
                epgManager.loadEpg()
                withContext(Dispatchers.Main) {
                    currentFocusedChannel?.let { updateSpotlight(it) }
                        ?: currentPlayingChannel?.let { updateSpotlight(it) }
                }
            } catch (oom: OutOfMemoryError) {
                // Chống crash bộ nhớ thấp
            } catch (e: Exception) {
                // Bỏ qua lỗi mạng
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
            layoutManager = TvLinearLayoutManager(this@MainActivity, { binding.rvCategories }) { focused, direction ->
                if (direction == View.FOCUS_RIGHT) findHorizontallyAlignedChannelView(focused) else null
            }
            adapter = categoryAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            itemAnimator = null
        }

        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                if (channel.id == SportsLiveManager.ACTION_REFRESH_SPORTS || channel.streamUrl == "action://refresh_sports") {
                    refreshSportsMatchesManually()
                } else if (channel.streamUrl.startsWith("match://")) {
                    val matchKey = channel.streamUrl.removePrefix("match://")
                    val matchGroup = playlistManager.sportsLiveManager.getMatch(matchKey)
                    if (matchGroup != null && matchGroup.sources.isNotEmpty()) {
                        showMatchSourcesDialog(matchGroup)
                    } else {
                        Toast.makeText(this, "Không tìm thấy nguồn phát cho trận đấu này", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (currentAppMode == AppMode.TV) {
                        currentPlayingMatchGroup = null
                    } else {
                        currentPlayingMatchGroup = playlistManager.sportsLiveManager.findMatchForChannel(channel)
                    }
                    playChannel(channel)
                    hideHandler.postDelayed({ hideOverlay() }, 800)
                }
            },
            onChannelLongClick = { channel ->
                if (channel.id != SportsLiveManager.ACTION_REFRESH_SPORTS && !channel.streamUrl.startsWith("action://") && !channel.streamUrl.startsWith("match://")) {
                    showChannelActionDialog(channel)
                }
            },
            onChannelFocused = { channel ->
                updateSpotlight(channel, immediate = false)
            }
        )
        binding.rvChannels.apply {
            layoutManager = TvLinearLayoutManager(this@MainActivity, { binding.rvChannels }) { focused, direction ->
                when (direction) {
                    View.FOCUS_LEFT -> findHorizontallyAlignedCategoryView(focused)
                    View.FOCUS_RIGHT -> focused // Khóa focus: Không cho focus nhảy sang cột Spotlight EPG/BXH bên phải
                    else -> null
                }
            }
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

        standingsManager = SportsStandingsManager(this)
        standingsAdapter = StandingsAdapter()
        binding.rvSpotlightStandings.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = standingsAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            itemAnimator = null
        }

        // Sidebar Navigation Buttons
        binding.navFavorites.setOnClickListener {
            resetOverlayTimer()
            if (currentAppMode != AppMode.TV) {
                switchAppMode(AppMode.TV, focusCategories = false)
            }
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

        binding.navModeTv.setOnClickListener {
            resetOverlayTimer()
            switchAppMode(AppMode.TV, focusCategories = true)
        }

        binding.navModeSports.setOnClickListener {
            resetOverlayTimer()
            if (currentAppMode != AppMode.SPORTS && sportsCategoriesList.isEmpty()) {
                showBottomNotification("⚽ Đang tải danh sách trận đấu trực tiếp hôm nay...")
            }
            switchAppMode(AppMode.SPORTS, focusCategories = true)
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
        binding.playerView.setOnClickListener {
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
                if (currentAppMode == AppMode.SPORTS && !ch.streamUrl.startsWith("match://")) {
                    currentPlayingMatchGroup = playlistManager.sportsLiveManager.findMatchForChannel(ch)
                }
                playChannel(ch)
                hideHandler.postDelayed({ hideOverlay() }, 800)
            },
            onSendKey = { action ->
                handleRemoteAction(action)
            },
            onPlaylistChanged = {
                loadPlaylist(forceRefresh = true)
                activeSettingsDialog?.refreshPlaylists()
                (activeCustomDialog as? QuickPlaylistDialog)?.refreshPlaylists()
            },
            onChannelsUpdated = {
                refreshChannelsState()
            },
            onEngineChanged = {
                initExoPlayer()
                applyPlayerVisibility()
                currentPlayingChannel?.let { playChannel(it) }
            },
            tvCategoriesProvider = {
                if (tvCategories.isNotEmpty()) tvCategories
                else allCategories.filter { !it.name.contains("LIVE EVENTS", ignoreCase = true) }
            },
            sportsCategoriesProvider = {
                sportsCategoriesList
            },
            getMatchGroup = { matchKey ->
                playlistManager.sportsLiveManager.getMatch(matchKey)
            },
            onRefreshSports = {
                refreshSportsMatchesManually()
            }
        )
        webServer?.start()
    }

    private fun handleRemoteAction(action: String) {
        resetOverlayTimer()
        val isOverlayVisible = (binding.channelGuideOverlay.visibility == View.VISIBLE)
        val activeDialog = if (activeSettingsDialog?.isShowing == true) activeSettingsDialog else activeCustomDialog?.takeIf { it.isShowing }

        if (activeDialog != null && activeDialog.isShowing) {
            when (action.lowercase()) {
                "back" -> {
                    activeDialog.dismiss()
                    return
                }
                "center", "ok" -> {
                    val focused = activeDialog.currentFocus
                    if (focused != null) {
                        focused.performClick()
                    } else {
                        activeDialog.window?.decorView?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                        activeDialog.window?.decorView?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                    }
                    return
                }
                "up", "down", "left", "right" -> {
                    val dir = when (action.lowercase()) {
                        "up" -> View.FOCUS_UP
                        "down" -> View.FOCUS_DOWN
                        "left" -> View.FOCUS_LEFT
                        else -> View.FOCUS_RIGHT
                    }
                    val focused = activeDialog.currentFocus
                    val next = focused?.focusSearch(dir)
                    if (next != null && next != focused) {
                        next.requestFocus(dir)
                    }
                    return
                }
                "menu" -> {
                    activeDialog.dismiss()
                    return
                }
            }
        }

        when (action.lowercase()) {
            "vol_up" -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }
            "vol_down" -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
            "mute" -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                } else {
                    am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                }
            }
            "overlay", "guide" -> {
                if (isOverlayVisible) hideOverlay() else showOverlay()
            }
            "menu" -> {
                showSettingsDialog()
            }
            "ch_prev" -> {
                switchChannel(next = false)
            }
            "ch_next" -> {
                switchChannel(next = true)
            }
            "back" -> {
                if (isOverlayVisible) {
                    hideOverlay()
                } else {
                    handleRemoteBackFullscreen()
                }
            }
            "center", "ok" -> {
                if (isOverlayVisible) {
                    val focused = currentFocus ?: binding.channelGuideOverlay.findFocus()
                    if (focused != null && focused !== binding.channelGuideOverlay) {
                        val chHolder = binding.rvChannels.findContainingViewHolder(focused)
                        if (chHolder != null) {
                            chHolder.itemView.performClick()
                            return
                        }
                        val catHolder = binding.rvCategories.findContainingViewHolder(focused)
                        if (catHolder != null) {
                            catHolder.itemView.performClick()
                            return
                        }
                        focused.performClick()
                    } else {
                        focusPlayingChannel()
                        binding.rvChannels.postDelayed({
                            val current = currentFocus ?: binding.rvChannels.findFocus()
                            val target = if (current != null) binding.rvChannels.findContainingViewHolder(current) else null
                            target?.itemView?.performClick() ?: current?.performClick()
                        }, 50)
                    }
                } else {
                    handleRemoteOkFullscreen()
                }
            }
            "up" -> {
                if (isOverlayVisible) {
                    navigateOverlay(View.FOCUS_UP)
                } else {
                    switchChannel(next = false)
                }
            }
            "down" -> {
                if (isOverlayVisible) {
                    navigateOverlay(View.FOCUS_DOWN)
                } else {
                    switchChannel(next = true)
                }
            }
            "left" -> {
                if (isOverlayVisible) {
                    navigateOverlay(View.FOCUS_LEFT)
                } else {
                    currentPlayingChannel?.let { showQuickChannelInfo(it) }
                }
            }
            "right" -> {
                if (isOverlayVisible) {
                    navigateOverlay(View.FOCUS_RIGHT)
                } else {
                    handleRemoteRightFullscreen()
                }
            }
        }
    }

    private fun navigateOverlay(direction: Int) {
        var focused = currentFocus ?: binding.channelGuideOverlay.findFocus()
        if (focused == null || focused === binding.channelGuideOverlay) {
            focusPlayingChannel()
            focused = currentFocus ?: binding.rvChannels.findFocus()
            if (focused == null) return
        }

        when (direction) {
            View.FOCUS_UP, View.FOCUS_DOWN -> {
                val isDown = (direction == View.FOCUS_DOWN)

                // 1. Phím Lên/Xuống khi đang trong danh sách kênh rvChannels
                val chHolder = binding.rvChannels.findContainingViewHolder(focused)
                if (chHolder != null) {
                    val currentPos = chHolder.adapterPosition
                    if (currentPos != RecyclerView.NO_POSITION) {
                        val targetPos = if (isDown) currentPos + 1 else currentPos - 1
                        if (targetPos in 0 until channelAdapter.itemCount) {
                            binding.rvChannels.scrollToPosition(targetPos)
                            binding.rvChannels.post {
                                val vh = binding.rvChannels.findViewHolderForAdapterPosition(targetPos)
                                if (vh != null) {
                                    vh.itemView.requestFocus()
                                } else {
                                    binding.rvChannels.layoutManager?.findViewByPosition(targetPos)?.requestFocus()
                                }
                            }
                            return
                        }
                    }
                }

                // 2. Phím Lên/Xuống khi đang trong danh mục rvCategories
                val catHolder = binding.rvCategories.findContainingViewHolder(focused)
                if (catHolder != null) {
                    val currentPos = catHolder.adapterPosition
                    if (currentPos != RecyclerView.NO_POSITION) {
                        val targetPos = if (isDown) currentPos + 1 else currentPos - 1
                        if (targetPos in 0 until categoryAdapter.itemCount) {
                            binding.rvCategories.scrollToPosition(targetPos)
                            binding.rvCategories.post {
                                val vh = binding.rvCategories.findViewHolderForAdapterPosition(targetPos)
                                if (vh != null) {
                                    vh.itemView.requestFocus()
                                } else {
                                    binding.rvCategories.layoutManager?.findViewByPosition(targetPos)?.requestFocus()
                                }
                            }
                            return
                        }
                    }
                }

                // 3. Phím Lên/Xuống khi đang trong EPG Timeline
                val epgHolder = binding.rvSpotlightEpg.findContainingViewHolder(focused)
                if (epgHolder != null) {
                    val currentPos = epgHolder.adapterPosition
                    if (currentPos != RecyclerView.NO_POSITION) {
                        val targetPos = if (isDown) currentPos + 1 else currentPos - 1
                        if (targetPos in 0 until epgTimelineAdapter.itemCount) {
                            binding.rvSpotlightEpg.scrollToPosition(targetPos)
                            binding.rvSpotlightEpg.post {
                                val vh = binding.rvSpotlightEpg.findViewHolderForAdapterPosition(targetPos)
                                if (vh != null) {
                                    vh.itemView.requestFocus()
                                } else {
                                    binding.rvSpotlightEpg.layoutManager?.findViewByPosition(targetPos)?.requestFocus()
                                }
                            }
                            return
                        }
                    }
                }

                // 4. Phím Lên/Xuống khi đang trong Bảng xếp hạng
                val standingHolder = binding.rvSpotlightStandings.findContainingViewHolder(focused)
                if (standingHolder != null) {
                    val currentPos = standingHolder.adapterPosition
                    if (currentPos != RecyclerView.NO_POSITION) {
                        val targetPos = if (isDown) currentPos + 1 else currentPos - 1
                        if (targetPos in 0 until standingsAdapter.itemCount) {
                            binding.rvSpotlightStandings.scrollToPosition(targetPos)
                            binding.rvSpotlightStandings.post {
                                val vh = binding.rvSpotlightStandings.findViewHolderForAdapterPosition(targetPos)
                                if (vh != null) {
                                    vh.itemView.requestFocus()
                                } else {
                                    binding.rvSpotlightStandings.layoutManager?.findViewByPosition(targetPos)?.requestFocus()
                                }
                            }
                            return
                        }
                    }
                }

                // 5. Dự phòng focusSearch mặc định
                val next = focused.focusSearch(direction)
                if (next != null && next != focused) {
                    next.requestFocus(direction)
                }
            }

            View.FOCUS_LEFT -> {
                // Từ Spotlight (EPG / Bảng xếp hạng) -> quay lại Danh sách kênh
                if (binding.layoutSpotlightCol.hasFocus() || isDescendantOf(focused, binding.layoutSpotlightCol)) {
                    focusFirstVisibleChannel()
                    return
                }

                // Từ rvChannels -> nhảy sang Danh mục tương ứng
                if (binding.rvChannels.findContainingViewHolder(focused) != null || binding.rvChannels.hasFocus()) {
                    val targetCat = findHorizontallyAlignedCategoryView(focused)
                    if (targetCat != null) {
                        targetCat.requestFocus()
                        return
                    } else {
                        val catPos = categoryAdapter.getSelectedPosition()
                        val vh = if (catPos != -1) binding.rvCategories.findViewHolderForAdapterPosition(catPos) else null
                        if (vh != null) {
                            vh.itemView.requestFocus()
                            return
                        } else {
                            binding.rvCategories.getChildAt(0)?.requestFocus()
                            return
                        }
                    }
                }

                // Từ rvCategories -> nhảy sang Sidebar menu
                if (binding.rvCategories.findContainingViewHolder(focused) != null || binding.rvCategories.hasFocus()) {
                    val targetNav = if (currentAppMode == AppMode.SPORTS) binding.navModeSports else binding.navModeTv
                    targetNav.requestFocus()
                    return
                }

                val next = focused.focusSearch(View.FOCUS_LEFT)
                if (next != null && next != focused) {
                    next.requestFocus(View.FOCUS_LEFT)
                }
            }

            View.FOCUS_RIGHT -> {
                // Từ Sidebar -> nhảy sang Danh mục đang chọn
                if (binding.layoutSidebar.hasFocus() || isDescendantOf(focused, binding.layoutSidebar)) {
                    val catPos = categoryAdapter.getSelectedPosition()
                    val target = if (catPos != -1) {
                        binding.rvCategories.findViewHolderForAdapterPosition(catPos)?.itemView
                    } else null
                    (target ?: binding.rvCategories.getChildAt(0))?.requestFocus()
                    return
                }

                // Từ rvCategories -> nhảy sang Kênh đang hiển thị
                if (binding.rvCategories.findContainingViewHolder(focused) != null || binding.rvCategories.hasFocus()) {
                    val targetChan = findHorizontallyAlignedChannelView(focused)
                    if (targetChan != null) {
                        targetChan.requestFocus()
                        return
                    } else {
                        focusFirstVisibleChannel()
                        return
                    }
                }

                // Từ rvChannels:
                // Ở chế độ TV: ấn sang phải để tương tác với EPG
                // Ở chế độ Thể thao: ấn sang phải để tương tác với Bảng xếp hạng
                if (binding.rvChannels.findContainingViewHolder(focused) != null || binding.rvChannels.hasFocus()) {
                    if (currentAppMode == AppMode.TV) {
                        if (focusSpotlightEpg()) {
                            return
                        }
                    } else if (currentAppMode == AppMode.SPORTS) {
                        if (focusSpotlightStandings()) {
                            return
                        }
                    }
                }

                // Nếu đang trong Spotlight thì giữ nguyên focus tại Spotlight
                if (binding.layoutSpotlightCol.hasFocus() || isDescendantOf(focused, binding.layoutSpotlightCol)) {
                    return
                }

                val next = focused.focusSearch(View.FOCUS_RIGHT)
                if (next != null && next != focused) {
                    next.requestFocus(View.FOCUS_RIGHT)
                }
            }
        }
    }

    private fun isDescendantOf(child: View?, parent: View): Boolean {
        var p: ViewParent? = child?.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private fun releaseExoPlayer() {
        try {
            binding.playerView.player = null
            player?.stop()
            player?.release()
            player = null
        } catch (e: Exception) {}
    }

    private fun initPlayer() {
        initExoPlayer()
        applyPlayerVisibility()
    }

    private fun applyPlayerVisibility() {
        binding.playerView.visibility = View.VISIBLE
    }

    private fun initExoPlayer() {
        // 1. Tối ưu bộ giải mã: Bật Deinterlace & Dự phòng Decoder kết hợp Workaround chống xé hình cho Mi Box 3c / Amlogic
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildVideoRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                val customVideoRenderer = object : MediaCodecVideoRenderer(
                    context,
                    codecAdapterFactory,
                    mediaCodecSelector,
                    allowedVideoJoiningTimeMs,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ) {
                    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean {
                        // Workaround chống xé hình (tearing) và màn hình đen cho Mi Box 3c, TX3 mini, Rockchip và Sony TV
                        val hw = Build.HARDWARE.lowercase()
                        val board = Build.BOARD.lowercase()
                        val dev = Build.DEVICE.lowercase()
                        val manu = Build.MANUFACTURER.lowercase()
                        if (hw.contains("amlogic") || board.contains("meson") ||
                            hw.contains("rockchip") || board.contains("rk3") ||
                            manu.contains("sony") || dev.contains("mibox") || dev.contains("once")) {
                            return true
                        }
                        return super.codecNeedsSetOutputSurfaceWorkaround(name)
                    }
                }
                out.add(customVideoRenderer)
            }
        }.apply {
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
                                currentMatchFallbackCount = 0
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
                        if (tryFallbackMatchStream("ExoPlayer ${error.errorCodeName}")) {
                            return
                        }
                        binding.layoutBuffering.visibility = View.GONE
                        binding.layoutQuickBuffering.visibility = View.GONE
                        binding.tvErrorMessage.visibility = View.VISIBLE
                        val mg = currentPlayingMatchGroup
                        if (mg != null && mg.sources.size > 1) {
                            binding.tvErrorDetail.text = "Lỗi kết nối luồng phát này.\nBấm ▶ (Phím Phải) để đổi nguồn khác (còn ${mg.sources.size - 1} nguồn)"
                        } else {
                            binding.tvErrorDetail.text = "Lỗi phát kênh: ${error.errorCodeName}\nBấm ▲ ▼ để đổi kênh khác"
                        }
                    }
                })
            }
    }

    private fun loadPlaylist(forceRefresh: Boolean, isStartup: Boolean = false) {
        val activeSource = prefs.getActivePlaylist()
        if (currentAppMode == AppMode.TV) {
            binding.tvActivePlaylistBadge.text = "Danh sách: ${activeSource.name}"
        }

        binding.layoutBuffering.visibility = View.VISIBLE
        binding.tvBufferingText.text = getString(R.string.loading_playlist)
        binding.tvErrorMessage.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val categories = playlistManager.loadPlaylist(
                    activeSource.id,
                    activeSource.url,
                    forceRefresh = forceRefresh,
                    includeSports = false
                )
                tvCategories.clear()
                tvCategories.addAll(categories)

                allChannels.clear()
                val allCat = categories.firstOrNull { it.name == "Tất cả" }
                if (allCat != null) {
                    allChannels.addAll(allCat.channels)
                } else if (categories.isNotEmpty()) {
                    allChannels.addAll(categories.flatMap { it.channels })
                }

                switchAppMode(AppMode.TV, focusCategories = false)

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
                    binding.playerView.post {
                        if (!isFinishing && !isDestroyed) {
                            playChannel(targetChannel)
                            // Tự động phát kênh mặc định ngay, ẩn giao diện cài đặt/danh mục để xem toàn màn hình
                            hideOverlay()
                        }
                    }
                } else {
                    showOverlay()
                }

                // Không tải ngầm trực tiếp thể thao lúc khởi động. Chỉ tải khi người dùng lần đầu chọn mục Trực tiếp Thể Thao.

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
            // Nếu danh sách trong RAM đang rỗng, nạp ngay từ cache đĩa (< 3ms)
            if (sportsCategoriesList.isEmpty()) {
                val cached = withContext(Dispatchers.IO) {
                    playlistManager.sportsLiveManager.getCachedSportsCategories()
                }
                if (cached.isNotEmpty()) {
                    applyUpdatedSportsCategories(cached)
                }
            }

            try {
                val sportsCategories = withContext(Dispatchers.IO) {
                    playlistManager.sportsLiveManager.getSportsCategories(forceRefresh = forceRefresh)
                }
                if (!isActive) return@launch
                if (sportsCategories.isNotEmpty()) {
                    applyUpdatedSportsCategories(sportsCategories)
                    val count = sportsCategories.firstOrNull()?.channels?.count { it.id != SportsLiveManager.ACTION_REFRESH_SPORTS } ?: 0
                    if (showNotification) {
                        showBottomNotification("⚽ Đã cập nhật xong! Tìm thấy $count trận đấu trực tiếp hôm nay.")
                    }
                } else if (showNotification && forceRefresh) {
                    showBottomNotification("Không tìm thấy thêm trận đấu mới nào.")
                }
            } catch (e: Exception) {
                // Giữ trải nghiệm phát kênh truyền hình liền mạch, không làm gián đoạn
            }
        }
    }

    private fun switchAppMode(mode: AppMode, focusCategories: Boolean = false) {
        currentAppMode = mode
        currentDisplayingLeague = null
        binding.navModeTv.isSelected = (mode == AppMode.TV)
        binding.navModeSports.isSelected = (mode == AppMode.SPORTS)
        binding.rvCategories.nextFocusLeftId = if (mode == AppMode.TV) R.id.navModeTv else R.id.navModeSports

        allCategories.clear()
        if (mode == AppMode.TV) {
            currentPlayingMatchGroup = null
            val activeSource = prefs.getActivePlaylist()
            binding.tvActivePlaylistBadge.text = "Danh sách: ${activeSource.name}"
            allCategories.addAll(tvCategories)
        } else {
            binding.tvActivePlaylistBadge.text = "⚽ Trực tiếp Thể Thao"
            if (sportsCategoriesList.isEmpty()) {
                val cached = playlistManager.sportsLiveManager.getCachedSportsCategories()
                if (cached.isNotEmpty()) {
                    sportsCategoriesList.addAll(cached)
                }
            }
            allCategories.addAll(sportsCategoriesList)
            // Lazy Loading: Chỉ kích hoạt tải mạng trực tiếp thể thao khi người dùng lần đầu chọn chế độ Thể Thao
            if (!hasLoadedSportsOnce) {
                hasLoadedSportsOnce = true
                val isFirstLoadEmpty = sportsCategoriesList.isEmpty()
                if (isFirstLoadEmpty) {
                    showBottomNotification("⚽ Đang tải danh sách trận đấu trực tiếp hôm nay...")
                }
                fetchSportsInBackground(forceRefresh = false, showNotification = isFirstLoadEmpty)
                startPeriodicSportsRefresh()
            }
        }

        categoryAdapter.submitList(allCategories.toList())

        // Xác định danh mục cần hiển thị
        val playing = currentPlayingChannel
        val targetCategory = if (mode == AppMode.SPORTS) {
            allCategories.firstOrNull { it.name.contains("LIVE EVENTS", ignoreCase = true) || it.name.contains("TRỰC TIẾP", ignoreCase = true) }
        } else if (playing != null) {
            allCategories.firstOrNull { cat -> cat.channels.any { it.streamUrl == playing.streamUrl } }
        } else null

        val catToSelect = targetCategory
            ?: allCategories.firstOrNull { it.name != "★ KÊNH YÊU THÍCH" }
            ?: allCategories.firstOrNull()

        if (catToSelect != null) {
            currentSelectedCategory = catToSelect
            categoryAdapter.setSelectedCategory(catToSelect)
            val channelToFocus = if (mode == AppMode.SPORTS && currentPlayingMatchGroup != null) {
                catToSelect.channels.firstOrNull { it.streamUrl == "match://${currentPlayingMatchGroup?.key}" }
                    ?: catToSelect.channels.firstOrNull { it.id != SportsLiveManager.ACTION_REFRESH_SPORTS }
                    ?: catToSelect.channels.firstOrNull()
            } else if (mode == AppMode.SPORTS) {
                catToSelect.channels.firstOrNull { it.id != SportsLiveManager.ACTION_REFRESH_SPORTS }
                    ?: catToSelect.channels.firstOrNull()
            } else if (catToSelect == targetCategory) {
                playing
            } else null
            displayCategoryChannels(catToSelect, focusChannel = channelToFocus)
        } else {
            currentCategoryChannels.clear()
            channelAdapter.submitList(emptyList())
            if (mode == AppMode.SPORTS) {
                binding.tvCategoryHeader.text = "⚽ Đang tải trận đấu trực tiếp..."
                binding.tvChannelCount.text = "Vui lòng chờ giây lát"
            } else {
                binding.tvCategoryHeader.text = if (mode == AppMode.TV) "Xem TV" else "Trực tiếp Thể thao"
                binding.tvChannelCount.text = getString(R.string.channels_count, 0)
            }
        }

        if (focusCategories) {
            binding.rvCategories.post {
                val catIndex = if (catToSelect != null) allCategories.indexOf(catToSelect).coerceAtLeast(0) else 0
                binding.rvCategories.scrollToPosition(catIndex)
                binding.rvCategories.findViewHolderForAdapterPosition(catIndex)?.itemView?.requestFocus()
                    ?: binding.rvCategories.requestFocus()
            }
        }
    }

    private fun findHorizontallyAlignedCategoryView(focusedChannelView: View): View? {
        if (binding.rvCategories.childCount == 0) return null

        val focusedLoc = IntArray(2)
        focusedChannelView.getLocationOnScreen(focusedLoc)
        val focusedCenterY = focusedLoc[1] + (focusedChannelView.height / 2)

        var closestChild: View? = null
        var minDiff = Int.MAX_VALUE

        for (i in 0 until binding.rvCategories.childCount) {
            val child = binding.rvCategories.getChildAt(i) ?: continue
            if (!child.isShown || !child.isFocusable) continue

            val childLoc = IntArray(2)
            child.getLocationOnScreen(childLoc)
            val childCenterY = childLoc[1] + (child.height / 2)

            val diff = Math.abs(childCenterY - focusedCenterY)
            if (diff < minDiff) {
                minDiff = diff
                closestChild = child
            }
        }

        return closestChild
    }

    private fun findHorizontallyAlignedChannelView(focusedCategoryView: View): View? {
        if (binding.rvChannels.childCount == 0) return null

        val focusedLoc = IntArray(2)
        focusedCategoryView.getLocationOnScreen(focusedLoc)
        val focusedCenterY = focusedLoc[1] + (focusedCategoryView.height / 2)

        var closestChild: View? = null
        var minDiff = Int.MAX_VALUE

        for (i in 0 until binding.rvChannels.childCount) {
            val child = binding.rvChannels.getChildAt(i) ?: continue
            if (!child.isShown || !child.isFocusable) continue

            val childLoc = IntArray(2)
            child.getLocationOnScreen(childLoc)
            val childCenterY = childLoc[1] + (child.height / 2)

            val diff = Math.abs(childCenterY - focusedCenterY)
            if (diff < minDiff) {
                minDiff = diff
                closestChild = child
            }
        }

        return closestChild
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
                            binding.rvChannels.layoutManager?.findViewByPosition(index)?.requestFocus() ?: run {
                                binding.rvChannels.postDelayed({
                                    binding.rvChannels.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                                        ?: binding.rvChannels.layoutManager?.findViewByPosition(index)?.requestFocus()
                                        ?: binding.rvChannels.getChildAt(0)?.requestFocus()
                                }, 60)
                            }
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
            binding.tvSpotlightLiveTag.visibility = View.GONE

            spotlightHandler.removeCallbacks(spotlightRunnable)
            pendingSpotlightChannel = channel
            if (immediate) {
                performHeavySpotlightUpdate(channel)
            } else {
                spotlightHandler.postDelayed(spotlightRunnable, 120L)
            }
            return
        }

        if (channel.streamUrl.startsWith("match://")) {
            currentFocusedChannel = channel
            val matchKey = channel.streamUrl.removePrefix("match://")
            val matchGroup = playlistManager.sportsLiveManager.getMatch(matchKey)
            // Yêu cầu 5: Title trận đấu phía trên bảng xếp hạng không ghi giờ thi đấu, chỉ cần ghi tên 2 đội
            val titleText = if (matchGroup != null && matchGroup.awayTeam.isNotBlank()) {
                "${matchGroup.homeTeam} vs ${matchGroup.awayTeam}"
            } else if (matchGroup != null) {
                matchGroup.homeTeam
            } else {
                channel.name.replace(Regex("""^(\[[^\]]+\]|⚽|\s)+"""), "")
                    .replace(Regex("""\s*•.*$"""), "")
                    .trim()
            }
            binding.tvSpotlightName.text = titleText
            binding.tvSpotlightResolution.text = if (matchGroup != null) "${matchGroup.sources.size} NGUỒN" else "TRẬN ĐẤU"
            binding.tvSpotlightCategory.text = matchGroup?.leagueName ?: "Trực Tiếp Bóng Đá"
            binding.tvSpotlightStatus.visibility = View.GONE

            // Yêu cầu 6: Chỉ hiện nhãn TRỰC TIẾP phía trên logo khi trận đấu đang diễn ra
            val isOngoing = if (matchGroup != null) playlistManager.sportsLiveManager.isMatchGroupOngoing(matchGroup) else playlistManager.sportsLiveManager.isMatchOngoing(channel)
            binding.tvSpotlightLiveTag.visibility = if (isOngoing) View.VISIBLE else View.GONE

            spotlightHandler.removeCallbacks(spotlightRunnable)
            pendingSpotlightChannel = channel
            if (immediate) {
                performHeavySpotlightUpdate(channel)
            } else {
                spotlightHandler.postDelayed(spotlightRunnable, 120L)
            }
            return
        }

        currentFocusedChannel = channel
        val isSportsMode = (currentAppMode == AppMode.SPORTS) || isSportsLiveMatch(channel)
        if (isSportsMode) {
            val mg = playlistManager.sportsLiveManager.findMatchForChannel(channel)
            if (mg != null) {
                binding.tvSpotlightName.text = if (mg.awayTeam.isNotBlank()) "${mg.homeTeam} vs ${mg.awayTeam}" else mg.homeTeam
                binding.tvSpotlightCategory.text = mg.leagueName
                binding.tvSpotlightLiveTag.visibility = if (playlistManager.sportsLiveManager.isMatchGroupOngoing(mg)) View.VISIBLE else View.GONE
            } else {
                val (home, away, _) = playlistManager.sportsLiveManager.extractTeamsAndLeague(channel.name)
                if (home.isNotBlank() && away.isNotBlank()) {
                    binding.tvSpotlightName.text = "$home vs $away"
                } else {
                    binding.tvSpotlightName.text = channel.name.replace(Regex("""^(\[[^\]]+\]|⚽|\s)+"""), "")
                        .replace(Regex("""\s*•.*$"""), "")
                        .trim()
                }
                val isOngoing = playlistManager.sportsLiveManager.isMatchOngoing(channel)
                binding.tvSpotlightLiveTag.visibility = if (isOngoing) View.VISIBLE else View.GONE
                binding.tvSpotlightCategory.text = currentSelectedCategory?.name ?: "Trực Tiếp Thể Thao"
            }
        } else {
            binding.tvSpotlightName.text = channel.name
            binding.tvSpotlightLiveTag.visibility = View.GONE
            binding.tvSpotlightCategory.text = currentSelectedCategory?.name ?: "IPTV"
        }

        // Độ phân giải hiển thị: Ưu tiên FHD 1080 (hoặc 4K), các kênh HD tại Việt Nam là 1080i/1080p
        val upperName = channel.name.uppercase()
        binding.tvSpotlightResolution.text = when {
            upperName.contains("4K") -> "4K UHD"
            upperName.contains("720") -> "HD 720p"
            upperName.contains("FHD") || upperName.contains("1080") || upperName.contains("HD") -> "FHD 1080"
            else -> "FHD 1080"
        }

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
            binding.layoutEpgTimeline.visibility = View.VISIBLE
            binding.layoutLeagueStandings.visibility = View.GONE
            binding.tvRightPanelTitle.text = "LỊCH THI ĐẤU THỂ THAO"
            binding.rvSpotlightEpg.visibility = View.GONE
            binding.tvEpgEmpty.visibility = View.VISIBLE
            binding.tvEpgEmpty.text = "Nhấn OK trên điều khiển để tải lại trận đấu"
            return
        }

        val isSportsMode = (currentAppMode == AppMode.SPORTS) || isSportsLiveMatch(channel)
        if (channel.streamUrl.startsWith("match://") || isSportsMode) {
            val matchKey = if (channel.streamUrl.startsWith("match://")) channel.streamUrl.removePrefix("match://") else ""
            val matchGroup = if (matchKey.isNotBlank()) playlistManager.sportsLiveManager.getMatch(matchKey)
                             else playlistManager.sportsLiveManager.findMatchForChannel(channel)
            val homeTeam = matchGroup?.homeTeam ?: ""
            val awayTeam = matchGroup?.awayTeam ?: ""
            val targetLeague = matchGroup?.league ?: playlistManager.sportsLiveManager.extractTeamsAndLeague(channel.name).third

            val leagueName = SportsLiveManager.LEAGUE_LABELS[targetLeague] ?: "Giải đấu"
            val leagueLogoUri = playlistManager.sportsLiveManager.getLeagueLogoUri(targetLeague)

            Glide.with(this)
                .load(leagueLogoUri)
                .override(160, 100)
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_nav_sports)
                .error(R.drawable.ic_nav_sports)
                .into(binding.ivSpotlightLogo)

            binding.tvSpotlightStreamInfo.text = if (channel.streamUrl.startsWith("match://")) {
                "Trận đấu: ${channel.name}\nBấm phím OK để xem danh sách nguồn phát"
            } else {
                "Trận đấu: ${channel.name}\nBấm phím OK để xem trực tiếp"
            }

            if (targetLeague in SportsLiveManager.PRIORITY_LEAGUES || targetLeague == "NBA") {
                binding.layoutEpgTimeline.visibility = View.GONE
                binding.layoutLeagueStandings.visibility = View.VISIBLE
                binding.tvRightPanelTitle.text = "BẢNG XẾP HẠNG"
                binding.tvEpgSourceTag.visibility = View.GONE
// binding.rvChannels.nextFocusRightId = R.id.rvSpotlightStandings

                if (currentDisplayingLeague != targetLeague) {
                    currentDisplayingLeague = targetLeague
                    binding.rvSpotlightStandings.visibility = View.GONE
                    binding.tvStandingsEmpty.visibility = View.VISIBLE
                    binding.tvStandingsEmpty.text = "Đang tải bảng xếp hạng $leagueName..."

                    lifecycleScope.launch(Dispatchers.Main) {
                        val standings = standingsManager.getStandings(targetLeague)
                        if (currentFocusedChannel?.streamUrl == channel.streamUrl) {
                            if (standings.isNotEmpty()) {
                                binding.tvStandingsEmpty.visibility = View.GONE
                                binding.rvSpotlightStandings.visibility = View.VISIBLE
                                standingsAdapter.submitList(standings, homeTeam, awayTeam)
                                val highlightIdx = standingsAdapter.getHighlightedIndex()
                                if (highlightIdx != -1) {
                                    binding.rvSpotlightStandings.scrollToPosition(highlightIdx.coerceAtLeast(0))
                                }
                            } else {
                                binding.rvSpotlightStandings.visibility = View.GONE
                                binding.tvStandingsEmpty.visibility = View.VISIBLE
                                binding.tvStandingsEmpty.text = "Chưa có bảng xếp hạng cho giải đấu này"
                            }
                        }
                    }
                } else {
                    // CÙNG GIẢI ĐẤU: Giữ nguyên danh sách, chỉ cập nhật highlight 2 đội và cuộn nhanh!
                    standingsAdapter.updateHighlights(homeTeam, awayTeam)
                    val highlightIdx = standingsAdapter.getHighlightedIndex()
                    if (highlightIdx != -1) {
                        binding.rvSpotlightStandings.scrollToPosition(highlightIdx.coerceAtLeast(0))
                    }
                }
                return
            } else if (channel.streamUrl.startsWith("match://")) {
                currentDisplayingLeague = null
                binding.layoutEpgTimeline.visibility = View.GONE
                binding.layoutLeagueStandings.visibility = View.VISIBLE
                binding.tvRightPanelTitle.text = "BẢNG XẾP HẠNG"
                binding.tvEpgSourceTag.visibility = View.GONE
                binding.rvSpotlightStandings.visibility = View.GONE
                binding.tvStandingsEmpty.visibility = View.VISIBLE
                binding.tvStandingsEmpty.text = "Nhấn OK trên điều khiển để chọn nguồn phát trực tiếp"
// binding.rvChannels.nextFocusRightId = R.id.rvSpotlightStandings
                return
            }
        }
        currentDisplayingLeague = null

        // TV Channel mode:
// binding.rvChannels.nextFocusRightId = R.id.rvSpotlightEpg
        binding.layoutLeagueStandings.visibility = View.GONE
        binding.layoutEpgTimeline.visibility = View.VISIBLE
        binding.tvRightPanelTitle.text = "LỊCH PHÁT SÓNG (EPG)"
        binding.tvEpgSourceTag.text = "lichphatsong.io.vn"
        binding.tvEpgSourceTag.visibility = View.VISIBLE 

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
        val udpxy = prefs.udpxyServer.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val protocol = when {
            channel.streamUrl.contains("/truc-tiep/") || channel.streamUrl.startsWith("streamfree://") -> "Web Live Stream (HLS)"
            channel.streamUrl.contains("/udp/", ignoreCase = true) || (udpxy.isNotBlank() && channel.streamUrl.startsWith("udp", ignoreCase = true)) -> "udpxy ($udpxy)"
            channel.streamUrl.contains("/rtp/", ignoreCase = true) || (udpxy.isNotBlank() && channel.streamUrl.startsWith("rtp", ignoreCase = true)) -> "udpxy ($udpxy)"
            channel.streamUrl.startsWith("udp", ignoreCase = true) -> "UDP Multicast"
            channel.streamUrl.startsWith("rtp", ignoreCase = true) -> "RTP Multicast"
            channel.streamUrl.contains(".m3u8", ignoreCase = true) -> "HLS / CDN"
            else -> "HTTP Stream"
        }
        val engineName = "ExoPlayer (Hardware Surface)"
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

        private fun tryFallbackMatchStream(errorMsg: String): Boolean {
        val playing = currentPlayingChannel ?: return false
        val isSports = (currentAppMode == AppMode.SPORTS)
        if (!isSports) return false

        val mg = currentPlayingMatchGroup ?: playlistManager.sportsLiveManager.findMatchForChannel(playing) ?: return false
        if (mg.sources.size <= 1) return false

        if (currentMatchFallbackCount >= mg.sources.size) {
            return false
        }

        val curIdx = mg.sources.indexOfFirst { it.streamUrl == playing.streamUrl || it.id == playing.id }
        val nextIdx = if (curIdx >= 0) (curIdx + 1) % mg.sources.size else 0
        val nextSource = mg.sources[nextIdx]

        currentMatchFallbackCount++
        showBottomNotification("⚠️ Luồng gặp sự cố. Tự chuyển (${nextIdx + 1}/${mg.sources.size}): ${nextSource.name}")

        binding.root.postDelayed({
            playChannel(nextSource, isFallback = true)
        }, 600)
        return true
    }

    private fun playChannel(channel: Channel, isFallback: Boolean = false) {
        if (!isFallback) {
            currentMatchFallbackCount = 0
        }
        if (channel.id == SportsLiveManager.ACTION_REFRESH_SPORTS || channel.streamUrl == "action://refresh_sports") {
            refreshSportsMatchesManually()
            return
        }

        // Nếu là kênh ảo của trận đấu (match://), tự động phát nguồn đầu tiên thay vì gửi link ảo cho player
        val targetChannel = if (channel.streamUrl.startsWith("match://")) {
            val key = channel.streamUrl.removePrefix("match://")
            val mg = playlistManager.sportsLiveManager.getMatch(key)
            if (mg != null && mg.sources.isNotEmpty()) {
                currentPlayingMatchGroup = mg
                mg.sources.first()
            } else {
                showBottomNotification("Chưa có nguồn phát trực tiếp cho trận đấu này")
                return
            }
        } else {
            channel
        }

        currentPlayingChannel = targetChannel
        prefs.lastChannelUrl = targetChannel.streamUrl
        if (currentAppMode == AppMode.TV) {
            currentPlayingMatchGroup = null
        } else if (currentPlayingMatchGroup == null || currentPlayingMatchGroup?.sources?.none { it.streamUrl == targetChannel.streamUrl || it.id == targetChannel.id } == true) {
            currentPlayingMatchGroup = playlistManager.sportsLiveManager.findMatchForChannel(targetChannel)
        }

        currentMeasuredBitrateMbps = 0f
        currentVideoWidth = 0
        currentVideoHeight = 0
        currentVideoFps = 0
        lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
        lastRxTime = System.currentTimeMillis()
        startTelemetryUpdates()

        channelAdapter.setPlayingChannel(targetChannel, currentPlayingMatchGroup?.key)
        updateSpotlight(targetChannel)

        showQuickChannelInfo(targetChannel)

        binding.tvErrorMessage.visibility = View.GONE
        binding.tvQuickBufferingText.text = "Đang tải luồng..."
        binding.layoutQuickBuffering.visibility = View.VISIBLE

        // Nếu là trận đấu thể thao trực tiếp từ web (Xôi Lạc, Cà Khịa, StreamFree), giải mã link m3u8 động
        if (targetChannel.streamUrl.contains("/truc-tiep/") || targetChannel.streamUrl.startsWith("streamfree://")) {
            binding.tvQuickBufferingText.text = "Đang kết nối luồng trực tiếp..."
            binding.layoutQuickBuffering.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.Main) {
                val resolved = withContext(Dispatchers.IO) {
                    playlistManager.sportsLiveManager.resolveLiveStreamUrl(targetChannel.streamUrl)
                }
                if (currentPlayingChannel?.id == targetChannel.id) {
                    val streamUriString = normalizeStreamUrl(resolved)
                    playWithExoPlayer(streamUriString)
                }
            }
            return
        }

        val streamUriString = normalizeStreamUrl(targetChannel.streamUrl)
        playWithExoPlayer(streamUriString)
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

    private fun normalizeStreamUrl(rawUrl: String): String {
        val (cleanUrl, headers) = parseStreamUrlAndHeaders(rawUrl)
        val trimmed = cleanUrl.trim()
        val udpxy = prefs.udpxyServer.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val effectiveUdpxy = if (udpxy.isNotBlank()) udpxy else PreferencesHelper.DEFAULT_UDPXY_SERVER

        // Nếu là luồng multicast (udp://, rtp://), tự động chuyển đổi sang HTTP proxy qua máy chủ udpxy
        val effectiveUrl = when {
            trimmed.startsWith("udp://@", ignoreCase = true) -> "http://$effectiveUdpxy/udp/" + trimmed.substring(7)
            trimmed.startsWith("udp://", ignoreCase = true) -> "http://$effectiveUdpxy/udp/" + trimmed.substring(6)
            trimmed.startsWith("rtp://@", ignoreCase = true) -> "http://$effectiveUdpxy/rtp/" + trimmed.substring(7)
            trimmed.startsWith("rtp://", ignoreCase = true) -> "http://$effectiveUdpxy/rtp/" + trimmed.substring(6)
            trimmed.matches(Regex("(?i)^http://[^/]+/(udp|rtp)/.*")) -> {
                trimmed.replaceFirst(Regex("(?i)^http://[^/]+/(udp|rtp)/"), "http://$effectiveUdpxy/$1/")
            }
            else -> trimmed
        }

        // ExoPlayer: Chuẩn Java URI không chấp nhận '@' trong host name
        val finalClean = when {
            effectiveUrl.startsWith("udp://@", ignoreCase = true) -> "udp://" + effectiveUrl.substring(7)
            effectiveUrl.startsWith("rtp://@", ignoreCase = true) -> "udp://" + effectiveUrl.substring(7)
            effectiveUrl.startsWith("rtp://", ignoreCase = true) -> "udp://" + effectiveUrl.substring(6)
            else -> effectiveUrl
        }

        return if (headers.isNotEmpty()) {
            val hStr = headers.entries.joinToString("&") { "${it.key}=${it.value}" }
            "$finalClean|$hStr"
        } else {
            finalClean
        }
    }

    private fun startTelemetryUpdates() {
        telemetryHandler.removeCallbacks(telemetryRunnable)
        telemetryHandler.postDelayed(telemetryRunnable, 800L)
    }

    private fun stopTelemetryUpdates() {
        telemetryHandler.removeCallbacks(telemetryRunnable)
    }

    private var lastCpuTotalTime = 0L
    private var lastCpuIdleTime = 0L
    private var currentCpuUsagePercent = 0

    private fun sampleSystemStats(): Triple<Int, Int, String> {
        // 1. RAM Usage
        var ramPct = 0
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            if (memInfo.totalMem > 0L) {
                ramPct = (((memInfo.totalMem - memInfo.availMem) * 100L) / memInfo.totalMem).toInt()
            }
        } catch (_: Exception) {}

        // 2. CPU Usage
        var cpuPct = currentCpuUsagePercent
        try {
            val statFile = File("/proc/stat")
            if (statFile.exists() && statFile.canRead()) {
                val line = statFile.bufferedReader().use { it.readLine() }
                if (line != null && line.startsWith("cpu ")) {
                    val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (parts.size >= 5) {
                        val user = parts[1].toLongOrNull() ?: 0L
                        val nice = parts[2].toLongOrNull() ?: 0L
                        val system = parts[3].toLongOrNull() ?: 0L
                        val idle = parts[4].toLongOrNull() ?: 0L
                        val iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L
                        val irq = if (parts.size > 6) parts[6].toLongOrNull() ?: 0L else 0L
                        val softirq = if (parts.size > 7) parts[7].toLongOrNull() ?: 0L else 0L

                        val total = user + nice + system + idle + iowait + irq + softirq
                        val idleTotal = idle + iowait

                        if (lastCpuTotalTime > 0L && total > lastCpuTotalTime) {
                            val diffTotal = total - lastCpuTotalTime
                            val diffIdle = idleTotal - lastCpuIdleTime
                            if (diffTotal > 0L) {
                                cpuPct = (100f * (diffTotal - diffIdle) / diffTotal).toInt().coerceIn(0, 100)
                                currentCpuUsagePercent = cpuPct
                            }
                        }
                        lastCpuTotalTime = total
                        lastCpuIdleTime = idleTotal
                    }
                }
            } else {
                val loadLine = File("/proc/loadavg").bufferedReader().use { it.readLine() }
                val load1 = loadLine.split(" ").firstOrNull()?.toFloatOrNull() ?: 0f
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                cpuPct = ((load1 / cores) * 100f).toInt().coerceIn(5, 95)
                currentCpuUsagePercent = cpuPct
            }
        } catch (_: Exception) {
            if (cpuPct <= 0) cpuPct = 12
        }

        // 3. GPU Usage
        var gpuStr = if (currentPlayingChannel != null) "HW" else "--%"
        val gpuPaths = listOf(
            "/sys/class/mpgpu/utilization",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/devices/platform/mali.0/utilization",
            "/sys/devices/platform/mali/utilization",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/devices/platform/pvrsrvkm.0/utilization"
        )
        for (p in gpuPaths) {
            try {
                val f = File(p)
                if (f.exists() && f.canRead()) {
                    val raw = f.bufferedReader().use { it.readLine() }?.trim() ?: ""
                    val num = raw.toIntOrNull()
                    if (num != null) {
                        val pct = if (num > 100) (num * 100 / 255) else num
                        gpuStr = "${pct.coerceIn(0, 100)}%"
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        return Triple(cpuPct, ramPct, gpuStr)
    }

    private fun updateSystemStatsBadge() {
        if (binding.channelGuideOverlay.visibility != View.VISIBLE) return
        val (cpu, ram, gpu) = sampleSystemStats()
        binding.tvSystemStatsBadge.text = "⚡ CPU: $cpu%  RAM: $ram%  GPU: $gpu"
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

        // 2. Dự phòng: Kiểm tra định dạng ExoPlayer
        if (sampledMbps == 0f) {
            val vf = player?.videoFormat
            if (vf != null && vf.bitrate > 0) {
                val exoMbps = vf.bitrate / 1_000_000f
                if (exoMbps in 0.1f..150.0f) {
                    sampledMbps = exoMbps
                }
            }
        }

        // 3. Cập nhật bitrate với bộ lọc làm mượt (Exponential Moving Average)
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
        val engineName = "ExoPlayer (Hardware Surface)"
        binding.tvSpotlightStreamInfo.text = "$resDim • $bitrateStr • $resBadge | Âm thanh: $audioDetails | $engineName"
    }

    private fun playWithExoPlayer(streamUriString: String) {
        try {
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

    private fun formatSportsOsdLine(channel: Channel): String {
        val raw = channel.name

        // 1. Tên giải đấu
        val mg = currentPlayingMatchGroup ?: playlistManager.sportsLiveManager.findMatchForChannel(channel)
        var league = mg?.leagueName?.ifBlank { mg.league } ?: ""
        if (league.isBlank()) {
            val lgMatch = Regex("""\b(Champions League|Cúp C1|EPL|Ngoại Hạng Anh|La Liga|Serie A|Bundesliga|Ligue 1|V-League|VLeague|Shopee Cup|AFC|NBA|F1|Tennis)\b""", RegexOption.IGNORE_CASE).find(raw)
            if (lgMatch != null) {
                league = lgMatch.value
            }
        }
        league = league.replace(Regex("""^[^\w\s\u00C0-\u1EF9]+"""), "").trim()
        if (league.equals("TRỰC TIẾP", ignoreCase = true)) league = ""

        // 2. Tên BLV
        val blvMatch = Regex("""\((?:BLV\s+)?([^\)]+)\)""", RegexOption.IGNORE_CASE).find(raw)
        var blv = blvMatch?.groupValues?.get(1)?.trim()
            ?.replace(Regex("""(?i)\b(hls|fhd|hd|sd|geo)\b"""), "")?.trim() ?: ""
        val isNhaDai = blv.equals("Nhà đài", ignoreCase = true) || raw.contains("Nhà đài", ignoreCase = true) || channel.groupTitle.contains("Nhà đài", ignoreCase = true)
        if (isNhaDai) {
            blv = ""
        } else if (blv.isNotBlank() && !blv.startsWith("BLV", ignoreCase = true)) {
            blv = "BLV $blv"
        }

        // 3. Tên Nguồn
        var sourceName = ""
        val sourceList = listOf("Chuối Chiên", "Gà Vàng", "Xôi Lạc", "Cà Khịa", "Bia Ôm", "S8 TV", "Cola TV", "Phá Làng", "Vua Sân Cỏ", "Khô Gà", "Giờ Vàng", "Sao Kê", "Xôi Gấc", "Xôi Chè")
        for (sm in sourceList) {
            if (raw.contains(sm, ignoreCase = true) || channel.groupTitle.contains(sm, ignoreCase = true)) {
                sourceName = sm
                break
            }
        }
        if (isNhaDai) {
            sourceName = "Nguồn Nhà Đài"
        } else if (sourceName.isBlank()) {
            val dashMatch = Regex("""-\s*([^\(\[\-]+)$""").find(raw)
            if (dashMatch != null && dashMatch.groupValues[1].isNotBlank()) {
                sourceName = dashMatch.groupValues[1].trim()
            } else if (channel.groupTitle.isNotBlank() && !channel.groupTitle.contains("LIVE", ignoreCase = true)) {
                sourceName = channel.groupTitle
            }
        }

        val blvSourcePart = when {
            blv.isNotBlank() && sourceName.isNotBlank() -> "$blv - $sourceName"
            blv.isNotBlank() -> blv
            sourceName.isNotBlank() -> sourceName
            else -> ""
        }

        return when {
            league.isNotBlank() && blvSourcePart.isNotBlank() -> "$league • $blvSourcePart"
            league.isNotBlank() -> league
            blvSourcePart.isNotBlank() -> blvSourcePart
            else -> "Trực tiếp Thể Thao"
        }
    }

    private fun showQuickChannelInfo(channel: Channel) {
        // Ẩn thông báo cạnh dưới nếu đang mở để tránh hai bảng đè lên nhau
        bottomNotificationHandler.removeCallbacks(hideBottomNotificationRunnable)
        binding.layoutBottomNotification.visibility = View.GONE

        binding.tvQuickNumber.text = String.format("%02d", channel.number)
        binding.tvQuickName.text = channel.name

        // Lấy tên chương trình EPG đang phát thời gian thực (hiển thị xong rồi tự tắt đi)
        val isSports = isSportsLiveMatch(channel) || (currentAppMode == AppMode.SPORTS) || channel.streamUrl.startsWith("match://")
        if (isSports) {
            val sportsLine = formatSportsOsdLine(channel)
            binding.tvQuickProgramTitle.text = "● Đang phát: $sportsLine"
            binding.tvQuickProgramTitle.visibility = View.VISIBLE
        } else {
            val currentProg = epgManager.getCurrentProgram(channel.id, channel.name)
            if (currentProg != null) {
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val startStr = timeFormat.format(Date(currentProg.startTime))
                val endStr = timeFormat.format(Date(currentProg.endTime))
                binding.tvQuickProgramTitle.text = "● Đang phát: ${currentProg.title} ($startStr - $endStr)"
            } else {
                binding.tvQuickProgramTitle.text = "● Đang phát: Chương trình trực tiếp"
            }
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

        // Dòng 4: Hướng dẫn nhấn sang phải để chọn nguồn phát (yêu cầu 2)
        binding.tvQuickHint.text = if (isSports) {
            "▶ Nhấn [Sang Phải] để chọn nguồn phát trực tiếp"
        } else {
            "▶ Nhấn [Sang Phải] để chọn danh sách phát"
        }
        binding.tvQuickHint.visibility = View.VISIBLE

        updateDynamicTelemetry()
        startTelemetryUpdates()

        binding.quickChannelInfo.visibility = View.VISIBLE

        hideHandler.removeCallbacks(hideQuickBannerRunnable)
        hideHandler.postDelayed(hideQuickBannerRunnable, 10000L)
    }

    private fun showChannelActionDialog(channel: Channel) {
        if (isFinishing || isDestroyed) return
        resetOverlayTimer()
        val dialog = ChannelActionDialog(this, channel) {
            refreshChannelsState()
        }
        activeCustomDialog = dialog
        dialog.setOnDismissListener {
            activeCustomDialog = null
            backPressedTime = 0L
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

        tvCategories.clear()
        tvCategories.addAll(playlistManager.groupChannels(allChannels))

        if (currentAppMode == AppMode.TV) {
            allCategories.clear()
            allCategories.addAll(tvCategories)
            categoryAdapter.submitList(allCategories.toList())

            val activeCat = currentSelectedCategory?.let { sel ->
                allCategories.firstOrNull { it.name == sel.name }
            } ?: allCategories.firstOrNull()

            if (activeCat != null) {
                currentSelectedCategory = activeCat
                categoryAdapter.setSelectedCategory(activeCat)
                displayCategoryChannels(activeCat)
            }
        }
    }

    private fun switchChannel(next: Boolean) {
        // Lấy danh sách kênh trong danh mục/list hiện hành (loại bỏ các nút chức năng như nút refresh thể thao)
        val activeChannels = (if (currentCategoryChannels.any { it.streamUrl == currentPlayingChannel?.streamUrl }) {
            currentCategoryChannels
        } else {
            allCategories.firstOrNull { cat ->
                cat.name != "★ KÊNH YÊU THÍCH" && cat.channels.any { it.streamUrl == currentPlayingChannel?.streamUrl }
            }?.channels ?: (if (currentAppMode == AppMode.TV) allChannels else sportsCategoriesList.flatMap { it.channels })
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            val now = System.currentTimeMillis()
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    backKeyDownTimestamp = now
                    event.startTracking()
                } else if (event.repeatCount >= 1 && (now - backKeyDownTimestamp >= 500L)) {
                    // Ấn giữ BACK để thoát luôn
                    pendingSingleBackRunnable?.let { hideHandler.removeCallbacks(it) }
                    pendingSingleBackRunnable = null
                    finish()
                    return true
                }

                // 1. Nếu đang có thông báo xác nhận thoát app đang chờ bấm lần 3:
                if (now < exitPromptActiveUntil) {
                    pendingSingleBackRunnable?.let { hideHandler.removeCallbacks(it) }
                    pendingSingleBackRunnable = null
                    finish()
                    return true
                }

                // 2. Yêu cầu 4: Nếu đang có pendingSingleBackRunnable (bấm lần 2 trong vòng 280ms) -> DOUBLE BACK!
                // TUYỆT ĐỐI KHÔNG mở menu danh sách kênh!
                if (pendingSingleBackRunnable != null) {
                    pendingSingleBackRunnable?.let { hideHandler.removeCallbacks(it) }
                    pendingSingleBackRunnable = null
                    exitPromptActiveUntil = now + 3000L
                    showBottomNotification("⚠️ Bấm thêm 1 lần QUAY LẠI nữa để thoát ứng dụng")
                    backHandledOnDown = true
                    return true
                }

                backHandledOnDown = true
                val isSettingsShowing = (activeSettingsDialog?.isShowing == true)
                if (isSettingsShowing) {
                    activeSettingsDialog?.dismiss()
                    backPressedTime = 0L
                    return true
                }

                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    // Đang ở trong menu: Bấm BACK để đóng menu
                    return handleBackInOverlay()
                } else {
                    // Đang xem toàn màn hình: Lên lịch mở menu sau 280ms để kiểm tra xem có double BACK hay không
                    val runnable = Runnable {
                        pendingSingleBackRunnable = null
                        handleRemoteBackFullscreen()
                    }
                    pendingSingleBackRunnable = runnable
                    hideHandler.postDelayed(runnable, 280L)
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (backHandledOnDown) {
                    backHandledOnDown = false
                    return true
                }
            }
        }

        // Bất kỳ phím nào khác được bấm sẽ hủy chờ phím BACK đơn
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            pendingSingleBackRunnable?.let { hideHandler.removeCallbacks(it) }
            pendingSingleBackRunnable = null
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            val isOverlayVisible = (binding.channelGuideOverlay.visibility == View.VISIBLE)
            val isSettingsShowing = (activeSettingsDialog?.isShowing == true)

            // Phím cài đặt / menu luôn hoạt động
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_HELP -> {
                    showSettingsDialog()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (!isSettingsShowing) {
                        switchChannel(next = true)
                        resetOverlayTimer()
                        return true
                    }
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (!isSettingsShowing) {
                        switchChannel(next = false)
                        resetOverlayTimer()
                        return true
                    }
                }
            }

            // Khi đang xem toàn màn hình (Overlay KHÔNG hiển thị):
            // Bắt trực tiếp mọi phím D-pad trên điều khiển
            if (!isOverlayVisible && !isSettingsShowing) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        handleRemoteOkFullscreen()
                        okHandledOnDown = true
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        switchChannel(next = false)
                        upDownHandledOnDown = true
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        switchChannel(next = true)
                        upDownHandledOnDown = true
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        // Nhấn Dpad Trái khi đang xem toàn màn hình: Mở lại thanh tiêu đề OSD góc trái bên dưới
                        currentPlayingChannel?.let { showQuickChannelInfo(it) }
                        leftHandledOnDown = true
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        handleRemoteRightFullscreen()
                        rightHandledOnDown = true
                        return true
                    }
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (okHandledOnDown) {
                        okHandledOnDown = false
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (rightHandledOnDown) {
                        rightHandledOnDown = false
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (leftHandledOnDown) {
                        leftHandledOnDown = false
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (upDownHandledOnDown) {
                        upDownHandledOnDown = false
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
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
                    handleRemoteOkFullscreen()
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
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    if (binding.layoutSpotlightCol.hasFocus() || binding.rvSpotlightEpg.hasFocus() || binding.rvSpotlightStandings.hasFocus()) {
                        focusFirstVisibleChannel()
                        return true
                    }
                    if (binding.rvChannels.hasFocus()) {
                        val focused = currentFocus ?: binding.rvChannels.findFocus()
                        if (focused != null) {
                            val targetCat = findHorizontallyAlignedCategoryView(focused)
                            if (targetCat != null) {
                                targetCat.requestFocus()
                                return true
                            }
                        }
                    }
                } else {
                    currentPlayingChannel?.let { showQuickChannelInfo(it) }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    if (binding.rvCategories.hasFocus()) {
                        val focused = currentFocus ?: binding.rvCategories.findFocus()
                        if (focused != null) {
                            val targetChan = findHorizontallyAlignedChannelView(focused)
                            if (targetChan != null) {
                                targetChan.requestFocus()
                                return true
                            }
                        }
                    } else if (binding.rvChannels.hasFocus() || (currentFocus?.let { binding.rvChannels.findContainingViewHolder(it) } != null)) {
                        if (currentAppMode == AppMode.TV) {
                            focusSpotlightEpg()
                            return true
                        } else if (currentAppMode == AppMode.SPORTS) {
                            focusSpotlightStandings()
                            return true
                        }
                    } else if (binding.layoutSpotlightCol.hasFocus() || isDescendantOf(currentFocus, binding.layoutSpotlightCol)) {
                        return true
                    }
                } else {
                    handleRemoteRightFullscreen()
                    return true
                }
            }
            KeyEvent.KEYCODE_M,
            KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_HELP -> {
                showSettingsDialog()
                return true
            }
            KeyEvent.KEYCODE_SPACE -> {
                toggleOverlay()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    return handleBackInOverlay()
                } else {
                    handleRemoteBackFullscreen()
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun handleRemoteOkFullscreen() {
        val playing = currentPlayingChannel
        val isSports = (currentAppMode == AppMode.SPORTS)
        if (isSports) {
            // Mục Thể Thao: Nhấn OK mở LIVE EVENTS với Highlight trận đấu đó
            if (currentAppMode != AppMode.SPORTS) {
                switchAppMode(AppMode.SPORTS, focusCategories = false)
            }
            val liveCat = allCategories.firstOrNull { it.name.contains("LIVE EVENTS", ignoreCase = true) || it.name.contains("TRỰC TIẾP", ignoreCase = true) }
            if (liveCat != null) {
                currentSelectedCategory = liveCat
                categoryAdapter.setSelectedCategory(liveCat)
                val mg = playlistManager.sportsLiveManager.findMatchForChannel(playing)
                    ?: if (currentPlayingMatchGroup?.sources?.any { it.streamUrl == playing?.streamUrl || it.id == playing?.id } == true) currentPlayingMatchGroup else null
                if (mg != null) {
                    currentPlayingMatchGroup = mg
                }
                val matchChannel = if (mg != null) {
                    liveCat.channels.firstOrNull { it.streamUrl == "match://${mg.key}" }
                } else if (playing != null) {
                    liveCat.channels.firstOrNull { it.streamUrl == playing.streamUrl || it.id == playing.id }
                        ?: liveCat.channels.firstOrNull { it.name.contains(playing.name) || playing.name.contains(it.name) }
                } else null
                displayCategoryChannels(liveCat, focusChannel = matchChannel ?: liveCat.channels.firstOrNull { it.id != SportsLiveManager.ACTION_REFRESH_SPORTS } ?: liveCat.channels.firstOrNull())
            }
            showOverlay()
            focusPlayingChannel()
        } else {
            // Mục Xem TV: Nhấn OK mở danh mục Tất cả với Highlight kênh đó
            if (currentAppMode != AppMode.TV) {
                switchAppMode(AppMode.TV, focusCategories = false)
            }
            val allCat = allCategories.firstOrNull { it.name.equals("Tất cả", ignoreCase = true) } ?: allCategories.firstOrNull()
            if (allCat != null) {
                currentSelectedCategory = allCat
                categoryAdapter.setSelectedCategory(allCat)
                val targetChan = allCat.channels.firstOrNull { it.streamUrl == playing?.streamUrl || it.id == playing?.id } ?: playing
                displayCategoryChannels(allCat, focusChannel = targetChan)
            }
            showOverlay()
            focusPlayingChannel()
        }
    }

    private fun handleRemoteBackFullscreen() {
        val playing = currentPlayingChannel
        val isSports = (currentAppMode == AppMode.SPORTS)
        if (isSports) {
            // Mục Thể Thao: Nhấn BACK/Dpad Trái mở danh mục Nguồn phát chứa link đó kèm highlight kênh đang phát
            if (currentAppMode != AppMode.SPORTS) {
                switchAppMode(AppMode.SPORTS, focusCategories = false)
            }
            val sourceCat = allCategories.firstOrNull { cat ->
                !cat.name.contains("LIVE EVENTS", ignoreCase = true) && !cat.name.contains("TRỰC TIẾP", ignoreCase = true) &&
                cat.channels.any { it.streamUrl == playing?.streamUrl }
            } ?: allCategories.firstOrNull { cat ->
                cat.channels.any { it.streamUrl == playing?.streamUrl }
            } ?: allCategories.firstOrNull { cat ->
                playing != null && cat.name.equals(playing.groupTitle, ignoreCase = true)
            } ?: allCategories.firstOrNull { !it.name.contains("LIVE EVENTS", ignoreCase = true) }
              ?: allCategories.firstOrNull()

            if (sourceCat != null) {
                currentSelectedCategory = sourceCat
                categoryAdapter.setSelectedCategory(sourceCat)
                val targetChan = sourceCat.channels.firstOrNull { it.streamUrl == playing?.streamUrl } ?: playing
                displayCategoryChannels(sourceCat, focusChannel = targetChan)
            }
            showOverlay()
            focusPlayingChannel()
        } else {
            // Mục Xem TV: Nhấn BACK/Dpad Trái mở danh mục con kèm highlight kênh đó
            if (currentAppMode != AppMode.TV) {
                switchAppMode(AppMode.TV, focusCategories = false)
            }
            val subCat = allCategories.firstOrNull { cat ->
                !cat.name.equals("Tất cả", ignoreCase = true) &&
                !cat.name.equals("Yêu thích", ignoreCase = true) &&
                cat.channels.any { it.streamUrl == playing?.streamUrl || it.id == playing?.id }
            } ?: allCategories.firstOrNull { cat ->
                !cat.name.equals("Tất cả", ignoreCase = true) &&
                playing != null && cat.name.equals(playing.groupTitle, ignoreCase = true)
            } ?: (if (currentSelectedCategory?.name != "Tất cả") currentSelectedCategory else null)
              ?: allCategories.firstOrNull { !it.name.equals("Tất cả", ignoreCase = true) }
              ?: allCategories.firstOrNull()

            if (subCat != null) {
                currentSelectedCategory = subCat
                categoryAdapter.setSelectedCategory(subCat)
                val targetChan = subCat.channels.firstOrNull { it.streamUrl == playing?.streamUrl || it.id == playing?.id } ?: playing
                displayCategoryChannels(subCat, focusChannel = targetChan)
            }
            showOverlay()
            focusPlayingChannel()
        }
        backPressedTime = 0L
    }

    private fun handleRemoteRightFullscreen() {
        val playing = currentPlayingChannel
        val isSports = (currentAppMode == AppMode.SPORTS)
        if (isSports) {
            // Mục Thể Thao: Nhấn Dpad Phải mở hộp thoại chọn nguồn phát trực tiếp của trận đấu
            // Chỉ hiển thị nguồn phát nếu kênh đang phát thuộc về một trận đấu thực sự
            val mg = playlistManager.sportsLiveManager.findMatchForChannel(playing)
                ?: if (currentPlayingMatchGroup?.sources?.any { it.streamUrl == playing?.streamUrl || it.id == playing?.id } == true) currentPlayingMatchGroup else null

            if (mg != null) {
                currentPlayingMatchGroup = mg
                showMatchSourcesDialog(mg)
            } else {
                showBottomNotification("Không có danh sách nguồn phát khác cho kênh này")
            }
        } else {
            // Mục Xem TV: Nhấn phím sang phải (►) mở hộp thoại chọn danh sách file .m3u
            showQuickPlaylistDialog()
        }
    }

    private fun handleBackInOverlay(): Boolean {
        val focused = currentFocus
        val isSpotlightFocused = focused != null && (
            binding.layoutSpotlightCol.hasFocus() ||
            focused.id == R.id.rvSpotlightStandings ||
            focused.id == R.id.rvSpotlightEpg ||
            focused.id == R.id.layoutStandingItem ||
            focused.id == R.id.layoutEpgItem ||
            (focused.parent != null && (focused.parent == binding.rvSpotlightStandings || focused.parent == binding.rvSpotlightEpg)) ||
            isDescendantOf(focused, binding.layoutSpotlightCol)
        )

        if (isSpotlightFocused) {
            focusFirstVisibleChannel()
            backPressedTime = 0L
            return true
        }

        val isSidebarFocused = focused != null && (focused.id == R.id.navFavorites ||
                focused.id == R.id.navModeTv || focused.id == R.id.navModeSports ||
                focused.id == R.id.navPlaylists || focused.id == R.id.navSidebarSettings ||
                focused.id == R.id.navRefreshPlaylists || focused.id == R.id.navCheckUpdate)

        if (isSidebarFocused) {
            if (System.currentTimeMillis() - backPressedTime < 2000) {
                finish()
                return true
            } else {
                backPressedTime = System.currentTimeMillis()
                showBottomNotification("Bấm QUAY LẠI lần nữa để thoát ứng dụng")
                return true
            }
        }

        // Bất kỳ phím back nào để thoát từ các menu (danh sách kênh, danh mục) -> đóng overlay
        // TUYỆT ĐỐI không tính là phím back lần 1 trong 2 lần ấn để thoát app
        hideOverlay()
        backPressedTime = 0L
        return true
    }

    override fun onBackPressed() {
        if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
            handleBackInOverlay()
            return
        }
        handleRemoteBackFullscreen()
    }

    private fun showOverlay() {
        binding.channelGuideOverlay.visibility = View.VISIBLE
        resetOverlayTimer()
        updateSystemStatsBadge()
        startTelemetryUpdates()

        val playing = currentPlayingChannel
        val targetMode = currentAppMode

        // Nếu danh mục hiện tại đã được chọn và hợp lệ thì giữ nguyên và focus kênh
        val currentCat = currentSelectedCategory
        if (currentCat != null && allCategories.contains(currentCat)) {
            val inCurrentCat = currentCategoryChannels.any { it.streamUrl == playing?.streamUrl || it.id == playing?.id }
            if (inCurrentCat || currentAppMode == AppMode.SPORTS) {
                focusPlayingChannel()
                return
            }
        }

        // Mặc định fallback khi chưa có danh mục chọn
        if (currentAppMode == AppMode.SPORTS) {
            val liveCat = allCategories.firstOrNull { it.name.contains("LIVE EVENTS", ignoreCase = true) || it.name.contains("TRỰC TIẾP", ignoreCase = true) }
            if (liveCat != null) {
                currentSelectedCategory = liveCat
                categoryAdapter.setSelectedCategory(liveCat)
                val mg = playlistManager.sportsLiveManager.findMatchForChannel(playing) ?: currentPlayingMatchGroup
                if (mg != null) {
                    currentPlayingMatchGroup = mg
                }
                val matchChannel = if (mg != null) {
                    liveCat.channels.firstOrNull { it.streamUrl == "match://${mg.key}" }
                } else null
                displayCategoryChannels(liveCat, focusChannel = matchChannel ?: liveCat.channels.firstOrNull { it.id != SportsLiveManager.ACTION_REFRESH_SPORTS } ?: liveCat.channels.firstOrNull())
                focusPlayingChannel()
                return
            }
        }

        if (playing != null) {
            val matchedCat = allCategories.firstOrNull { cat ->
                cat.channels.any { it.streamUrl == playing.streamUrl }
            } ?: allCategories.firstOrNull { it.name == "Tất cả" } ?: allCategories.firstOrNull()

            if (matchedCat != null) {
                currentSelectedCategory = matchedCat
                categoryAdapter.setSelectedCategory(matchedCat)
                displayCategoryChannels(matchedCat, focusChannel = playing)
                return
            }

            focusPlayingChannel()
            return
        }

        binding.rvChannels.requestFocus()
    }

    private fun focusPlayingChannel() {
        val playing = currentPlayingChannel
        if (currentAppMode == AppMode.SPORTS) {
            val mg = playlistManager.sportsLiveManager.findMatchForChannel(playing)
                ?: if (currentPlayingMatchGroup?.sources?.any { it.streamUrl == playing?.streamUrl || it.id == playing?.id } == true) currentPlayingMatchGroup else null
            if (mg != null) {
                currentPlayingMatchGroup = mg
                val matchUrl = "match://${mg.key}"
                val matchIndex = currentCategoryChannels.indexOfFirst { it.streamUrl == matchUrl }
                if (matchIndex != -1) {
                    val matchChannel = currentCategoryChannels[matchIndex]
                    binding.rvChannels.scrollToPosition(matchIndex)
                    binding.rvChannels.post {
                        val vh = binding.rvChannels.findViewHolderForAdapterPosition(matchIndex)
                        if (vh != null) {
                            vh.itemView.requestFocus()
                        } else {
                            binding.rvChannels.layoutManager?.findViewByPosition(matchIndex)?.requestFocus() ?: run {
                                binding.rvChannels.postDelayed({
                                    binding.rvChannels.findViewHolderForAdapterPosition(matchIndex)?.itemView?.requestFocus()
                                        ?: binding.rvChannels.layoutManager?.findViewByPosition(matchIndex)?.requestFocus()
                                        ?: binding.rvChannels.getChildAt(0)?.requestFocus()
                                }, 60)
                            }
                        }
                    }
                    updateSpotlight(matchChannel)
                    return
                }
            }
        }

        if (playing == null) {
            focusFirstVisibleChannel()
            return
        }
        val playingIndex = currentCategoryChannels.indexOfFirst { it.streamUrl == playing.streamUrl || it.id == playing.id }
        if (playingIndex != -1) {
            val targetChannel = currentCategoryChannels[playingIndex]
            binding.rvChannels.scrollToPosition(playingIndex)
            binding.rvChannels.post {
                val vh = binding.rvChannels.findViewHolderForAdapterPosition(playingIndex)
                if (vh != null) {
                    vh.itemView.requestFocus()
                } else {
                    binding.rvChannels.layoutManager?.findViewByPosition(playingIndex)?.requestFocus() ?: run {
                        binding.rvChannels.postDelayed({
                            binding.rvChannels.findViewHolderForAdapterPosition(playingIndex)?.itemView?.requestFocus()
                                ?: binding.rvChannels.layoutManager?.findViewByPosition(playingIndex)?.requestFocus()
                                ?: binding.rvChannels.getChildAt(0)?.requestFocus()
                        }, 60)
                    }
                }
            }
            updateSpotlight(targetChannel)
        } else {
            focusFirstVisibleChannel()
        }
    }

    private fun focusFirstVisibleChannel() {
        val targetPos = currentFocusedChannel?.let { ch ->
            currentCategoryChannels.indexOfFirst { it.streamUrl == ch.streamUrl }.takeIf { it != -1 }
        } ?: run {
            val lm = binding.rvChannels.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: 0
            if (first != RecyclerView.NO_POSITION && first in currentCategoryChannels.indices) first else 0
        }
        binding.rvChannels.post {
            val vh = binding.rvChannels.findViewHolderForAdapterPosition(targetPos)
            if (vh != null) {
                vh.itemView.requestFocus()
            } else {
                binding.rvChannels.layoutManager?.findViewByPosition(targetPos)?.requestFocus() ?: run {
                    binding.rvChannels.postDelayed({
                        binding.rvChannels.findViewHolderForAdapterPosition(targetPos)?.itemView?.requestFocus()
                            ?: binding.rvChannels.layoutManager?.findViewByPosition(targetPos)?.requestFocus()
                            ?: binding.rvChannels.getChildAt(0)?.requestFocus()
                    }, 60)
                }
            }
        }
    }

    private fun focusSpotlightEpg(): Boolean {
        if (binding.rvSpotlightEpg.visibility == View.VISIBLE && epgTimelineAdapter.itemCount > 0) {
            val currPos = epgTimelineAdapter.getCurrentPlayingPosition().let { if (it >= 0) it else 0 }
            val vh = binding.rvSpotlightEpg.findViewHolderForAdapterPosition(currPos)
            if (vh != null) {
                vh.itemView.requestFocus()
                return true
            }
            val view = binding.rvSpotlightEpg.layoutManager?.findViewByPosition(currPos)
                ?: binding.rvSpotlightEpg.getChildAt(0)
            if (view != null) {
                view.requestFocus()
                return true
            }
            binding.rvSpotlightEpg.scrollToPosition(currPos)
            binding.rvSpotlightEpg.post {
                val target = binding.rvSpotlightEpg.layoutManager?.findViewByPosition(currPos)
                    ?: binding.rvSpotlightEpg.findViewHolderForAdapterPosition(currPos)?.itemView
                    ?: binding.rvSpotlightEpg.getChildAt(0)
                target?.requestFocus()
            }
            return true
        } else {
            showBottomNotification("Chưa có lịch phát sóng EPG cho kênh này")
            return false
        }
    }

    private fun focusSpotlightStandings(): Boolean {
        if (binding.rvSpotlightStandings.visibility == View.VISIBLE && standingsAdapter.itemCount > 0) {
            val highlightIdx = standingsAdapter.getHighlightedIndex().let { if (it >= 0) it else 0 }
            val vh = binding.rvSpotlightStandings.findViewHolderForAdapterPosition(highlightIdx)
            if (vh != null) {
                vh.itemView.requestFocus()
                return true
            }
            val view = binding.rvSpotlightStandings.layoutManager?.findViewByPosition(highlightIdx)
                ?: binding.rvSpotlightStandings.getChildAt(0)
            if (view != null) {
                view.requestFocus()
                return true
            }
            binding.rvSpotlightStandings.scrollToPosition(highlightIdx)
            binding.rvSpotlightStandings.post {
                val target = binding.rvSpotlightStandings.layoutManager?.findViewByPosition(highlightIdx)
                    ?: binding.rvSpotlightStandings.findViewHolderForAdapterPosition(highlightIdx)?.itemView
                    ?: binding.rvSpotlightStandings.getChildAt(0)
                target?.requestFocus()
            }
            return true
        } else {
            showBottomNotification("Chưa có dữ liệu bảng xếp hạng cho trận đấu này")
            return false
        }
    }

    private fun showMatchSourcesDialog(matchGroup: SportsLiveManager.MatchGroup) {
        if (isFinishing || isDestroyed) return
        resetOverlayTimer()
        val dialog = MatchSourcesDialog(this, matchGroup) { selectedChannel ->
            currentPlayingMatchGroup = matchGroup
            playChannel(selectedChannel)
            hideHandler.postDelayed({ hideOverlay() }, 800)
        }
        activeCustomDialog = dialog
        dialog.setOnDismissListener {
            activeCustomDialog = null
            backPressedTime = 0L
        }
        dialog.show()
    }

    private fun showQuickPlaylistDialog() {
        if (isFinishing || isDestroyed) return
        if (activeCustomDialog?.isShowing == true) {
            activeCustomDialog?.dismiss()
            return
        }
        resetOverlayTimer()
        val dialog = QuickPlaylistDialog(
            context = this,
            onPlaylistSelected = {
                hideOverlay()
                loadPlaylist(forceRefresh = false)
                loadEpgData()
            },
            onOpenFullSettings = {
                showSettingsDialog()
            }
        )
        activeCustomDialog = dialog
        dialog.setOnDismissListener {
            activeCustomDialog = null
            backPressedTime = 0L
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
        backPressedTime = 0L
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
            hideHandler.postDelayed(hideOverlayRunnable, 15000L) // Yêu cầu 2: Tăng thời gian hiển thị lên 15 giây
        }
    }

    private fun showSettingsDialog() {
        if (isFinishing || isDestroyed) return
        if (activeSettingsDialog?.isShowing == true) {
            activeSettingsDialog?.dismiss()
            backPressedTime = 0L
            return
        }
        resetOverlayTimer()
        val dialog = SettingsDialog(this) { playlistChanged, engineChanged ->
            if (playlistChanged) {
                loadPlaylist(forceRefresh = true)
                loadEpgData()
            }
            if (engineChanged) {
                initExoPlayer()
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
        dialog.setOnDismissListener {
            activeSettingsDialog = null
            backPressedTime = 0L
        }
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

                // 2. Tải lại active TV playlist
                val activeSource = prefs.getActivePlaylist()
                val categories = withContext(Dispatchers.IO) {
                    playlistManager.loadPlaylist(activeSource.id, activeSource.url, forceRefresh = true, includeSports = false)
                }
                tvCategories.clear()
                tvCategories.addAll(categories)

                allChannels.clear()
                val allCat = categories.firstOrNull { it.name == "Tất cả" }
                if (allCat != null) {
                    allChannels.addAll(allCat.channels)
                } else if (categories.isNotEmpty()) {
                    allChannels.addAll(categories.flatMap { it.channels })
                }

                // 3. Tải lại trực tiếp thể thao
                val sports = withContext(Dispatchers.IO) {
                    playlistManager.sportsLiveManager.getSportsCategories(forceRefresh = true)
                }
                sportsCategoriesList.clear()
                sportsCategoriesList.addAll(sports)

                // 4. Cập nhật giao diện
                switchAppMode(currentAppMode, focusCategories = false)

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
    }

    override fun onResume() {
        super.onResume()
        if (currentPlayingChannel != null && player?.isPlaying == false) {
            player?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        stopTelemetryUpdates()
        player?.pause()
    }

    private fun isSportsLiveMatch(channel: Channel?): Boolean {
        if (channel == null) return false
        if (channel.id == SportsLiveManager.ACTION_REFRESH_SPORTS || channel.streamUrl.startsWith("action://") || channel.streamUrl.startsWith("match://")) return true
        if (currentAppMode == AppMode.TV && !channel.id.startsWith("src_") && !channel.id.startsWith("streamfree_") && !channel.id.startsWith("intl_sports_")) return false
        if (currentAppMode == AppMode.SPORTS) return true
        if (channel.id.startsWith("src_") || channel.id.startsWith("streamfree_") || channel.id.startsWith("intl_sports_")) return true
        val g = channel.groupTitle
        if (g.contains("Chuối Chiên") || g.contains("Gà Vàng") || g.contains("Bia Ôm") ||
            g.contains("Phá Làng") || g.contains("Vua Sân Cỏ") || g.contains("Sao Kê") || g.contains("LIVE EVENTS")) return true
        return channel.streamUrl.contains("/truc-tiep/") ||
                channel.streamUrl.startsWith("streamfree://") ||
                channel.name.startsWith("🔴") ||
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
            standingsManager.clearCache()
            val sportsCategories = withContext(Dispatchers.IO) {
                playlistManager.sportsLiveManager.getSportsCategories(forceRefresh = true)
            }
            binding.layoutQuickBuffering.visibility = View.GONE
            if (sportsCategories.isNotEmpty()) {
                applyUpdatedSportsCategories(sportsCategories)
                val count = sportsCategories.firstOrNull()?.channels?.count { it.id != SportsLiveManager.ACTION_REFRESH_SPORTS } ?: 0
                showBottomNotification("⚽ Đã cập nhật xong! Tìm thấy $count trận đấu trực tiếp hôm nay.")
                val focused = currentFocusedChannel ?: currentPlayingChannel
                if (focused != null) {
                    updateSpotlight(focused, immediate = true)
                }
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
                    standingsManager.clearCache()
                    val sportsCategories = withContext(Dispatchers.IO) {
                        playlistManager.sportsLiveManager.getSportsCategories(forceRefresh = true)
                    }
                    if (sportsCategories.isNotEmpty()) {
                        applyUpdatedSportsCategories(sportsCategories)
                        if (binding.channelGuideOverlay.visibility == View.VISIBLE && currentAppMode == AppMode.SPORTS) {
                            val focused = currentFocusedChannel ?: currentPlayingChannel
                            if (focused != null) {
                                updateSpotlight(focused, immediate = true)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun applyUpdatedSportsCategories(sportsCategories: List<Category>) {
        sportsCategoriesList.clear()
        sportsCategoriesList.addAll(sportsCategories)

        if (currentAppMode == AppMode.SPORTS) {
            allCategories.clear()
            allCategories.addAll(sportsCategoriesList)
            categoryAdapter.submitList(allCategories.toList())

            val currentCatName = currentSelectedCategory?.name ?: ""
            val updatedCat = allCategories.firstOrNull { it.name == currentCatName } ?: allCategories.firstOrNull()
            if (updatedCat != null) {
                currentSelectedCategory = updatedCat
                categoryAdapter.setSelectedCategory(updatedCat)
                if (binding.channelGuideOverlay.visibility == View.VISIBLE) {
                    displayCategoryChannels(updatedCat)
                }
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

        releaseExoPlayer()
    }
}
