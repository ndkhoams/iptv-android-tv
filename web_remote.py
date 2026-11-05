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

# Playlist Management
playlists_lock = Lock()

def get_playlists():
    pl_path = os.path.join(DATA_DIR, "playlists.json")
    with playlists_lock:
        if os.path.exists(pl_path):
            try:
                with open(pl_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, list) and len(data) > 0:
                        return data
            except Exception as e:
                print(f"[-] Load playlists error: {e}")
        # Default playlists
        default_pl = [
            {
                "id": "pl_ktv",
                "name": "Kênh Truyền Hình (KTV)",
                "url": f"http://10.0.0.222:{PORT}/data/ktv.m3u",
                "isSelected": True
            },
            {
                "id": "pl_thethao",
                "name": "Thể Thao M3U",
                "url": f"http://10.0.0.222:{PORT}/data/thethao.m3u",
                "isSelected": False
            }
        ]
        try:
            with open(pl_path, "w", encoding="utf-8") as f:
                json.dump(default_pl, f, ensure_ascii=False, indent=2)
        except Exception:
            pass
        return default_pl

def save_playlists(playlists):
    pl_path = os.path.join(DATA_DIR, "playlists.json")
    with playlists_lock:
        try:
            with open(pl_path, "w", encoding="utf-8") as f:
                json.dump(playlists, f, ensure_ascii=False, indent=2)
            return True
        except Exception as e:
            print(f"[-] Save playlists error: {e}")
            return False

def get_active_playlist():
    playlists = get_playlists()
    for pl in playlists:
        if pl.get("isSelected"):
            return pl
    if playlists:
        playlists[0]["isSelected"] = True
        save_playlists(playlists)
        return playlists[0]
    return {"id": "pl_ktv", "name": "KTV", "url": f"http://10.0.0.222:{PORT}/data/ktv.m3u", "isSelected": True}

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
current_cached_url = ""

