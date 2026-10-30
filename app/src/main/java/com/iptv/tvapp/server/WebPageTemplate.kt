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
        <h1>📱 FPT PLAY IPTV REMOTE</h1>
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
            <button class="submit-btn" onclick="addNewPlaylist()">+ Thêm &amp; Lưu Vào TV</button>
        </div>

        <h3 style="font-size:15px; margin-bottom:10px; color:var(--text-main);">Danh Sách Playlist Đã Có:</h3>
        <div id="playlistsContainer">Đang tải...</div>
    </div>

    <!-- TAB 4: SETTINGS -->
    <div id="tab-settings" class="tab-content">
        <div class="form-card">
            <h3>⚙️ Cài Đặt Khởi Động TV</h3>
            <div style="margin-bottom:16px;">
                <label style="display:flex; align-items:center; gap:10px; font-size:14px; color:#fff; cursor:pointer;">
                    <input type="checkbox" id="chkAutoBoot" onchange="updateBootSettings()" style="width:18px; height:18px;">
                    Tự động mở ứng dụng khi TV khởi động
                </label>
            </div>

            <div style="margin-top:14px; border-top:1px solid var(--card-border); padding-top:14px;">
                <label style="display:block; font-size:13px; color:var(--text-sub); margin-bottom:6px;">Chế độ mở kênh khi khởi động:</label>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px; margin-bottom:8px;">
                    <input type="radio" name="startupMode" value="LAST_WATCHED" id="optLastWatched" onchange="updateBootSettings()">
                    Vào lại kênh vừa xem gần nhất
                </label>
                <label style="display:flex; align-items:center; gap:8px; font-size:13px;">
                    <input type="radio" name="startupMode" value="FIXED_CHANNEL" id="optFixedChannel" onchange="updateBootSettings()">
                    Kênh cố định: <span id="lblFixedName" style="color:var(--accent); font-weight:bold;">Chưa chọn</span>
                </label>
            </div>
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
            fetch('/api/status').then(function(res) { return res.json(); })
            .then(function(data) {
                document.getElementById('bannerChName').innerText = data.nowPlaying || 'Chưa phát kênh';
                document.getElementById('tvIpBadge').innerText = 'TV: ' + data.ip;
            }).catch(function() {});
        }

        function loadChannels() {
            var container = document.getElementById('channelsContainer');
            fetch('/api/channels').then(function(res) { return res.json(); })
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
                loadChannels();
            }).catch(function() {});
        }

        function loadPlaylists() {
            var container = document.getElementById('playlistsContainer');
            fetch('/api/playlists').then(function(res) { return res.json(); })
            .then(function(list) {
                var html = '';
                for (var i = 0; i < list.length; i++) {
                    var item = list[i];
                    var itemClass = item.isSelected ? 'playlist-item selected' : 'playlist-item';
                    var selBadge = item.isSelected ? ' ✓ (Đang chọn)' : '';
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
            if (!name || !url) {
                showToast('Vui lòng nhập tên và link M3U!');
                return;
            }
            fetch('/api/playlist/add', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ name: name, url: url })
            }).then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.success) {
                    showToast('Đã thêm playlist mới!');
                    document.getElementById('newPlName').value = '';
                    document.getElementById('newPlUrl').value = '';
                    loadPlaylists();
                }
            }).catch(function() {
                showToast('Lỗi thêm playlist!');
            });
        }

        function selectPlaylist(id) {
            fetch('/api/playlist/select', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ id: id })
            }).then(function() {
                showToast('Đã chuyển playlist trên TV!');
                loadPlaylists();
                fetchStatus();
            }).catch(function() {});
        }

        function deletePlaylist(id) {
            if (!confirm('Xóa danh sách này?')) return;
            fetch('/api/playlist/delete', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ id: id })
            }).then(function() {
                showToast('Đã xóa playlist!');
                loadPlaylists();
            }).catch(function() {});
        }

        function loadSettings() {
            fetch('/api/settings').then(function(res) { return res.json(); })
            .then(function(data) {
                document.getElementById('chkAutoBoot').checked = data.autoStartOnBoot;
                if (data.startupMode === 'FIXED_CHANNEL') {
                    document.getElementById('optFixedChannel').checked = true;
                } else {
                    document.getElementById('optLastWatched').checked = true;
                }
                document.getElementById('lblFixedName').innerText = data.defaultStartupChannelName || 'Chưa chọn';
            }).catch(function() {});
        }

        function updateBootSettings() {
            var autoBoot = document.getElementById('chkAutoBoot').checked;
            var mode = document.querySelector('input[name="startupMode"]:checked').value;
            fetch('/api/settings/boot', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ autoStartOnBoot: autoBoot, startupMode: mode })
            }).then(function() {
                showToast('Đã lưu cài đặt khởi động!');
            }).catch(function() {});
        }

        fetchStatus();
        setInterval(fetchStatus, 3000);
    </script>
</body>
</html>
        """.trimIndent()
    }
}
