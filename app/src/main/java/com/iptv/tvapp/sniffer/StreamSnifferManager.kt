package com.iptv.tvapp.sniffer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeoutException

/**
 * Trình bóc tách luồng trực tiếp ngầm (Headless WebView Stream Sniffer)
 * Phục vụ các kênh thể thao quốc tế & trận đấu bóng đá trực tiếp sử dụng WebAssembly / dynamic token (PPV.st, Embed.st, StreamFree...)
 * Cơ chế hoạt động:
 * 1. Mở WebView ẩn 1px trên Main Thread với Desktop User-Agent.
 * 2. Tắt tải ảnh (blockNetworkImage = true) để nạp trang thần tốc (< 2 giây).
 * 3. Chặn quảng cáo, trackers và vô hiệu hóa Service Worker độc quyền.
 * 4. Can thiệp shouldInterceptRequest để tóm luồng .m3u8 cùng Referer/Origin headers.
 * 5. Trả về dummy m3u8 response để ngắt nạp segment trong WebView, tiết kiệm RAM Android TV Box.
 * 6. Bàn giao link hoàn chỉnh (URL|Referer=...&Origin=...) cho ExoPlayer / VLC giải mã phần cứng.
 */
class StreamSnifferManager(private val context: Context) {

    companion object {
        private const val TAG = "StreamSniffer"

        // User-Agent Chrome Desktop chuẩn để vượt qua Cloudflare WAF & kích hoạt giao diện trình phát đầy đủ
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // Danh sách tên miền quảng cáo / popunder / tracker cần chặn đứng
        private val AD_DOMAINS = listOf(
            "googlesyndication.com",
            "doubleclick.net",
            "google-analytics.com",
            "adservice.google.com",
            "adnxs.com",
            "monetag.com",
            "histats.com",
            "clks.pro",
            "drawerexperienceletting.com",
            "ad.html",
            "popunder",
            "popads",
            "yandex.ru",
            "taboola.com",
            "outbrain.com",
            "alwingulla.com",
            "aclib.run"
        )

        // Script JavaScript can thiệp vào trang web để kích hoạt player và kiểm tra file phát
        private const val INJECTED_POLLER_JS = """
            (function() {
                try {
                    // 1. Kiểm tra JWPlayer nếu có
                    if (window.jwplayer && typeof window.jwplayer === 'function') {
                        var p = window.jwplayer('player') || window.jwplayer();
                        if (p && p.getConfig) {
                            var cfg = p.getConfig();
                            var file = cfg.file || (cfg.playlist && cfg.playlist[0] && cfg.playlist[0].file) || (cfg.sources && cfg.sources[0] && cfg.sources[0].file);
                            if (file && (file.indexOf('.m3u8') !== -1 || file.indexOf('.m3u') !== -1)) {
                                console.log('SNIFFER_M3U8:' + file);
                            }
                        }
                    }
                    // 2. Kiểm tra thẻ video HTML5
                    var videos = document.getElementsByTagName('video');
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        if (v.src && (v.src.indexOf('.m3u8') !== -1 || v.src.indexOf('.m3u') !== -1)) {
                            console.log('SNIFFER_M3U8:' + v.src);
                            break;
                        }
                    }
                    // 3. Tự kích hoạt nút Play nếu player đang chờ click (để bắt đầu tải playlist)
                    var playBtn = document.querySelector('.jw-display-icon-container, .play-button, button[aria-label="Play"], .vjs-big-play-button, .play-btn');
                    if (playBtn) {
                        playBtn.click();
                    }
                } catch(e) {}
            })();
        """

        // Phản hồi M3U8 giả lập để ngắt nạp segment video trong WebView
        private const val DUMMY_M3U8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-ENDLIST\n"

        // Script Service Worker rỗng để trung hòa cơ chế chặn WebAssembly Service Worker
        private const val NOOP_SW = "self.addEventListener('install', function(e){self.skipWaiting();}); self.addEventListener('activate', function(e){self.clients.claim();});"
    }

