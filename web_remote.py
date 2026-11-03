#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
TV Play Web Remote & LAN Hub for Armbian (10.0.0.222:8080)
Provides:
  1. Responsive Mobile Web Remote Controller (D-Pad, Channels, Sports, Settings)
  2. Real-time Event Streaming (SSE - Server-Sent Events) for Samsung Tizen & Android TV
  3. LAN Hub for M3U playlists and sports data (zero internet latency for TVs)
"""

import os
import sys
import json
import time
import queue
import urllib.request
import urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from threading import Lock

PORT = int(os.environ.get("PORT", "8080"))
DATA_DIR = os.environ.get("IPTV_DATA_DIR", "/opt/iptv-sports/data")
os.makedirs(DATA_DIR, exist_ok=True)

# Shared state
state_lock = Lock()
current_tv_state = {
    "nowPlaying": "Chưa có luồng phát",
    "channelNumber": 1,
    "currentMode": "tv",
    "clientType": "Chưa kết nối",
    "lastSeen": 0,
    "tvConnected": False
}

# Connected SSE client queues
sse_clients = []
sse_lock = Lock()

def broadcast_event(event_type, data):
    payload = f"event: {event_type}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
    with sse_lock:
        dead_clients = []
        for q in sse_clients:
            try:
                q.put_nowait(payload)
            except Exception:
                dead_clients.append(q)
        for d in dead_clients:
            if d in sse_clients:
                sse_clients.remove(d)

def get_local_or_remote_data(filename, remote_url):
    local_path = os.path.join(DATA_DIR, filename)
    if os.path.exists(local_path) and os.path.getsize(local_path) > 50:
        try:
            with open(local_path, "r", encoding="utf-8") as f:
                return f.read()
        except Exception:
            pass
    # Fetch from remote and cache
    try:
        req = urllib.request.Request(remote_url, headers={"User-Agent": "TVPlay-Armbian/1.0"})
        with urllib.request.urlopen(req, timeout=8) as resp:
            content = resp.read().decode("utf-8", "ignore")
            if len(content) > 50:
                with open(local_path, "w", encoding="utf-8") as f:
                    f.write(content)
                return content
    except Exception as e:
        print(f"[-] Fetch error for {filename}: {e}")
    return ""

def parse_m3u_channels(m3u_text):
    channels = []
    current_logo = ""
    current_group = "Tất cả"
    current_name = ""
    num = 1
    
    for line in m3u_text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.upper().startswith("#EXTINF:"):
            current_group = "Tất cả"
            current_logo = ""
            current_name = ""
            
            grp_m = urllib.parse.unquote(line)
            # Match group-title
            import re
            grp = re.search(r'group-title="([^"]+)"', line, re.I)
            if grp:
                current_group = grp.group(1).strip()
            logo = re.search(r'tvg-logo="([^"]+)"', line, re.I)
            if logo:
                current_logo = logo.group(1).strip()
            
            comma_idx = line.rfind(",")
            if comma_idx != -1:
                current_name = line[comma_idx+1:].strip()
        elif not line.startswith("#"):
            if current_name and line.startswith(("http", "udp", "rtp")):
                channels.append({
                    "number": num,
                    "name": current_name,
                    "logoUrl": current_logo or "icon.png",
                    "groupTitle": current_group,
                    "streamUrl": line
                })
                num += 1
                current_name = ""
    return channels

# Pre-cached channels in memory
cached_channels = []
cached_sports_data = None
last_m3u_load = 0

def get_channels():
    global cached_channels, last_m3u_load
    now = time.time()
    if not cached_channels or (now - last_m3u_load > 300):
        m3u = get_local_or_remote_data("ktv.m3u", "https://fastly.jsdelivr.net/gh/ndkhoams/iptv@main/ktv.m3u")
        if not m3u:
            m3u = get_local_or_remote_data("ktv.m3u", "https://raw.githubusercontent.com/ndkhoams/iptv/main/ktv.m3u")
        if m3u:
            cached_channels = parse_m3u_channels(m3u)
            last_m3u_load = now
    return cached_channels

def get_sports():
    global cached_sports_data
    content = get_local_or_remote_data("thethao-data.json", "https://fastly.jsdelivr.net/gh/ndkhoams/iptv@main/thethao-data.json")
    if not content:
        content = get_local_or_remote_data("thethao-data.json", "https://raw.githubusercontent.com/ndkhoams/iptv/main/thethao-data.json")
    if content:
        try:
            raw = json.loads(content)
            categories = []
            live_events = raw.get("live_events", [])
            if live_events:
                categories.append({
                    "name": f"🔴 LIVE EVENTS ({len(live_events)} trận)",
                    "matches": [{
                        "title": m.get("displayTitle", f"{m.get('homeTeam', '')} vs {m.get('awayTeam', '')}"),
                        "time": m.get("time", ""),
                        "isOngoing": m.get("isOngoing", False),
                        "sources": [{"name": s.get("name", "Nguồn"), "streamUrl": s.get("url", "")} for s in m.get("sources", [])]
                    } for m in live_events]
                })
            
            leagues = raw.get("leagues", {})
            for l_code, l_matches in leagues.items():
                if l_matches:
                    categories.append({
                        "name": f"🏆 {l_code} ({len(l_matches)} trận)",
                        "matches": [{
                            "title": m.get("displayTitle", f"{m.get('homeTeam', '')} vs {m.get('awayTeam', '')}"),
                            "time": m.get("time", ""),
                            "isOngoing": m.get("isOngoing", False),
                            "sources": [{"name": s.get("name", "Nguồn"), "streamUrl": s.get("url", "")} for s in m.get("sources", [])]
                        } for m in l_matches]
                    })
            cached_sports_data = {"categories": categories, "liveCount": len(live_events)}
            return cached_sports_data
        except Exception as e:
            print(f"[-] parse sports error: {e}")
    return cached_sports_data or {"categories": [], "liveCount": 0}

WEB_REMOTE_HTML = """<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>TV Play - Web Remote</title>
    <style>
        :root {
            --bg-color: #0E0C1B;
            --card-bg: #161526;
            --card-border: #2E294E;
            --accent: #FF6500;
            --accent-hover: #FF8533;
            --text-main: #f8fafc;
            --text-sub: #94a3b8;
            --green: #10b981;
            --red: #ef4444;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; -webkit-tap-highlight-color: transparent; }
        body { background-color: var(--bg-color); color: var(--text-main); min-height: 100vh; padding-bottom: 75px; user-select: none; }
        
        header { background: #121024; padding: 12px 16px; border-bottom: 1px solid var(--card-border); position: sticky; top: 0; z-index: 100; display: flex; justify-content: space-between; align-items: center; }
        .brand-title { display: flex; align-items: center; gap: 6px; font-size: 16px; font-weight: 800; }
        .brand-orange { color: var(--accent); }
        .tv-status-badge { font-size: 11px; padding: 3px 8px; border-radius: 12px; font-weight: 600; display: flex; align-items: center; gap: 5px; }
        .status-connected { background: rgba(16, 185, 129, 0.15); color: var(--green); border: 1px solid rgba(16, 185, 129, 0.3); }
        .status-disconnected { background: rgba(239, 68, 68, 0.15); color: var(--red); border: 1px solid rgba(239, 68, 68, 0.3); }
        .status-dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }

        .now-playing-banner { background: linear-gradient(135deg, rgba(255, 101, 0, 0.18), #0E0C1B); border-bottom: 1px solid rgba(255, 101, 0, 0.35); padding: 10px 16px; font-size: 13px; display: flex; justify-content: space-between; align-items: center; }
        .now-playing-left { display: flex; align-items: center; gap: 8px; overflow: hidden; }
        .ch-badge { background: var(--accent); color: #fff; font-size: 10px; font-weight: 800; padding: 2px 6px; border-radius: 4px; white-space: nowrap; }
        .ch-name { font-weight: 700; color: #fff; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .osd-btn { background: #1e293b; border: 1px solid var(--accent); color: var(--accent); font-size: 11px; font-weight: 700; padding: 5px 10px; border-radius: 6px; cursor: pointer; white-space: nowrap; }
        .osd-btn:active { background: var(--accent); color: #fff; }

        .tab-content { display: none; padding: 14px 16px; max-width: 540px; margin: 0 auto; }
        .tab-content.active { display: block; }

        /* REMOTE CONTROLLER TAB */
        .remote-card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 20px; padding: 18px 16px; box-shadow: 0 10px 30px rgba(0,0,0,0.5); margin-bottom: 14px; }
        .dpad-container { width: 230px; height: 230px; margin: 12px auto; position: relative; background: #0b1120; border-radius: 50%; border: 2px solid var(--card-border); box-shadow: inset 0 4px 14px rgba(0,0,0,0.7); }
        .dpad-btn { position: absolute; background: #1e293b; border: 1px solid var(--card-border); color: #fff; font-size: 18px; font-weight: bold; display: flex; align-items: center; justify-content: center; cursor: pointer; border-radius: 12px; transition: transform 0.08s, background 0.08s; }
        .dpad-btn:active { background: var(--accent); transform: scale(0.94); }
        .dpad-up { top: 10px; left: 77px; width: 76px; height: 54px; border-radius: 28px 28px 8px 8px; }
        .dpad-down { bottom: 10px; left: 77px; width: 76px; height: 54px; border-radius: 8px 8px 28px 28px; }
        .dpad-left { top: 77px; left: 10px; width: 54px; height: 76px; border-radius: 28px 8px 8px 28px; }
        .dpad-right { top: 77px; right: 10px; width: 54px; height: 76px; border-radius: 8px 28px 28px 8px; }
        .dpad-ok { top: 77px; left: 77px; width: 76px; height: 76px; border-radius: 50%; background: var(--accent); color: #fff; font-size: 19px; border: 2px solid var(--accent-hover); box-shadow: 0 4px 12px rgba(255, 101, 0, 0.4); }
        .dpad-ok:active { background: #e05800; transform: scale(0.92); }

        .row-actions { display: flex; gap: 10px; margin-top: 12px; }
        .action-btn { flex: 1; padding: 12px 6px; background: #1e293b; border: 1px solid var(--card-border); border-radius: 10px; color: #fff; font-size: 13px; font-weight: 700; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 6px; transition: all 0.1s; }
        .action-btn:active { background: var(--accent); border-color: var(--accent); transform: scale(0.95); }
        .btn-mode-toggle { background: #241e3d; border-color: #6366f1; color: #a5b4fc; }

        .keypad-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 12px; }
        .key-num { padding: 12px; background: #121024; border: 1px solid var(--card-border); border-radius: 8px; color: #fff; font-size: 15px; font-weight: 700; cursor: pointer; text-align: center; }
        .key-num:active { background: var(--accent); }

        /* BOTTOM NAV */
        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; height: 64px; background: #121024; border-top: 1px solid var(--card-border); display: flex; justify-content: space-around; align-items: center; z-index: 100; }
        .nav-btn { background: none; border: none; color: var(--text-sub); font-size: 11px; display: flex; flex-direction: column; align-items: center; gap: 4px; cursor: pointer; flex: 1; padding: 6px 0; font-weight: 600; }
        .nav-btn.active { color: var(--accent); font-weight: 800; }
        .nav-btn svg { width: 22px; height: 22px; fill: currentColor; }

        /* CHANNELS TAB */
        .cat-pills-wrap { display: flex; gap: 8px; overflow-x: auto; padding: 2px 0 10px; margin-bottom: 8px; scrollbar-width: none; }
        .cat-pills-wrap::-webkit-scrollbar { display: none; }
        .cat-pill { background: #1e293b; border: 1px solid var(--card-border); color: var(--text-sub); padding: 7px 14px; border-radius: 18px; font-size: 12px; font-weight: 600; white-space: nowrap; cursor: pointer; }
        .cat-pill.active { background: var(--accent); color: #fff; border-color: var(--accent-hover); box-shadow: 0 2px 8px rgba(255,101,0,0.4); }
        .search-box { width: 100%; padding: 11px 14px; background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 10px; color: #fff; font-size: 14px; margin-bottom: 12px; outline: none; }
        .search-box:focus { border-color: var(--accent); }
        .channels-list { max-height: calc(100vh - 240px); overflow-y: auto; }
        .channel-row { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 10px; padding: 10px 14px; margin-bottom: 8px; display: flex; align-items: center; justify-content: space-between; cursor: pointer; transition: background 0.12s; }
        .channel-row:active { background: rgba(255, 101, 0, 0.2); border-color: var(--accent); }
        .channel-left { display: flex; align-items: center; gap: 12px; flex: 1; overflow: hidden; }
        .ch-num { color: var(--accent); font-weight: 800; font-size: 14px; min-width: 26px; }
        .ch-title { font-size: 14px; font-weight: 700; color: #fff; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .ch-group { font-size: 11px; color: var(--text-sub); }

        /* SPORTS TAB */
        .sports-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
        .refresh-btn { background: #1e293b; border: 1px solid var(--accent); color: var(--accent); font-size: 12px; font-weight: 700; padding: 8px 14px; border-radius: 8px; cursor: pointer; }
        .refresh-btn:active { background: var(--accent); color: #fff; }
        .match-card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 12px 14px; margin-bottom: 10px; }
        .match-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; cursor: pointer; }
        .match-title { font-size: 14px; font-weight: 700; color: #fff; line-height: 1.35; flex: 1; }
        .match-time-badge { background: #334155; color: #cbd5e1; font-size: 11px; padding: 3px 8px; border-radius: 8px; font-weight: 700; white-space: nowrap; }
        .badge-live { background: var(--red); color: #fff; animation: pulse 1.5s infinite; }
        @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.6; } }
        .sources-box { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--card-border); display: none; }
        .sources-box.show { display: block; }
        .source-item { width: 100%; padding: 10px 12px; background: #0b1120; border: 1px solid var(--card-border); border-radius: 8px; color: #e2e8f0; font-size: 13px; font-weight: 600; margin-bottom: 6px; cursor: pointer; display: flex; justify-content: space-between; align-items: center; }
        .source-item:active { background: var(--accent); color: #fff; border-color: var(--accent-hover); }

        /* SETTINGS TAB */
        .settings-card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 16px; margin-bottom: 14px; }
        .settings-card h3 { font-size: 14px; color: var(--accent); margin-bottom: 10px; font-weight: 700; }
        .info-row { display: flex; justify-content: space-between; font-size: 13px; padding: 6px 0; border-bottom: 1px solid rgba(255,255,255,0.05); }
        .info-row span:first-child { color: var(--text-sub); }
        .info-row span:last-child { color: #fff; font-weight: 600; }

        /* TOAST */
        #toast { position: fixed; top: 60px; left: 50%; transform: translateX(-50%); background: #1e293b; border: 1px solid var(--accent); color: #fff; padding: 8px 16px; border-radius: 20px; font-size: 13px; font-weight: 600; z-index: 200; box-shadow: 0 4px 14px rgba(0,0,0,0.6); opacity: 0; transition: opacity 0.25s; pointer-events: none; }
        #toast.show { opacity: 1; }
    </style>
</head>
<body>

    <header>
        <div class="brand-title">
            <span>📺</span>
            <span>TV <span class="brand-orange">PLAY</span> REMOTE</span>
        </div>
        <div id="tvStatusBadge" class="tv-status-badge status-disconnected">
            <span class="status-dot"></span>
            <span id="tvStatusText">Chờ TV kết nối...</span>
        </div>
    </header>

    <div class="now-playing-banner">
        <div class="now-playing-left">
            <span class="ch-badge">ĐANG PHÁT</span>
            <span id="bannerChName" class="ch-name">Chưa có luồng phát</span>
        </div>
        <button class="osd-btn" onclick="sendKey('overlay')">Bật OSD 📺</button>
    </div>

    <!-- TAB 1: REMOTE -->
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
                <button class="action-btn" onclick="sendKey('menu')">☰ Menu / Cài Đặt</button>
            </div>

            <div class="row-actions">
                <button class="action-btn" onclick="sendKey('ch_prev')">⏮ Kênh Trước</button>
                <button class="action-btn" onclick="sendKey('ch_next')">⏭ Kênh Tiếp</button>
            </div>

            <div class="row-actions">
                <button class="action-btn" onclick="sendKey('vol_down')">🔉 Giảm Âm</button>
                <button class="action-btn" onclick="sendKey('mute')">🔇 Tắt Tiếng</button>
                <button class="action-btn" onclick="sendKey('vol_up')">🔊 Tăng Âm</button>
            </div>

            <div class="row-actions">
                <button class="action-btn btn-mode-toggle" onclick="sendKey('mode_tv')">📺 Xem TV</button>
                <button class="action-btn btn-mode-toggle" onclick="sendKey('mode_sports')">⚽ Trực tiếp Thể Thao</button>
            </div>

            <div class="keypad-grid">
                <button class="key-num" onclick="sendKey('1')">1</button>
                <button class="key-num" onclick="sendKey('2')">2</button>
                <button class="key-num" onclick="sendKey('3')">3</button>
                <button class="key-num" onclick="sendKey('4')">4</button>
                <button class="key-num" onclick="sendKey('5')">5</button>
                <button class="key-num" onclick="sendKey('6')">6</button>
                <button class="key-num" onclick="sendKey('7')">7</button>
                <button class="key-num" onclick="sendKey('8')">8</button>
                <button class="key-num" onclick="sendKey('9')">9</button>
                <button class="key-num" onclick="sendKey('mode_fav')">★</button>
                <button class="key-num" onclick="sendKey('0')">0</button>
                <button class="key-num" onclick="sendKey('exit')">Thoát</button>
            </div>
        </div>
    </div>

    <!-- TAB 2: KÊNH TV -->
    <div id="tab-channels" class="tab-content">
        <div id="catPills" class="cat-pills-wrap"></div>
        <input type="text" id="channelSearch" class="search-box" placeholder="🔍 Tìm kiếm kênh truyền hình..." oninput="filterChannels()">
        <div id="channelsContainer" class="channels-list">Đang tải danh sách kênh...</div>
    </div>

    <!-- TAB 3: THỂ THAO -->
    <div id="tab-sports" class="tab-content">
        <div class="sports-header">
            <span style="font-size:14px; font-weight:700;">🔥 LỊCH ĐẤU TRỰC TIẾP HÔM NAY</span>
            <button class="refresh-btn" onclick="loadSports()">🔄 Làm mới</button>
        </div>
        <div id="sportsContainer">Đang tải dữ liệu thể thao...</div>
    </div>

    <!-- TAB 4: CÀI ĐẶT -->
    <div id="tab-settings" class="tab-content">
        <div class="settings-card">
            <h3>📡 HỆ THỐNG MẠNG & ARMBIAN HUB</h3>
            <div class="info-row"><span>Máy chủ Web Remote:</span><span id="infoRemoteHost">10.0.0.222:8080</span></div>
            <div class="info-row"><span>Máy chủ udpxy Multicast:</span><span>10.0.0.222:4022</span></div>
            <div class="info-row"><span>Kênh TV LAN M3U:</span><span>/data/ktv.m3u</span></div>
            <div class="info-row"><span>Thể thao LAN JSON:</span><span>/data/thethao-data.json</span></div>
        </div>

        <div class="settings-card">
            <h3>📺 TRẠNG THÁI TV THÔNG MINH</h3>
            <div class="info-row"><span>Loại TV:</span><span id="infoTvType">Đang chờ kết nối</span></div>
            <div class="info-row"><span>Kênh đang xem:</span><span id="infoTvChannel">Chưa có luồng phát</span></div>
            <div class="info-row"><span>Chế độ:</span><span id="infoTvMode">TV</span></div>
        </div>
    </div>

    <!-- BOTTOM NAVIGATION -->
    <div class="bottom-nav">
        <button class="nav-btn active" onclick="switchTab('remote')">
            <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm-1-13h2v4h-2zm0 6h2v2h-2z"/></svg>
            <span>Điều Khiển</span>
        </button>
        <button class="nav-btn" onclick="switchTab('channels')">
            <svg viewBox="0 0 24 24"><path d="M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z"/></svg>
            <span>Kênh TV</span>
        </button>
        <button class="nav-btn" onclick="switchTab('sports')">
            <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/></svg>
            <span>Thể Thao</span>
        </button>
        <button class="nav-btn" onclick="switchTab('settings')">
            <svg viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>
            <span>Cài Đặt</span>
        </button>
    </div>

    <div id="toast"></div>

    <script>
        var allChannels = [];
        var activeCat = 'Tất cả';
        var toastTimer = null;

        function showToast(msg) {
            var t = document.getElementById('toast');
            t.textContent = msg;
            t.classList.add('show');
            clearTimeout(toastTimer);
            toastTimer = setTimeout(function() { t.classList.remove('show'); }, 2000);
        }

        function switchTab(tabId) {
            document.querySelectorAll('.tab-content').forEach(function(el) { el.classList.remove('active'); });
            document.querySelectorAll('.nav-btn').forEach(function(el) { el.classList.remove('active'); });
            var targetTab = document.getElementById('tab-' + tabId);
            if (targetTab) targetTab.classList.add('active');
            
            var btns = document.querySelectorAll('.nav-btn');
            if (tabId === 'remote') btns[0].classList.add('active');
            if (tabId === 'channels') { btns[1].classList.add('active'); if (!allChannels.length) loadChannels(); }
            if (tabId === 'sports') { btns[2].classList.add('active'); loadSports(); }
            if (tabId === 'settings') btns[3].classList.add('active');
        }

        function sendKey(key) {
            if (navigator.vibrate) navigator.vibrate(25);
            fetch('/api/key', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ key: key })
            }).then(function(res) { return res.json(); }).then(function(data) {
                showToast('Đã gửi: ' + key.toUpperCase());
            }).catch(function(e) {
                showToast('Lỗi gửi lệnh!');
            });
        }

        function playChannel(ch) {
            if (navigator.vibrate) navigator.vibrate(30);
            fetch('/api/play', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(ch)
            }).then(function(res) { return res.json(); }).then(function(d) {
                showToast('Phát kênh: ' + ch.name);
                document.getElementById('bannerChName').textContent = '[' + ch.number + '] ' + ch.name;
            }).catch(function(e) {
                showToast('Lỗi phát kênh');
            });
        }

        function playSportsSource(matchTitle, source) {
            if (navigator.vibrate) navigator.vibrate(30);
            fetch('/api/sports/play', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    matchTitle: matchTitle,
                    sourceName: source.name,
                    streamUrl: source.streamUrl
                })
            }).then(function(res) { return res.json(); }).then(function(d) {
                showToast('Phát trận: ' + matchTitle);
                document.getElementById('bannerChName').textContent = '⚽ ' + matchTitle + ' (' + source.name + ')';
            }).catch(function(e) {
                showToast('Lỗi phát luồng thể thao');
            });
        }

        function loadChannels() {
            var container = document.getElementById('channelsContainer');
            container.innerHTML = 'Đang tải danh sách kênh...';
            fetch('/api/channels').then(function(res) { return res.json(); }).then(function(channels) {
                allChannels = channels;
                buildCategories();
                renderChannels();
            }).catch(function(e) {
                container.innerHTML = '<div style="color:var(--red); padding:10px;">Lỗi nạp kênh từ Armbian</div>';
            });
        }

        function buildCategories() {
            var cats = ['Tất cả'];
            var catMap = {};
            allChannels.forEach(function(c) {
                var g = c.groupTitle || 'Khác';
                if (!catMap[g]) { catMap[g] = true; cats.push(g); }
            });
            var pills = document.getElementById('catPills');
            pills.innerHTML = '';
            cats.forEach(function(cat) {
                var btn = document.createElement('button');
                btn.className = 'cat-pill' + (cat === activeCat ? ' active' : '');
                btn.textContent = cat;
                btn.onclick = function() {
                    activeCat = cat;
                    document.querySelectorAll('.cat-pill').forEach(function(p) { p.classList.remove('active'); });
                    btn.classList.add('active');
                    filterChannels();
                };
                pills.appendChild(btn);
            });
        }

        function filterChannels() {
            var query = (document.getElementById('channelSearch').value || '').toLowerCase().trim();
            var filtered = allChannels.filter(function(ch) {
                var matchCat = (activeCat === 'Tất cả') || (ch.groupTitle === activeCat);
                var matchQuery = !query || ch.name.toLowerCase().indexOf(query) !== -1 || String(ch.number) === query;
                return matchCat && matchQuery;
            });
            renderChannelList(filtered);
        }

        function renderChannels() {
            filterChannels();
        }

        function renderChannelList(list) {
            var container = document.getElementById('channelsContainer');
            if (!list.length) {
                container.innerHTML = '<div style="color:var(--text-sub); padding:16px; text-align:center;">Không tìm thấy kênh phù hợp</div>';
                return;
            }
            var html = '';
            list.forEach(function(ch, idx) {
                html += '<div class="channel-row" onclick=\\'playChannel(' + JSON.stringify(ch).replace(/'/g, "&#39;") + ')\\'>' +
                    '<div class="channel-left">' +
                        '<span class="ch-num">' + (ch.number < 10 ? '0' + ch.number : ch.number) + '</span>' +
                        '<div>' +
                            '<div class="ch-title">' + ch.name + '</div>' +
                            '<div class="ch-group">' + (ch.groupTitle || 'Kênh TV') + '</div>' +
                        '</div>' +
                    '</div>' +
                    '<span style="color:var(--accent); font-size:12px; font-weight:700;">PHÁT ▶</span>' +
                '</div>';
            });
            container.innerHTML = html;
        }

        function toggleMatchSources(boxId) {
            var box = document.getElementById(boxId);
            if (box) {
                box.classList.toggle('show');
            }
        }

        function loadSports() {
            var container = document.getElementById('sportsContainer');
            container.innerHTML = 'Đang tải dữ liệu thể thao...';
            fetch('/api/sports/categories').then(function(res) { return res.json(); }).then(function(data) {
                renderSports(data);
            }).catch(function(e) {
                container.innerHTML = '<div style="color:var(--red); padding:10px;">Lỗi tải dữ liệu thể thao</div>';
            });
        }

        function renderSports(data) {
            var container = document.getElementById('sportsContainer');
            var categories = data.categories || [];
            if (!categories.length) {
                container.innerHTML = '<div style="color:var(--text-sub); padding:16px; text-align:center;">Hiện chưa có lịch trận đấu thể thao trực tiếp</div>';
                return;
            }
            var html = '';
            var boxCounter = 0;
            categories.forEach(function(cat) {
                var isLive = (cat.name || '').toUpperCase().indexOf('LIVE EVENTS') !== -1;
                html += '<div style="margin: 14px 0 8px; font-size:13px; font-weight:800; color:' + (isLive ? 'var(--red)' : 'var(--accent)') + ';">' + cat.name + '</div>';
                
                (cat.matches || []).forEach(function(m) {
                    boxCounter++;
                    var boxId = 'match-sources-' + boxCounter;
                    var sources = m.sources || [];
                    html += '<div class="match-card">' +
                        '<div class="match-header" onclick="toggleMatchSources(\\'' + boxId + '\\')">' +
                            '<div class="match-title">' + m.title + '</div>' +
                            '<div class="match-time-badge ' + (m.isOngoing ? 'badge-live' : '') + '">' +
                                (m.isOngoing ? '● TRỰC TIẾP' : (m.time || 'Sắp đá')) +
                            '</div>' +
                        '</div>' +
                        '<div id="' + boxId + '" class="sources-box">';
                    
                    if (!sources.length) {
                        html += '<div style="font-size:12px; color:var(--text-sub); padding:6px 0;">Chưa cập nhật luồng phát</div>';
                    } else {
                        sources.forEach(function(s) {
                            var sJson = JSON.stringify(s).replace(/'/g, "&#39;");
                            var titleEsc = m.title.replace(/'/g, "\\\\'");
                            html += '<button class="source-item" onclick=\\'playSportsSource("' + titleEsc + '", ' + sJson + ')\\'>' +
                                '<span>📺 ' + s.name + '</span>' +
                                '<span style="color:var(--accent);">Chọn ▶</span>' +
                            '</button>';
                        });
                    }
                    html += '</div></div>';
                });
            });
            container.innerHTML = html;
        }

        // Poll TV state every 3s
        function checkTvStatus() {
            fetch('/api/status').then(function(res) { return res.json(); }).then(function(data) {
                var badge = document.getElementById('tvStatusBadge');
                var text = document.getElementById('tvStatusText');
                var banner = document.getElementById('bannerChName');
                var infoType = document.getElementById('infoTvType');
                var infoCh = document.getElementById('infoTvChannel');
                var infoMode = document.getElementById('infoTvMode');

                if (data.tvConnected) {
                    badge.className = 'tv-status-badge status-connected';
                    text.textContent = 'TV: Đã kết nối (' + (data.clientType || 'Tizen') + ')';
                    infoType.textContent = data.clientType || 'Samsung Tizen';
                } else {
                    badge.className = 'tv-status-badge status-disconnected';
                    text.textContent = 'Chờ TV kết nối...';
                    infoType.textContent = 'Chưa kết nối';
                }

                if (data.nowPlaying) {
                    banner.textContent = data.nowPlaying;
                    infoCh.textContent = data.nowPlaying;
                }
                if (data.currentMode) {
                    infoMode.textContent = data.currentMode.toUpperCase();
                }
            }).catch(function(e) {});
        }

        checkTvStatus();
        setInterval(checkTvStatus, 3000);
    </script>
</body>
</html>
"""

class RemoteHTTPRequestHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Silent standard logs to avoid cluttering journalctl
        pass

    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")

    def do_HEAD(self):
        self.do_GET()

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self):
        url_parts = urllib.parse.urlparse(self.path)
        path = url_parts.path

        if path in ["/", "/index.html"]:
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(WEB_REMOTE_HTML.encode("utf-8"))
            return

        # Real-time SSE Endpoint for Samsung Tizen & Android TV
        if path == "/events":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_cors_headers()
            self.end_headers()

            q = queue.Queue(maxsize=100)
            with sse_lock:
                sse_clients.append(q)

            # Send welcome message
            welcome_payload = f"event: welcome\ndata: {json.dumps({'status': 'connected', 'server': 'Armbian 10.0.0.222:8080'}, ensure_ascii=False)}\n\n"
            try:
                self.wfile.write(welcome_payload.encode("utf-8"))
                self.wfile.flush()
            except Exception:
                with sse_lock:
                    if q in sse_clients: sse_clients.remove(q)
                return

            try:
                while True:
                    try:
                        msg = q.get(timeout=15)
                        self.wfile.write(msg.encode("utf-8"))
                        self.wfile.flush()
                    except queue.Empty:
                        # Send ping comment to keep connection alive
                        self.wfile.write(b": keep-alive\n\n")
                        self.wfile.flush()
            except Exception:
                pass
            finally:
                with sse_lock:
                    if q in sse_clients:
                        sse_clients.remove(q)
            return

        if path == "/api/status":
            with state_lock:
                now = time.time()
                # Consider connected if last report was within 15 seconds
                is_connected = (now - current_tv_state.get("lastSeen", 0)) < 15
                resp_data = dict(current_tv_state)
                resp_data["tvConnected"] = is_connected
            
            resp_bytes = json.dumps(resp_data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(resp_bytes)
            return

        if path == "/api/channels":
            channels = get_channels()
            resp_bytes = json.dumps(channels, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(resp_bytes)
            return

        if path == "/api/sports/categories":
            sports_data = get_sports()
            resp_bytes = json.dumps(sports_data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(resp_bytes)
            return

        # Serve static data files from DATA_DIR (e.g. /data/ktv.m3u, /data/thethao-data.json)
        if path.startswith("/data/"):
            filename = os.path.basename(path)
            filepath = os.path.join(DATA_DIR, filename)
            if not os.path.exists(filepath):
                # Try auto-fetching if it is ktv.m3u or thethao-data.json
                if filename == "ktv.m3u":
                    get_local_or_remote_data("ktv.m3u", "https://fastly.jsdelivr.net/gh/ndkhoams/iptv@main/ktv.m3u")
                elif filename == "thethao-data.json":
                    get_local_or_remote_data("thethao-data.json", "https://fastly.jsdelivr.net/gh/ndkhoams/iptv@main/thethao-data.json")

            if os.path.exists(filepath):
                self.send_response(200)
                if filename.endswith(".m3u"):
                    self.send_header("Content-Type", "audio/x-mpegurl; charset=utf-8")
                elif filename.endswith(".json"):
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                else:
                    self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_cors_headers()
                self.end_headers()
                with open(filepath, "rb") as f:
                    self.wfile.write(f.read())
                return

        self.send_response(404)
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(b"404 Not Found")

    def do_POST(self):
        url_parts = urllib.parse.urlparse(self.path)
        path = url_parts.path

        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length).decode("utf-8", "ignore") if content_length > 0 else ""
        
        post_data = {}
        if body:
            try:
                post_data = json.loads(body)
            except Exception:
                try:
                    post_data = dict(urllib.parse.parse_qsl(body))
                except Exception:
                    pass

        if path == "/api/key":
            key = post_data.get("key", "").strip().lower()
            if key:
                print(f"[Remote] Key: {key}")
                broadcast_event("key", {"key": key})
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "key": key}).encode("utf-8"))
            return

        if path == "/api/play":
            print(f"[Remote] Play channel: {post_data.get('name')}")
            broadcast_event("play_channel", post_data)
            with state_lock:
                current_tv_state["nowPlaying"] = f"[{post_data.get('number', '')}] {post_data.get('name', '')}"
                current_tv_state["currentMode"] = "tv"
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True}).encode("utf-8"))
            return

        if path == "/api/sports/play":
            print(f"[Remote] Play sports: {post_data.get('matchTitle')}")
            broadcast_event("play_match", post_data)
            with state_lock:
                current_tv_state["nowPlaying"] = f"⚽ {post_data.get('matchTitle', '')} ({post_data.get('sourceName', '')})"
                current_tv_state["currentMode"] = "sports"
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True}).encode("utf-8"))
            return

        if path == "/api/tv_state":
            with state_lock:
                current_tv_state["nowPlaying"] = post_data.get("nowPlaying", current_tv_state["nowPlaying"])
                current_tv_state["channelNumber"] = post_data.get("channelNumber", current_tv_state["channelNumber"])
                current_tv_state["currentMode"] = post_data.get("currentMode", current_tv_state["currentMode"])
                current_tv_state["clientType"] = post_data.get("clientType", "Samsung Tizen")
                current_tv_state["lastSeen"] = time.time()
                current_tv_state["tvConnected"] = True
            
            # Broadcast state change to any open web remotes
            broadcast_event("tv_state_update", current_tv_state)

            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True}).encode("utf-8"))
            return

        self.send_response(404)
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(b"404 Not Found")

def run():
    server_address = ("0.0.0.0", PORT)
    httpd = ThreadingHTTPServer(server_address, RemoteHTTPRequestHandler)
    print(f"==================================================")
    print(f"  TV PLAY WEB REMOTE & LAN HUB RUNNING ON PORT {PORT}")
    print(f"  Access URL: http://10.0.0.222:{PORT}")
    print(f"  Data Directory: {DATA_DIR}")
    print(f"==================================================")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()

if __name__ == "__main__":
    run()