def get_channels(force_refresh=False):
    global cached_channels, last_m3u_load, current_cached_url
    now = time.time()
    active_pl = get_active_playlist()
    active_url = active_pl.get("url", f"http://10.0.0.222:{PORT}/data/ktv.m3u")
    
    if force_refresh or not cached_channels or (current_cached_url != active_url) or (now - last_m3u_load > 300):
        m3u_content = ""
        parsed = urllib.parse.urlparse(active_url)
        if parsed.path.startswith("/data/"):
            fname = os.path.basename(parsed.path)
            local_path = os.path.join(DATA_DIR, fname)
            if os.path.exists(local_path):
                try:
                    with open(local_path, "r", encoding="utf-8") as f:
                        m3u_content = f.read()
                except Exception:
                    pass
            if not m3u_content and fname == "ktv.m3u":
                m3u_content = get_local_or_remote_data("ktv.m3u", "https://fastly.jsdelivr.net/gh/ndkhoams/iptv@main/ktv.m3u")
                if not m3u_content:
                    m3u_content = get_local_or_remote_data("ktv.m3u", "https://raw.githubusercontent.com/ndkhoams/iptv/main/ktv.m3u")
        else:
            fname = f"cache_{active_pl.get('id', 'temp')}.m3u"
            m3u_content = get_local_or_remote_data(fname, active_url)
            
        if m3u_content:
            cached_channels = parse_m3u_channels(m3u_content)
            last_m3u_load = now
            current_cached_url = active_url
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

        /* PLAYLIST TAB */
        .form-card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 14px; padding: 16px; margin-bottom: 14px; box-shadow: 0 4px 16px rgba(0,0,0,0.3); }
        .form-card h3 { font-size: 14px; color: var(--accent); margin-bottom: 12px; font-weight: 700; display: flex; align-items: center; gap: 6px; }
        .form-group { margin-bottom: 12px; }
        .form-group label { display: block; font-size: 12px; color: var(--text-sub); margin-bottom: 5px; font-weight: 600; }
        .form-input { width: 100%; padding: 11px 13px; background: #0b1120; border: 1px solid var(--card-border); border-radius: 8px; color: #fff; font-size: 13px; outline: none; }
        .form-input:focus { border-color: var(--accent); }
        .submit-btn { width: 100%; padding: 12px; background: var(--accent); border: none; border-radius: 8px; color: #fff; font-weight: bold; font-size: 13px; cursor: pointer; transition: background 0.15s; }
        .submit-btn:active { background: var(--accent-hover); transform: scale(0.98); }

        .playlist-item { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 12px 14px; margin-bottom: 10px; display: flex; align-items: center; justify-content: space-between; gap: 10px; transition: border-color 0.15s; }
        .playlist-item.selected { border-color: var(--accent); background: rgba(255, 101, 0, 0.12); box-shadow: 0 0 12px rgba(255,101,0,0.25); }
        .playlist-info { flex: 1; overflow: hidden; }
        .playlist-name { font-weight: bold; font-size: 14px; color: #fff; margin-bottom: 4px; display: flex; align-items: center; gap: 6px; }
        .playlist-badge-sel { background: var(--green); color: #fff; font-size: 10px; font-weight: 800; padding: 2px 7px; border-radius: 4px; white-space: nowrap; }
        .playlist-url { font-size: 11px; color: var(--text-sub); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .playlist-actions { display: flex; gap: 6px; flex-shrink: 0; }
        .btn-sm { padding: 7px 12px; border-radius: 6px; font-size: 12px; font-weight: bold; cursor: pointer; border: none; transition: transform 0.1s; }
        .btn-sm:active { transform: scale(0.94); }
        .btn-select { background: var(--green); color: #fff; }
        .btn-delete { background: var(--red); color: #fff; }

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

    <!-- TAB 4: DANH SÁCH PHÁT (PLAYLISTS) -->
    <div id="tab-playlists" class="tab-content">
        <div class="form-card">
            <h3>🔗 Dán Link M3U Mới Từ Điện Thoại</h3>
            <div class="form-group">
                <label>Tên danh sách</label>
                <input type="text" id="newPlName" class="form-input" placeholder="Ví dụ: Kênh Thể Thao, Kênh Nước Ngoài...">
            </div>
            <div class="form-group">
                <label>Đường link URL (M3U / M3U8)</label>
                <input type="url" id="newPlUrl" class="form-input" placeholder="http://... hoặc https://...">
            </div>
            <div style="margin-top:8px; margin-bottom:12px; display:flex; align-items:center; gap:8px;">
                <input type="checkbox" id="newPlSelectNow" checked style="width:18px; height:18px; accent-color:#FF6500; cursor:pointer;">
                <label for="newPlSelectNow" style="font-size:13px; color:var(--text-main); cursor:pointer;">Kích hoạt phát danh sách này ngay trên TV</label>
            </div>
            <button class="submit-btn" onclick="addNewPlaylist()">+ Thêm &amp; Lưu Vào TV</button>
        </div>

        <div class="form-card">
            <h3>📂 Tải File M3U Từ Điện Thoại Lên TV</h3>
            <p style="font-size:12px; color:var(--text-sub); margin-bottom:12px; line-height:1.4;">Truyền file M3U/M3U8 từ bộ nhớ điện thoại hoặc máy tính sang TV qua Wi-Fi.</p>
            <div class="form-group">
                <label>Tên danh sách (tùy chọn)</label>
                <input type="text" id="uploadPlName" class="form-input" placeholder="Để trống sẽ lấy theo tên file">
            </div>
            <div class="form-group">
                <label>Chọn file (.m3u, .m3u8, .txt)</label>
                <input type="file" id="fileLocalM3u" accept=".m3u,.m3u8,.txt" class="form-input" style="padding:8px;">
            </div>
            <div style="margin-top:8px; margin-bottom:12px; display:flex; align-items:center; gap:8px;">
                <input type="checkbox" id="uploadPlSelectNow" checked style="width:18px; height:18px; accent-color:#10b981; cursor:pointer;">
                <label for="uploadPlSelectNow" style="font-size:13px; color:var(--text-main); cursor:pointer;">Kích hoạt phát danh sách này ngay trên TV</label>
            </div>
            <button class="submit-btn" style="background:#10b981;" onclick="uploadLocalPlaylist()">⬆ Tải File Lên TV</button>
        </div>

        <h3 style="font-size:14px; margin: 18px 0 10px; color:var(--accent); font-weight:700;">📑 Danh Sách Playlist Đã Có:</h3>
        <div id="playlistsContainer">Đang tải danh sách playlist...</div>
    </div>

    <!-- TAB 5: CÀI ĐẶT -->
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
        <button class="nav-btn" onclick="switchTab('playlists')">
            <svg viewBox="0 0 24 24"><path d="M4 10h12v2H4zm0-4h12v2H4zm0 8h8v2H4zm10 0v6l5-3z"/></svg>
            <span>Danh Sách</span>
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
            if (tabId === 'playlists') { btns[3].classList.add('active'); loadPlaylists(); }
            if (tabId === 'settings') btns[4].classList.add('active');
        }

        /* PLAYLIST FUNCTIONS */
        var currentPlaylists = [];

        function loadPlaylists() {
            var container = document.getElementById('playlistsContainer');
            container.innerHTML = '<div style="color:var(--text-sub); padding:10px;">Đang tải...</div>';
            fetch('/api/playlists?t=' + Date.now())
            .then(function(res) { return res.json(); })
            .then(function(list) {
                currentPlaylists = list || [];
                if (!list || list.length === 0) {
                    container.innerHTML = '<div style="color:var(--text-sub); padding:12px;">Chưa có playlist nào.</div>';
                    return;
                }
                var html = '';
                list.forEach(function(item, idx) {
                    var isSel = item.isSelected;
                    var itemClass = isSel ? 'playlist-item selected' : 'playlist-item';
                    var selBadge = isSel ? '<span class="playlist-badge-sel">✓ ĐANG PHÁT</span>' : '';
                    var selectBtn = !isSel ? '<button class="btn-sm btn-select" onclick="onSelectPlaylistClick(' + idx + ')">Chọn</button>' : '';
                    html += '<div class="' + itemClass + '">' +
                        '<div class="playlist-info">' +
                            '<div class="playlist-name"><span>' + item.name + '</span> ' + selBadge + '</div>' +
                            '<div class="playlist-url">' + item.url + '</div>' +
                        '</div>' +
                        '<div class="playlist-actions">' +
                            selectBtn +
                            '<button class="btn-sm btn-delete" onclick="onDeletePlaylistClick(' + idx + ')">Xóa</button>' +
                        '</div>' +
                    '</div>';
                });
                container.innerHTML = html;
            }).catch(function() {
                container.innerHTML = '<div style="color:var(--red); padding:10px;">Lỗi tải danh sách playlist.</div>';
            });
        }

        function onSelectPlaylistClick(idx) {
            if (currentPlaylists && currentPlaylists[idx]) {
                selectPlaylist(currentPlaylists[idx].id);
            }
        }

        function onDeletePlaylistClick(idx) {
            if (currentPlaylists && currentPlaylists[idx]) {
                deletePlaylist(currentPlaylists[idx].id);
            }
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
                    showToast('Đã thêm playlist mới thành công!');
                    document.getElementById('newPlName').value = '';
                    document.getElementById('newPlUrl').value = '';
                    loadPlaylists();
                    if (selectNow) {
                        allChannels = [];
                        setTimeout(loadChannels, 600);
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
                        showToast('Đã tải lên TV: ' + (data.name || file.name));
                        fileInput.value = '';
                        document.getElementById('uploadPlName').value = '';
                        loadPlaylists();
                        if (selectNow) {
                            allChannels = [];
                            setTimeout(loadChannels, 600);
                        }
                    } else {
                        showToast('Lỗi: ' + (data.error || 'Không thể tải file'));
                    }
                }).catch(function() {
                    showToast('Lỗi đường truyền khi tải file!');
                });
            };
            reader.readAsText(file);
        }

        function selectPlaylist(id) {
            showToast('Đang chuyển playlist...');
            fetch('/api/playlist/select', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ id: id })
            }).then(function(res) { return res.json(); })
            .then(function(d) {
                if (d.success) {
                    showToast('Đã chuyển playlist trên TV!');
                    loadPlaylists();
                    allChannels = [];
                    setTimeout(loadChannels, 600);
                } else {
                    showToast('Lỗi: ' + (d.error || 'Không chuyển được'));
                }
            }).catch(function() {
                showToast('Lỗi kết nối khi chọn playlist');
            });
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
                    loadPlaylists();
                    allChannels = [];
                    setTimeout(loadChannels, 600);
                } else {
                    showToast('Lỗi: ' + (data.error || 'Không thể xóa'));
                }
            }).catch(function() {
                showToast('Lỗi kết nối khi xóa playlist');
            });
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

        var currentFilteredChannels = [];

        function renderChannelList(list) {
            currentFilteredChannels = list;
            var container = document.getElementById('channelsContainer');
            if (!list.length) {
                container.innerHTML = '<div style="color:var(--text-sub); padding:16px; text-align:center;">Không tìm thấy kênh phù hợp</div>';
                return;
            }
            var html = '';
            list.forEach(function(ch, idx) {
                html += '<div class="channel-row" onclick="onChannelClick(' + idx + ')">' +
                    '<div class="channel-left">' +
                        '<span class="ch-num">' + (ch.number < 10 ? '0' + ch.number : ch.number) + '</span>' +
                        '<div>' +
                            '<div class="ch-title">' + (ch.name || '') + '</div>' +
                            '<div class="ch-group">' + (ch.groupTitle || 'Kênh TV') + '</div>' +
                        '</div>' +
                    '</div>' +
                    '<span style="color:var(--accent); font-size:12px; font-weight:700;">PHÁT ▶</span>' +
                '</div>';
            });
            container.innerHTML = html;
        }

        function onChannelClick(idx) {
            if (currentFilteredChannels && currentFilteredChannels[idx]) {
                playChannel(currentFilteredChannels[idx]);
            }
        }
        function toggleMatchSources(counter) {
            var box = document.getElementById('match-sources-' + counter);
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

        var currentSportsCategories = [];

        function renderSports(data) {
            var container = document.getElementById('sportsContainer');
            var categories = data.categories || [];
            currentSportsCategories = categories;
            if (!categories.length) {
                container.innerHTML = '<div style="color:var(--text-sub); padding:16px; text-align:center;">Hiện chưa có lịch trận đấu thể thao trực tiếp</div>';
                return;
            }
            var html = '';
            var boxCounter = 0;
            categories.forEach(function(cat, catIdx) {
                var isLive = (cat.name || '').toUpperCase().indexOf('LIVE EVENTS') !== -1;
                html += '<div style="margin: 14px 0 8px; font-size:13px; font-weight:800; color:' + (isLive ? 'var(--red)' : 'var(--accent)') + ';">' + cat.name + '</div>';
                
                (cat.matches || []).forEach(function(m, matchIdx) {
                    boxCounter++;
                    var sources = m.sources || [];
                    html += '<div class="match-card">' +
                        '<div class="match-header" onclick="toggleMatchSources(' + boxCounter + ')">' +
                            '<div class="match-title">' + m.title + '</div>' +
                            '<div class="match-time-badge ' + (m.isOngoing ? 'badge-live' : '') + '">' +
                                (m.isOngoing ? '● TRỰC TIẾP' : (m.time || 'Sắp đá')) +
                            '</div>' +
                        '</div>' +
                        '<div id="match-sources-' + boxCounter + '" class="sources-box">';
                    
                    if (!sources.length) {
                        html += '<div style="font-size:12px; color:var(--text-sub); padding:6px 0;">Chưa cập nhật luồng phát</div>';
                    } else {
                        sources.forEach(function(s, srcIdx) {
                            html += '<button class="source-item" onclick="onSportsSourceClick(' + catIdx + ',' + matchIdx + ',' + srcIdx + ')">' +
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

        function onSportsSourceClick(catIdx, matchIdx, srcIdx) {
            try {
                var cat = currentSportsCategories[catIdx];
                var match = cat.matches[matchIdx];
                var src = match.sources[srcIdx];
                playSportsSource(match.title, src);
            } catch (e) {}
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
                is_connected = (now - current_tv_state.get("lastSeen", 0)) < 35
                resp_data = dict(current_tv_state)
                resp_data["tvConnected"] = is_connected
            
            resp_bytes = json.dumps(resp_data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(resp_bytes)
            return

        if path == "/api/playlists":
            playlists = get_playlists()
            resp_bytes = json.dumps(playlists, ensure_ascii=False).encode("utf-8")
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

        if path == "/api/playlist/add":
            name = (post_data.get("name") or "").strip()
            url = (post_data.get("url") or "").strip()
            select_now = bool(post_data.get("selectImmediately", True))
            
            if not name or not url:
                self.send_response(400)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": "Thiếu tên hoặc URL danh sách"}).encode("utf-8"))
                return
                
            pl_id = f"pl_{int(time.time()*1000)}"
            playlists = get_playlists()
            if select_now:
                for p in playlists:
                    p["isSelected"] = False
                    
            new_entry = {
                "id": pl_id,
                "name": name,
                "url": url,
                "isSelected": select_now
            }
            playlists.append(new_entry)
            save_playlists(playlists)
            
            if select_now:
                get_channels(force_refresh=True)
                broadcast_event("playlist_select", new_entry)
            broadcast_event("sync_playlists", playlists)
            
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "id": pl_id, "name": name, "url": url}).encode("utf-8"))
            return

        if path == "/api/playlist/upload":
            filename = (post_data.get("filename") or f"custom_{int(time.time())}.m3u").strip()
            custom_name = (post_data.get("name") or "").strip()
            if not custom_name:
                custom_name = os.path.splitext(filename)[0] or "Danh sách tải lên"
            content = post_data.get("content") or ""
            select_now = bool(post_data.get("selectImmediately", True))
            
            if not content or len(content.strip()) < 10:
                self.send_response(400)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": "Nội dung file không hợp lệ"}).encode("utf-8"))
                return
                
            pl_id = f"pl_{int(time.time()*1000)}"
            save_fname = f"custom_{pl_id}.m3u"
            save_path = os.path.join(DATA_DIR, save_fname)
            try:
                with open(save_path, "w", encoding="utf-8") as f:
                    f.write(content)
            except Exception as e:
                self.send_response(500)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": f"Lỗi lưu file: {e}"}).encode("utf-8"))
                return
                
            file_url = f"http://10.0.0.222:{PORT}/data/{save_fname}"
            playlists = get_playlists()
            if select_now:
                for p in playlists:
                    p["isSelected"] = False
                    
            new_entry = {
                "id": pl_id,
                "name": custom_name,
                "url": file_url,
                "filename": save_fname,
                "isSelected": select_now
            }
            playlists.append(new_entry)
            save_playlists(playlists)
            
            if select_now:
                get_channels(force_refresh=True)
                broadcast_event("playlist_select", new_entry)
            broadcast_event("sync_playlists", playlists)
            
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "id": pl_id, "name": custom_name, "url": file_url}).encode("utf-8"))
            return

        if path == "/api/playlist/select":
            pl_id = (post_data.get("id") or "").strip()
            playlists = get_playlists()
            found = None
            for p in playlists:
                if p.get("id") == pl_id:
                    p["isSelected"] = True
                    found = p
                else:
                    p["isSelected"] = False
                    
            if not found:
                self.send_response(404)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": "Playlist không tồn tại"}).encode("utf-8"))
                return
                
            save_playlists(playlists)
            get_channels(force_refresh=True)
            broadcast_event("playlist_select", found)
            broadcast_event("sync_playlists", playlists)
            
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "playlist": found}).encode("utf-8"))
            return

        if path == "/api/playlist/delete":
            pl_id = (post_data.get("id") or "").strip()
            playlists = get_playlists()
            if len(playlists) <= 1:
                self.send_response(400)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": "Không thể xóa danh sách duy nhất còn lại!"}).encode("utf-8"))
                return
                
            target = None
            new_list = []
            for p in playlists:
                if p.get("id") == pl_id:
                    target = p
                else:
                    new_list.append(p)
                    
            if not target:
                self.send_response(404)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": "Không tìm thấy playlist"}).encode("utf-8"))
                return
                
            if target.get("filename"):
                fpath = os.path.join(DATA_DIR, target["filename"])
                if os.path.exists(fpath):
                    try:
                        os.remove(fpath)
                    except Exception:
                        pass
                        
            if target.get("isSelected") and len(new_list) > 0:
                new_list[0]["isSelected"] = True
                get_channels(force_refresh=True)
                broadcast_event("playlist_select", new_list[0])
                
            save_playlists(new_list)
            broadcast_event("sync_playlists", new_list)
            
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