    private var webView: WebView? = null
    private var isSniffing = false
    private var currentTargetUrl: String? = null
    private var currentCallback: ((Result<String>) -> Unit)? = null
    private var timeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        if (webView == null) {
            webView = WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                visibility = View.INVISIBLE

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.userAgentString = DESKTOP_UA

                // Tối ưu hóa bộ nhớ & tốc độ (< 2 giây): Cấm tải hình ảnh qua mạng
                settings.blockNetworkImage = true
                settings.loadsImagesAutomatically = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: return false
                        if (msg.startsWith("SNIFFER_M3U8:")) {
                            val sniffed = msg.removePrefix("SNIFFER_M3U8:").trim()
                            if (sniffed.isNotBlank()) {
                                Log.d(TAG, "Bắt được luồng qua JS Console: $sniffed")
                                handleSniffedSuccess(sniffed, emptyMap())
                                return true
                            }
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        val reqUrlLower = reqUrl.lowercase()

                        // 1. Chặn đứng các mạng quảng cáo / trackers để trang load nhanh như chớp
                        if (AD_DOMAINS.any { reqUrlLower.contains(it) }) {
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        // 2. Vô hiệu hóa Service Worker độc quyền của các web player
                        if (reqUrlLower.endsWith("/sw.js") || reqUrlLower.contains("service-worker.js") || reqUrlLower.contains("/sw-worker.js")) {
                            return WebResourceResponse(
                                "application/javascript",
                                "UTF-8",
                                ByteArrayInputStream(NOOP_SW.toByteArray(Charsets.UTF_8))
                            )
                        }

                        // 3. Tóm luồng phát .m3u8 / .m3u
                        if (isTargetStream(reqUrlLower)) {
                            if (isSniffing) {
                                Log.d(TAG, "Tóm được luồng phát M3U8 từ network: $reqUrl")
                                val headers = request.requestHeaders ?: emptyMap()
                                handleSniffedSuccess(reqUrl, headers)
                            }

                            // Trả về dummy m3u8 để WebView ngắt kết nối nạp video ngay lập tức
                            return WebResourceResponse(
                                "application/vnd.apple.mpegurl",
                                "UTF-8",
                                ByteArrayInputStream(DUMMY_M3U8.toByteArray(Charsets.UTF_8))
                            )
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (isSniffing) {
                            view?.evaluateJavascript(INJECTED_POLLER_JS, null)
                        }
                    }
                }
            }
        }
        return webView!!
    }

    /**
     * Kiểm tra URL có phải là luồng trực tiếp HLS (.m3u8) hay không
     */
    private fun isTargetStream(urlLower: String): Boolean {
        if (urlLower.contains(".m3u8") || urlLower.contains(".m3u")) return true
        if (urlLower.contains("/hls/") && urlLower.contains("playlist")) return true
        if (urlLower.contains("/live/") && (urlLower.contains("index") || urlLower.contains("manifest"))) return true
        return false
    }

    /**
     * Xử lý khi bóc tách thành công luồng m3u8 và trích xuất đầy đủ headers
     */
    @Synchronized
    private fun handleSniffedSuccess(m3u8Url: String, requestHeaders: Map<String, String>) {
        if (!isSniffing) return
        isSniffing = false
        timeoutJob?.cancel()
        timeoutJob = null

        val callback = currentCallback
        currentCallback = null

        // Xử lý nếu link m3u8 là đường dẫn tương đối (/live/...)
        val resolvedM3u8 = if (m3u8Url.startsWith("/") && currentTargetUrl != null) {
            try {
                val baseUri = Uri.parse(currentTargetUrl)
                "${baseUri.scheme}://${baseUri.host}$m3u8Url"
            } catch (e: Exception) { m3u8Url }
        } else {
            m3u8Url
        }

        // Trích xuất Referer & Origin chuẩn
        val headers = mutableMapOf<String, String>()
        val referer = requestHeaders["Referer"] ?: requestHeaders["referer"] ?: currentTargetUrl
        val origin = requestHeaders["Origin"] ?: requestHeaders["origin"] ?: referer?.let {
            try {
                val uri = Uri.parse(it)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) { null }
        }
        val userAgent = requestHeaders["User-Agent"] ?: requestHeaders["user-agent"] ?: DESKTOP_UA

        referer?.let { headers["Referer"] = it }
        origin?.let { headers["Origin"] = it }
        headers["User-Agent"] = userAgent

        // Định dạng URL chuẩn kèm Headers để chuyển giao cho ExoPlayer / VLC: URL|Referer=...&Origin=...
        val formattedHeaders = headers.entries.joinToString("&") { "${it.key}=${it.value}" }
        val finalUrl = if (formattedHeaders.isNotBlank()) "$resolvedM3u8|$formattedHeaders" else resolvedM3u8

        Log.d(TAG, "Bóc tách thành công! Link chuyển cho Player: $finalUrl")

        // Dọn dẹp WebView ngay lập tức trên UI thread
        scope.launch(Dispatchers.Main) {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            callback?.invoke(Result.success(finalUrl))
        }
    }

    /**
     * Bóc tách luồng .m3u8 từ một URL trang nhúng (Embed URL / Live URL)
     * @param embedUrl URL trang xem trực tiếp (ví dụ: https://embed.st/embed/sports/skysportsf1 hoặc https://streamfree.top/...)
     * @param timeoutMs Thời gian chờ tối đa (mặc định 10 giây)
     * @param callback Nhận kết quả thành công kèm Header hoặc lỗi Timeout
     */
    fun sniffStream(
        embedUrl: String,
        timeoutMs: Long = 10000L,
        callback: (Result<String>) -> Unit
    ) {
        scope.launch(Dispatchers.Main) {
            cancel()

            isSniffing = true
            currentTargetUrl = embedUrl
            currentCallback = callback

            Log.d(TAG, "Bắt đầu bóc tách luồng từ: $embedUrl (timeout: ${timeoutMs}ms)")

            val wv = ensureWebView()

            // Thiết lập Coroutine đếm ngược timeout
            timeoutJob = scope.launch {
                delay(timeoutMs)
                if (isSniffing) {
                    Log.w(TAG, "Timeout khi bóc tách luồng: $embedUrl")
                    isSniffing = false
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    val cb = currentCallback
                    currentCallback = null
                    cb?.invoke(Result.failure(TimeoutException("Hết thời gian chờ (timeout ${timeoutMs}ms) không bóc tách được luồng .m3u8")))
                }
            }

            // Nạp URL vào WebView để kích hoạt sniffer
            wv.loadUrl(embedUrl)
        }
    }

    /**
     * Hủy bỏ quá trình bóc tách hiện tại khi người dùng đổi kênh
     */
    fun cancel() {
        isSniffing = false
        timeoutJob?.cancel()
        timeoutJob = null
        currentCallback = null
        currentTargetUrl = null
        scope.launch(Dispatchers.Main) {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
            }
        }
    }

    /**
     * Giải phóng tài nguyên khi Activity kết thúc
     */
    fun destroy() {
        cancel()
        scope.launch(Dispatchers.Main) {
            webView?.apply {
                stopLoading()
                clearCache(true)
                clearHistory()
                destroy()
            }
            webView = null
        }
    }
}
