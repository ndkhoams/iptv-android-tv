package com.iptv.tvapp.server

object WebPageTemplate {

    fun getHtml(): String {
        return """
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>IPTV TV Remote & Quản Lý</title>
    <style>
        :root {
            --bg-color: #0E0C1B;
            --card-bg: #161526;
            --card-border: #2E294E;
            --accent: #FF6500;
            --text-main: #f8fafc;
            --text-sub: #94a3b8;
            --btn-active: #FF6500;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; -webkit-tap-highlight-color: transparent; }
        body { background-color: var(--bg-color); color: var(--text-main); min-height: 100vh; padding-bottom: 70px; }
        header { background: #121024; padding: 14px 16px; border-bottom: 1px solid var(--card-border); position: sticky; top: 0; z-index: 100; display: flex; justify-content: space-between; align-items: center; }
        header h1 { font-size: 17px; color: var(--accent); font-weight: 700; }
        .now-playing-banner { background: linear-gradient(135deg, #FF650033, #0E0C1B); border-bottom: 1px solid #FF650066; padding: 10px 16px; font-size: 13px; display: flex; justify-content: space-between; align-items: center; }
        .now-playing-banner span.ch-name { font-weight: bold; color: #fff; }
        .now-playing-banner span.ch-badge { background: #ef4444; color: #fff; font-size: 10px; font-weight: bold; padding: 2px 6px; border-radius: 4px; }

        .tab-content { display: none; padding: 16px; max-width: 500px; margin: 0 auto; }
        .tab-content.active { display: block; }
        
        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; height: 60px; background: #131d2e; border-top: 1px solid var(--card-border); display: flex; justify-content: space-around; align-items: center; z-index: 100; }
        .nav-btn { background: none; border: none; color: var(--text-sub); font-size: 11px; display: flex; flex-direction: column; align-items: center; gap: 4px; cursor: pointer; flex: 1; padding: 6px 0; }
        .nav-btn.active { color: var(--accent); font-weight: bold; }
        .nav-btn svg { width: 22px; height: 22px; fill: currentColor; }

        .remote-card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 16px; padding: 20px; box-shadow: 0 8px 24px rgba(0,0,0,0.4); margin-bottom: 16px; }
        .dpad-container { width: 220px; height: 220px; margin: 10px auto; position: relative; background: #0b1120; border-radius: 50%; border: 2px solid var(--card-border); box-shadow: inset 0 4px 12px rgba(0,0,0,0.6); }
        .dpad-btn { position: absolute; background: #1e293b; border: 1px solid var(--card-border); color: var(--text-main); font-size: 16px; font-weight: bold; display: flex; align-items: center; justify-content: center; cursor: pointer; border-radius: 10px; transition: all 0.1s; }
        .dpad-btn:active { background: var(--btn-active); transform: scale(0.95); }
        .dpad-up { top: 10px; left: 75px; width: 70px; height: 50px; border-radius: 25px 25px 8px 8px; }
        .dpad-down { bottom: 10px; left: 75px; width: 70px; height: 50px; border-radius: 8px 8px 25px 25px; }
        .dpad-left { top: 75px; left: 10px; width: 50px; height: 70px; border-radius: 25px 8px 8px 25px; }
        .dpad-right { top: 75px; right: 10px; width: 50px; height: 70px; border-radius: 8px 25px 25px 8px; }
        .dpad-ok { top: 75px; left: 75px; width: 70px; height: 70px; border-radius: 50%; background: #FF6500; color: #fff; font-size: 18px; border: 2px solid #FF8533; }

        .row-actions { display: flex; gap: 12px; margin-top: 14px; }
        .action-btn { flex: 1; padding: 12px; background: #1e293b; border: 1px solid var(--card-border); border-radius: 10px; color: #fff; font-size: 14px; font-weight: bold; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 6px; }
        .action-btn:active { background: var(--btn-active); }

        .search-box { width: 100%; padding: 12px 14px; background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 10px; color: #fff; font-size: 14px; margin-bottom: 12px; outline: none; }
        .search-box:focus { border-color: var(--accent); }
        .channel-item { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 10px; padding: 10px 12px; margin-bottom: 8px; display: flex; align-items: center; justify-content: space-between; cursor: pointer; transition: background 0.15s; }
        .channel-item:active { background: #2563eb; }
        .channel-left { display: flex; align-items: center; gap: 10px; flex: 1; overflow: hidden; }
        .channel-num { color: var(--accent); font-weight: bold; font-size: 14px; min-width: 28px; }
        .channel-logo { width: 36px; height: 28px; object-fit: contain; background: #000; border-radius: 4px; }
        .channel-title { font-size: 15px; font-weight: bold; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--text-main); }
        .btn-fav { background: none; border: none; font-size: 20px; color: #64748b; padding: 4px 8px; cursor: pointer; }
        .btn-fav.active { color: #fbbf24; }

        .form-card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 16px; margin-bottom: 16px; }
        .form-card h3 { font-size: 15px; color: var(--accent); margin-bottom: 12px; }
        .form-group { margin-bottom: 10px; }
        .form-group label { display: block; font-size: 12px; color: var(--text-sub); margin-bottom: 4px; }
        .form-input { width: 100%; padding: 10px 12px; background: #0b1120; border: 1px solid var(--card-border); border-radius: 8px; color: #fff; font-size: 13px; outline: none; }
        .form-input:focus { border-color: var(--accent); }
        .submit-btn { width: 100%; padding: 12px; background: #FF6500; border: none; border-radius: 8px; color: #fff; font-weight: bold; font-size: 14px; cursor: pointer; margin-top: 6px; }
        .submit-btn:active { background: #FF8533; }
        
        .playlist-item { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 10px; padding: 12px; margin-bottom: 8px; display: flex; align-items: center; justify-content: space-between; }
        .playlist-item.selected { border-color: var(--accent); background: #FF650022; }
        .playlist-info { flex: 1; margin-right: 10px; overflow: hidden; }
        .playlist-name { font-weight: bold; font-size: 14px; color: #fff; margin-bottom: 2px; }
        .playlist-url { font-size: 11px; color: var(--text-sub); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .playlist-actions { display: flex; gap: 8px; }
        .btn-sm { padding: 6px 12px; border-radius: 6px; font-size: 12px; font-weight: bold; cursor: pointer; border: none; }
        .btn-select { background: #10b981; color: #fff; }
        .btn-delete { background: #ef4444; color: #fff; }

        #toast { position: fixed; top: 70px; left: 50%; transform: translateX(-50%); background: #1e293b; border: 1px solid var(--accent); color: #fff; padding: 8px 16px; border-radius: 20px; font-size: 13px; z-index: 200; box-shadow: 0 4px 12px rgba(0,0,0,0.5); opacity: 0; transition: opacity 0.3s; pointer-events: none; }
        #toast.show { opacity: 1; }
    </style>
</head>
<body>

    <header>
        <h1>📱 TV PLAY REMOTE</h1>
        <span id="tvIpBadge" style="font-size:12px; color:var(--text-sub);">Đang kết nối TV...</span>
    </header>

    <div class="now-playing-banner">
        <div>
            <span class="ch-badge">ĐANG PHÁT</span>
            <span id="bannerChName" class="ch-name" style="margin-left: 6px;">Đang tải...</span>
        </div>
        <button onclick="sendKey('center')" style="background:none; border:none; color:var(--accent); font-size:12px; font-weight:bold; cursor:pointer;">Mở OSD 📺</button>
    </div>

    <!-- TAB 1: REMOTE CONTROLLER -->
    <div id="tab-remote" class="tab-content active">
        <div class="remote-card">
            <div class="dpad-container">
                <button class="dpad-btn dpad-up" onclick="sendKey('up')">▲</button>
                <button class="dpad-btn dpad-down" onclick="sendKey('down')">▼</button>
                <button class="dpad-btn dpad-left" onclick="sendKey('left')">◄</button>
                <button class="dpad-btn dpad-right" onclick="sendKey('right')">►</button>
                <button class="dpad-btn dpad-ok" onclick="sendKey('center')">OK</button>
            </div>

            <div class="row-actions">
                <button class="action-btn" onclick="sendKey('back')">↩ Quay Lại</button>
                <button class="action-btn" onclick="sendKey('menu')">☰ Menu Cài Đặt</button>
            </div>

            <div class="row-actions">
                <button class="action-btn" onclick="sendKey('ch_prev')">⏮ Kênh Trước</button>
                <button class="action-btn" onclick="sendKey('ch_next')">⏭ Kênh Tiếp</button>
            </div>
        </div>
    </div>

    <!-- TAB 2: CHANNEL LIST -->
    <div id="tab-channels" class="tab-content">
        <input type="text" id="channelSearch" class="search-box" placeholder="🔍 Tìm kiếm kênh truyền hình..." oninput="filterChannels()">
        <div id="channelsContainer">Đang tải danh sách kênh...</div>
    </div>

    <!-- TAB 2.5: FAVORITE CHANNELS -->
    <div id="tab-favorites" class="tab-content">
        <input type="text" id="favSearch" class="search-box" placeholder="🔍 Tìm kiếm trong kênh yêu thích..." oninput="filterFavorites()">
        <div id="favoritesContainer">Đang tải danh sách yêu thích...</div>
    </div>

    <!-- TAB 3: PLAYLIST MANAGER -->
    <div id="tab-playlists" class="tab-content">
        <div class="form-card">
            <h3>+ Dán Link M3U Mới Từ Điện Thoại</h3>
            <div class="form-group">
                <label>Tên danh sách</label>
                <input type="text" id="newPlName" class="form-input" placeholder="Ví dụ: Kênh Thể Thao, Kênh VN...">
            </div>
            <div class="form-group">
                <label>Đường link URL (M3U / M3U8)</label>
                <input type="url" id="newPlUrl" class="form-input" placeholder="http://... hoặc https://...">
            </div>
            <div style="margin-top:10px; margin-bottom:12px; display:flex; align-items:center; gap:8px;">
                <input type="checkbox" id="newPlSelectNow" checked style="width:18px; height:18px; accent-color:#ff6500; cursor:pointer;">
                <label for="newPlSelectNow" style="font-size:13px; color:var(--text-main); cursor:pointer;">Kích hoạt phát danh sách này ngay trên TV</label>
            </div>
            <button class="submit-btn" onclick="addNewPlaylist()">+ Thêm &amp; Lưu Vào TV</button>
        </div>

        <div class="form-card">
            <h3>📂 Tải File M3U Từ Điện Thoại Lên TV</h3>
            <p style="font-size:12px; color:var(--text-sub); margin-bottom:10px;">Truyền file M3U/M3U8 từ bộ nhớ điện thoại hoặc máy tính sang TV qua Wi-Fi.</p>
            <div class="form-group">
                <label>Tên danh sách (tùy chọn)</label>
                <input type="text" id="uploadPlName" class="form-input" placeholder="Để trống sẽ lấy theo tên file">
            </div>
            <div class="form-group">
                <label>Chọn file (.m3u, .m3u8, .txt)</label>
                <input type="file" id="fileLocalM3u" accept=".m3u,.m3u8,.txt" class="form-input" style="padding:6px;">
            </div>
            <div style="margin-top:10px; margin-bottom:12px; display:flex; align-items:center; gap:8px;">
                <input type="checkbox" id="uploadPlSelectNow" checked style="width:18px; height:18px; accent-color:#10b981; cursor:pointer;">
                <label for="uploadPlSelectNow" style="font-size:13px; color:var(--text-main); cursor:pointer;">Kích hoạt phát danh sách này ngay trên TV</label>
            </div>
            <button class="submit-btn" style="background:#10b981;" onclick="uploadLocalPlaylist()">⬆ Tải File Lên TV</button>
        </div>

        <h3 style="font-size:15px; margin-bottom:10px; color:var(--text-main);">Danh Sách Playlist Đã Có:</h3>
        <div id="playlistsContainer">Đang tải...</div>
    </div>

    <!-- TAB 4: SETTINGS -->
    <div id="tab-settings" class="tab-content">
        <!-- 1. BỘ GIẢI MÃ VIDEO -->
        <div class="form-card">
            <h3>🎬 1. Bộ Giải Mã Video (Player Engine)</h3>
            <p style="font-size:12px; color:var(--text-sub); margin-bottom:10px;">Lựa chọn trình phát giải mã phần cứng cho Android TV.</p>
            <div>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; margin-bottom:10px; cursor:pointer;">
                    <input type="radio" name="playerEngine" value="VLC" id="engineVlc" onchange="updateDecoderSettings()">
                    <span><strong style="color:#10b981;">LibVLC</strong> (Phần cứng MediaCodec - Mượt mà 60fps, Không xanh màn)</span>
                </label>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; cursor:pointer;">
                    <input type="radio" name="playerEngine" value="EXOPLAYER" id="engineExo" onchange="updateDecoderSettings()">
                    <span><strong>ExoPlayer</strong> (Google MediaCodec - Nhẹ máy chuẩn Android TV)</span>
                </label>
            </div>
        </div>

        <!-- 2. THUẬT TOÁN DEINTERLACE -->
        <div class="form-card">
            <h3>⚡ 2. Khử Xé Hình 1080i (VLC Deinterlace)</h3>
            <p style="font-size:12px; color:var(--text-sub); margin-bottom:10px;">Khử hiện tượng răng cưa ngang khi các kênh đài truyền hình phát chuẩn 1080i50.</p>
            <div>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; margin-bottom:10px; cursor:pointer;">
                    <input type="radio" name="deinterlaceMode" value="yadif" id="deintYadif" onchange="updateDeinterlaceSettings()">
                    <span><strong style="color:#34D399;">Yadif (Khuyên dùng)</strong> - Nét như PC, không mờ, không bóng ma</span>
                </label>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; margin-bottom:10px; cursor:pointer;">
                    <input type="radio" name="deinterlaceMode" value="linear" id="deintLinear" onchange="updateDeinterlaceSettings()">
                    <span><strong>Linear</strong> - Nội suy mượt mà, triệt tiêu xé hình, siêu nhẹ</span>
                </label>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; margin-bottom:10px; cursor:pointer;">
                    <input type="radio" name="deinterlaceMode" value="blend" id="deintBlend" onchange="updateDeinterlaceSettings()">
                    <span><strong>Blend</strong> - Gộp 2 trường quét (Mức độ cơ bản)</span>
                </label>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; cursor:pointer;">
                    <input type="radio" name="deinterlaceMode" value="off" id="deintOff" onchange="updateDeinterlaceSettings()">
                    <span><strong>Tắt Deinterlace</strong> - Giữ nguyên luồng gốc (cho kênh 720p/1080p)</span>
                </label>
            </div>
        </div>

        <!-- 3. HTTP USER AGENT -->
        <div class="form-card">
            <h3>🌐 3. HTTP User Agent Tùy Chỉnh</h3>
            <p style="font-size:12px; color:var(--text-sub); margin-bottom:10px;">Danh tính nhận diện gửi tới máy chủ phát IPTV. Mặc định dùng danh tính FPT Play Box để vượt rào chặn kênh.</p>
            <div class="form-group">
                <label>User Agent Chuỗi:</label>
                <textarea id="txtHttpUserAgent" class="form-input" style="height:60px; font-size:11px; resize:none;"></textarea>
            </div>
            <div style="display:flex; gap:8px;">
                <button class="submit-btn" style="flex:1;" onclick="updateUserAgentSettings()">Lưu User Agent</button>
                <button class="submit-btn" style="flex:1; background:#475569;" onclick="resetDefaultUserAgent()">Khôi Phục Mặc Định</button>
            </div>
        </div>

        <!-- 4. MÁY CHỦ UDPXY -->
        <div class="form-card">
            <h3>📡 4. Cấu Hình Máy Chủ udpxy</h3>
            <p style="font-size:12px; color:var(--text-sub); margin-bottom:10px;">Tự động chuyển đổi luồng multicast (UDP/RTP) sang HTTP proxy mà không cần sửa link.</p>
            <div class="form-group">
                <label>Máy chủ udpxy (IP:Port):</label>
                <input type="text" id="txtUdpxyServer" class="form-input" placeholder="Ví dụ: 192.168.1.1:4022 hoặc 10.0.0.222:4022">
            </div>
            <button class="submit-btn" onclick="updateUdpxySettings()">Lưu Cấu Hình udpxy</button>
        </div>

        <!-- 5. CÀI ĐẶT KHỞI ĐỘNG CÙNG TV -->
        <div class="form-card">
            <h3>🚀 5. Cài Đặt Khởi Động &amp; Mở Kênh</h3>
            <div style="margin-bottom:12px;">
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; cursor:pointer;">
                    <input type="checkbox" id="chkAutoStartBoot" onchange="updateBootSettings()">
                    <strong>Tự động khởi động ứng dụng khi bật Android TV</strong>
                </label>
            </div>
            <div>
                <label style="display:block; font-size:13px; color:var(--text-sub); margin-bottom:8px;">Chế độ mở kênh khi khởi động ứng dụng:</label>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; margin-bottom:10px; cursor:pointer;">
                    <input type="radio" name="startupMode" value="FIRST_CHANNEL" id="optFirstChannel" onchange="updateBootSettings()">
                    Mở kênh đầu tiên của danh sách (Mặc định)
                </label>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; margin-bottom:10px; cursor:pointer;">
                    <input type="radio" name="startupMode" value="LAST_WATCHED" id="optLastWatched" onchange="updateBootSettings()">
                    Vào lại kênh vừa xem gần nhất
                </label>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; cursor:pointer;">
                    <input type="radio" name="startupMode" value="FIXED_CHANNEL" id="optFixedChannel" onchange="updateBootSettings()">
                    Kênh cố định: <span id="lblFixedName" style="color:var(--accent); font-weight:bold;">Chưa chọn</span>
                </label>
            </div>
        </div>

        <!-- 6. SAO LƯU & KHÔI PHỤC -->
        <div class="form-card">
            <h3>💾 6. Sao Lưu &amp; Khôi Phục Toàn Bộ Cấu Hình</h3>
            <p style="font-size:12px; color:var(--text-sub); margin-bottom:10px;">Lưu toàn bộ danh sách M3U, bộ giải mã, khử răng cưa, User Agent, kênh yêu thích, udpxy và thiết lập về điện thoại/máy tính hoặc khôi phục lại.</p>
            <div style="display:flex; gap:10px; margin-bottom:10px;">
                <button class="submit-btn" style="flex:1; background:#38BDF8;" onclick="downloadBackup()">📥 Tải File Sao Lưu (.json)</button>
            </div>
            <div class="form-group" style="margin-top:10px;">
                <label>Khôi phục từ file sao lưu trên máy:</label>
                <input type="file" id="fileRestore" accept=".json" class="form-input" style="padding:6px;">
            </div>
            <button class="submit-btn" style="background:#10B981;" onclick="restoreBackupFromFile()">📤 Khôi Phục Cấu Hình</button>
        </div>
    </div>

    <!-- BOTTOM NAVIGATION -->
    <div class="bottom-nav">
        <button class="nav-btn active" onclick="switchTab('remote', this)">
            <svg viewBox="0 0 24 24"><path d="M17 1H7c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-2-2-2zm-5 18c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm3-8H9V9h6v2z"/></svg>
            Remote
        </button>
        <button class="nav-btn" onclick="switchTab('channels', this)">
            <svg viewBox="0 0 24 24"><path d="M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z"/></svg>
            Kênh TV
        </button>
        <button class="nav-btn" onclick="switchTab('favorites', this)">
            <svg viewBox="0 0 24 24"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"/></svg>
            Yêu Thích
        </button>
        <button class="nav-btn" onclick="switchTab('playlists', this)">
            <svg viewBox="0 0 24 24"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z"/></svg>
            Playlists
        </button>
        <button class="nav-btn" onclick="switchTab('settings', this)">
            <svg viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.56-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>
            Cài Đặt
        </button>
    </div>

    <div id="toast">Thông báo</div>

    <script>
        var allChannels = [];

        function showToast(msg) {
            var toast = document.getElementById('toast');
            toast.innerText = msg;
            toast.classList.add('show');
            setTimeout(function() { toast.classList.remove('show'); }, 2000);
        }

        function switchTab(tabId, btn) {
            var tabs = document.querySelectorAll('.tab-content');
            for (var i = 0; i < tabs.length; i++) tabs[i].classList.remove('active');
            var btns = document.querySelectorAll('.nav-btn');
            for (var j = 0; j < btns.length; j++) btns[j].classList.remove('active');

            document.getElementById('tab-' + tabId).classList.add('active');
            btn.classList.add('active');

            if (tabId === 'channels') loadChannels();
            if (tabId === 'favorites') loadFavorites();
            if (tabId === 'playlists') loadPlaylists();
            if (tabId === 'settings') loadSettings();
        }

        function sendKey(action) {
            fetch('/api/key', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ action: action })
            }).then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.nowPlaying) {
                    document.getElementById('bannerChName').innerText = data.nowPlaying;
                }
            }).catch(function() {
                showToast("Lỗi gửi lệnh!");
            });
        }

        function fetchStatus() {
            fetch('/api/status?t=' + Date.now(), { cache: 'no-store' }).then(function(res) { return res.json(); })
            .then(function(data) {
                document.getElementById('bannerChName').innerText = data.nowPlaying || 'Chưa phát kênh';
                document.getElementById('tvIpBadge').innerText = 'TV: ' + data.ip;
            }).catch(function() {});
        }

        function loadChannels() {
            var container = document.getElementById('channelsContainer');
            fetch('/api/channels?t=' + Date.now(), { cache: 'no-store' }).then(function(res) { return res.json(); })
            .then(function(data) {
                allChannels = data;
                renderChannels(allChannels);
            }).catch(function() {
                container.innerHTML = 'Lỗi tải danh sách kênh.';
            });
        }

        function renderChannels(list) {
            var container = document.getElementById('channelsContainer');
            if (list.length === 0) {
                container.innerHTML = '<div style="text-align:center; color:var(--text-sub); padding:30px;">Không có kênh nào.</div>';
                return;
            }
            var html = '';
            for (var i = 0; i < list.length; i++) {
                var ch = list[i];
                var numStr = ch.number < 10 ? '0' + ch.number : '' + ch.number;
                var encUrl = encodeURIComponent(ch.streamUrl);
                var logoHtml = ch.logoUrl ? '<img class="channel-logo" src="' + ch.logoUrl + '" onerror="this.style.display=\'none\'">' : '';
                var favClass = ch.isFavorite ? 'btn-fav active' : 'btn-fav';
                html += '<div class="channel-item" onclick="playChannel(\'' + encUrl + '\')">' +
                    '<div class="channel-left">' +
                        '<span class="channel-num">' + numStr + '</span>' +
                        logoHtml +
                        '<div class="channel-title">' + ch.name + '</div>' +
                    '</div>' +
                    '<button class="' + favClass + '" onclick="event.stopPropagation(); toggleFav(\'' + encUrl + '\')">★</button>' +
                '</div>';
            }
            container.innerHTML = html;
        }

        function filterChannels() {
            var keyword = document.getElementById('channelSearch').value.toLowerCase();
            var filtered = [];
            for (var i = 0; i < allChannels.length; i++) {
                if (allChannels[i].name.toLowerCase().indexOf(keyword) !== -1) {
                    filtered.push(allChannels[i]);
                }
            }
            renderChannels(filtered);
        }

        function loadFavorites() {
            var container = document.getElementById('favoritesContainer');
            fetch('/api/channels?t=' + Date.now(), { cache: 'no-store' }).then(function(res) { return res.json(); })
            .then(function(data) {
                allChannels = data;
                filterFavorites();
            }).catch(function() {
                container.innerHTML = 'Lỗi tải danh sách yêu thích.';
            });
        }

        function filterFavorites() {
            var searchEl = document.getElementById('favSearch');
            var keyword = searchEl ? searchEl.value.toLowerCase().trim() : '';
            var favList = allChannels.filter(function(ch) {
                return ch.isFavorite && (!keyword || ch.name.toLowerCase().indexOf(keyword) !== -1);
            });
            renderFavorites(favList);
        }

        function renderFavorites(list) {
            var container = document.getElementById('favoritesContainer');
            if (list.length === 0) {
                var searchEl = document.getElementById('favSearch');
                var keyword = searchEl ? searchEl.value.trim() : '';
                if (keyword) {
                    container.innerHTML = '<div style="text-align:center; color:var(--text-sub); padding:30px;">Không tìm thấy kênh yêu thích nào khớp với từ khóa.</div>';
                } else {
                    container.innerHTML = '<div style="text-align:center; color:var(--text-sub); padding:30px; line-height:1.6;">Chưa có kênh yêu thích nào.<br><span style="font-size:12px; color:var(--accent);">Nhấn biểu tượng ★ ở thẻ Kênh TV để lưu kênh yêu thích vào đây.</span></div>';
                }
                return;
            }
            var html = '';
            for (var i = 0; i < list.length; i++) {
                var ch = list[i];
                var numStr = ch.number < 10 ? '0' + ch.number : '' + ch.number;
                var encUrl = encodeURIComponent(ch.streamUrl);
                var logoHtml = ch.logoUrl ? '<img class="channel-logo" src="' + ch.logoUrl + '" onerror="this.style.display=\'none\'">' : '';
                html += '<div class="channel-item" onclick="playChannel(\'' + encUrl + '\')">' +
                    '<div class="channel-left">' +
                        '<span class="channel-num">' + numStr + '</span>' +
                        logoHtml +
                        '<div class="channel-title">' + ch.name + '</div>' +
                    '</div>' +
                    '<button class="btn-fav active" onclick="event.stopPropagation(); toggleFav(\'' + encUrl + '\')">★</button>' +
                '</div>';
            }
            container.innerHTML = html;
        }

        function playChannel(encodedUrl) {
            fetch('/api/play', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ streamUrl: decodeURIComponent(encodedUrl) })
            }).then(function(res) { return res.json(); })
            .then(function(data) {
                showToast('Đang phát: ' + data.channelName);
                document.getElementById('bannerChName').innerText = data.channelName;
            }).catch(function() {
                showToast('Lỗi gửi lệnh phát!');
            });
        }

        function toggleFav(encodedUrl) {
            fetch('/api/favorite/toggle', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ streamUrl: decodeURIComponent(encodedUrl) })
            }).then(function(res) { return res.json(); })
            .then(function(data) {
                showToast(data.isFavorite ? 'Đã thêm Yêu Thích ★' : 'Đã bỏ Yêu Thích');
                var targetUrl = decodeURIComponent(encodedUrl);
                for (var i = 0; i < allChannels.length; i++) {
                    if (allChannels[i].streamUrl === targetUrl) {
                        allChannels[i].isFavorite = data.isFavorite;
                        break;
                    }
                }
                filterChannels();
                filterFavorites();
            }).catch(function() {});
        }

        function loadPlaylists() {
            var container = document.getElementById('playlistsContainer');
            fetch('/api/playlists?t=' + Date.now(), { cache: 'no-store' })
            .then(function(res) { return res.json(); })
            .then(function(list) {
                var html = '';
                if (!list || list.length === 0) {
                    container.innerHTML = '<div style="color:var(--text-sub); padding:12px;">Chưa có playlist nào.</div>';
                    return;
                }
                for (var i = 0; i < list.length; i++) {
                    var item = list[i];
                    var itemClass = item.isSelected ? 'playlist-item selected' : 'playlist-item';
                    var selBadge = item.isSelected ? ' ✓ (Đang phát)' : '';
                    var selectBtn = !item.isSelected ? '<button class="btn-sm btn-select" onclick="selectPlaylist(\'' + item.id + '\')">Chọn</button>' : '';
                    html += '<div class="' + itemClass + '">' +
                        '<div class="playlist-info">' +
                            '<div class="playlist-name">' + item.name + selBadge + '</div>' +
                            '<div class="playlist-url">' + item.url + '</div>' +
                        '</div>' +
                        '<div class="playlist-actions">' +
                            selectBtn +
                            '<button class="btn-sm btn-delete" onclick="deletePlaylist(\'' + item.id + '\')">Xóa</button>' +
                        '</div>' +
                    '</div>';
                }
                container.innerHTML = html;
            }).catch(function() {
                container.innerHTML = 'Lỗi tải danh sách playlist.';
            });
        }

        function addNewPlaylist() {
            var name = document.getElementById('newPlName').value.trim();
            var url = document.getElementById('newPlUrl').value.trim();
            var selectNow = document.getElementById('newPlSelectNow') ? document.getElementById('newPlSelectNow').checked : true;
            if (!name || !url) {
                showToast('Vui lòng nhập tên và link M3U!');
                return;
            }
            showToast('Đang thêm playlist...');
            fetch('/api/playlist/add', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ name: name, url: url, selectImmediately: selectNow })
            }).then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.success) {
                    showToast('Đã thêm playlist mới lên TV thành công!');
                    document.getElementById('newPlName').value = '';
                    document.getElementById('newPlUrl').value = '';
                    loadPlaylists();
                    if (selectNow) {
                        setTimeout(loadChannels, 600);
                        setTimeout(fetchStatus, 600);
                    }
                } else {
                    showToast('Lỗi: ' + (data.error || 'Không thể thêm playlist'));
                }
            }).catch(function() {
                showToast('Lỗi thêm playlist!');
            });
        }

        function uploadLocalPlaylist() {
            var fileInput = document.getElementById('fileLocalM3u');
            if (!fileInput.files || fileInput.files.length === 0) {
                showToast('Vui lòng chọn file M3U!');
                return;
            }
            var file = fileInput.files[0];
            var customName = document.getElementById('uploadPlName').value.trim();
            var selectNow = document.getElementById('uploadPlSelectNow') ? document.getElementById('uploadPlSelectNow').checked : true;
            showToast('Đang tải file lên TV...');
            var reader = new FileReader();
            reader.onload = function(e) {
                var content = e.target.result;
                fetch('/api/playlist/upload', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({
                        name: customName,
                        filename: file.name,
                        content: content,
                        selectImmediately: selectNow
                    })
                }).then(function(res) { return res.json(); })
                .then(function(data) {
                    if (data.success) {
                        showToast('Đã tải lên TV thành công: ' + (data.name || file.name));
                        fileInput.value = '';
                        document.getElementById('uploadPlName').value = '';
                        loadPlaylists();
                        if (selectNow) {
                            setTimeout(loadChannels, 600);
                            setTimeout(fetchStatus, 600);
                        }
                    } else {
                        showToast('Lỗi tải file lên TV!');
                    }
                }).catch(function() {
                    showToast('Lỗi đường truyền khi tải file!');
                });
            };
            reader.readAsText(file);
        }

        function selectPlaylist(id) {
            showToast('Đang chuyển playlist trên TV...');
            fetch('/api/playlist/select', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ id: id })
            }).then(function() {
                showToast('Đã chuyển playlist trên TV!');
                loadPlaylists();
                setTimeout(loadChannels, 600);
                setTimeout(fetchStatus, 600);
            }).catch(function() {});
        }

        function deletePlaylist(id) {
            if (!confirm('Bạn có chắc chắn muốn xóa danh sách này?')) return;
            fetch('/api/playlist/delete', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ id: id })
            }).then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.success) {
                    showToast('Đã xóa playlist!');
                } else {
                    showToast(data.error || 'Không thể xóa danh sách!');
                }
                loadPlaylists();
                setTimeout(loadChannels, 600);
                setTimeout(fetchStatus, 600);
            }).catch(function() {});
        }

        var DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

        function loadSettings() {
            fetch('/api/settings?t=' + Date.now(), { cache: 'no-store' }).then(function(res) { return res.json(); })
            .then(function(data) {
                // 1. Player Engine
                if (data.playerEngine === 'EXOPLAYER') {
                    document.getElementById('engineExo').checked = true;
                } else {
                    document.getElementById('engineVlc').checked = true;
                }

                // 2. Deinterlace Mode
                if (data.deinterlaceMode === 'linear') {
                    document.getElementById('deintLinear').checked = true;
                } else if (data.deinterlaceMode === 'blend') {
                    document.getElementById('deintBlend').checked = true;
                } else if (data.deinterlaceMode === 'off') {
                    document.getElementById('deintOff').checked = true;
                } else {
                    document.getElementById('deintYadif').checked = true;
                }

                // 3. User Agent
                document.getElementById('txtHttpUserAgent').value = data.httpUserAgent || DEFAULT_USER_AGENT;

                // 4. Boot Settings
                document.getElementById('chkAutoStartBoot').checked = !!data.autoStartOnBoot;
                if (data.startupMode === 'FIXED_CHANNEL') {
                    document.getElementById('optFixedChannel').checked = true;
                } else if (data.startupMode === 'LAST_WATCHED') {
                    document.getElementById('optLastWatched').checked = true;
                } else {
                    document.getElementById('optFirstChannel').checked = true;
                }
                document.getElementById('lblFixedName').innerText = data.defaultStartupChannelName || 'Chưa chọn';

                // 5. UDPXY Server
                document.getElementById('txtUdpxyServer').value = data.udpxyServer || '';
            }).catch(function() {});
        }

        function updateDecoderSettings() {
            var engine = document.querySelector('input[name="playerEngine"]:checked').value;
            fetch('/api/settings/decoder', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ playerEngine: engine })
            }).then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.success) {
                    showToast('Đã đổi trình phát trên TV: ' + engine);
                }
            }).catch(function() {
                showToast('Lỗi lưu bộ giải mã!');
            });
        }

        function updateDeinterlaceSettings() {
            var mode = document.querySelector('input[name="deinterlaceMode"]:checked').value;
            fetch('/api/settings/deinterlace', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ deinterlaceMode: mode })
            }).then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.success) {
                    showToast('Đã đổi thuật toán Deinterlace: ' + mode.toUpperCase());
                }
            }).catch(function() {
                showToast('Lỗi lưu thuật toán Deinterlace!');
            });
        }

        function updateUserAgentSettings() {
            var ua = document.getElementById('txtHttpUserAgent').value.trim();
            if (!ua) ua = DEFAULT_USER_AGENT;
            fetch('/api/settings/useragent', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ httpUserAgent: ua })
            }).then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.success) {
                    showToast('Đã lưu User Agent mới trên TV!');
                    document.getElementById('txtHttpUserAgent').value = data.httpUserAgent;
                }
            }).catch(function() {
                showToast('Lỗi lưu User Agent!');
            });
        }

        function resetDefaultUserAgent() {
            document.getElementById('txtHttpUserAgent').value = DEFAULT_USER_AGENT;
            updateUserAgentSettings();
        }

        function updateBootSettings() {
            var autoBoot = document.getElementById('chkAutoStartBoot').checked;
            var mode = document.querySelector('input[name="startupMode"]:checked').value;
            fetch('/api/settings/boot', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ autoStartOnBoot: autoBoot, startupMode: mode })
            }).then(function() {
                showToast('Đã lưu cài đặt khởi động!');
            }).catch(function() {});
        }

        function updateUdpxySettings() {
            var val = document.getElementById('txtUdpxyServer').value.trim();
            fetch('/api/settings/udpxy', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ udpxyServer: val })
            }).then(function() {
                showToast('Đã lưu và cập nhật server udpxy!');
            }).catch(function() {
                showToast('Lỗi lưu server udpxy!');
            });
        }

        function downloadBackup() {
            window.location.href = '/api/backup';
        }

        function restoreBackupFromFile() {
            var fileInput = document.getElementById('fileRestore');
            if (!fileInput.files || fileInput.files.length === 0) {
                showToast('Vui lòng chọn file .json để khôi phục!');
                return;
            }
            var file = fileInput.files[0];
            var reader = new FileReader();
            reader.onload = function(e) {
                var content = e.target.result;
                fetch('/api/restore', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: content
                }).then(function(res) { return res.json(); })
                .then(function(data) {
                    if (data.success) {
                        showToast('Đã khôi phục thành công toàn bộ cài đặt!');
                        loadPlaylists();
                        loadSettings();
                    } else {
                        showToast('Lỗi: File sao lưu không hợp lệ!');
                    }
                }).catch(function() {
                    showToast('Lỗi khôi phục cấu hình!');
                });
            };
            reader.readAsText(file);
        }

        fetchStatus();
        setInterval(fetchStatus, 3000);
    </script>
</body>
</html>
        """.trimIndent()
    }
}
