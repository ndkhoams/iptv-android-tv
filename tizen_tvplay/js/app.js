/**
 * TV Play - Samsung Tizen Smart TV Edition
 * Exact Clone of TV Play Android TV (FPT Play Box G2 Dark Theme)
 * Full HD 1080p & 4K Crystal UHD Responsive
 */

(function () {
    'use strict';

    // ==========================================
    // 0. OTA BOOTSTRAPPER (TỰ ĐỘNG CHẠY BẢN MỚI NHẤT)
    // ==========================================
    var otaCode = localStorage.getItem('tvplay_tizen_ota_code');
    var isOtaRunning = window.__TVPLAY_OTA_ACTIVE__ || false;
    if (otaCode && !isOtaRunning) {
        try {
            window.__TVPLAY_OTA_ACTIVE__ = true;
            (new Function(otaCode))();
            return;
        } catch (e) {
            console.error("Lỗi khởi chạy bản OTA, hoàn nguyên về bản gốc:", e);
            localStorage.removeItem('tvplay_tizen_ota_code');
        }
    }

    // Helper tương thích mọi đời Tizen OS
    function pad2(num) {
        var s = String(num);
        return s.length >= 2 ? s : "0" + s;
    }

    if (!String.prototype.padStart) {
        String.prototype.padStart = function (targetLength, padString) {
            targetLength = targetLength >> 0;
            padString = String((typeof padString !== 'undefined' ? padString : ' '));
            if (this.length > targetLength) return String(this);
            targetLength = targetLength - this.length;
            if (targetLength > padString.length) {
                padString += padString.repeat(targetLength / padString.length);
            }
            return padString.slice(0, targetLength) + String(this);
        };
    }

    if (!Array.prototype.find) {
        Array.prototype.find = function (predicate) {
            if (this == null) throw new TypeError('Array.prototype.find called on null or undefined');
            if (typeof predicate !== 'function') throw new TypeError('predicate must be a function');
            var list = Object(this);
            var length = list.length >>> 0;
            var thisArg = arguments[1];
            for (var i = 0; i < length; i++) {
                var value = list[i];
                if (predicate.call(thisArg, value, i, list)) return value;
            }
            return undefined;
        };
    }

    if (!Array.prototype.findIndex) {
        Array.prototype.findIndex = function (predicate) {
            if (this == null) throw new TypeError('Array.prototype.findIndex called on null or undefined');
            if (typeof predicate !== 'function') throw new TypeError('predicate must be a function');
            var list = Object(this);
            var length = list.length >>> 0;
            var thisArg = arguments[1];
            for (var i = 0; i < length; i++) {
                var value = list[i];
                if (predicate.call(thisArg, value, i, list)) return i;
            }
            return -1;
        };
    }

    // ==========================================
    // 1. CẤU HÌNH & HẰNG SỐ
    // ==========================================
    var APP_VERSION = "2.2.3";
    var DEFAULT_M3U_URL = "https://fastly.jsdelivr.net/gh/ndkhoams/iptv@main/ktv.m3u";
    var DEFAULT_UDPXY_SERVER = "10.0.0.222:4022";

    var SPORTS_DATA_URLS = [
        "https://fastly.jsdelivr.net/gh/ndkhoams/iptv@main/thethao-data.json",
        "https://cdn.jsdelivr.net/gh/ndkhoams/iptv@main/thethao-data.json",
        "https://raw.githubusercontent.com/ndkhoams/iptv/main/thethao-data.json"
    ];

    var OTA_VERSION_URLS = [
        "https://fastly.jsdelivr.net/gh/ndkhoams/iptv@main/tizen_ota_version.json",
        "https://gcore.jsdelivr.net/gh/ndkhoams/iptv@main/tizen_ota_version.json",
        "https://cdn.jsdelivr.net/gh/ndkhoams/iptv@main/tizen_ota_version.json",
        "https://testingcf.jsdelivr.net/gh/ndkhoams/iptv@main/tizen_ota_version.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/ndkhoams/iptv/main/tizen_ota_version.json",
        "https://ghfast.top/https://raw.githubusercontent.com/ndkhoams/iptv/main/tizen_ota_version.json",
        "https://raw.githubusercontent.com/ndkhoams/iptv/main/tizen_ota_version.json"
    ];

    // Keycodes điều khiển Samsung Tizen
    var TIZEN_KEYS = {
        KEY_ENTER: 13,
        KEY_UP: 38,
        KEY_DOWN: 40,
        KEY_LEFT: 37,
        KEY_RIGHT: 39,
        KEY_RETURN: 10009, // Tizen Back / Return
        KEY_ESCAPE: 27,    // Browser Escape
        KEY_BACKSPACE: 8,
        KEY_SPACE: 32,
        KEY_CHANNEL_UP: 427,
        KEY_CHANNEL_DOWN: 428,
        KEY_PLAY: 415,
        KEY_PAUSE: 19,
        KEY_PLAY_PAUSE: 10252,
        KEY_MENU: 448,     // Remote Menu
        KEY_M: 77,
        KEY_S: 83,
        KEY_RED: 403,
        KEY_GREEN: 404,
        KEY_YELLOW: 405,
        KEY_BLUE: 406,
        KEY_0: 48, KEY_1: 49, KEY_2: 50, KEY_3: 51, KEY_4: 52,
        KEY_5: 53, KEY_6: 54, KEY_7: 55, KEY_8: 56, KEY_9: 57
    };

    // Modes ứng dụng (TÁCH BIỆT HOÀN TOÀN NHƯ ANDROID)
    var APP_MODE_TV = 'tv';
    var APP_MODE_SPORTS = 'sports';
    var APP_MODE_FAVORITES = 'favorites';

    // ==========================================
    // 2. BIẾN TRẠNG THÁI (STATE)
    // ==========================================
    var currentAppMode = APP_MODE_TV;

    // Dữ liệu truyền hình (TV)
    var tvChannels = [];
    var tvCategories = [];
    var categories = [];

    // Dữ liệu thể thao (Sports)
    var sportsLiveMatches = [];
    var sportsCategories = [];
    var sportsFixtures = [];
    var currentSelectedMatch = null;

    // Dữ liệu Lịch phát sóng (EPG)
    var epgMap = {};
    var isEpgLoading = false;
    var isEpgLoaded = false;
    var EPG_URLS = [
        "https://lichphatsong.io.vn/epg.xml",
        "https://epg.io.vn/epgc.xml"
    ];

    // Kênh yêu thích
    var favoriteUrls = [];

    // Kênh đang phát hiện tại
    var currentPlayingChannel = null;
    var currentPlayingMatch = null;

    // Trạng thái điều hướng
    var isOverlayOpen = false;
    var isMatchModalOpen = false;
    var isSettingsModalOpen = false;
    var isQuickPlaylistModalOpen = false;
    var quickPlaylistModalEl = null;
    var quickPlaylistsListEl = null;
    var quickPlaylistFocusIdx = 0;

    var focusedColumn = 'channels'; // 'sidebar' | 'categories' | 'channels' | 'spotlight'
    var selectedSidebarIdx = 1;     // 0: Fav, 1: TV, 2: Sports, 3: Refresh, 4: Settings
    var selectedCatIdx = 0;
    var selectedChIdx = 0;
    var selectedSourceIdx = 0;
    var selectedSpotlightSourceIdx = 0;
    var settingsFocusIdx = 0;

    // Cài đặt người dùng
    var udpxyServer = "";
    var playlists = [];
    var activePlaylistId = "";

    // Player & Timeouts
    var hls = null;
    var mpegtsPlayer = null;
    var avplayActive = false;
    var videoEl = null;
    var quickInfoTimeout = null;
    var toastTimeout = null;
    var numberInputBuffer = "";
    var numberInputTimeout = null;

    // Elements tham chiếu
    var spinnerEl, quickInfoEl, overlayEl, categoriesListEl, channelsListEl;
    var quickNumEl, quickLogoEl, quickNameEl, quickModeTagEl, quickProgramEl, quickTechEl, quickBufferingTagEl;
    var colChannelsTitleEl, colChannelsCountEl, colCategoriesHeaderEl;
    var spotlightLogoEl, spotlightNameEl, spotlightCatTagEl, spotlightResTagEl, spotlightPlayingTagEl, spotlightLiveTagEl, spotlightEpgContentEl;
    var matchModalEl, modalMatchTitleEl, modalMatchSubtitleEl, matchSourcesListEl;
    var settingsModalEl, udpxyInputEl, playlistsManageListEl, otaStatusTextEl;
    var toastEl, clockEl;
    var sidebarBtns = [];

    // ==========================================
    // 3. KHỞI TẠO ỨNG DỤNG (INITIALIZATION)
    // ==========================================
    window.addEventListener('DOMContentLoaded', function () {
        initElements();
        initTizenKeys();
        initClock();
        loadSettings();
        initEventListeners();
        updateWebRemoteIp();
        loadAllData();
    });

    function initElements() {
        videoEl = document.getElementById('tv-video');
        spinnerEl = document.getElementById('buffering-spinner');
        quickInfoEl = document.getElementById('quick-info');
        overlayEl = document.getElementById('channel-overlay');
        categoriesListEl = document.getElementById('categories-list');
        channelsListEl = document.getElementById('channels-list');
        
        quickNumEl = document.getElementById('quick-number');
        quickLogoEl = document.getElementById('quick-logo');
        quickNameEl = document.getElementById('quick-name');
        quickModeTagEl = document.getElementById('quick-mode-tag');
        quickProgramEl = document.getElementById('quick-program');
        quickTechEl = document.getElementById('quick-tech-info');
        quickBufferingTagEl = document.getElementById('quick-buffering-tag');

        colCategoriesHeaderEl = document.getElementById('col-categories-header');
        colChannelsTitleEl = document.getElementById('col-channels-title');
        colChannelsCountEl = document.getElementById('col-channels-count');

        spotlightLogoEl = document.getElementById('spotlight-logo');
        spotlightNameEl = document.getElementById('spotlight-name');
        spotlightCatTagEl = document.getElementById('spotlight-cat-tag');
        spotlightResTagEl = document.getElementById('spotlight-res-tag');
        spotlightPlayingTagEl = document.getElementById('spotlight-playing-tag');
        spotlightLiveTagEl = document.getElementById('spotlight-live-tag');
        spotlightEpgContentEl = document.getElementById('spotlight-epg-content');

        // Modals
        matchModalEl = document.getElementById('match-sources-modal');
        modalMatchTitleEl = document.getElementById('modal-match-title');
        modalMatchSubtitleEl = document.getElementById('modal-match-subtitle');
        matchSourcesListEl = document.getElementById('match-sources-list');

        settingsModalEl = document.getElementById('settings-modal');
        udpxyInputEl = document.getElementById('setting-udpxy-input');
        playlistsManageListEl = document.getElementById('playlists-manage-list');
        otaStatusTextEl = document.getElementById('ota-status-text');

        quickPlaylistModalEl = document.getElementById('quick-playlist-modal');
        quickPlaylistsListEl = document.getElementById('quick-playlists-list');

        var quickPlCloseBtn = document.getElementById('quick-playlist-close-btn');
        if (quickPlCloseBtn) quickPlCloseBtn.addEventListener('click', closeQuickPlaylistModal);

        var btnQuickClose = document.getElementById('btn-quick-close-playlists');
        if (btnQuickClose) btnQuickClose.addEventListener('click', closeQuickPlaylistModal);

        var btnQuickManage = document.getElementById('btn-quick-manage-playlists');
        if (btnQuickManage) {
            btnQuickManage.addEventListener('click', function () {
                closeQuickPlaylistModal();
                openSettingsModal();
            });
        }

        toastEl = document.getElementById('toast-message');
        clockEl = document.getElementById('digital-clock');

        sidebarBtns = [
            document.getElementById('nav-fav'),
            document.getElementById('nav-tv'),
            document.getElementById('nav-sports'),
            document.getElementById('nav-refresh'),
            document.getElementById('nav-settings')
        ];
    }

    function initTizenKeys() {
        if (window.tizen && window.tizen.tvinputdevice) {
            var keysToRegister = [
                'MediaPlay', 'MediaPause', 'MediaPlayPause',
                'ChannelUp', 'ChannelDown',
                'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue',
                '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'
            ];
            keysToRegister.forEach(function (k) {
                try {
                    tizen.tvinputdevice.registerKey(k);
                } catch (e) {}
            });
        }
    }

    function initClock() {
        function updateClock() {
            var now = new Date();
            var h = pad2(now.getHours());
            var m = pad2(now.getMinutes());
            if (clockEl) {
                clockEl.textContent = h + ':' + m;
            }
        }
        updateClock();
        setInterval(updateClock, 10000);
    }

    // ==========================================
    // 4. QUẢN LÝ CÀI ĐẶT & UDPXY & PLAYLISTS & REAL IP
    // ==========================================
    function updateWebRemoteIp() {
        var badge = document.getElementById('header-remote-badge');
        if (!badge) return;

        // 1. Tizen SystemInfo API (Lấy IP thật trên TV)
        if (window.tizen && window.tizen.systeminfo) {
            try {
                tizen.systeminfo.getPropertyValue("WIFI_NETWORK", function (wifi) {
                    if (wifi && wifi.ipAddress && wifi.ipAddress !== "0.0.0.0") {
                        badge.textContent = "🌐 IP TV: " + wifi.ipAddress;
                    }
                });
                tizen.systeminfo.getPropertyValue("ETHERNET_NETWORK", function (eth) {
                    if (eth && eth.ipAddress && eth.ipAddress !== "0.0.0.0") {
                        badge.textContent = "🌐 IP TV: " + eth.ipAddress;
                    }
                });
            } catch (e) {}
        }

        // 2. Kiểm tra qua hostname nếu chạy qua HTTP Server
        var host = window.location.hostname;
        if (host && host !== "127.0.0.1" && host !== "localhost") {
            badge.textContent = "🌐 IP TV: " + host;
        } else {
            // Lấy LAN IP từ server preview
            try {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '/api/my_ip', true);
                xhr.onload = function () {
                    if (xhr.status === 200 && xhr.responseText.trim()) {
                        badge.textContent = "🌐 IP TV: " + xhr.responseText.trim();
                    }
                };
                xhr.send();
            } catch (e) {}
        }
        // Lên lịch cập nhật lại sau 2s và 5s để đảm bảo lấy được IP khi mạng vừa kết nối
        if (!window.__web_remote_timer_set__) {
            window.__web_remote_timer_set__ = true;
            setTimeout(updateWebRemoteIp, 2000);
            setTimeout(updateWebRemoteIp, 5000);
        }
    }

    function loadSettings() {
        try {
            udpxyServer = localStorage.getItem('tvplay_udpxy') || DEFAULT_UDPXY_SERVER;
            localStorage.setItem('tvplay_udpxy', udpxyServer);
            if (udpxyInputEl) udpxyInputEl.value = udpxyServer;

            var savedFavs = localStorage.getItem('tvplay_favorites');
            favoriteUrls = savedFavs ? JSON.parse(savedFavs) : [];

            var savedPlaylists = localStorage.getItem('tvplay_playlists');
            if (savedPlaylists) {
                playlists = JSON.parse(savedPlaylists);
                // Xoá bỏ triệt để các danh sách dự phòng và offline nội bộ
                playlists = playlists.filter(function (p) {
                    return p.id !== 'pl_backup' && p.id !== 'pl_local' &&
                           p.name !== 'Dự phòng Fastly' && p.name !== 'Offline Nội bộ';
                });
                if (playlists.length === 0) {
                    playlists = [{ id: "pl_ktv", name: "KTV Playlist", url: DEFAULT_M3U_URL, isSelected: true }];
                }
                savePlaylists();
            } else {
                playlists = [
                    { id: "pl_ktv", name: "KTV Playlist", url: DEFAULT_M3U_URL, isSelected: true }
                ];
                savePlaylists();
            }

            var activePl = playlists.find(function (p) { return p.isSelected; }) || playlists[0];
            activePlaylistId = activePl ? activePl.id : "pl_ktv";

            var badge = document.getElementById('header-playlist-badge');
            if (badge && activePl) badge.textContent = "Danh sách: " + activePl.name;
        } catch (e) {
            favoriteUrls = [];
        }
    }

    function saveSettings() {
        try {
            localStorage.setItem('tvplay_udpxy', udpxyServer.trim());
            localStorage.setItem('tvplay_favorites', JSON.stringify(favoriteUrls));
            savePlaylists();
        } catch (e) {}
    }

    function savePlaylists() {
        try {
            localStorage.setItem('tvplay_playlists', JSON.stringify(playlists));
        } catch (e) {}
    }

    function resolveStreamUrl(rawUrl) {
        if (!rawUrl) return "";
        var server = (udpxyServer && udpxyServer.trim()) ? udpxyServer.trim() : DEFAULT_UDPXY_SERVER;
        var cleanServer = server.replace(/^https?:\/\//i, '').replace(/\/+$/, '').trim() || DEFAULT_UDPXY_SERVER;

        // 1. Chuyển đổi link udp:// hoặc rtp:// hoặc upd:// (hỗ trợ cả có @ và không có @)
        var udpMatch = rawUrl.match(/^(udp|rtp|upd):\/\/@?([0-9a-zA-Z\.\-]+:[0-9]+.*)/i);
        if (udpMatch) {
            var proto = udpMatch[1].toLowerCase();
            if (proto === 'upd') proto = 'udp';
            var hostPort = udpMatch[2].trim();
            return "http://" + cleanServer + "/" + proto + "/" + hostPort;
        }

        // 2. Nếu link gốc đã là link udpxy từ M3U (http://old-server:port/udp/... hoặc /rtp/...) -> thay bằng udpxyServer của người dùng
        var existingUdpxyMatch = rawUrl.match(/^https?:\/\/[^\/]+\/(udp|rtp)\/(.*)/i);
        if (existingUdpxyMatch) {
            var proto2 = existingUdpxyMatch[1].toLowerCase();
            var streamPath = existingUdpxyMatch[2];
            return "http://" + cleanServer + "/" + proto2 + "/" + streamPath;
        }

        // 3. Chuyển đổi link có tham số udpxy
        if (rawUrl.toLowerCase().indexOf("udpxy=") >= 0) {
            return rawUrl.replace(/udpxy=[^&]+/i, "udpxy=" + cleanServer);
        }
        if (rawUrl.toLowerCase().indexOf("121124.xyz/tv/fpt") >= 0) {
            var sep = rawUrl.indexOf("?") >= 0 ? "&" : "?";
            return rawUrl + sep + "udpxy=" + cleanServer;
        }

        return rawUrl;
    }

    function toggleFavorite(channel) {
        if (!channel || !channel.url) return;
        var idx = favoriteUrls.indexOf(channel.url);
        if (idx >= 0) {
            favoriteUrls.splice(idx, 1);
            showToast("Đã xóa khỏi mục Yêu thích");
        } else {
            favoriteUrls.push(channel.url);
            showToast("★ Đã thêm vào mục Yêu thích");
        }
        saveSettings();
        if (currentAppMode === APP_MODE_FAVORITES) {
            buildFavoritesCategories();
            renderCategories();
            renderChannels();
        } else {
            renderChannels();
        }
    }

    // ==========================================
    // 5. TẢI DỮ LIỆU TÁCH BIỆT: TV & THỂ THAO
    // ==========================================
    function resolveLeagueLogo(rawLogo, league, leagueName, title) {
        var raw = (rawLogo || "").toLowerCase();
        var lg = (league || "").toUpperCase();
        var str = ((rawLogo || "") + " " + (league || "") + " " + (leagueName || "") + " " + (title || "")).toLowerCase();

        // Ưu tiên mã giải đấu chính thức
        if (lg === "UCL" || raw.indexOf("ic_league_ucl") >= 0 || str.indexOf("champions") >= 0 || str.indexOf("cúp c1") >= 0) return "assets/leagues/ic_league_ucl.png";
        if (lg === "EPL" || raw.indexOf("ic_league_epl") >= 0 || str.indexOf("premier") >= 0 || str.indexOf("ngoại hạng") >= 0) return "assets/leagues/ic_league_epl.png";
        if (lg === "LALIGA" || raw.indexOf("ic_league_laliga") >= 0 || str.indexOf("tây ban nha") >= 0) return "assets/leagues/ic_league_laliga.png";
        if (lg === "SERIE_A" || raw.indexOf("ic_league_seriea") >= 0 || str.indexOf("serie") >= 0 || str.indexOf(" ý ") >= 0) return "assets/leagues/ic_league_seriea.png";
        if (lg === "BUNDESLIGA" || raw.indexOf("ic_league_bundesliga") >= 0 || str.indexOf("bundesliga") >= 0 || str.indexOf("đức") >= 0) return "assets/leagues/ic_league_bundesliga.png";
        if (lg === "LIGUE1" || raw.indexOf("ic_league_ligue1") >= 0 || str.indexOf("ligue") >= 0 || str.indexOf("pháp") >= 0) return "assets/leagues/ic_league_ligue1.png";
        if (lg === "VLEAGUE" || raw.indexOf("ic_league_vleague") >= 0 || str.indexOf("vleague") >= 0 || str.indexOf("việt nam") >= 0) return "assets/leagues/ic_league_vleague.png";
        if (lg === "NBA" || raw.indexOf("ic_league_nba") >= 0 || str.indexOf("bóng rổ") >= 0 || str.indexOf("basketball") >= 0) return "assets/leagues/ic_league_nba.png";
        if (lg === "TENNIS" || raw.indexOf("ic_league_tennis") >= 0 || str.indexOf("quần vợt") >= 0 || str.indexOf("atp") >= 0 || str.indexOf("wimbledon") >= 0) return "assets/leagues/ic_league_tennis.png";
        if (lg === "F1" || raw.indexOf("ic_league_f1") >= 0 || str.indexOf("đua xe") >= 0 || str.indexOf("formula") >= 0) return "assets/leagues/ic_league_f1.png";

        if (rawLogo && rawLogo.indexOf("http") === 0) return rawLogo;
        return "assets/leagues/ic_nav_sports.png";
    }

    function loadAllData() {
        showSpinner("Đang tải dữ liệu...");
        loadEpgData();
        loadTvPlaylist(function () {
            loadSportsData(function () {
                hideSpinner();
                switchAppMode(currentAppMode);

                var lastChannelUrl = localStorage.getItem('tvplay_last_url');
                var startCh = null;
                if (lastChannelUrl) {
                    startCh = tvChannels.find(function (c) { return c.url === lastChannelUrl; });
                }
                if (!startCh && tvChannels.length > 0) {
                    startCh = tvChannels[0];
                }
                if (startCh) {
                    playChannel(startCh);
                }
            });
        });
    }

    function getActivePlaylistUrl() {
        var activePl = playlists.find(function (p) { return p.id === activePlaylistId; });
        return activePl ? activePl.url : DEFAULT_M3U_URL;
    }

    function loadTvPlaylist(callback) {
        var activeUrl = getActivePlaylistUrl();
        var urlsToTry = [];
        if (activeUrl) {
            urlsToTry.push(activeUrl);
            if (activeUrl.indexOf('raw.githubusercontent.com') >= 0) {
                urlsToTry.push(activeUrl.replace('https://raw.githubusercontent.com/', 'https://ghproxy.net/https://raw.githubusercontent.com/'));
                urlsToTry.push(activeUrl.replace('https://raw.githubusercontent.com/', 'https://ghfast.top/https://raw.githubusercontent.com/'));
                urlsToTry.push(activeUrl.replace('https://raw.githubusercontent.com/', 'https://raw.gitmirror.com/'));
            }
        }
        urlsToTry.push("https://fastly.jsdelivr.net/gh/ndkhoams/iptv@main/ktv.m3u");

        function tryFetch(idx) {
            if (idx >= urlsToTry.length) {
                if (callback) callback();
                return;
            }
            var xhr = new XMLHttpRequest();
            xhr.open('GET', urlsToTry[idx], true);
            xhr.timeout = 10000;
            xhr.onload = function () {
                if (xhr.status >= 200 && xhr.status < 300 && xhr.responseText.length > 50) {
                    tvChannels = parseM3U(xhr.responseText);
                    buildTvCategories();
                    if (callback) callback();
                } else {
                    tryFetch(idx + 1);
                }
            };
            xhr.onerror = function () { tryFetch(idx + 1); };
            xhr.ontimeout = function () { tryFetch(idx + 1); };
            xhr.send();
        }
        tryFetch(0);
    }

    function loadSportsData(callback) {
        function tryFetch(idx) {
            if (idx >= SPORTS_DATA_URLS.length) {
                if (callback) callback();
                return;
            }
            var xhr = new XMLHttpRequest();
            xhr.open('GET', SPORTS_DATA_URLS[idx], true);
            xhr.timeout = 8000;
            xhr.onload = function () {
                if (xhr.status >= 200 && xhr.status < 300) {
                    try {
                        var json = JSON.parse(xhr.responseText);
                        parseSportsData(json);
                    } catch (e) {}
                    if (callback) callback();
                } else {
                    tryFetch(idx + 1);
                }
            };
            xhr.onerror = function () { tryFetch(idx + 1); };
            xhr.ontimeout = function () { tryFetch(idx + 1); };
            xhr.send();
        }
        tryFetch(0);
    }

    function parseM3U(content) {
        var lines = content.split(/\r?\n/);
        var channels = [];
        var currentLogo = "";
        var currentGroup = "Tất cả";
        var currentName = "";
        var channelNum = 1;

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i].trim();
            if (!line) continue;

            if (line.toUpperCase().indexOf("#EXTINF:") === 0) {
                currentGroup = "Tất cả";
                currentLogo = "icon.png";
                currentName = "";
                var currentTvgId = "";

                var idMatch = line.match(/tvg-id="([^"]*)"/i);
                if (idMatch && idMatch[1].trim()) currentTvgId = idMatch[1].trim();

                var gMatch = line.match(/group-title="([^"]*)"/i);
                if (gMatch && gMatch[1].trim()) currentGroup = gMatch[1].trim();

                var lMatch = line.match(/tvg-logo="([^"]*)"/i);
                if (lMatch && lMatch[1].trim()) currentLogo = lMatch[1].trim();

                var commaIdx = line.lastIndexOf(',');
                if (commaIdx !== -1 && commaIdx < line.length - 1) {
                    currentName = line.substring(commaIdx + 1).trim();
                }
                var nMatch = line.match(/tvg-name="([^"]*)"/i);
                if (!currentName && nMatch && nMatch[1].trim()) currentName = nMatch[1].trim();
            } else if (line.toUpperCase().indexOf("#EXTGRP:") === 0) {
                var grp = line.substring(8).trim();
                if (grp) currentGroup = grp;
            } else if (line.indexOf("#") !== 0) {
                var streamUrl = line.trim();
                if (streamUrl.indexOf("http://") === 0 || streamUrl.indexOf("https://") === 0 || streamUrl.indexOf("rtp://") === 0 || streamUrl.indexOf("udp://") === 0) {
                    channels.push({
                        id: "tv_" + channelNum,
                        number: channelNum,
                        name: currentName || ("Kênh " + channelNum),
                        group: currentGroup,
                        logo: currentLogo,
                        url: streamUrl,
                        tvgId: currentTvgId || "",
                        isMatch: false
                    });
                    channelNum++;
                }
                currentName = "";
                currentLogo = "icon.png";
                currentGroup = "Tất cả";
            }
        }
        return channels;
    }

    function buildTvCategories() {
        var groupMap = {};
        tvChannels.forEach(function (ch) {
            if (!groupMap[ch.group]) groupMap[ch.group] = [];
            groupMap[ch.group].push(ch);
        });

        tvCategories = [];
        tvCategories.push({ name: "Tất cả kênh", channels: tvChannels });

        var priority = ["Truyền hình Quốc gia", "VTV", "VTC", "HTV", "HTVC", "Sự Kiện VTVPrime", "VTVcab", "THỂ THAO QUỐC TẾ", "Thể Thao", "Giải Trí", "Phim truyện", "Thiếu nhi", "Âm nhạc", "Địa phương"];
        var added = {};

        priority.forEach(function (pName) {
            for (var key in groupMap) {
                if (!added[key] && key.toLowerCase().indexOf(pName.toLowerCase()) >= 0) {
                    tvCategories.push({ name: key, channels: groupMap[key] });
                    added[key] = true;
                }
            }
        });

        for (var key in groupMap) {
            if (!added[key] && key !== "Tất cả") {
                tvCategories.push({ name: key, channels: groupMap[key] });
                added[key] = true;
            }
        }
    }

    function getMatchTimestamp(ev) {
        if (!ev) return 0;
        if (ev.timestamp && typeof ev.timestamp === 'number' && ev.timestamp > 0) {
            return ev.timestamp;
        }
        var now = new Date();
        var curYear = now.getFullYear();
        var curMonth = now.getMonth();
        var curDay = now.getDate();
        var curHours = 12, curMinutes = 0;

        var timeStr = ev.time || "";
        var tMatch = timeStr.match(/(\d{1,2}):(\d{2})/);
        if (!tMatch && (ev.name || ev.displayTitle)) {
            tMatch = (ev.name || ev.displayTitle).match(/\b(\d{1,2}):(\d{2})\b/);
        }
        if (tMatch) {
            curHours = parseInt(tMatch[1], 10);
            curMinutes = parseInt(tMatch[2], 10);
        }

        var dateStr = ev.date || "";
        var dMatch = dateStr.match(/(\d{1,2})[\/\-](\d{1,2})/);
        if (!dMatch && (ev.name || ev.displayTitle)) {
            dMatch = (ev.name || ev.displayTitle).match(/\b(\d{1,2})[\/\-](\d{1,2})\b/);
        }
        if (dMatch) {
            curDay = parseInt(dMatch[1], 10);
            curMonth = parseInt(dMatch[2], 10) - 1;
        }

        return new Date(curYear, curMonth, curDay, curHours, curMinutes, 0, 0).getTime();
    }

    function canonicalizeTeamName(name) {
        if (!name) return "";
        return name.toLowerCase()
            .replace(/\b(fc|cf|sc|clb|club|united|utd|city|athletic|atletico|real|sporting|inter|ac)\b/gi, " ")
            .replace(/[^a-z0-9\u00C0-\u1EF9]/gi, " ")
            .replace(/\s+/g, " ")
            .trim();
    }

    function extractTeamsFromText(text) {
        if (!text) return { home: "", away: "" };
        var clean = text
            .replace(/\[\d{1,2}:\d{2}(\s+\d{1,2}\/\d{1,2})?\]/g, "")
            .replace(/\(\s*blv[^\)]*\)/gi, "")
            .replace(/\[\s*blv[^\]]*\]/gi, "")
            .replace(/\b(blv\s+[^\-\–\|]+)/gi, "")
            .replace(/\b(hls|sd|hd|fhd|4k|raw)\b/gi, "")
            .replace(/[\(\[\{][^\)\]\}]*[\)\]\}]/g, "")
            .replace(/•.*$/g, "")
            .replace(/[-|].*$/g, "")
            .trim();
        var parts = clean.split(/\s+(?:vs|Vs|VS|-)\s+/);
        if (parts.length >= 2) {
            return { home: parts[0].trim(), away: parts[1].trim() };
        }
        return { home: clean, away: "" };
    }

    function findFixtureForMatch(ev) {
        if (!sportsFixtures || sportsFixtures.length === 0 || !ev) return null;
        var home = ev.homeTeam || "";
        var away = ev.awayTeam || "";
        if (!home && (ev.name || ev.displayTitle)) {
            var extracted = extractTeamsFromText(ev.displayTitle || ev.name);
            home = extracted.home;
            away = extracted.away;
        }
        if (!home) return null;

        var hLow = home.toLowerCase();
        var aLow = (away || "").toLowerCase();
        var cH = canonicalizeTeamName(home);
        var cA = canonicalizeTeamName(away);

        for (var i = 0; i < sportsFixtures.length; i++) {
            var fx = sportsFixtures[i];
            var f1 = (fx.home_team || "").toLowerCase();
            var f2 = (fx.away_team || "").toLowerCase();
            var cf1 = canonicalizeTeamName(fx.home_team);
            var cf2 = canonicalizeTeamName(fx.away_team);

            var m1 = (hLow.indexOf(f1) >= 0 || f1.indexOf(hLow) >= 0 || (cH.length >= 3 && cf1.length >= 3 && (cH.indexOf(cf1) >= 0 || cf1.indexOf(cH) >= 0))) &&
                     (!aLow || aLow.indexOf(f2) >= 0 || f2.indexOf(aLow) >= 0 || (cA.length >= 3 && cf2.length >= 3 && (cA.indexOf(cf2) >= 0 || cf2.indexOf(cA) >= 0)));
            var m2 = (hLow.indexOf(f2) >= 0 || f2.indexOf(hLow) >= 0 || (cH.length >= 3 && cf2.length >= 3 && (cH.indexOf(cf2) >= 0 || cf2.indexOf(cH) >= 0))) &&
                     (!aLow || aLow.indexOf(f1) >= 0 || f1.indexOf(aLow) >= 0 || (cA.length >= 3 && cf1.length >= 3 && (cA.indexOf(cf1) >= 0 || cf1.indexOf(cA) >= 0)));
            if (m1 || m2) return fx;
        }
        return null;
    }

    function isMatchFinished(ev) {
        if (!ev) return false;

        // 1. Kiểm tra từ khóa FT trực tiếp trên tiêu đề
        var title = ((ev.name || "") + " " + (ev.displayTitle || "")).toUpperCase();
        if (title.indexOf(" FT") >= 0 || title.indexOf("(FT)") >= 0 || title.indexOf("[FT]") >= 0 ||
            title.indexOf("FINAL") >= 0 || title.indexOf("KẾT THÚC") >= 0) {
            return true;
        }
        var timeStr = (ev.time || "").toUpperCase();
        if (timeStr === "FT" || timeStr === "FINAL" || timeStr === "KẾT THÚC") {
            return true;
        }

        // 2. Kiểm tra trên API các nguồn xem tỉ số (sportsFixtures)
        var fx = findFixtureForMatch(ev);
        if (fx) {
            var detail = (fx.detail || "").toUpperCase();
            if (fx.is_completed === true || fx.state === "post" ||
                detail.indexOf("FT") >= 0 || detail.indexOf("FINAL") >= 0 || detail.indexOf("AET") >= 0 || detail.indexOf("AP") >= 0) {
                return true;
            }
            if (fx.state === "in" || detail.indexOf("'") >= 0 || detail.indexOf("HT") >= 0) {
                return false;
            }
        } else {
            // Không có trên API tỉ số: Dùng mốc fallback 140 phút sau khi bắt đầu
            if (ev.isCompleted === true) return true;
            var ts = getMatchTimestamp(ev);
            if (ts > 0) {
                var now = Date.now();
                if (now - ts > 140 * 60 * 1000) {
                    return true;
                }
            }
        }

        return ev.isCompleted === true;
    }

    function isMatchOngoing(ev) {
        if (!ev) return false;
        if (isMatchFinished(ev)) return false;

        var timeStr = (ev.time || "").toUpperCase();
        var title = ((ev.name || "") + " " + (ev.displayTitle || "")).toUpperCase();
        if (title.indexOf(" TRỰC TIẾP") >= 0 || title.indexOf("LIVE NOW") >= 0) return true;

        var ts = getMatchTimestamp(ev);
        var now = Date.now();

        // Kiểm tra trên API tỉ số
        var fx = findFixtureForMatch(ev);
        if (fx) {
            var detail = (fx.detail || "").toUpperCase();
            if (fx.state === "in" || detail.indexOf("'") >= 0 || detail.indexOf("HT") >= 0) {
                return true;
            }
            if (fx.state === "pre") {
                // Trận đấu diễn ra trong khoảng từ 15 phút trước giờ thi đấu
                return ts > 0 && (now >= ts - 15 * 60 * 1000);
            }
        }

        if (timeStr === "LIVE") return true;

        // Không có trên API tỉ số: bắt đầu từ 15 phút trước giờ thi đấu, kết thúc sau 140 phút
        if (ts > 0) {
            return (now >= ts - 15 * 60 * 1000) && (now - ts <= 140 * 60 * 1000);
        }

        return ev.isOngoing === true;
    }

    function parseSportsData(sportsData) {
        if (!sportsData) return;
        sportsFixtures = sportsData.fixtures || [];
        sportsLiveMatches = [];
        sportsCategories = [];

        // 1. live_events: Mỗi trận đấu chỉ hiển thị DUY NHẤT 1 DÒNG với LOGO GIẢI ĐẤU
        if (sportsData.live_events && sportsData.live_events.length > 0) {
            // Lọc bỏ toàn bộ các trận đấu đã kết thúc (FT) như trên Android TV
            var activeEvents = sportsData.live_events.filter(function (ev) {
                return !isMatchFinished(ev);
            });

            // Sắp xếp: Trận đang diễn ra (LIVE NOW) lên đầu, tiếp theo là trận sắp diễn ra theo thời gian
            activeEvents.sort(function (a, b) {
                var aOngoing = isMatchOngoing(a);
                var bOngoing = isMatchOngoing(b);
                if (aOngoing && !bOngoing) return -1;
                if (!aOngoing && bOngoing) return 1;
                return getMatchTimestamp(a) - getMatchTimestamp(b);
            });

            activeEvents.forEach(function (ev, evIdx) {
                var ongoing = isMatchOngoing(ev);
                var lg = (ev.league || "").toUpperCase();
                var sportIcon = (lg === "F1") ? "🏎️" : ((lg === "TENNIS") ? "🎾" : ((lg === "NBA") ? "🏀" : (ongoing ? "🔴" : "⚽")));

                var cleanTeams = (ev.homeTeam + (ev.awayTeam ? " vs " + ev.awayTeam : "")).trim();
                var timePart = ev.date ? ("[" + ev.time + " " + ev.date + "]") : ("[" + ev.time + "]");
                var displayName = sportIcon + " " + timePart + " " + cleanTeams;

                var leagueLogo = resolveLeagueLogo(ev.logo, ev.league, ev.leagueName, cleanTeams);
                var sourcesList = [];

                if (ev.sources && ev.sources.length > 0) {
                    ev.sources.forEach(function (src, sIdx) {
                        sourcesList.push({
                            id: "src_" + evIdx + "_" + sIdx,
                            name: src.name,
                            url: src.url,
                            logo: src.logo || leagueLogo
                        });
                    });
                }

                sportsLiveMatches.push({
                    id: "match_" + evIdx,
                    number: evIdx + 1,
                    name: displayName,
                    group: "⚽ LIVE EVENTS",
                    logo: leagueLogo,
                    url: (sourcesList.length > 0) ? sourcesList[0].url : "",
                    isMatch: true,
                    matchData: ev,
                    sources: sourcesList
                });
            });

            sportsCategories.push({
                name: "⚽ LIVE EVENTS",
                channels: sportsLiveMatches
            });
        }

        // 2. source_groups: Các kênh theo nguồn thể thao (Chuối Chiên, Gà Vàng...)
        if (sportsData.source_groups && sportsData.source_groups.length > 0) {
            sportsData.source_groups.forEach(function (sg) {
                if (sg.channels && sg.channels.length > 0) {
                    var chList = [];
                    sg.channels.forEach(function (sc, scIdx) {
                        chList.push({
                            id: "sg_" + sg.name + "_" + scIdx,
                            number: scIdx + 1,
                            name: sc.name,
                            group: sg.name,
                            logo: (sc.logo && sc.logo.indexOf("http") === 0) ? sc.logo : resolveLeagueLogo(sc.logo, "", sg.name, sc.name),
                            url: sc.url,
                            isMatch: false
                        });
                    });
                    sportsCategories.push({
                        name: sg.name,
                        channels: chList
                    });
                }
            });
        }
    }

    function buildFavoritesCategories() {
        var favChannels = [];
        tvChannels.forEach(function (ch) {
            if (favoriteUrls.indexOf(ch.url) >= 0) favChannels.push(ch);
        });
        sportsLiveMatches.forEach(function (m) {
            if (favoriteUrls.indexOf(m.url) >= 0) favChannels.push(m);
        });

        categories = [{
            name: "★ Kênh Yêu Thích (" + favChannels.length + ")",
            channels: favChannels
        }];
    }

    // ==========================================
    // 6. CHUYỂN ĐỔI CHẾ ĐỘ ỨNG DỤNG (APP MODES)
    // ==========================================
    function switchAppMode(mode) {
        currentAppMode = mode;
        selectedSidebarIdx = (mode === APP_MODE_FAVORITES) ? 0 : (mode === APP_MODE_TV) ? 1 : 2;

        sidebarBtns.forEach(function (btn, idx) {
            if (btn) {
                if (idx === selectedSidebarIdx) btn.classList.add('active');
                else btn.classList.remove('active');
            }
        });

        if (mode === APP_MODE_TV) {
            categories = tvCategories;
            if (colCategoriesHeaderEl) colCategoriesHeaderEl.textContent = "DANH MỤC TV";
        } else if (mode === APP_MODE_SPORTS) {
            categories = sportsCategories;
            if (colCategoriesHeaderEl) colCategoriesHeaderEl.textContent = "DANH MỤC THỂ THAO";
        } else if (mode === APP_MODE_FAVORITES) {
            buildFavoritesCategories();
            if (colCategoriesHeaderEl) colCategoriesHeaderEl.textContent = "KÊNH YÊU THÍCH";
        }

        selectedCatIdx = 0;
        selectedChIdx = 0;
        selectedSpotlightSourceIdx = 0;
        renderCategories();
        renderChannels();
    }

    // ==========================================
    // 7. RENDER GIAO DIỆN (CATEGORIES, CHANNELS, SPOTLIGHT)
    // ==========================================
    function renderCategories() {
        if (!categoriesListEl) return;
        categoriesListEl.innerHTML = "";

        categories.forEach(function (cat, idx) {
            var li = document.createElement('li');
            li.className = 'cat-item' + (idx === selectedCatIdx ? ' selected' : '');
            li.dataset.index = idx;

            var titleSpan = document.createElement('span');
            titleSpan.className = 'cat-item-name';
            titleSpan.textContent = cat.name;

            var countSpan = document.createElement('span');
            countSpan.className = 'cat-item-count';
            countSpan.textContent = cat.channels.length;

            li.appendChild(titleSpan);
            li.appendChild(countSpan);

            li.addEventListener('click', function () {
                selectedCatIdx = idx;
                focusedColumn = 'categories';
                updateFocusView();
                renderChannels();
            });

            categoriesListEl.appendChild(li);
        });
    }

    function renderChannels() {
        if (!channelsListEl) return;
        channelsListEl.innerHTML = "";

        var currentCat = categories[selectedCatIdx] || categories[0];
        if (!currentCat) return;

        if (colChannelsTitleEl) {
            colChannelsTitleEl.textContent = currentCat.name.toUpperCase();
        }
        if (colChannelsCountEl) {
            colChannelsCountEl.textContent = currentCat.channels.length + " mục";
        }

        if (selectedChIdx >= currentCat.channels.length) {
            selectedChIdx = 0;
        }

        currentCat.channels.forEach(function (ch, idx) {
            var li = document.createElement('li');
            var isPlaying = (currentPlayingChannel && currentPlayingChannel.url === ch.url) ||
                            (currentPlayingMatch && currentPlayingMatch.id === ch.id);
            li.className = 'ch-card' + (isPlaying ? ' playing' : '');
            li.dataset.index = idx;

            var numSpan = document.createElement('span');
            numSpan.className = 'ch-card-num';
            numSpan.textContent = pad2(ch.number);

            var img = document.createElement('img');
            img.className = 'ch-card-logo';
            img.src = ch.logo || ((currentAppMode === APP_MODE_SPORTS) ? 'assets/leagues/ic_nav_sports.png' : 'icon.png');
            img.onerror = function () { this.src = (currentAppMode === APP_MODE_SPORTS) ? 'assets/leagues/ic_nav_sports.png' : 'icon.png'; };

            var nameSpan = document.createElement('span');
            nameSpan.className = 'ch-card-name';
            nameSpan.textContent = ch.name;

            li.appendChild(numSpan);
            li.appendChild(img);
            li.appendChild(nameSpan);

            if (ch.isMatch && ch.sources && ch.sources.length > 0) {
                var badgeSources = document.createElement('span');
                badgeSources.className = 'badge-sources-count';
                badgeSources.textContent = ch.sources.length + " nguồn";
                li.appendChild(badgeSources);
            } else {
                var badgeFhd = document.createElement('span');
                badgeFhd.className = 'badge-fhd';
                badgeFhd.textContent = 'FHD';
                li.appendChild(badgeFhd);
            }

            if (isPlaying) {
                var badgePlaying = document.createElement('span');
                badgePlaying.className = 'badge-live-playing';
                badgePlaying.textContent = 'ĐANG PHÁT';
                li.appendChild(badgePlaying);
            }

            if (favoriteUrls.indexOf(ch.url) >= 0) {
                var star = document.createElement('span');
                star.className = 'star-fav';
                star.textContent = '★';
                li.appendChild(star);
            }

            li.addEventListener('click', function () {
                selectedChIdx = idx;
                focusedColumn = 'channels';
                updateFocusView();
                handleChannelClick(ch);
            });

            channelsListEl.appendChild(li);
        });

        updateFocusView();
        updateSpotlight();
    }

    function handleChannelClick(ch) {
        if (!ch) return;
        if (ch.isMatch && ch.sources && ch.sources.length > 0) {
            openMatchSourcesModal(ch);
        } else {
            playChannel(ch);
            closeOverlay();
        }
    }

    function updateFocusView() {
        sidebarBtns.forEach(function (btn, idx) {
            if (btn) {
                if (focusedColumn === 'sidebar' && idx === selectedSidebarIdx) btn.classList.add('focused');
                else btn.classList.remove('focused');
            }
        });

        var catItems = categoriesListEl.querySelectorAll('.cat-item');
        catItems.forEach(function (el, idx) {
            if (idx === selectedCatIdx) {
                el.classList.add('selected');
                if (focusedColumn === 'categories') {
                    el.classList.add('focused');
                    el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
                } else {
                    el.classList.remove('focused');
                }
            } else {
                el.classList.remove('selected', 'focused');
            }
        });

        var chItems = channelsListEl.querySelectorAll('.ch-card');
        chItems.forEach(function (el, idx) {
            if (idx === selectedChIdx && (focusedColumn === 'channels' || focusedColumn === 'spotlight')) {
                if (focusedColumn === 'channels') el.classList.add('focused');
                else el.classList.remove('focused');
                el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
            } else {
                el.classList.remove('focused');
            }
        });

        // Spotlight sources focus
        if (spotlightEpgContentEl) {
            var chips = spotlightEpgContentEl.querySelectorAll('.match-source-chip');
            chips.forEach(function (chip, idx) {
                if (focusedColumn === 'spotlight' && idx === selectedSpotlightSourceIdx) {
                    chip.classList.add('focused');
                    chip.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
                } else {
                    chip.classList.remove('focused');
                }
            });
        }
    }

    function formatMatchSourceTitle(source, match) {
        if (!source) return "Luồng phát";
        var raw = (typeof source === "string") ? source : (source.name || "");
        var group = (source && source.group) || "";

        // 1. Tìm thông tin BLV trong ngoặc đơn (BLV ...) hoặc (Tên BLV)
        var blv = "";
        var blvMatch = raw.match(/\((?:BLV\s+)?([^\)]+)\)/i);
        if (blvMatch) {
            blv = blvMatch[1].trim();
            blv = blv.replace(/\b(hls|fhd|hd|sd|geo)\b/gi, '').trim();
        }

        // 2. Tìm thông tin định dạng [hls], [geo], [FHD], v.v.
        var formatTag = "";
        var fmtMatch = raw.match(/\[([^\]]+)\]/g);
        if (fmtMatch) {
            fmtMatch.forEach(function (tag) {
                var inner = tag.replace(/[\[\]]/g, '').trim().toLowerCase();
                if (!inner.match(/\d{1,2}:\d{2}/)) {
                    formatTag += " [" + inner.toUpperCase() + "]";
                }
            });
        }

        // 3. Tìm tên nguồn kênh phát (Chuối Chiên, Gà Vàng, Cola TV, Bia Ôm, ...)
        var sourceName = "";
        var sourceMap = [
            { name: "Chuối Chiên", icon: "🍌 Chuối Chiên" },
            { name: "Gà Vàng", icon: "🐔 Gà Vàng" },
            { name: "Xôi Lạc", icon: "🍚 Xôi Lạc" },
            { name: "Cà Khịa", icon: "🌶️ Cà Khịa" },
            { name: "Bia Ôm", icon: "🍺 Bia Ôm" },
            { name: "S8", icon: "🎱 S8 TV" },
            { name: "Cola", icon: "🥤 Cola TV" },
            { name: "Phá Làng", icon: "💥 Phá Làng" },
            { name: "Vua Sân Cỏ", icon: "👑 Vua Sân Cỏ" },
            { name: "Khô Gà", icon: "🍗 Khô Gà" },
            { name: "Giờ Vàng", icon: "⏰ Giờ Vàng" },
            { name: "Sao Kê", icon: "📜 Sao Kê" },
            { name: "Xôi Gấc", icon: "🍧 Xôi Gấc" }
        ];

        for (var i = 0; i < sourceMap.length; i++) {
            var sm = sourceMap[i];
            if (raw.indexOf(sm.name) >= 0 || group.indexOf(sm.name) >= 0) {
                sourceName = sm.icon;
                break;
            }
        }

        if (!sourceName) {
            var dashMatch = raw.match(/-\s*([^\(\[\-]+)$/);
            if (dashMatch && dashMatch[1].trim()) {
                sourceName = dashMatch[1].trim();
            }
        }

        if (blv.toLowerCase() === "nhà đài" || raw.toLowerCase().indexOf("nhà đài") >= 0) {
            sourceName = "📺 Nguồn Nhà Đài";
            blv = "";
        }

        var result = "";
        if (sourceName && blv) {
            var blvText = blv.toLowerCase().indexOf("blv") === 0 ? blv : ("BLV " + blv);
            result = sourceName + " • " + blvText;
        } else if (sourceName) {
            result = sourceName;
        } else if (blv) {
            var blvText2 = blv.toLowerCase().indexOf("blv") === 0 ? blv : ("BLV " + blv);
            result = "🎙️ " + blvText2;
        } else {
            var cleaned = raw.replace(/\[\d{1,2}:\d{2}(?:\s+\d{1,2}[\/\-]\d{1,2})?\]/g, '')
                             .replace(/\b\d{1,2}:\d{2}(?:\s+\d{1,2}[\/\-]\d{1,2})?\b/g, '')
                             .replace(/⚽|🔴|🏀|🎾|🏎️/g, '')
                             .trim();
            if (match && match.homeTeam && match.awayTeam) {
                cleaned = cleaned.replace(new RegExp(match.homeTeam, "gi"), "")
                                 .replace(new RegExp(match.awayTeam, "gi"), "")
                                 .replace(/(?:vs|Vs|VS|-)/g, "")
                                 .trim();
            }
            result = cleaned ? cleaned : (source.name || "Nguồn trực tiếp");
        }

        if (formatTag) {
            result += formatTag;
        }

        return result.trim();
    }

    function formatMatchSourceTitle(source, match) {
        if (!source) return "Luồng phát";
        var raw = (typeof source === "string") ? source : (source.name || "");
        var group = (source && source.group) || "";

        // 1. Tìm thông tin BLV trong ngoặc đơn (BLV ...) hoặc (Tên BLV)
        var blv = "";
        var blvMatch = raw.match(/\((?:BLV\s+)?([^\)]+)\)/i);
        if (blvMatch) {
            blv = blvMatch[1].trim();
            blv = blv.replace(/\b(hls|fhd|hd|sd|geo)\b/gi, '').trim();
        }

        // 2. Tìm thông tin định dạng [hls], [geo], [FHD], v.v.
        var formatTag = "";
        var fmtMatch = raw.match(/\[([^\]]+)\]/g);
        if (fmtMatch) {
            fmtMatch.forEach(function (tag) {
                var inner = tag.replace(/[\[\]]/g, '').trim().toLowerCase();
                if (!inner.match(/\d{1,2}:\d{2}/)) {
                    formatTag += " [" + inner.toUpperCase() + "]";
                }
            });
        }

        // 3. Tìm tên nguồn kênh phát (Chuối Chiên, Gà Vàng, Cola TV, Bia Ôm, ...)
        var sourceName = "";
        var sourceMap = [
            { name: "Chuối Chiên", icon: "🍌 Chuối Chiên" },
            { name: "Gà Vàng", icon: "🐔 Gà Vàng" },
            { name: "Xôi Lạc", icon: "🍚 Xôi Lạc" },
            { name: "Cà Khịa", icon: "🌶️ Cà Khịa" },
            { name: "Bia Ôm", icon: "🍺 Bia Ôm" },
            { name: "S8", icon: "🎱 S8 TV" },
            { name: "Cola", icon: "🥤 Cola TV" },
            { name: "Phá Làng", icon: "💥 Phá Làng" },
            { name: "Vua Sân Cỏ", icon: "👑 Vua Sân Cỏ" },
            { name: "Khô Gà", icon: "🍗 Khô Gà" },
            { name: "Giờ Vàng", icon: "⏰ Giờ Vàng" },
            { name: "Sao Kê", icon: "📜 Sao Kê" },
            { name: "Xôi Gấc", icon: "🍧 Xôi Gấc" }
        ];

        for (var i = 0; i < sourceMap.length; i++) {
            var sm = sourceMap[i];
            if (raw.indexOf(sm.name) >= 0 || group.indexOf(sm.name) >= 0) {
                sourceName = sm.icon;
                break;
            }
        }

        if (!sourceName) {
            var dashMatch = raw.match(/-\s*([^\(\[\-]+)$/);
            if (dashMatch && dashMatch[1].trim()) {
                sourceName = dashMatch[1].trim();
            }
        }

        if (blv.toLowerCase() === "nhà đài" || raw.toLowerCase().indexOf("nhà đài") >= 0) {
            sourceName = "📺 Nguồn Nhà Đài";
            blv = "";
        }

        var result = "";
        if (sourceName && blv) {
            var blvText = blv.toLowerCase().indexOf("blv") === 0 ? blv : ("BLV " + blv);
            result = sourceName + " • " + blvText;
        } else if (sourceName) {
            result = sourceName;
        } else if (blv) {
            var blvText2 = blv.toLowerCase().indexOf("blv") === 0 ? blv : ("BLV " + blv);
            result = "🎙️ " + blvText2;
        } else {
            var cleaned = raw.replace(/\[\d{1,2}:\d{2}(?:\s+\d{1,2}[\/\-]\d{1,2})?\]/g, '')
                             .replace(/\b\d{1,2}:\d{2}(?:\s+\d{1,2}[\/\-]\d{1,2})?\b/g, '')
                             .replace(/⚽|🔴|🏀|🎾|🏎️/g, '')
                             .trim();
            if (match && match.homeTeam && match.awayTeam) {
                cleaned = cleaned.replace(new RegExp(match.homeTeam, "gi"), "")
                                 .replace(new RegExp(match.awayTeam, "gi"), "")
                                 .replace(/(?:vs|Vs|VS|-)/g, "")
                                 .trim();
            }
            result = cleaned ? cleaned : (source.name || "Nguồn trực tiếp");
        }

        if (formatTag) {
            result += formatTag;
        }

        return result.trim();
    }

    // ==========================================
    // XỬ LÝ LỊCH PHÁT SÓNG (EPG)
    // ==========================================
    function parseEpgTime(s) {
        if (!s || s.length < 14) return 0;
        var y = parseInt(s.substr(0, 4), 10);
        var m = parseInt(s.substr(4, 2), 10) - 1;
        var d = parseInt(s.substr(6, 2), 10);
        var h = parseInt(s.substr(8, 2), 10);
        var min = parseInt(s.substr(10, 2), 10);
        var sec = parseInt(s.substr(12, 2), 10);
        var tzMatch = s.match(/([+-])(\d{2}):?(\d{2})?/);
        if (tzMatch) {
            var sign = tzMatch[1] === '-' ? -1 : 1;
            var tzH = parseInt(tzMatch[2], 10) * sign;
            var tzM = (tzMatch[3] ? parseInt(tzMatch[3], 10) : 0) * sign;
            var totalOffsetMins = tzH * 60 + tzM;
            return Date.UTC(y, m, d, h, min, sec) - totalOffsetMins * 60 * 1000;
        }
        return new Date(y, m, d, h, min, sec).getTime();
    }

    function parseEpgXml(xmlText) {
        if (!xmlText) return;
        var progRegex = /<programme\s+start="([^"]+)"\s+stop="([^"]+)"\s+channel="([^"]+)"[^>]*>([\s\S]*?)<\/programme>/g;
        var match;
        var temp = {};
        while ((match = progRegex.exec(xmlText)) !== null) {
            var startStr = match[1];
            var stopStr = match[2];
            var channelId = match[3].toLowerCase();
            var inner = match[4];
            var titleMatch = /<title[^>]*>([\s\S]*?)<\/title>/.exec(inner);
            var title = titleMatch ? titleMatch[1].trim() : '';

            var startTs = parseEpgTime(startStr);
            var stopTs = parseEpgTime(stopStr);

            if (!temp[channelId]) temp[channelId] = [];
            temp[channelId].push({
                startTime: startTs,
                endTime: stopTs,
                title: title
            });
        }
        epgMap = temp;
    }

    function loadEpgData(force) {
        if (!force && (isEpgLoading || isEpgLoaded)) return;
        isEpgLoading = true;

        function tryFetch(idx) {
            if (idx >= EPG_URLS.length) {
                isEpgLoading = false;
                return;
            }
            var url = EPG_URLS[idx];
            var xhr = new XMLHttpRequest();
            xhr.open('GET', url, true);
            xhr.timeout = 15000;
            xhr.onload = function () {
                if (xhr.status >= 200 && xhr.status < 300 && xhr.responseText) {
                    try {
                        parseEpgXml(xhr.responseText);
                        isEpgLoaded = true;
                    } catch (e) {
                        console.error("Lỗi phân tích EPG XML:", e);
                    }
                    isEpgLoading = false;
                    if (isOverlayOpen) {
                        updateSpotlight();
                    }
                    if (currentPlayingChannel && !currentPlayingChannel.isMatch) {
                        updateQuickInfoBar(currentPlayingChannel);
                    }
                } else {
                    tryFetch(idx + 1);
                }
            };
            xhr.onerror = function () { tryFetch(idx + 1); };
            xhr.ontimeout = function () { tryFetch(idx + 1); };
            xhr.send();
        }

        tryFetch(0);
    }

    function normalizeChannelNameForEpg(name) {
        if (!name) return '';
        return name.toLowerCase()
            .replace(/[\s\-\_\(\)\[\]]/g, '')
            .replace(/8\.5mbps|8mbps|4mbps|fhd|4k|sd/gi, '')
            .replace(/hd/gi, '')
            .trim();
    }

    function getTimelineForChannel(ch) {
        if (!ch) return [];
        var tvgId = (ch.tvgId || "").toLowerCase().trim();
        if (tvgId) {
            if (epgMap[tvgId]) return epgMap[tvgId];
            if (epgMap[tvgId + "hd"]) return epgMap[tvgId + "hd"];
            if (tvgId.endsWith("hd") && epgMap[tvgId.replace(/hd$/, "")]) return epgMap[tvgId.replace(/hd$/, "")];
        }

        var norm = normalizeChannelNameForEpg(ch.name);
        if (!norm) return [];

        if (epgMap[norm]) return epgMap[norm];
        if (epgMap[norm + "hd"]) return epgMap[norm + "hd"];

        for (var k in epgMap) {
            var normK = normalizeChannelNameForEpg(k);
            if (normK === norm) {
                return epgMap[k];
            }
        }
        return [];
    }

    function getCurrentEpgProgram(ch) {
        if (!ch || ch.isMatch) return null;
        var list = getTimelineForChannel(ch);
        if (!list || list.length === 0) return null;
        var now = Date.now();
        for (var i = 0; i < list.length; i++) {
            if (now >= list[i].startTime && now < list[i].endTime) {
                return list[i];
            }
        }
        return null;
    }

    function formatEpgTime(ts) {
        if (!ts) return "--:--";
        var d = new Date(ts);
        return pad2(d.getHours()) + ":" + pad2(d.getMinutes());
    }

    function updateSpotlight() {
        var currentCat = categories[selectedCatIdx];
        if (!currentCat || !currentCat.channels || currentCat.channels.length === 0) return;
        var ch = currentCat.channels[selectedChIdx] || currentCat.channels[0];
        if (!ch) return;

        if (spotlightLogoEl) {
            spotlightLogoEl.src = ch.logo || ((currentAppMode === APP_MODE_SPORTS) ? 'assets/leagues/ic_nav_sports.png' : 'icon.png');
            spotlightLogoEl.onerror = function () { this.src = (currentAppMode === APP_MODE_SPORTS) ? 'assets/leagues/ic_nav_sports.png' : 'icon.png'; };
        }
        if (spotlightNameEl) spotlightNameEl.textContent = ch.name;
        if (spotlightCatTagEl) spotlightCatTagEl.textContent = ch.group || "Truyền Hình";

        var isPlaying = (currentPlayingChannel && currentPlayingChannel.url === ch.url) ||
                        (currentPlayingMatch && currentPlayingMatch.id === ch.id);
        if (spotlightPlayingTagEl) {
            if (isPlaying) spotlightPlayingTagEl.classList.remove('hidden');
            else spotlightPlayingTagEl.classList.add('hidden');
        }

        // Nhãn TRỰC TIẾP phía trên logo chỉ hiển thị khi trận đấu đang thực sự diễn ra (như trên ATV)
        var isLiveOngoing = false;
        if (ch.isMatch && ch.matchData) {
            isLiveOngoing = isMatchOngoing(ch.matchData);
        } else if (currentAppMode === APP_MODE_SPORTS) {
            isLiveOngoing = isMatchOngoing(ch);
        }
        if (spotlightLiveTagEl) {
            if (isLiveOngoing) spotlightLiveTagEl.classList.remove('hidden');
            else spotlightLiveTagEl.classList.add('hidden');
        }

        if (spotlightEpgContentEl) {
            spotlightEpgContentEl.innerHTML = "";
            var titleHeader = document.getElementById('epg-box-title');

            if (ch.isMatch && ch.matchData) {
                if (titleHeader) titleHeader.textContent = "CHI TIẾT TRẬN ĐẤU";
                var md = ch.matchData;
                var card = document.createElement('div');
                card.className = 'match-info-card';

                var leagueDiv = document.createElement('div');
                leagueDiv.className = 'match-league';
                leagueDiv.textContent = md.leagueName || md.league || "BÓNG ĐÁ TRỰC TIẾP";

                var timeBadge = document.createElement('div');
                timeBadge.className = 'match-time-badge';
                timeBadge.textContent = "⏰ Giờ đấu: " + (md.time || "Trực tiếp") + (md.date ? " (" + md.date + ")" : "");

                var srcTitle = document.createElement('div');
                srcTitle.className = 'match-sources-title';
                srcTitle.textContent = "Các nguồn phát (" + (ch.sources ? ch.sources.length : 0) + "): [Bấm ► để chọn]";

                card.appendChild(leagueDiv);
                card.appendChild(timeBadge);
                card.appendChild(srcTitle);

                if (ch.sources && ch.sources.length > 0) {
                    ch.sources.forEach(function (s, sIdx) {
                        var chip = document.createElement('div');
                        chip.className = 'match-source-chip' + (focusedColumn === 'spotlight' && sIdx === selectedSpotlightSourceIdx ? ' focused' : '');
                        chip.dataset.index = sIdx;

                        var iconSpan = document.createElement('span');
                        iconSpan.className = 'match-source-icon';
                        iconSpan.textContent = "▶";

                        var textSpan = document.createElement('span');
                        textSpan.className = 'match-source-text';
                        textSpan.textContent = formatMatchSourceTitle(s, ch.matchData);

                        chip.appendChild(iconSpan);
                        chip.appendChild(textSpan);

                        chip.addEventListener('click', function () {
                            playMatchSource(ch, s);
                        });
                        card.appendChild(chip);
                    });
                }
                spotlightEpgContentEl.appendChild(card);
            } else {
                if (titleHeader) titleHeader.textContent = "LỊCH PHÁT SÓNG (EPG)";
                var now = Date.now();
                var epgList = getTimelineForChannel(ch);

                if (epgList && epgList.length > 0) {
                    var activeProgs = epgList.filter(function (p) {
                        return p.endTime >= now - 15 * 60 * 1000;
                    }).slice(0, 10);

                    if (activeProgs.length > 0) {
                        activeProgs.forEach(function (prog) {
                            var item = document.createElement('div');
                            var isCurrent = (now >= prog.startTime && now < prog.endTime);
                            item.className = 'epg-item' + (isCurrent ? ' current' : '');

                            var timeSpan = document.createElement('div');
                            timeSpan.className = 'epg-time';
                            var timeStr = formatEpgTime(prog.startTime);
                            if (prog.endTime) {
                                timeStr += " - " + formatEpgTime(prog.endTime);
                            }
                            timeSpan.textContent = timeStr;

                            var nameSpan = document.createElement('div');
                            nameSpan.className = 'epg-name';
                            nameSpan.textContent = (isCurrent ? "● Đang phát: " : "") + prog.title;

                            item.appendChild(timeSpan);
                            item.appendChild(nameSpan);
                            spotlightEpgContentEl.appendChild(item);
                        });
                        return;
                    }
                }

                var emptyDiv = document.createElement('div');
                emptyDiv.className = 'epg-item';
                emptyDiv.style.color = '#888';
                emptyDiv.style.fontStyle = 'italic';
                emptyDiv.textContent = 'Chưa có thông tin lịch phát sóng cho kênh này';
                spotlightEpgContentEl.appendChild(emptyDiv);
            }
        }
    }

    // ==========================================
    // 8. HỘP THOẠI CHỌN NGUỒN PHÁT (MATCH SOURCES MODAL)
    // ==========================================
    function openMatchSourcesModal(matchItem) {
        if (!matchItem || !matchItem.sources || matchItem.sources.length === 0) return;
        currentSelectedMatch = matchItem;
        isMatchModalOpen = true;
        closeOverlay();

        if (modalMatchTitleEl) modalMatchTitleEl.textContent = matchItem.name;
        if (modalMatchSubtitleEl) {
            var md = matchItem.matchData;
            var sub = "⏰ " + (md && md.time ? md.time : "Trực tiếp") + " • " + matchItem.sources.length + " nguồn phát trực tiếp";
            modalMatchSubtitleEl.textContent = sub;
        }

        renderMatchSourcesList();
        if (matchModalEl) matchModalEl.classList.remove('hidden');
        setTimeout(updateMatchSourcesFocus, 50);
    }

    function closeMatchSourcesModal() {
        isMatchModalOpen = false;
        if (matchModalEl) matchModalEl.classList.add('hidden');
    }

    function renderMatchSourcesList() {
        if (!matchSourcesListEl || !currentSelectedMatch) return;
        matchSourcesListEl.innerHTML = "";

        // Tự động chọn nguồn đang phát nếu trận này đang phát
        selectedSourceIdx = 0;
        if (currentPlayingChannel && currentSelectedMatch.sources) {
            var curSrcIdx = currentSelectedMatch.sources.findIndex(function (s) {
                return s.url === currentPlayingChannel.url || (currentPlayingChannel.id && currentPlayingChannel.id.indexOf(s.id) >= 0);
            });
            if (curSrcIdx >= 0) selectedSourceIdx = curSrcIdx;
        }

        currentSelectedMatch.sources.forEach(function (src, idx) {
            var isCurrent = (currentPlayingChannel && (currentPlayingChannel.url === src.url || (currentPlayingChannel.id && currentPlayingChannel.id.indexOf(src.id) >= 0)));
            var li = document.createElement('li');
            li.className = 'source-item' + (idx === selectedSourceIdx ? ' focused' : '') + (isCurrent ? ' playing' : '');
            li.dataset.index = idx;

            var numSpan = document.createElement('span');
            numSpan.className = 'source-num';
            numSpan.textContent = pad2(idx + 1);

            var badgeSpan = document.createElement('span');
            badgeSpan.className = 'source-badge';
            var upper = src.name.toUpperCase();
            badgeSpan.textContent = upper.indexOf("4K") >= 0 ? "4K" : upper.indexOf("FHD") >= 0 ? "FHD" : "HD";

            var nameSpan = document.createElement('span');
            nameSpan.className = 'source-name';
            nameSpan.textContent = formatMatchSourceTitle(src, currentSelectedMatch ? currentSelectedMatch.matchData : null);

            li.appendChild(numSpan);
            li.appendChild(badgeSpan);
            li.appendChild(nameSpan);

            if (isCurrent) {
                var playingBadge = document.createElement('span');
                playingBadge.className = 'badge-live-playing';
                playingBadge.textContent = 'ĐANG PHÁT';
                li.appendChild(playingBadge);
            }

            li.addEventListener('click', function () {
                selectedSourceIdx = idx;
                playMatchSource(currentSelectedMatch, src);
            });

            matchSourcesListEl.appendChild(li);
        });
    }

    function updateMatchSourcesFocus() {
        var items = matchSourcesListEl.querySelectorAll('.source-item');
        items.forEach(function (el, idx) {
            if (idx === selectedSourceIdx) {
                el.classList.add('focused');
                el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
            } else {
                el.classList.remove('focused');
            }
        });
    }

    function playMatchSource(match, source) {
        currentPlayingMatch = match;
        currentAppMode = APP_MODE_SPORTS;
        var leagueName = (match.matchData && (match.matchData.leagueName || match.matchData.league)) ?
                         (match.matchData.leagueName || match.matchData.league) : "TRỰC TIẾP";
        var cleanLeague = leagueName.replace(/^[^\w\s\u00C0-\u1EF9]+/u, '').trim();

        var fakeChannel = {
            id: match.id + "_" + source.id,
            number: match.number,
            name: match.name,
            sourceName: source.name,
            group: source.name,
            matchLeague: cleanLeague || "TRỰC TIẾP",
            logo: match.logo || "assets/leagues/ic_nav_sports.png",
            url: source.url,
            isMatch: true,
            match: match
        };
        closeMatchSourcesModal();
        closeOverlay();
        playChannel(fakeChannel);
    }

    // ==========================================
    // 9. HỘP THOẠI CÀI ĐẶT & UDPXY & OTA UPDATE
    // ==========================================
    function openSettingsModal() {
        isSettingsModalOpen = true;
        if (udpxyInputEl) udpxyInputEl.value = udpxyServer;
        renderPlaylistsManageList();
        if (settingsModalEl) settingsModalEl.classList.remove('hidden');
        settingsFocusIdx = 0;
        setTimeout(updateSettingsModalFocus, 50);
    }

    function closeSettingsModal() {
        isSettingsModalOpen = false;
        if (settingsModalEl) settingsModalEl.classList.add('hidden');
    }

    function renderPlaylistsManageList() {
        if (!playlistsManageListEl) return;
        playlistsManageListEl.innerHTML = "";

        playlists.forEach(function (pl) {
            var div = document.createElement('div');
            div.className = 'playlist-entry' + (pl.id === activePlaylistId ? ' active' : '');

            var infoDiv = document.createElement('div');
            infoDiv.className = 'playlist-info';

            var nameDiv = document.createElement('div');
            nameDiv.className = 'playlist-name';
            nameDiv.textContent = pl.name + (pl.id === activePlaylistId ? " (Đang dùng)" : "");

            var urlDiv = document.createElement('div');
            urlDiv.className = 'playlist-url';
            urlDiv.textContent = pl.url;

            infoDiv.appendChild(nameDiv);
            infoDiv.appendChild(urlDiv);

            var btnGroup = document.createElement('div');
            btnGroup.className = 'btn-group';

            var actBtn = document.createElement('button');
            actBtn.className = 'action-btn';
            actBtn.textContent = pl.id === activePlaylistId ? "Đã chọn" : "Kích hoạt";
            actBtn.addEventListener('click', function () {
                activePlaylistId = pl.id;
                playlists.forEach(function (p) { p.isSelected = (p.id === pl.id); });
                savePlaylists();
                renderPlaylistsManageList();
                var badge = document.getElementById('header-playlist-badge');
                if (badge) badge.textContent = "Danh sách: " + pl.name;
                showToast("Đã kích hoạt: " + pl.name);
                loadTvPlaylist(function () {
                    if (currentAppMode === APP_MODE_TV) {
                        categories = tvCategories;
                        renderCategories();
                        renderChannels();
                    }
                });
            });
            btnGroup.appendChild(actBtn);

            // Nút Xóa danh sách (nếu không phải danh sách duy nhất)
            if (playlists.length > 1 && pl.id !== activePlaylistId) {
                var delBtn = document.createElement('button');
                delBtn.className = 'action-btn btn-danger';
                delBtn.textContent = "Xóa";
                delBtn.addEventListener('click', function () {
                    playlists = playlists.filter(function (p) { return p.id !== pl.id; });
                    savePlaylists();
                    renderPlaylistsManageList();
                    showToast("Đã xóa danh sách: " + pl.name);
                });
                btnGroup.appendChild(delBtn);
            }

            div.appendChild(infoDiv);
            div.appendChild(btnGroup);
            playlistsManageListEl.appendChild(div);
        });
    }

    function getSettingsFocusable() {
        if (!settingsModalEl) return [];
        return Array.from(settingsModalEl.querySelectorAll('input:not([disabled]), button:not([disabled])'));
    }

    function updateSettingsModalFocus() {
        var focusable = getSettingsFocusable();
        if (focusable.length === 0) return;
        if (settingsFocusIdx < 0) settingsFocusIdx = 0;
        if (settingsFocusIdx >= focusable.length) settingsFocusIdx = focusable.length - 1;

        focusable.forEach(function (el, idx) {
            if (idx === settingsFocusIdx) {
                el.classList.add('focused');
                el.focus();
                el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
            } else {
                el.classList.remove('focused');
            }
        });
    }

    function handleSettingsModalKeys(keyCode, e) {
        var focusable = getSettingsFocusable();
        if (focusable.length === 0) return;

        switch (keyCode) {
            case TIZEN_KEYS.KEY_UP:
                settingsFocusIdx = (settingsFocusIdx - 1 + focusable.length) % focusable.length;
                updateSettingsModalFocus();
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_DOWN:
                settingsFocusIdx = (settingsFocusIdx + 1) % focusable.length;
                updateSettingsModalFocus();
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_LEFT:
                if (settingsFocusIdx > 0) {
                    settingsFocusIdx--;
                    updateSettingsModalFocus();
                }
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_RIGHT:
                if (settingsFocusIdx < focusable.length - 1) {
                    settingsFocusIdx++;
                    updateSettingsModalFocus();
                }
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_ENTER:
            case TIZEN_KEYS.KEY_SPACE:
                var currentEl = focusable[settingsFocusIdx];
                if (currentEl) {
                    if (currentEl.tagName === 'BUTTON') {
                        currentEl.click();
                    } else if (currentEl.tagName === 'INPUT') {
                        currentEl.focus();
                    }
                }
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_RETURN:
            case TIZEN_KEYS.KEY_ESCAPE:
            case TIZEN_KEYS.KEY_BACKSPACE:
                closeSettingsModal();
                e.preventDefault();
                break;
        }
    }

    function compareVersions(v1, v2) {
        if (!v1 || !v2) return 0;
        var p1 = String(v1).replace(/^v/i, '').split('.').map(function (n) { return parseInt(n, 10) || 0; });
        var p2 = String(v2).replace(/^v/i, '').split('.').map(function (n) { return parseInt(n, 10) || 0; });
        var len = Math.max(p1.length, p2.length);
        for (var i = 0; i < len; i++) {
            var n1 = p1[i] || 0;
            var n2 = p2[i] || 0;
            if (n1 > n2) return 1;
            if (n1 < n2) return -1;
        }
        return 0;
    }

    function checkOtaUpdate(manual) {
        if (otaStatusTextEl) otaStatusTextEl.textContent = "Đang kiểm tra máy chủ cập nhật GitHub / CDN...";
        var serverResponded = false;

        function tryOta(idx) {
            if (idx >= OTA_VERSION_URLS.length) {
                if (serverResponded) {
                    if (otaStatusTextEl) otaStatusTextEl.textContent = "Bạn đang dùng phiên bản mới nhất (v" + APP_VERSION + ")";
                    if (manual) showToast("Ứng dụng đang ở phiên bản mới nhất (v" + APP_VERSION + ")!");
                } else {
                    if (otaStatusTextEl) otaStatusTextEl.textContent = "Lỗi kết nối mạng: Không thể kết nối máy chủ OTA!";
                    if (manual) showToast("Lỗi mạng: Không thể kết nối máy chủ cập nhật (kiểm tra WiFi/DNS)!");
                }
                return;
            }
            var xhr = new XMLHttpRequest();
            xhr.open('GET', OTA_VERSION_URLS[idx] + "?_ts=" + Date.now(), true);
            xhr.timeout = 7000;
            xhr.onload = function () {
                if (xhr.status >= 200 && xhr.status < 300) {
                    serverResponded = true;
                    try {
                        var ota = JSON.parse(xhr.responseText);
                        if (ota && ota.version && compareVersions(ota.version, APP_VERSION) > 0 && ota.bundleUrl) {
                            if (otaStatusTextEl) otaStatusTextEl.textContent = "Tìm thấy bản mới v" + ota.version + "! Đang tải mã nguồn...";
                            if (manual) showToast("Tìm thấy bản cập nhật mới v" + ota.version + "!");
                            downloadOtaBundle(ota.bundleUrl, ota.version);
                        } else {
                            if (otaStatusTextEl) otaStatusTextEl.textContent = "Bạn đang dùng phiên bản mới nhất (v" + APP_VERSION + ")";
                            if (manual) showToast("Ứng dụng đang ở phiên bản mới nhất (v" + APP_VERSION + ")!");
                        }
                    } catch (e) {
                        tryOta(idx + 1);
                    }
                } else if (xhr.status === 404) {
                    serverResponded = true;
                    tryOta(idx + 1);
                } else {
                    tryOta(idx + 1);
                }
            };
            xhr.onerror = function () { tryOta(idx + 1); };
            xhr.ontimeout = function () { tryOta(idx + 1); };
            xhr.send();
        }
        tryOta(0);
    }

    function downloadOtaBundle(bundleUrl, newVersion) {
        var urls = [];
        if (bundleUrl) {
            urls.push(bundleUrl);
            if (bundleUrl.indexOf('raw.githubusercontent.com') >= 0) {
                urls.push(bundleUrl.replace('https://raw.githubusercontent.com/ndkhoams/iptv/main/', 'https://fastly.jsdelivr.net/gh/ndkhoams/iptv@main/'));
                urls.push(bundleUrl.replace('https://raw.githubusercontent.com/ndkhoams/iptv/main/', 'https://ghproxy.net/https://raw.githubusercontent.com/ndkhoams/iptv/main/'));
                urls.push(bundleUrl.replace('https://raw.githubusercontent.com/ndkhoams/iptv/main/', 'https://ghfast.top/https://raw.githubusercontent.com/ndkhoams/iptv/main/'));
            } else if (bundleUrl.indexOf('fastly.jsdelivr.net') >= 0) {
                urls.push(bundleUrl.replace('fastly.jsdelivr.net', 'gcore.jsdelivr.net'));
                urls.push(bundleUrl.replace('fastly.jsdelivr.net', 'cdn.jsdelivr.net'));
            }
        }
        function tryDownload(dIdx) {
            if (dIdx >= urls.length) {
                if (otaStatusTextEl) otaStatusTextEl.textContent = "Lỗi tải file gói cập nhật từ máy chủ!";
                showToast("Lỗi mạng: Không thể tải mã nguồn bản cập nhật!");
                return;
            }
            var xhr = new XMLHttpRequest();
            xhr.open('GET', urls[dIdx] + "?_ts=" + Date.now(), true);
            xhr.timeout = 15000;
            xhr.onload = function () {
                if (xhr.status >= 200 && xhr.status < 300 && xhr.responseText.length > 500) {
                    localStorage.setItem('tvplay_tizen_ota_code', xhr.responseText);
                    localStorage.setItem('tvplay_tizen_ota_version', newVersion);
                    if (otaStatusTextEl) otaStatusTextEl.textContent = "🎉 Cập nhật thành công lên v" + newVersion + "! Đang khởi động lại...";
                    showToast("Cập nhật thành công lên v" + newVersion + "! Đang khởi động lại...");
                    setTimeout(function () {
                        location.reload();
                    }, 1500);
                } else {
                    tryDownload(dIdx + 1);
                }
            };
            xhr.onerror = function () { tryDownload(dIdx + 1); };
            xhr.ontimeout = function () { tryDownload(dIdx + 1); };
            xhr.send();
        }
        tryDownload(0);
    }

    // ==========================================
    // 9B. HỘP THOẠI CHỌN NHANH DANH SÁCH FILE M3U
    // ==========================================
    function openQuickPlaylistModal() {
        isQuickPlaylistModalOpen = true;
        closeOverlay();
        renderQuickPlaylists();
        if (quickPlaylistModalEl) quickPlaylistModalEl.classList.remove('hidden');
        var actIdx = playlists.findIndex(function (p) { return p.id === activePlaylistId; });
        quickPlaylistFocusIdx = (actIdx >= 0) ? actIdx : 0;
        setTimeout(updateQuickPlaylistFocus, 50);
    }

    function closeQuickPlaylistModal() {
        isQuickPlaylistModalOpen = false;
        if (quickPlaylistModalEl) quickPlaylistModalEl.classList.add('hidden');
    }

    function renderQuickPlaylists() {
        if (!quickPlaylistsListEl) return;
        quickPlaylistsListEl.innerHTML = "";

        playlists.forEach(function (pl, idx) {
            var li = document.createElement('li');
            var isActive = (pl.id === activePlaylistId);
            li.className = 'quick-playlist-item' + (isActive ? ' active' : '');
            li.dataset.index = idx;

            var radioSpan = document.createElement('span');
            radioSpan.className = 'quick-pl-radio';
            radioSpan.textContent = isActive ? '●' : '○';

            var infoDiv = document.createElement('div');
            infoDiv.className = 'quick-pl-info';

            var nameDiv = document.createElement('div');
            nameDiv.className = 'quick-pl-name';
            nameDiv.textContent = pl.name;

            var urlDiv = document.createElement('div');
            urlDiv.className = 'quick-pl-url';
            urlDiv.textContent = pl.url;

            infoDiv.appendChild(nameDiv);
            infoDiv.appendChild(urlDiv);

            li.appendChild(radioSpan);
            li.appendChild(infoDiv);

            if (isActive) {
                var badge = document.createElement('span');
                badge.className = 'quick-pl-badge';
                badge.textContent = 'ĐANG DÙNG';
                li.appendChild(badge);
            }

            li.addEventListener('click', function () {
                selectQuickPlaylist(pl);
            });

            quickPlaylistsListEl.appendChild(li);
        });
    }

    function getQuickPlaylistFocusable() {
        if (!quickPlaylistModalEl) return [];
        var items = Array.from(quickPlaylistModalEl.querySelectorAll('.quick-playlist-item'));
        var btns = Array.from(quickPlaylistModalEl.querySelectorAll('.quick-playlist-footer button'));
        return items.concat(btns);
    }

    function updateQuickPlaylistFocus() {
        var focusable = getQuickPlaylistFocusable();
        if (focusable.length === 0) return;
        if (quickPlaylistFocusIdx < 0) quickPlaylistFocusIdx = 0;
        if (quickPlaylistFocusIdx >= focusable.length) quickPlaylistFocusIdx = focusable.length - 1;

        focusable.forEach(function (el, idx) {
            if (idx === quickPlaylistFocusIdx) {
                el.classList.add('focused');
                el.focus();
                el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
            } else {
                el.classList.remove('focused');
            }
        });
    }

    function handleQuickPlaylistKeys(keyCode, e) {
        var focusable = getQuickPlaylistFocusable();
        if (focusable.length === 0) return;

        switch (keyCode) {
            case TIZEN_KEYS.KEY_UP:
                quickPlaylistFocusIdx = (quickPlaylistFocusIdx - 1 + focusable.length) % focusable.length;
                updateQuickPlaylistFocus();
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_DOWN:
                quickPlaylistFocusIdx = (quickPlaylistFocusIdx + 1) % focusable.length;
                updateQuickPlaylistFocus();
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_LEFT:
                if (quickPlaylistFocusIdx > 0) {
                    quickPlaylistFocusIdx--;
                    updateQuickPlaylistFocus();
                }
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_RIGHT:
                if (quickPlaylistFocusIdx < focusable.length - 1) {
                    quickPlaylistFocusIdx++;
                    updateQuickPlaylistFocus();
                }
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_ENTER:
            case TIZEN_KEYS.KEY_SPACE:
                var target = focusable[quickPlaylistFocusIdx];
                if (target) {
                    target.click();
                }
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_RETURN:
            case TIZEN_KEYS.KEY_ESCAPE:
            case TIZEN_KEYS.KEY_BACKSPACE:
                closeQuickPlaylistModal();
                e.preventDefault();
                break;
        }
    }

    function selectQuickPlaylist(pl) {
        activePlaylistId = pl.id;
        playlists.forEach(function (p) { p.isSelected = (p.id === pl.id); });
        savePlaylists();
        renderPlaylistsManageList();
        var badge = document.getElementById('header-playlist-badge');
        if (badge) badge.textContent = "Danh sách: " + pl.name;
        showToast("Đã chuyển sang: " + pl.name);
        closeQuickPlaylistModal();
        showSpinner("Đang tải danh sách: " + pl.name + "...");
        loadTvPlaylist(function () {
            hideSpinner();
            if (currentAppMode === APP_MODE_TV) {
                categories = tvCategories;
                selectedCatIdx = 0;
                selectedChIdx = 0;
                renderCategories();
                renderChannels();
                if (tvChannels.length > 0) {
                    playChannel(tvChannels[0]);
                }
            }
        });
    }

    // ==========================================
    // 10. TRÌNH PHÁT VIDEO (VIDEO PLAYER HỖ TRỢ UDPXY)
    // ==========================================
    function safePlay() {
        if (!videoEl) return;
        var promise = videoEl.play();
        if (promise !== undefined) {
            promise.catch(function (err) {
                if (err && err.name === 'NotAllowedError') {
                    videoEl.muted = true;
                    videoEl.play().catch(function () {});
                    showToast("Click chuột hoặc bấm phím để bật âm thanh");
                    var unmute = function () {
                        videoEl.muted = false;
                        window.removeEventListener('keydown', unmute);
                        window.removeEventListener('click', unmute);
                    };
                    window.addEventListener('keydown', unmute);
                    window.addEventListener('click', unmute);
                }
            });
        }
    }

    function initVideoListeners(el) {
        if (!el) return;
        el.addEventListener('playing', function () {
            hideSpinner();
            if (quickBufferingTagEl) quickBufferingTagEl.classList.add('hidden');
        });
        el.addEventListener('waiting', function () {
            showSpinner("Đang đệm tín hiệu...");
            if (quickBufferingTagEl) quickBufferingTagEl.classList.remove('hidden');
        });
        el.addEventListener('canplay', function () {
            hideSpinner();
        });
        el.addEventListener('error', function () {
            hideSpinner();
            if (el.error && el.error.code !== 0 && !avplayActive && el.currentSrc) {
                showToast("Lỗi phát video từ nguồn này");
            }
        });
        el.addEventListener('click', function () {
            toggleOverlay();
        });
    }

    function ensureCleanVideoElement() {
        if (!videoEl || videoEl.error) {
            var oldV = document.getElementById('tv-video');
            var newV = document.createElement('video');
            newV.id = 'tv-video';
            newV.playsInline = true;
            if (oldV && oldV.parentNode) {
                oldV.parentNode.replaceChild(newV, oldV);
            }
            videoEl = newV;
            initVideoListeners(videoEl);
        }
    }

    function destroyMediaPlayers() {
        if (hls) {
            try { hls.destroy(); } catch(e) {}
            hls = null;
        }
        if (mpegtsPlayer) {
            try {
                mpegtsPlayer.pause();
                mpegtsPlayer.unload();
                mpegtsPlayer.detachMediaElement();
                mpegtsPlayer.destroy();
            } catch(e) {}
            mpegtsPlayer = null;
        }
        if (window.webapis && window.webapis.avplay) {
            try { webapis.avplay.stop(); } catch(e) {}
            try { webapis.avplay.close(); } catch(e) {}
            avplayActive = false;
        }
        if (videoEl && videoEl.error) {
            ensureCleanVideoElement();
        } else if (videoEl && videoEl.src) {
            videoEl.removeAttribute('src');
        }
        document.body.style.backgroundColor = '#121212';
        var appContainer = document.getElementById('app-container');
        if (appContainer) appContainer.classList.remove('avplay-active');
    }

    function initMpegtsPlayer(streamUrl) {
        try {
            ensureCleanVideoElement();
            if (mpegtsPlayer) {
                try {
                    mpegtsPlayer.pause();
                    mpegtsPlayer.unload();
                    mpegtsPlayer.detachMediaElement();
                    mpegtsPlayer.destroy();
                } catch (e) {}
                mpegtsPlayer = null;
            }

            if (videoEl.src) {
                videoEl.removeAttribute('src');
            }

            // Luồng TS IPTV từ udpxy dùng định dạng audio MP2 (MPEG-1 Layer 2)
            // Trình duyệt PC Chrome không hỗ trợ giải mã MP2 qua MSE nên tắt audio để video H.264 phát mượt Full HD
            mpegtsPlayer = mpegts.createPlayer({
                type: 'mse',
                isLive: true,
                hasAudio: false,
                hasVideo: true,
                url: streamUrl
            }, {
                enableWorker: false,
                lazyLoad: false,
                liveBufferLatencyChasing: true,
                autoCleanupSourceBuffer: true
            });

            mpegtsPlayer.attachMediaElement(videoEl);
            mpegtsPlayer.load();

            mpegtsPlayer.on(mpegts.Events.ERROR, function (type, detail, info) {
                console.warn("mpegts.js error:", type, detail, info);
            });

            var p = mpegtsPlayer.play();
            if (p && p.catch) {
                p.catch(function () {});
            }
        } catch (e) {
            console.warn("mpegts.js error, fallback direct videoEl:", e);
        }
    }

    function playHtml5Stream(streamUrl, isUdpxyStream, isHlsStream) {
        // 1. Đối với luồng MPEG-TS / udpxy trong trình duyệt -> dùng mpegts.js (MSE)
        if (isUdpxyStream && window.mpegts && mpegts.isSupported()) {
            initMpegtsPlayer(streamUrl);
            return;
        }

        // 2. Đối với luồng HLS m3u8 -> dùng hls.js
        if (window.Hls && Hls.isSupported() && (isHlsStream || streamUrl.indexOf("http") === 0)) {
            hls = new Hls({
                enableWorker: true,
                lowLatencyMode: true,
                backBufferLength: 30,
                maxBufferLength: 30,
                maxMaxBufferLength: 60
            });

            hls.loadSource(streamUrl);
            hls.attachMedia(videoEl);

            hls.on(Hls.Events.MANIFEST_PARSED, function () {
                safePlay();
            });

            hls.on(Hls.Events.ERROR, function (event, data) {
                if (data.fatal) {
                    switch (data.type) {
                        case Hls.ErrorTypes.NETWORK_ERROR:
                            hls.startLoad();
                            break;
                        case Hls.ErrorTypes.MEDIA_ERROR:
                            hls.recoverMediaError();
                            break;
                        default:
                            hideSpinner();
                            try {
                                hls.destroy();
                                hls = null;
                                ensureCleanVideoElement();
                                videoEl.src = streamUrl;
                                videoEl.load();
                                safePlay();
                            } catch(e) {
                                showToast("Lỗi tín hiệu luồng kênh");
                            }
                            break;
                    }
                }
            });
        } else {
            // 3. Fallback trực tiếp vào thẻ HTML5 video
            ensureCleanVideoElement();
            videoEl.src = streamUrl;
            videoEl.load();
            safePlay();
        }
    }

    function findMatchForPlayingChannel(channel) {
        if (!channel) return currentPlayingMatch;
        if (channel.match) return channel.match;
        if (channel.isMatch && channel.matchData) return channel;

        // Nếu kênh là kênh TV thông thường (id bắt đầu bằng 'tv_')
        if (channel.id && channel.id.indexOf('tv_') === 0) return null;

        var found = sportsLiveMatches.find(function (m) {
            if (m.id === channel.id) return true;
            if (m.url && channel.url && m.url === channel.url) return true;
            if (m.sources && m.sources.some(function (s) { return s.url === channel.url || s.id === channel.id; })) return true;
            return false;
        });
        return found || null;
    }

    function isSportsPlayback(channel) {
        if (!channel) return (currentAppMode === APP_MODE_SPORTS);
        if (channel.isMatch || channel.match) return true;

        // Kênh thuộc danh mục thể thao (id bắt đầu bằng 'sg_' hoặc 'match_')
        if (channel.id && (channel.id.indexOf('sg_') === 0 || channel.id.indexOf('match_') === 0)) {
            return true;
        }

        // Kênh thuộc danh mục truyền hình (id bắt đầu bằng 'tv_')
        if (channel.id && channel.id.indexOf('tv_') === 0) {
            return false;
        }

        // Kiểm tra xem kênh này có nằm trong danh sách trận đấu hoặc nhóm thể thao không
        if (findMatchForPlayingChannel(channel)) return true;

        var inSportsCat = sportsCategories.some(function (cat) {
            return cat.channels && cat.channels.some(function (c) {
                return c.id === channel.id;
            });
        });
        if (inSportsCat) return true;

        var inTv = tvChannels.some(function (c) {
            return c.id === channel.id;
        });
        if (inTv) return false;

        return currentAppMode === APP_MODE_SPORTS;
    }

    function playChannel(channel) {
        if (!channel || !channel.url) return;
        currentPlayingChannel = channel;
        var isSports = isSportsPlayback(channel);

        if (isSports) {
            currentAppMode = APP_MODE_SPORTS;
            var match = findMatchForPlayingChannel(channel);
            currentPlayingMatch = match || null;
        } else {
            currentPlayingMatch = null;
            currentAppMode = APP_MODE_TV;
        }

        try {
            localStorage.setItem('tvplay_last_url', channel.url);
        } catch (e) {}

        showQuickInfo(channel);
        showSpinner("Đang tải luồng: " + channel.name + "...");

        var streamUrl = resolveStreamUrl(channel.url);
        var isUdpxyStream = (streamUrl.indexOf("/udp/") >= 0 || streamUrl.indexOf("/rtp/") >= 0 || streamUrl.indexOf(".ts") >= 0);
        var isHlsStream = (streamUrl.indexOf(".m3u8") >= 0 || streamUrl.indexOf("/hls/") >= 0);

        destroyMediaPlayers();

        // 1. Tizen TV Native AVPlay (Ưu tiên phần cứng trên TV Samsung Tizen cho luồng Multicast/UDP/udpxy)
        if (window.webapis && window.webapis.avplay) {
            try {
                try { webapis.avplay.stop(); } catch (e) {}
                try { webapis.avplay.close(); } catch (e) {}

                // Thêm hậu tố /video.ts cho udpxy để AVPlay Tizen nhận diện định dạng container MPEG-TS
                var avplayUrl = streamUrl;
                if (isUdpxyStream && avplayUrl.indexOf('.ts') < 0 && avplayUrl.indexOf('.m3u8') < 0) {
                    avplayUrl = avplayUrl.replace(/\/?$/, '/video.ts');
                }

                webapis.avplay.open(avplayUrl);
                try {
                    webapis.avplay.setDisplayMethod('PLAYER_DISPLAY_MODE_FULL_SCREEN');
                } catch (e) {}
                
                // Chuẩn hệ toạ độ AVPlay Samsung luôn là 1920x1080 (kể cả trên màn 4K)
                webapis.avplay.setDisplayRect(0, 0, 1920, 1080);

                try {
                    webapis.avplay.setStreamingProperty("INITIAL_BUFFERING_TIME", "2000");
                } catch (e) {}

                webapis.avplay.setListener({
                    onbufferingstart: function () { showSpinner("Đang đệm tín hiệu..."); },
                    onbufferingcomplete: function () { hideSpinner(); },
                    onerror: function (err) {
                        console.error("AVPlay error:", err);
                        hideSpinner();
                        showToast("Lỗi tín hiệu luồng kênh (AVPlay)");
                    }
                });

                webapis.avplay.prepareAsync(function () {
                    webapis.avplay.play();
                    avplayActive = true;
                    document.body.style.backgroundColor = 'transparent';
                    var appContainer = document.getElementById('app-container');
                    if (appContainer) appContainer.classList.add('avplay-active');
                    hideSpinner();
                }, function (err) {
                    console.warn("AVPlay prepareAsync fail, fallback to HTML5 video:", err);
                    playHtml5Stream(streamUrl, isUdpxyStream, isHlsStream);
                });

                if (isOverlayOpen) renderChannels();
                return;
            } catch (e) {
                console.warn("AVPlay open exception, fallback to HTML5 video:", e);
            }
        }

        // 2. Trình phát HTML5 / mpegts.js / Hls.js (Dành cho Windows Preview & Browser)
        playHtml5Stream(streamUrl, isUdpxyStream, isHlsStream);

        if (isOverlayOpen) {
            renderChannels();
        }
    }

    // ==========================================
    // 11. ĐIỀU KHIỂN & SỰ KIỆN REMOTE CHUẨN ANDROID TV
    // ==========================================
    function initEventListeners() {
        initVideoListeners(videoEl);

        // Sidebar click
        sidebarBtns.forEach(function (btn, idx) {
            if (btn) {
                btn.addEventListener('click', function () {
                    triggerSidebarAction(idx);
                });
            }
        });

        // Match modal events
        var matchCloseBtn = document.getElementById('modal-match-close');
        if (matchCloseBtn) matchCloseBtn.addEventListener('click', closeMatchSourcesModal);

        // Settings modal events
        var settingsCloseBtn = document.getElementById('settings-close-btn');
        if (settingsCloseBtn) settingsCloseBtn.addEventListener('click', closeSettingsModal);

        var udpxySaveBtn = document.getElementById('setting-udpxy-save');
        if (udpxySaveBtn) {
            udpxySaveBtn.addEventListener('click', function () {
                if (udpxyInputEl) {
                    udpxyServer = udpxyInputEl.value.trim();
                    saveSettings();
                    showToast("Đã lưu máy chủ udpxy: " + (udpxyServer || "Mặc định"));
                    if (currentPlayingChannel) {
                        playChannel(currentPlayingChannel);
                    }
                }
            });
        }

        var btnAddPlaylist = document.getElementById('btn-add-playlist');
        if (btnAddPlaylist) {
            btnAddPlaylist.addEventListener('click', function () {
                var nameEl = document.getElementById('new-playlist-name');
                var urlEl = document.getElementById('new-playlist-url');
                var name = nameEl ? nameEl.value.trim() : "";
                var url = urlEl ? urlEl.value.trim() : "";
                if (!name || !url) {
                    showToast("Vui lòng nhập tên và link M3U!");
                    return;
                }
                playlists.push({
                    id: "pl_" + Date.now(),
                    name: name,
                    url: url,
                    isSelected: false
                });
                savePlaylists();
                renderPlaylistsManageList();
                if (nameEl) nameEl.value = "";
                if (urlEl) urlEl.value = "";
                showToast("Đã thêm danh sách: " + name);
            });
        }

        var btnCheckOta = document.getElementById('btn-check-ota');
        if (btnCheckOta) btnCheckOta.addEventListener('click', function () { checkOtaUpdate(true); });

        var btnResetOta = document.getElementById('btn-reset-ota');
        if (btnResetOta) {
            btnResetOta.addEventListener('click', function () {
                localStorage.removeItem('tvplay_tizen_ota_code');
                localStorage.removeItem('tvplay_tizen_ota_version');
                showToast("Đã khôi phục phiên bản gốc! Đang tải lại...");
                setTimeout(function () { location.reload(); }, 1000);
            });
        }

        window.addEventListener('keydown', handleKeyDown);
    }

    function triggerSidebarAction(idx) {
        selectedSidebarIdx = idx;
        if (idx === 0) {
            switchAppMode(APP_MODE_FAVORITES);
            focusedColumn = 'channels';
            updateFocusView();
        } else if (idx === 1) {
            switchAppMode(APP_MODE_TV);
            focusedColumn = 'channels';
            updateFocusView();
        } else if (idx === 2) {
            switchAppMode(APP_MODE_SPORTS);
            focusedColumn = 'channels';
            updateFocusView();
        } else if (idx === 3) {
            showToast("Đang làm mới danh sách...");
            loadAllData();
        } else if (idx === 4) {
            openSettingsModal();
        }
    }

    function handleKeyDown(e) {
        var keyCode = e.keyCode;

        // Phím số 0-9
        if (keyCode >= TIZEN_KEYS.KEY_0 && keyCode <= TIZEN_KEYS.KEY_9) {
            var num = keyCode - TIZEN_KEYS.KEY_0;
            handleNumberInput(num);
            e.preventDefault();
            return;
        }

        // Khi đang mở Match Sources Modal (Chọn luồng phát)
        if (isMatchModalOpen) {
            handleMatchModalKeys(keyCode, e);
            return;
        }

        // Khi đang mở Settings Modal -> Điều hướng bằng Remote D-Pad
        if (isSettingsModalOpen) {
            handleSettingsModalKeys(keyCode, e);
            return;
        }

        // Khi đang mở Quick Playlist Modal -> Điều hướng bằng Remote D-Pad
        if (isQuickPlaylistModalOpen) {
            handleQuickPlaylistKeys(keyCode, e);
            return;
        }

        switch (keyCode) {
            // RETURN / ESC / BACKSPACE
            case TIZEN_KEYS.KEY_RETURN:
            case TIZEN_KEYS.KEY_ESCAPE:
            case TIZEN_KEYS.KEY_BACKSPACE:
                if (isOverlayOpen) {
                    if (focusedColumn === 'spotlight') {
                        focusedColumn = 'channels';
                        updateFocusView();
                    } else if (focusedColumn === 'channels') {
                        focusedColumn = 'categories';
                        updateFocusView();
                    } else {
                        closeOverlay();
                    }
                } else {
                    handleRemoteBackFullscreen();
                }
                e.preventDefault();
                break;

            // ENTER / OK / SPACE
            case TIZEN_KEYS.KEY_ENTER:
            case TIZEN_KEYS.KEY_SPACE:
                if (!isOverlayOpen) {
                    handleRemoteOkFullscreen();
                } else {
                    if (focusedColumn === 'sidebar') {
                        triggerSidebarAction(selectedSidebarIdx);
                    } else if (focusedColumn === 'categories') {
                        focusedColumn = 'channels';
                        selectedChIdx = 0;
                        updateFocusView();
                        updateSpotlight();
                    } else if (focusedColumn === 'spotlight') {
                        var curMatch = categories[selectedCatIdx].channels[selectedChIdx];
                        if (curMatch && curMatch.sources && curMatch.sources[selectedSpotlightSourceIdx]) {
                            playMatchSource(curMatch, curMatch.sources[selectedSpotlightSourceIdx]);
                        }
                    } else {
                        var currentCat = categories[selectedCatIdx];
                        if (currentCat && currentCat.channels[selectedChIdx]) {
                            handleChannelClick(currentCat.channels[selectedChIdx]);
                        }
                    }
                }
                e.preventDefault();
                break;

            // ARROW UP
            case TIZEN_KEYS.KEY_UP:
                if (!isOverlayOpen) {
                    switchChannelByOffset(-1);
                } else {
                    if (focusedColumn === 'sidebar') {
                        if (selectedSidebarIdx > 0) {
                            selectedSidebarIdx--;
                            updateFocusView();
                        }
                    } else if (focusedColumn === 'categories') {
                        if (selectedCatIdx > 0) {
                            selectedCatIdx--;
                            selectedChIdx = 0;
                            renderCategories();
                            renderChannels();
                        }
                    } else if (focusedColumn === 'spotlight') {
                        var curM = categories[selectedCatIdx].channels[selectedChIdx];
                        if (curM && curM.sources && selectedSpotlightSourceIdx > 0) {
                            selectedSpotlightSourceIdx--;
                            updateFocusView();
                        }
                    } else {
                        if (selectedChIdx > 0) {
                            selectedChIdx--;
                            selectedSpotlightSourceIdx = 0;
                            updateFocusView();
                            updateSpotlight();
                        }
                    }
                }
                e.preventDefault();
                break;

            // ARROW DOWN
            case TIZEN_KEYS.KEY_DOWN:
                if (!isOverlayOpen) {
                    switchChannelByOffset(1);
                } else {
                    if (focusedColumn === 'sidebar') {
                        if (selectedSidebarIdx < sidebarBtns.length - 1) {
                            selectedSidebarIdx++;
                            updateFocusView();
                        }
                    } else if (focusedColumn === 'categories') {
                        if (selectedCatIdx < categories.length - 1) {
                            selectedCatIdx++;
                            selectedChIdx = 0;
                            renderCategories();
                            renderChannels();
                        }
                    } else if (focusedColumn === 'spotlight') {
                        var curMatch2 = categories[selectedCatIdx].channels[selectedChIdx];
                        if (curMatch2 && curMatch2.sources && selectedSpotlightSourceIdx < curMatch2.sources.length - 1) {
                            selectedSpotlightSourceIdx++;
                            updateFocusView();
                        }
                    } else {
                        var maxLen = categories[selectedCatIdx].channels.length;
                        if (selectedChIdx < maxLen - 1) {
                            selectedChIdx++;
                            selectedSpotlightSourceIdx = 0;
                            updateFocusView();
                            updateSpotlight();
                        }
                    }
                }
                e.preventDefault();
                break;

            // ARROW LEFT
            case TIZEN_KEYS.KEY_LEFT:
                if (!isOverlayOpen) {
                    if (currentPlayingChannel) showQuickInfo(currentPlayingChannel);
                } else {
                    if (focusedColumn === 'spotlight') {
                        focusedColumn = 'channels';
                        updateFocusView();
                    } else if (focusedColumn === 'channels') {
                        focusedColumn = 'categories';
                        updateFocusView();
                    } else if (focusedColumn === 'categories') {
                        focusedColumn = 'sidebar';
                        updateFocusView();
                    }
                }
                e.preventDefault();
                break;

            // ARROW RIGHT (CHUYỂN SANG CỘT NGUỒN PHÁT BÊN PHẢI HOẶC MỞ HỘP THOẠI KHI XEM TOÀN MÀN HÌNH)
            case TIZEN_KEYS.KEY_RIGHT:
                if (!isOverlayOpen) {
                    handleRemoteRightFullscreen();
                } else {
                    if (focusedColumn === 'sidebar') {
                        focusedColumn = 'categories';
                        updateFocusView();
                    } else if (focusedColumn === 'categories') {
                        focusedColumn = 'channels';
                        selectedChIdx = 0;
                        selectedSpotlightSourceIdx = 0;
                        updateFocusView();
                        updateSpotlight();
                    } else if (focusedColumn === 'channels') {
                        var curItem = categories[selectedCatIdx].channels[selectedChIdx];
                        if (curItem && curItem.isMatch && curItem.sources && curItem.sources.length > 0) {
                            focusedColumn = 'spotlight';
                            selectedSpotlightSourceIdx = 0;
                            updateFocusView();
                        }
                    }
                }
                e.preventDefault();
                break;

            // PHÍM MENU / CÀI ĐẶT
            case TIZEN_KEYS.KEY_MENU:
            case TIZEN_KEYS.KEY_M:
            case TIZEN_KEYS.KEY_S:
                openSettingsModal();
                e.preventDefault();
                break;

            // CHANNEL UP / CHANNEL DOWN
            case TIZEN_KEYS.KEY_CHANNEL_UP:
                switchChannelByOffset(1);
                e.preventDefault();
                break;

            case TIZEN_KEYS.KEY_CHANNEL_DOWN:
                switchChannelByOffset(-1);
                e.preventDefault();
                break;

            // MEDIA PLAY / PAUSE
            case TIZEN_KEYS.KEY_PLAY:
                if (videoEl) videoEl.play();
                e.preventDefault();
                break;

            case TIZEN_KEYS.KEY_PAUSE:
                if (videoEl) videoEl.pause();
                e.preventDefault();
                break;

            case TIZEN_KEYS.KEY_PLAY_PAUSE:
                if (videoEl) {
                    if (videoEl.paused) videoEl.play();
                    else videoEl.pause();
                }
                e.preventDefault();
                break;

            // PHÍM ĐỎ (RED) - YÊU THÍCH
            case TIZEN_KEYS.KEY_RED:
                if (isOverlayOpen && focusedColumn === 'channels') {
                    var curCh = categories[selectedCatIdx].channels[selectedChIdx];
                    toggleFavorite(curCh);
                } else if (currentPlayingChannel) {
                    toggleFavorite(currentPlayingChannel);
                }
                e.preventDefault();
                break;
        }
    }

    function handleMatchModalKeys(keyCode, e) {
        if (!currentSelectedMatch || !currentSelectedMatch.sources) return;
        var total = currentSelectedMatch.sources.length;

        switch (keyCode) {
            case TIZEN_KEYS.KEY_UP:
                if (selectedSourceIdx > 0) {
                    selectedSourceIdx--;
                    updateMatchSourcesFocus();
                }
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_DOWN:
                if (selectedSourceIdx < total - 1) {
                    selectedSourceIdx++;
                    updateMatchSourcesFocus();
                }
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_ENTER:
            case TIZEN_KEYS.KEY_SPACE:
                var src = currentSelectedMatch.sources[selectedSourceIdx];
                if (src) playMatchSource(currentSelectedMatch, src);
                e.preventDefault();
                break;
            case TIZEN_KEYS.KEY_RETURN:
            case TIZEN_KEYS.KEY_ESCAPE:
            case TIZEN_KEYS.KEY_BACKSPACE:
                closeMatchSourcesModal();
                e.preventDefault();
                break;
        }
    }

    function handleRemoteRightFullscreen() {
        var isSports = isSportsPlayback(currentPlayingChannel);
        if (isSports) {
            var match = findMatchForPlayingChannel(currentPlayingChannel);
            if (match && match.sources && match.sources.length > 0) {
                currentPlayingMatch = match;
                openMatchSourcesModal(match);
            } else {
                showToast("Không có danh sách nguồn phát khác cho trận này");
            }
        } else {
            // Mục Xem TV: Nhấn phím sang phải (►) mở hộp thoại chọn danh sách file .m3u
            openQuickPlaylistModal();
        }
    }

    function handleRemoteOkFullscreen() {
        var isSports = isSportsPlayback(currentPlayingChannel);
        if (isSports) {
            switchAppMode(APP_MODE_SPORTS);
            if (currentPlayingChannel) {
                var sCatIdx = sportsCategories.findIndex(function (cat) {
                    return cat.channels && cat.channels.some(function (c) {
                        return c.id === currentPlayingChannel.id || (currentPlayingChannel.id.indexOf('sg_') === 0 && c.url === currentPlayingChannel.url);
                    });
                });
                if (sCatIdx >= 0) {
                    selectedCatIdx = sCatIdx;
                    renderCategories();
                    renderChannels();
                    var sChIdx = sportsCategories[sCatIdx].channels.findIndex(function (c) {
                        return c.id === currentPlayingChannel.id || c.url === currentPlayingChannel.url;
                    });
                    if (sChIdx >= 0) selectedChIdx = sChIdx;
                } else {
                    var match = findMatchForPlayingChannel(currentPlayingChannel);
                    if (match) {
                        var found = sportsLiveMatches.findIndex(function (m) { return m.id === match.id; });
                        if (found >= 0) selectedChIdx = found;
                    }
                }
            }
            openOverlay();
            focusedColumn = 'channels';
            updateFocusView();
            updateSpotlight();
        } else {
            switchAppMode(APP_MODE_TV);
            if (currentPlayingChannel) {
                var catIdx = categories.findIndex(function (cat) {
                    return cat.name !== "Tất cả kênh" && cat.channels.some(function (c) { return c.id === currentPlayingChannel.id || c.url === currentPlayingChannel.url; });
                });
                if (catIdx >= 0) {
                    selectedCatIdx = catIdx;
                    renderCategories();
                    renderChannels();
                    var chIdx = categories[catIdx].channels.findIndex(function (c) { return c.id === currentPlayingChannel.id || c.url === currentPlayingChannel.url; });
                    if (chIdx >= 0) selectedChIdx = chIdx;
                } else {
                    var fIdx = tvChannels.findIndex(function (c) { return c.id === currentPlayingChannel.id || c.url === currentPlayingChannel.url; });
                    if (fIdx >= 0) selectedChIdx = fIdx;
                }
            }
            openOverlay();
            focusedColumn = 'channels';
            updateFocusView();
            updateSpotlight();
        }
    }

    function handleRemoteBackFullscreen() {
        if (!currentPlayingChannel) {
            openOverlay();
            return;
        }
        var isSports = isSportsPlayback(currentPlayingChannel);
        if (isSports) {
            switchAppMode(APP_MODE_SPORTS);
            if (currentPlayingChannel) {
                var sCatIdx2 = sportsCategories.findIndex(function (cat) {
                    return cat.channels && cat.channels.some(function (c) {
                        return c.id === currentPlayingChannel.id || (currentPlayingChannel.id.indexOf('sg_') === 0 && c.url === currentPlayingChannel.url);
                    });
                });
                if (sCatIdx2 >= 0) {
                    selectedCatIdx = sCatIdx2;
                    renderCategories();
                    renderChannels();
                    var sChIdx2 = sportsCategories[sCatIdx2].channels.findIndex(function (c) {
                        return c.id === currentPlayingChannel.id || c.url === currentPlayingChannel.url;
                    });
                    if (sChIdx2 >= 0) selectedChIdx = sChIdx2;
                } else {
                    var match2 = findMatchForPlayingChannel(currentPlayingChannel);
                    if (match2) {
                        var found2 = sportsLiveMatches.findIndex(function (m) { return m.id === match2.id; });
                        if (found2 >= 0) selectedChIdx = found2;
                    }
                }
            }
            openOverlay();
            focusedColumn = 'channels';
            updateFocusView();
            updateSpotlight();
        } else {
            switchAppMode(APP_MODE_TV);
            var catIdx2 = categories.findIndex(function (cat) {
                return cat.name !== "Tất cả kênh" && cat.channels.some(function (c) { return c.id === currentPlayingChannel.id || c.url === currentPlayingChannel.url; });
            });
            if (catIdx2 >= 0) {
                selectedCatIdx = catIdx2;
                renderCategories();
                renderChannels();
                var chIdx2 = categories[catIdx2].channels.findIndex(function (c) { return c.id === currentPlayingChannel.id || c.url === currentPlayingChannel.url; });
                if (chIdx2 >= 0) selectedChIdx = chIdx2;
            } else {
                var fIdx3 = tvChannels.findIndex(function (c) { return c.id === currentPlayingChannel.id || c.url === currentPlayingChannel.url; });
                if (fIdx3 >= 0) selectedChIdx = fIdx3;
            }
            openOverlay();
            focusedColumn = 'channels';
            updateFocusView();
            updateSpotlight();
        }
    }

    function handleNumberInput(digit) {
        numberInputBuffer += digit;
        showToast("Kênh: " + numberInputBuffer);

        if (numberInputTimeout) clearTimeout(numberInputTimeout);
        numberInputTimeout = setTimeout(function () {
            var targetNum = parseInt(numberInputBuffer, 10);
            numberInputBuffer = "";

            var currentList = (currentAppMode === APP_MODE_SPORTS) ? sportsLiveMatches : tvChannels;
            var found = currentList.find(function (c) { return c.number === targetNum; });
            if (found) {
                handleChannelClick(found);
            } else {
                showToast("Không tìm thấy kênh số " + targetNum);
            }
        }, 1200);
    }

    function switchChannelByOffset(offset) {
        var isSports = isSportsPlayback(currentPlayingChannel);
        var currentList = isSports ? sportsLiveMatches : tvChannels;
        if (!currentList || currentList.length === 0) return;
        var curIdx = 0;
        if (currentPlayingChannel) {
            curIdx = currentList.findIndex(function (c) {
                if (c.url === currentPlayingChannel.url) return true;
                if (c.id === currentPlayingChannel.id) return true;
                if (c.sources && c.sources.some(function (s) { return s.url === currentPlayingChannel.url; })) return true;
                return false;
            });
            if (curIdx < 0) curIdx = 0;
        }

        var nextIdx = (curIdx + offset + currentList.length) % currentList.length;
        handleChannelClick(currentList[nextIdx]);
    }

    // ==========================================
    // 12. HIỂN THỊ OVERLAY & THẺ OSD TOÀN MÀN HÌNH
    // ==========================================
    function openOverlay() {
        isOverlayOpen = true;
        if (overlayEl) overlayEl.classList.remove('hidden');
        renderCategories();
        renderChannels();
        updateFocusView();
        updateSpotlight();
    }

    function closeOverlay() {
        isOverlayOpen = false;
        if (overlayEl) overlayEl.classList.add('hidden');
    }

    function toggleOverlay() {
        if (isOverlayOpen) closeOverlay();
        else openOverlay();
    }

    function showQuickInfo(channel) {
        if (!quickInfoEl) return;
        var isSports = isSportsPlayback(channel);

        if (quickNumEl) quickNumEl.textContent = pad2(channel.number);
        if (quickLogoEl) {
            quickLogoEl.src = channel.logo || (isSports ? 'assets/leagues/ic_nav_sports.png' : 'icon.png');
            quickLogoEl.onerror = function () { this.src = isSports ? 'assets/leagues/ic_nav_sports.png' : 'icon.png'; };
        }
        if (quickNameEl) quickNameEl.textContent = channel.name;
        if (quickModeTagEl) {
            if (channel.isMatch) {
                var lg = (channel.matchLeague || "TRỰC TIẾP").toUpperCase();
                quickModeTagEl.textContent = lg;
            } else if (isSports) {
                quickModeTagEl.textContent = "THỂ THAO";
            } else {
                quickModeTagEl.textContent = "XEM TV";
            }
        }
        if (quickProgramEl) {
            var curProg = getCurrentEpgProgram(channel);
            var activeName = curProg ? curProg.title : (channel.sourceName || (channel.isMatch ? (channel.matchLeague || "Trực tiếp") : (channel.group || channel.name)));
            quickProgramEl.textContent = "● Đang phát: " + activeName;
        }
        if (quickTechEl) {
            var url = resolveStreamUrl(channel.url);
            var techStr = "1920x1080 @ 25fps • 0.5 Mbps • MPEG AAC AUDIO Stereo 2.0";
            if (url.indexOf("/udp/") >= 0 || url.indexOf("/rtp/") >= 0) {
                techStr = "MPEG-TS (udpxy Multicast) • 1920x1080 • Audio AAC Stereo 2.0";
            }
            quickTechEl.textContent = techStr;
        }

        var hintEl = quickInfoEl.querySelector('.quick-hint');
        if (hintEl) {
            if (isSports) {
                hintEl.textContent = "► Nhấn [OK] mở danh sách kênh • [Sang Phải] chọn luồng phát • [Trái] hiện OSD";
            } else {
                hintEl.textContent = "► Nhấn [OK] mở danh sách kênh • [Sang Phải] chọn danh sách phát M3U • [Trái] hiện OSD";
            }
        }

        quickInfoEl.classList.remove('hidden');

        if (quickInfoTimeout) clearTimeout(quickInfoTimeout);
        quickInfoTimeout = setTimeout(function () {
            quickInfoEl.classList.add('hidden');
        }, 4000);
    }

    function showSpinner(text) {
        if (spinnerEl) {
            var txtEl = document.getElementById('buffering-text');
            if (txtEl && text) txtEl.textContent = text;
            spinnerEl.classList.remove('hidden');
        }
    }

    function hideSpinner() {
        if (spinnerEl) spinnerEl.classList.add('hidden');
    }

    function showToast(msg) {
        if (!toastEl) return;
        toastEl.textContent = msg;
        toastEl.classList.remove('hidden');

        if (toastTimeout) clearTimeout(toastTimeout);
        toastTimeout = setTimeout(function () {
            toastEl.classList.add('hidden');
        }, 3000);
    }

    // Expose for external controls / debug / tests
    window.resolveStreamUrl = resolveStreamUrl;
    window.playChannel = playChannel;
    window.openQuickPlaylistModal = openQuickPlaylistModal;
    window.closeQuickPlaylistModal = closeQuickPlaylistModal;
    window.openMatchSourcesModal = openMatchSourcesModal;
    window.closeMatchSourcesModal = closeMatchSourcesModal;
    window.openSettingsModal = openSettingsModal;

})();
