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
import subprocess
import threading
import base64
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
                "url": "https://raw.githubusercontent.com/ndkhoams/iptv/refs/heads/main/ktv.m3u",
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
    return {"id": "pl_ktv", "name": "KTV", "url": "https://raw.githubusercontent.com/ndkhoams/iptv/refs/heads/main/ktv.m3u", "isSelected": True}

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

def get_local_or_remote_data(filename, remote_url, max_age_seconds=600):
    local_path = os.path.join(DATA_DIR, filename)
    if os.path.exists(local_path) and os.path.getsize(local_path) > 50:
        file_age = time.time() - os.path.getmtime(local_path)
        if file_age < max_age_seconds:
            try:
                with open(local_path, "r", encoding="utf-8") as f:
                    return f.read()
            except Exception:
                pass
    # Fetch from remote and cache
    try:
        req = urllib.request.Request(remote_url, headers={"User-Agent": "TVPlay-Armbian/1.0", "Cache-Control": "no-cache"})
        with urllib.request.urlopen(req, timeout=8) as resp:
            content = resp.read().decode("utf-8", "ignore")
            if len(content) > 50:
                with open(local_path, "w", encoding="utf-8") as f:
                    f.write(content)
                return content
    except Exception as e:
        print(f"[-] Fetch error for {filename}: {e}")
        if os.path.exists(local_path):
            try:
                with open(local_path, "r", encoding="utf-8") as f:
                    return f.read()
            except Exception:
                pass
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

# Samsung Tizen TV Configuration & Deployment
TIZEN_CLI_PATH = "/opt/samsung/apps2samsung-cli"
TIZEN_WGT_PATH = "/opt/samsung/TVplay.wgt"
TIZEN_UPLOADS_DIR = "/opt/samsung/uploads"
DEFAULT_TIZEN_IP = "10.0.0.9"

tizen_state_lock = Lock()
tizen_deploy_state = {
    "status": "idle",       # "idle", "running", "success", "error"
    "action": "",           # "check", "install", "launch", "upload"
    "tvIp": DEFAULT_TIZEN_IP,
    "message": "Sẵn sàng",
    "log": "",
    "timestamp": int(time.time()),
    "wgtExists": False,
    "wgtSize": 0,
    "wgtMtime": 0
}

def get_available_wgts():
    wgts = []
    # 1. Check default TVplay.wgt
    if os.path.exists(TIZEN_WGT_PATH):
        sz = os.path.getsize(TIZEN_WGT_PATH)
        mtime = int(os.path.getmtime(TIZEN_WGT_PATH))
        wgts.append({
            "name": "TVplay.wgt",
            "path": TIZEN_WGT_PATH,
            "size": sz,
            "sizeStr": f"{sz / 1024:.1f} KB" if sz < 1024*1024 else f"{sz / (1024*1024):.1f} MB",
            "mtime": mtime,
            "isDefault": True,
            "tag": "Bản chuẩn máy chủ"
        })
    # 2. Scan /opt/samsung/ for other .wgt files
    samsung_dir = os.path.dirname(TIZEN_WGT_PATH)
    if os.path.exists(samsung_dir):
        for f in sorted(os.listdir(samsung_dir)):
            if f.endswith(".wgt") and f != "TVplay.wgt":
                fpath = os.path.join(samsung_dir, f)
                if os.path.isfile(fpath):
                    sz = os.path.getsize(fpath)
                    mtime = int(os.path.getmtime(fpath))
                    wgts.append({
                        "name": f,
                        "path": fpath,
                        "size": sz,
                        "sizeStr": f"{sz / 1024:.1f} KB" if sz < 1024*1024 else f"{sz / (1024*1024):.1f} MB",
                        "mtime": mtime,
                        "isDefault": False,
                        "tag": "Gói ứng dụng có sẵn"
                    })
    # 3. Scan uploads dir
    if os.path.exists(TIZEN_UPLOADS_DIR):
        for f in sorted(os.listdir(TIZEN_UPLOADS_DIR)):
            if f.endswith(".wgt"):
                fpath = os.path.join(TIZEN_UPLOADS_DIR, f)
                if os.path.isfile(fpath):
                    sz = os.path.getsize(fpath)
                    mtime = int(os.path.getmtime(fpath))
                    wgts.append({
                        "name": f,
                        "path": fpath,
                        "size": sz,
                        "sizeStr": f"{sz / 1024:.1f} KB" if sz < 1024*1024 else f"{sz / (1024*1024):.1f} MB",
                        "mtime": mtime,
                        "isDefault": False,
                        "tag": "Người dùng tải lên"
                    })
    return wgts

def get_tizen_status():
    with tizen_state_lock:
        st = dict(tizen_deploy_state)
    wgts = get_available_wgts()
    st["wgts"] = wgts
    st["wgtExists"] = len(wgts) > 0
    if wgts:
        st["wgtSize"] = wgts[0]["size"]
        st["wgtMtime"] = wgts[0]["mtime"]
        st["defaultWgt"] = wgts[0]["name"]
    return st

def execute_tizen_task(action, tv_ip=DEFAULT_TIZEN_IP, wgt_path=None):
    def _task():
        target_wgt = wgt_path if (wgt_path and os.path.exists(wgt_path)) else TIZEN_WGT_PATH
        wgt_name = os.path.basename(target_wgt)
        with tizen_state_lock:
            tizen_deploy_state["status"] = "running"
            tizen_deploy_state["action"] = action
            tizen_deploy_state["tvIp"] = tv_ip
            tizen_deploy_state["timestamp"] = int(time.time())
            if action == "check":
                tizen_deploy_state["message"] = f"Đang kiểm tra TV ({tv_ip})..."
                tizen_deploy_state["log"] = f"[*] Kiểm tra kết nối tới Samsung TV tại {tv_ip}...\n"
            elif action == "install":
                tizen_deploy_state["message"] = f"Đang cài đặt {wgt_name} lên TV ({tv_ip})..."
                tizen_deploy_state["log"] = f"[*] Bắt đầu ký và cài đặt {target_wgt} lên TV {tv_ip}...\n"
            elif action == "launch":
                tizen_deploy_state["message"] = f"Đang mở app TV Play trên TV ({tv_ip})..."
                tizen_deploy_state["log"] = f"[*] Gửi lệnh mở ứng dụng TVPlay0001.TVPlay lên TV {tv_ip}...\n"
        
        broadcast_event("tizen_status", get_tizen_status())
        
        try:
            if action == "check":
                cmd = [TIZEN_CLI_PATH, "tv", tv_ip]
            elif action == "install":
                cmd = [TIZEN_CLI_PATH, "install", target_wgt, "--tv", tv_ip, "--yes"]
            elif action == "launch":
                cmd = [TIZEN_CLI_PATH, "launch", "TVPlay0001.TVPlay", "--tv", tv_ip]
            else:
                raise ValueError(f"Hành động không hợp lệ: {action}")
            
            proc = subprocess.run(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=120
            )
            output = proc.stdout or ""
            with tizen_state_lock:
                tizen_deploy_state["log"] += output + "\n"
                tizen_deploy_state["timestamp"] = int(time.time())
                if proc.returncode == 0:
                    tizen_deploy_state["status"] = "success"
                    if action == "check":
                        tizen_deploy_state["message"] = f"TV ({tv_ip}) đang BẬT & sẵn sàng nhận lệnh!"
                    elif action == "install":
                        tizen_deploy_state["message"] = f"Cài đặt {wgt_name} thành công lên TV ({tv_ip})!"
                    elif action == "launch":
                        tizen_deploy_state["message"] = f"Đã mở TV Play trên TV ({tv_ip})!"
                else:
                    tizen_deploy_state["status"] = "error"
                    tizen_deploy_state["message"] = f"Thực hiện thất bại (Mã lỗi {proc.returncode})"
        except subprocess.TimeoutExpired:
            with tizen_state_lock:
                tizen_deploy_state["status"] = "error"
                tizen_deploy_state["message"] = "Hết thời gian chờ (Timeout 120s)"
                tizen_deploy_state["log"] += "\n[!] Lỗi: Quá thời gian chờ phản hồi từ TV.\n"
        except Exception as ex:
            with tizen_state_lock:
                tizen_deploy_state["status"] = "error"
                tizen_deploy_state["message"] = f"Lỗi: {str(ex)}"
                tizen_deploy_state["log"] += f"\n[!] Lỗi hệ thống: {str(ex)}\n"
        
        broadcast_event("tizen_status", get_tizen_status())

    t = threading.Thread(target=_task, daemon=True)
    t.start()

WEB_REMOTE_HTML = r"""<!DOCTYPE html>
<html lang="vi" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
    <title>TV Play - Web Remote</title>
    <link rel="icon" type="image/png" href="https://ndkhoams.github.io/icons/favicon.png">
    <style>
        :root {
            --bg: #000000;
            --bg-raised: #0c0c0f;
            --bg-card: rgba(22, 22, 26, 0.78);
            --bg-card-hover: rgba(32, 32, 38, 0.88);
            --cell: #1c1c1e;
            --cell-hover: rgba(255, 255, 255, 0.08);
            --cell-active: rgba(255, 255, 255, 0.14);
            --label: #f5f5f7;
            --label-2: rgba(235, 235, 245, 0.65);
            --label-3: rgba(235, 235, 245, 0.4);
            --separator: rgba(255, 255, 255, 0.08);
            --separator-hover: rgba(255, 255, 255, 0.16);
            --accent: #ff6500;
            --accent-hover: #ff8533;
            --accent-glow: rgba(255, 101, 0, 0.35);
            --blue: #0a84ff;
            --orange: #ff9f0a;
            --green: #30d158;
            --red: #ff453a;
            --font-body: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, sans-serif;
            --font-display: -apple-system, BlinkMacSystemFont, "SF Pro Display", "Segoe UI", Roboto, sans-serif;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            font-family: var(--font-body);
            -webkit-tap-highlight-color: transparent;
        }

        body {
            background-color: var(--bg);
            color: var(--label);
            min-height: 100vh;
            min-height: -webkit-fill-available;
            padding-bottom: calc(env(safe-area-inset-bottom, 12px) + 85px);
            user-select: none;
            overflow-x: hidden;
            -webkit-font-smoothing: antialiased;
        }

        /* Ambient Dynamic Glow */
        .ambient-glow {
            position: fixed;
            width: 650px;
            max-width: 95vw;
            height: 380px;
            top: -60px;
            left: 50%;
            transform: translateX(-50%);
            background: radial-gradient(ellipse at center, rgba(255, 101, 0, 0.16) 0%, rgba(10, 132, 255, 0.08) 45%, transparent 70%);
            filter: blur(80px);
            pointer-events: none;
            z-index: 0;
        }

        /* Glassmorphism Tokens */
        .glass-card {
            background: var(--bg-card);
            backdrop-filter: blur(28px) saturate(180%);
            -webkit-backdrop-filter: blur(28px) saturate(180%);
            border: 0.5px solid rgba(255, 255, 255, 0.1);
            border-top: 1px solid rgba(255, 255, 255, 0.2);
            border-radius: 24px;
            box-shadow: 0 10px 40px rgba(0, 0, 0, 0.6);
        }

        .glass-pill {
            background: rgba(28, 28, 32, 0.82);
            backdrop-filter: blur(28px) saturate(190%);
            -webkit-backdrop-filter: blur(28px) saturate(190%);
            border: 0.5px solid rgba(255, 255, 255, 0.16);
            box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
        }

        /* Sticky Top Header */
        header.app-header-sticky {
            position: sticky;
            top: 0;
            z-index: 50;
            padding-top: max(calc(env(safe-area-inset-top, 0px) + 10px), 14px);
            padding-bottom: 12px;
            padding-left: 16px;
            padding-right: 16px;
            background: rgba(10, 10, 12, 0.92);
            backdrop-filter: blur(24px) saturate(180%);
            -webkit-backdrop-filter: blur(24px) saturate(180%);
            border-bottom: 1px solid rgba(255, 255, 255, 0.08);
            box-shadow: 0 8px 30px rgba(0, 0, 0, 0.7);
            display: flex;
            justify-content: space-between;
            align-items: center;
        }

        .header-inner {
            width: 100%;
            max-width: 540px;
            margin: 0 auto;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }

        .brand-title {
            display: flex;
            align-items: center;
            gap: 10px;
        }

        .brand-logo-box {
            width: 38px;
            height: 38px;
            border-radius: 14px;
            background: linear-gradient(135deg, #ff6500 0%, #ff8800 50%, #ffaa00 100%);
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 4px 16px rgba(255, 101, 0, 0.35);
            flex-shrink: 0;
        }

        .brand-logo-box svg {
            width: 20px;
            height: 20px;
            color: #ffffff;
            fill: currentColor;
        }

        .brand-name {
            font-size: 16px;
            font-weight: 800;
            color: #ffffff;
            font-family: var(--font-display);
            letter-spacing: -0.02em;
            line-height: 1.15;
        }

        .brand-sub {
            font-size: 11px;
            font-weight: 600;
            color: var(--accent);
            text-transform: uppercase;
            letter-spacing: 0.06em;
        }

        .tv-status-badge {
            font-size: 11px;
            padding: 4px 10px;
            border-radius: 14px;
            font-weight: 600;
            display: flex;
            align-items: center;
            gap: 6px;
            transition: all 0.2s;
        }

        .status-connected {
            background: rgba(48, 209, 88, 0.16);
            color: var(--green);
            border: 0.5px solid rgba(48, 209, 88, 0.35);
            box-shadow: 0 0 12px rgba(48, 209, 88, 0.2);
        }

        .status-disconnected {
            background: rgba(255, 69, 58, 0.14);
            color: var(--red);
            border: 0.5px solid rgba(255, 69, 58, 0.3);
        }

        .status-dot {
            width: 7px;
            height: 7px;
            border-radius: 50%;
            background: currentColor;
        }

        .status-connected .status-dot {
            box-shadow: 0 0 8px var(--green);
            animation: pulse-dot 2s infinite;
        }

        @keyframes pulse-dot {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.4; transform: scale(0.85); }
        }

        /* Now Playing Banner (Strand Mini-Hero) */
        .now-playing-wrap {
            padding: 10px 16px 4px;
            max-width: 540px;
            margin: 0 auto;
            position: relative;
            z-index: 10;
        }

        .now-playing-card {
            background: linear-gradient(135deg, rgba(255, 101, 0, 0.15) 0%, rgba(20, 20, 26, 0.85) 100%);
            border: 0.5px solid rgba(255, 101, 0, 0.3);
            border-top: 1px solid rgba(255, 150, 50, 0.4);
            border-radius: 18px;
            padding: 10px 14px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            gap: 10px;
            box-shadow: 0 6px 24px rgba(0, 0, 0, 0.5), 0 0 20px rgba(255, 101, 0, 0.12);
        }

        .now-playing-left {
            display: flex;
            align-items: center;
            gap: 10px;
            overflow: hidden;
            flex: 1;
        }

        .live-badge {
            background: linear-gradient(135deg, #ff453a 0%, #ff6500 100%);
            color: #fff;
            font-size: 10px;
            font-weight: 800;
            padding: 3px 7px;
            border-radius: 6px;
            white-space: nowrap;
            letter-spacing: 0.05em;
            display: flex;
            align-items: center;
            gap: 4px;
            box-shadow: 0 2px 8px rgba(255, 69, 58, 0.4);
            flex-shrink: 0;
        }

        .ch-name {
            font-weight: 700;
            font-size: 13px;
            color: #ffffff;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }

        .osd-pill-btn {
            background: rgba(255, 255, 255, 0.08);
            border: 0.5px solid rgba(255, 255, 255, 0.2);
            color: var(--accent);
            font-size: 11px;
            font-weight: 700;
            padding: 6px 12px;
            border-radius: 12px;
            cursor: pointer;
            white-space: nowrap;
            transition: all 0.15s;
            flex-shrink: 0;
        }

        .osd-pill-btn:hover {
            background: rgba(255, 101, 0, 0.2);
            border-color: var(--accent);
            color: #ffffff;
        }

        .osd-pill-btn:active {
            transform: scale(0.94);
        }

        /* Tab Content Wrappers */
        .tab-content {
            display: none;
            padding: 12px 16px 20px;
            max-width: 540px;
            margin: 0 auto;
            position: relative;
            z-index: 10;
        }

        .tab-content.active {
            display: block;
            animation: fadeIn 0.22s cubic-bezier(0.16, 1, 0.3, 1) forwards;
        }

        @keyframes fadeIn {
            from { opacity: 0; transform: translateY(4px); }
            to { opacity: 1; transform: translateY(0); }
        }

        /* ========================================================================= */
        /* TAB 1: REMOTE CONTROLLER TAB                                              */
        /* ========================================================================= */
        .remote-card {
            background: var(--bg-card);
            backdrop-filter: blur(28px) saturate(180%);
            -webkit-backdrop-filter: blur(28px) saturate(180%);
            border: 0.5px solid rgba(255, 255, 255, 0.12);
            border-top: 1px solid rgba(255, 255, 255, 0.24);
            border-radius: 28px;
            padding: 18px 16px 20px;
            box-shadow: 0 16px 44px -4px rgba(0, 0, 0, 0.8), 0 0 24px rgba(255, 101, 0, 0.12);
            margin-bottom: 14px;
        }

        .dpad-container {
            width: 236px;
            height: 236px;
            margin: 14px auto 18px;
            position: relative;
            background: radial-gradient(circle at 50% 50%, #202028 0%, #111116 70%, #08080a 100%);
            border-radius: 50%;
            border: 1px solid rgba(255, 255, 255, 0.14);
            box-shadow: 
                inset 0 3px 8px rgba(255, 255, 255, 0.12),
                inset 0 -4px 12px rgba(0, 0, 0, 0.85),
                0 14px 40px rgba(0, 0, 0, 0.8);
        }

        .dpad-btn {
            position: absolute;
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid rgba(255, 255, 255, 0.08);
            color: var(--label);
            font-size: 16px;
            font-weight: 800;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            transition: all 0.1s cubic-bezier(0.16, 1, 0.3, 1);
        }

        .dpad-btn:hover {
            background: rgba(255, 255, 255, 0.1);
        }

        .dpad-btn:active {
            background: var(--accent);
            color: #ffffff;
            box-shadow: 0 0 16px var(--accent-glow);
            transform: scale(0.94);
        }

        .dpad-up {
            top: 8px;
            left: 80px;
            width: 76px;
            height: 56px;
            border-radius: 36px 36px 12px 12px;
        }

        .dpad-down {
            bottom: 8px;
            left: 80px;
            width: 76px;
            height: 56px;
            border-radius: 12px 12px 36px 36px;
        }

        .dpad-left {
            top: 80px;
            left: 8px;
            width: 56px;
            height: 76px;
            border-radius: 36px 12px 12px 36px;
        }

        .dpad-right {
            top: 80px;
            right: 8px;
            width: 56px;
            height: 76px;
            border-radius: 12px 36px 36px 12px;
        }

        .dpad-ok {
            top: 80px;
            left: 80px;
            width: 76px;
            height: 76px;
            border-radius: 50%;
            background: linear-gradient(135deg, #ff6500 0%, #ff8800 50%, #ffaa00 100%);
            color: #ffffff;
            font-size: 18px;
            font-weight: 900;
            letter-spacing: 0.05em;
            border: 1.5px solid rgba(255, 255, 255, 0.4);
            box-shadow: 
                inset 0 1px 2px rgba(255, 255, 255, 0.6),
                0 8px 24px rgba(255, 101, 0, 0.45);
        }

        .dpad-ok:active {
            transform: scale(0.92);
            box-shadow: 0 2px 10px rgba(255, 101, 0, 0.6);
        }

        .row-actions {
            display: flex;
            gap: 10px;
            margin-top: 10px;
        }

        .action-btn {
            flex: 1;
            padding: 12px 8px;
            background: rgba(255, 255, 255, 0.06);
            border: 1px solid rgba(255, 255, 255, 0.12);
            border-radius: 14px;
            color: var(--label);
            font-size: 12px;
            font-weight: 700;
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 6px;
            transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
        }

        .action-btn:hover {
            background: rgba(255, 255, 255, 0.1);
            border-color: rgba(255, 255, 255, 0.2);
        }

        .action-btn:active {
            transform: scale(0.95);
            background: rgba(255, 101, 0, 0.25);
            border-color: var(--accent);
            color: #fff;
        }

        .btn-mode-tv {
            background: linear-gradient(135deg, rgba(10, 132, 255, 0.15) 0%, rgba(10, 132, 255, 0.05) 100%);
            border-color: rgba(10, 132, 255, 0.3);
            color: #93c5fd;
        }

        .btn-mode-sports {
            background: linear-gradient(135deg, rgba(255, 101, 0, 0.18) 0%, rgba(255, 159, 10, 0.08) 100%);
            border-color: rgba(255, 101, 0, 0.35);
            color: #fdba74;
        }

        .keypad-grid {
            display: grid;
            grid-template-columns: repeat(3, 1fr);
            gap: 8px;
            margin-top: 14px;
            padding-top: 14px;
            border-top: 1px dashed rgba(255, 255, 255, 0.1);
        }

        .key-num {
            padding: 12px 0;
            background: rgba(255, 255, 255, 0.04);
            border: 1px solid rgba(255, 255, 255, 0.09);
            border-radius: 12px;
            color: var(--label);
            font-size: 15px;
            font-weight: 700;
            cursor: pointer;
            text-align: center;
            transition: all 0.12s;
        }

        .key-num:hover {
            background: rgba(255, 255, 255, 0.08);
        }

        .key-num:active {
            background: var(--accent);
            color: #fff;
            border-color: var(--accent-hover);
            transform: scale(0.94);
        }

        /* ========================================================================= */
        /* TAB 2: CHANNELS TAB                                                       */
        /* ========================================================================= */
        .cat-pills-wrap {
            display: flex;
            gap: 8px;
            overflow-x: auto;
            padding: 2px 2px 10px;
            margin-bottom: 8px;
            scrollbar-width: none;
        }

        .cat-pills-wrap::-webkit-scrollbar {
            display: none;
        }

        .cat-pill {
            background: rgba(255, 255, 255, 0.06);
            border: 0.5px solid rgba(255, 255, 255, 0.14);
            color: var(--label-2);
            padding: 7px 15px;
            border-radius: 20px;
            font-size: 12px;
            font-weight: 600;
            white-space: nowrap;
            cursor: pointer;
            transition: all 0.15s;
        }

        .cat-pill:hover {
            color: #ffffff;
            background: rgba(255, 255, 255, 0.1);
        }

        .cat-pill.active {
            background: var(--accent);
            color: #fff;
            border-color: var(--accent-hover);
            font-weight: 700;
            box-shadow: 0 4px 14px rgba(255, 101, 0, 0.4);
        }

        .search-wrap {
            position: relative;
            margin-bottom: 12px;
        }

        .search-icon-svg {
            position: absolute;
            left: 14px;
            top: 50%;
            transform: translateY(-50%);
            width: 16px;
            height: 16px;
            color: var(--label-3);
            pointer-events: none;
        }

        .search-box {
            width: 100%;
            padding: 12px 14px 12px 38px;
            background: rgba(255, 255, 255, 0.05);
            border: 0.5px solid rgba(255, 255, 255, 0.12);
            border-radius: 16px;
            color: #fff;
            font-size: 13px;
            outline: none;
            transition: all 0.2s;
        }

        .search-box:focus {
            border-color: var(--accent);
            background: rgba(255, 255, 255, 0.08);
            box-shadow: 0 0 16px rgba(255, 101, 0, 0.2);
        }

        .channels-list {
            max-height: calc(100vh - 250px);
            overflow-y: auto;
            padding-right: 2px;
        }

        .channel-row {
            background: var(--bg-card);
            border: 0.5px solid rgba(255, 255, 255, 0.09);
            border-radius: 18px;
            padding: 10px 14px;
            margin-bottom: 8px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 12px;
            cursor: pointer;
            transition: all 0.15s cubic-bezier(0.16, 1, 0.3, 1);
        }

        .channel-row:hover {
            background: var(--bg-card-hover);
            border-color: rgba(255, 255, 255, 0.18);
            transform: translateY(-1px);
        }

        .channel-row:active {
            background: rgba(255, 101, 0, 0.18);
            border-color: var(--accent);
            transform: scale(0.99);
        }

        .channel-left {
            display: flex;
            align-items: center;
            gap: 12px;
            flex: 1;
            overflow: hidden;
        }

        .ch-logo-box {
            width: 40px;
            height: 40px;
            border-radius: 12px;
            background: linear-gradient(135deg, #1c1c24 0%, #121216 100%);
            border: 0.5px solid rgba(255, 255, 255, 0.12);
            display: flex;
            align-items: center;
            justify-content: center;
            flex-shrink: 0;
            overflow: hidden;
            box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.1);
        }

        .ch-num {
            color: var(--accent);
            font-weight: 800;
            font-size: 13px;
            font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", monospace;
        }

        .ch-title {
            font-size: 13px;
            font-weight: 700;
            color: #fff;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            line-height: 1.25;
        }

        .ch-group {
            font-size: 10px;
            color: var(--label-3);
            margin-top: 2px;
            display: inline-block;
            padding: 1px 6px;
            border-radius: 4px;
            background: rgba(255, 255, 255, 0.05);
            font-family: monospace;
        }

        .btn-play-pill {
            background: rgba(255, 101, 0, 0.12);
            border: 0.5px solid rgba(255, 101, 0, 0.3);
            color: var(--accent);
            font-size: 11px;
            font-weight: 800;
            padding: 5px 12px;
            border-radius: 12px;
            white-space: nowrap;
            transition: all 0.15s;
        }

        .channel-row:hover .btn-play-pill {
            background: var(--accent);
            color: #fff;
            box-shadow: 0 0 10px rgba(255, 101, 0, 0.4);
        }

        /* ========================================================================= */
        /* TAB 3: SPORTS TAB                                                         */
        /* ========================================================================= */
        .sports-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 12px;
        }

        .sports-section-title {
            font-size: 14px;
            font-weight: 800;
            color: #fff;
            display: flex;
            align-items: center;
            gap: 6px;
        }

        .refresh-btn {
            background: rgba(255, 255, 255, 0.06);
            border: 0.5px solid var(--accent);
            color: var(--accent);
            font-size: 11px;
            font-weight: 700;
            padding: 6px 12px;
            border-radius: 12px;
            cursor: pointer;
            transition: all 0.15s;
            display: flex;
            align-items: center;
            gap: 5px;
        }

        .refresh-btn:hover {
            background: var(--accent);
            color: #fff;
        }

        .refresh-btn:active {
            transform: scale(0.95);
        }

        .match-card {
            background: var(--bg-card);
            border: 0.5px solid rgba(255, 255, 255, 0.1);
            border-top: 1px solid rgba(255, 255, 255, 0.18);
            border-radius: 20px;
            padding: 14px 16px;
            margin-bottom: 12px;
            transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
            cursor: pointer;
            box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
        }

        .match-card:hover {
            border-color: rgba(255, 255, 255, 0.22);
            transform: translateY(-1px);
        }

        .match-top-row {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 8px;
            margin-bottom: 10px;
            font-size: 11px;
        }

        .match-league {
            color: var(--blue);
            font-weight: 800;
            text-transform: uppercase;
            letter-spacing: 0.04em;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }

        .match-badges-right {
            display: flex;
            align-items: center;
            gap: 6px;
            flex-shrink: 0;
        }

        .time-pill {
            background: rgba(255, 159, 10, 0.15);
            border: 0.5px solid rgba(255, 159, 10, 0.35);
            color: var(--orange);
            font-size: 10px;
            font-family: monospace;
            font-weight: 700;
            padding: 2px 7px;
            border-radius: 6px;
        }

        .live-tag {
            background: var(--red);
            color: #fff;
            font-size: 10px;
            font-weight: 800;
            padding: 2px 7px;
            border-radius: 6px;
            display: flex;
            align-items: center;
            gap: 4px;
            box-shadow: 0 0 10px rgba(255, 69, 58, 0.4);
            animation: pulse-live 1.6s infinite;
        }

        @keyframes pulse-live {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.7; }
        }

        /* Teams Row */
        .match-teams-row {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 8px;
            padding: 4px 0;
        }

        .team-box {
            display: flex;
            align-items: center;
            gap: 8px;
            flex: 1;
            min-width: 0;
        }

        .team-box.away {
            flex-direction: row-reverse;
            text-align: right;
        }

        .team-avatar {
            width: 32px;
            height: 32px;
            border-radius: 10px;
            background: rgba(255, 255, 255, 0.08);
            border: 0.5px solid rgba(255, 255, 255, 0.14);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 11px;
            font-weight: 800;
            color: var(--label);
            flex-shrink: 0;
            overflow: hidden;
        }

        .team-name {
            font-size: 13px;
            font-weight: 700;
            color: #ffffff;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }

        .vs-pill {
            font-size: 11px;
            font-weight: 800;
            color: var(--label-3);
            padding: 2px 6px;
            border-radius: 6px;
            background: rgba(255, 255, 255, 0.05);
            flex-shrink: 0;
        }

        .sources-box {
            margin-top: 12px;
            padding-top: 10px;
            border-top: 1px dashed rgba(255, 255, 255, 0.12);
            display: none;
        }

        .sources-box.show {
            display: block;
            animation: fadeIn 0.2s cubic-bezier(0.16, 1, 0.3, 1) forwards;
        }

        .source-item {
            width: 100%;
            padding: 10px 14px;
            background: rgba(255, 255, 255, 0.04);
            border: 0.5px solid rgba(255, 255, 255, 0.1);
            border-radius: 12px;
            color: var(--label);
            font-size: 12px;
            font-weight: 600;
            margin-bottom: 6px;
            cursor: pointer;
            display: flex;
            justify-content: space-between;
            align-items: center;
            transition: all 0.15s;
        }

        .source-item:hover {
            background: rgba(255, 255, 255, 0.08);
            border-color: rgba(255, 255, 255, 0.2);
        }

        .source-item:active {
            background: var(--accent);
            color: #fff;
            border-color: var(--accent-hover);
        }

        /* ========================================================================= */
        /* TAB 4: PLAYLISTS TAB                                                      */
        /* ========================================================================= */
        .form-card {
            background: var(--bg-card);
            border: 0.5px solid rgba(255, 255, 255, 0.1);
            border-top: 1px solid rgba(255, 255, 255, 0.2);
            border-radius: 22px;
            padding: 18px 16px;
            margin-bottom: 14px;
            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.6);
        }

        .form-card h3 {
            font-size: 14px;
            color: var(--accent);
            margin-bottom: 12px;
            font-weight: 800;
            display: flex;
            align-items: center;
            gap: 6px;
            font-family: var(--font-display);
        }

        .form-group {
            margin-bottom: 12px;
        }

        .form-group label {
            display: block;
            font-size: 12px;
            color: var(--label-2);
            margin-bottom: 5px;
            font-weight: 600;
        }

        .form-input {
            width: 100%;
            padding: 11px 14px;
            background: rgba(255, 255, 255, 0.05);
            border: 0.5px solid rgba(255, 255, 255, 0.12);
            border-radius: 12px;
            color: #fff;
            font-size: 13px;
            outline: none;
            transition: all 0.2s;
        }

        .form-input:focus {
            border-color: var(--accent);
            background: rgba(255, 255, 255, 0.08);
            box-shadow: 0 0 14px rgba(255, 101, 0, 0.25);
        }

        .submit-btn {
            width: 100%;
            padding: 12px;
            background: linear-gradient(135deg, #ff6500 0%, #ff9f0a 100%);
            border: none;
            border-radius: 14px;
            color: #fff;
            font-weight: 800;
            font-size: 13px;
            cursor: pointer;
            box-shadow: 0 6px 20px rgba(255, 101, 0, 0.35);
            transition: all 0.15s;
        }

        .submit-btn:hover {
            filter: brightness(1.08);
        }

        .submit-btn:active {
            transform: scale(0.98);
        }

        .playlist-item {
            background: var(--bg-card);
            border: 0.5px solid rgba(255, 255, 255, 0.1);
            border-radius: 16px;
            padding: 12px 14px;
            margin-bottom: 10px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 10px;
            transition: all 0.15s;
        }

        .playlist-item.selected {
            border-color: var(--accent);
            background: rgba(255, 101, 0, 0.08);
            box-shadow: 0 0 16px rgba(255, 101, 0, 0.2);
        }

        .playlist-info {
            flex: 1;
            overflow: hidden;
        }

        .playlist-name {
            font-weight: 700;
            font-size: 13px;
            color: #fff;
            margin-bottom: 3px;
            display: flex;
            align-items: center;
            gap: 6px;
        }

        .playlist-badge-sel {
            background: var(--green);
            color: #fff;
            font-size: 9px;
            font-weight: 800;
            padding: 2px 6px;
            border-radius: 4px;
            white-space: nowrap;
        }

        .playlist-url {
            font-size: 11px;
            color: var(--label-3);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            font-family: monospace;
        }

        .playlist-actions {
            display: flex;
            gap: 6px;
            flex-shrink: 0;
        }

        .btn-sm {
            padding: 7px 12px;
            border-radius: 10px;
            font-size: 11px;
            font-weight: 800;
            cursor: pointer;
            border: none;
            transition: all 0.12s;
        }

        .btn-sm:active {
            transform: scale(0.93);
        }

        .btn-select {
            background: rgba(48, 209, 88, 0.2);
            border: 0.5px solid var(--green);
            color: #4ade80;
        }

        .btn-select:hover {
            background: var(--green);
            color: #fff;
        }

        .btn-delete {
            background: rgba(255, 69, 58, 0.15);
            border: 0.5px solid var(--red);
            color: #f87171;
        }

        .btn-delete:hover {
            background: var(--red);
            color: #fff;
        }

        /* ========================================================================= */
        /* TAB 5: SETTINGS & SAMSUNG TIZEN DEPLOYMENT                                */
        /* ========================================================================= */
        .settings-card {
            background: var(--bg-card);
            border: 0.5px solid rgba(255, 255, 255, 0.1);
            border-top: 1px solid rgba(255, 255, 255, 0.2);
            border-radius: 22px;
            padding: 16px 18px;
            margin-bottom: 14px;
            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5);
        }

        .settings-card h3 {
            font-size: 13px;
            color: var(--accent);
            margin-bottom: 12px;
            font-weight: 800;
            letter-spacing: 0.04em;
            font-family: var(--font-display);
        }

        .info-row {
            display: flex;
            justify-content: space-between;
            font-size: 12px;
            padding: 7px 0;
            border-bottom: 0.5px solid rgba(255, 255, 255, 0.06);
        }

        .info-row span:first-child {
            color: var(--label-2);
        }

        .info-row span:last-child {
            color: #ffffff;
            font-weight: 600;
        }

        /* ========================================================================= */
        /* STRAND LIQUID GLASS BOTTOM DOCK (Chuẩn phong cách TV Play Strand Image 3) */
        /* ========================================================================= */
        .strand-dock-container {
            position: fixed;
            bottom: calc(env(safe-area-inset-bottom, 8px) + 8px);
            left: 12px;
            right: 12px;
            max-width: 520px;
            margin-left: auto;
            margin-right: auto;
            z-index: 90;
            display: flex;
            flex-direction: row;
            align-items: center;
            justify-content: center;
            pointer-events: none;
        }

        .strand-liquid-dock {
            pointer-events: auto;
            flex: 1 1 auto;
            height: 62px;
            background: linear-gradient(135deg, rgba(255, 255, 255, 0.16) 0%, rgba(255, 255, 255, 0.05) 100%), rgba(20, 20, 26, 0.88);
            backdrop-filter: blur(40px) saturate(210%);
            -webkit-backdrop-filter: blur(40px) saturate(210%);
            border: 1px solid rgba(255, 255, 255, 0.18);
            border-top: 1px solid rgba(255, 255, 255, 0.45);
            box-shadow: 
                inset 0 1px 1px 0 rgba(255, 255, 255, 0.4),
                inset 0 -1px 1px 0 rgba(0, 0, 0, 0.4),
                0 16px 44px -8px rgba(0, 0, 0, 0.85),
                0 0 24px 0 rgba(255, 101, 0, 0.2);
            border-radius: 32px;
            padding: 4px 6px;
            display: flex;
            flex-direction: row;
            align-items: center;
            justify-content: space-around;
        }

        .dock-tab {
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            flex: 1 1 0%;
            height: 100%;
            padding: 4px 6px;
            border-radius: 24px;
            color: var(--label-2);
            transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
            cursor: pointer;
            background: transparent;
            border: none;
            outline: none;
            gap: 3px;
        }

        .dock-tab svg {
            width: 20px;
            height: 20px;
            fill: currentColor;
            transition: transform 0.15s, color 0.15s;
        }

        .dock-tab span {
            font-size: 10px;
            font-weight: 600;
            letter-spacing: 0.02em;
        }

        .dock-tab:hover {
            color: #ffffff;
        }

        .dock-tab.active {
            color: #ffffff;
            background: rgba(255, 255, 255, 0.16);
            box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.3), 0 2px 8px rgba(0, 0, 0, 0.3);
        }

        .dock-tab.active svg {
            color: var(--accent);
            filter: drop-shadow(0 0 8px var(--accent-glow));
            transform: scale(1.08);
        }

        .dock-tab.active span {
            color: #ffffff;
            font-weight: 700;
        }

        /* Floating Toast */
        #toast {
            position: fixed;
            top: 70px;
            left: 50%;
            transform: translateX(-50%);
            background: rgba(24, 24, 30, 0.94);
            backdrop-filter: blur(28px);
            -webkit-backdrop-filter: blur(28px);
            border: 0.5px solid var(--accent);
            color: #ffffff;
            padding: 8px 18px;
            border-radius: 24px;
            font-size: 12px;
            font-weight: 700;
            z-index: 200;
            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.8), 0 0 20px rgba(255, 101, 0, 0.25);
            opacity: 0;
            transition: opacity 0.25s, transform 0.25s;
            pointer-events: none;
        }

        #toast.show {
            opacity: 1;
            transform: translateX(-50%) translateY(4px);
        }
    
        /* SPORTS ENHANCED STYLES (Synchronized with ndkhoams.github.io) */
        .sports-filter-scroll {
            display: flex;
            gap: 8px;
            overflow-x: auto;
            padding: 4px 2px 14px;
            margin-bottom: 8px;
            scrollbar-width: none;
            -webkit-overflow-scrolling: touch;
        }
        .sports-filter-scroll::-webkit-scrollbar { display: none; }

        .sports-filter-pill {
            padding: 6px 14px;
            border-radius: 9999px;
            font-size: 11px;
            font-weight: 700;
            background: rgba(28, 28, 34, 0.7);
            border: 0.5px solid rgba(255, 255, 255, 0.12);
            color: var(--label-2);
            white-space: nowrap;
            cursor: pointer;
            transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
        }
        .sports-filter-pill.active {
            background: linear-gradient(135deg, rgba(255, 101, 0, 0.25), rgba(255, 140, 0, 0.15));
            border-color: var(--accent);
            color: #fff;
            box-shadow: 0 0 12px rgba(255, 101, 0, 0.35);
        }

        .sport-badge {
            display: inline-flex;
            align-items: center;
            gap: 4px;
            padding: 2px 7px;
            border-radius: 6px;
            font-size: 10px;
            font-weight: 800;
            letter-spacing: 0.02em;
            flex-shrink: 0;
            box-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
        }
        .sport-football { background: rgba(14, 165, 233, 0.15); border: 0.5px solid rgba(14, 165, 233, 0.4); color: #7dd3fc; }
        .sport-basketball { background: rgba(249, 115, 22, 0.15); border: 0.5px solid rgba(249, 115, 22, 0.4); color: #fdba74; }
        .sport-f1 { background: rgba(244, 63, 94, 0.15); border: 0.5px solid rgba(244, 63, 94, 0.4); color: #fda4af; }
        .sport-tennis { background: rgba(132, 204, 22, 0.15); border: 0.5px solid rgba(132, 204, 22, 0.4); color: #bef264; }
        .sport-rugby { background: rgba(16, 185, 129, 0.15); border: 0.5px solid rgba(16, 185, 129, 0.4); color: #6ee7b7; }
        .sport-billiards { background: rgba(168, 85, 247, 0.15); border: 0.5px solid rgba(168, 85, 247, 0.4); color: #d8b4fe; }
        .sport-martial { background: rgba(239, 68, 68, 0.15); border: 0.5px solid rgba(239, 68, 68, 0.4); color: #fca5a5; }
        .sport-esports { background: rgba(99, 102, 241, 0.15); border: 0.5px solid rgba(99, 102, 241, 0.4); color: #a5b4fc; }

        .match-card {
            background: var(--bg-card);
            backdrop-filter: blur(28px) saturate(180%);
            -webkit-backdrop-filter: blur(28px) saturate(180%);
            border: 0.5px solid rgba(255, 255, 255, 0.1);
            border-top: 1px solid rgba(255, 255, 255, 0.2);
            border-radius: 20px;
            padding: 14px 16px;
            margin-bottom: 12px;
            transition: all 0.22s cubic-bezier(0.16, 1, 0.3, 1);
            cursor: pointer;
            position: relative;
            overflow: hidden;
            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5);
        }
        .match-card:hover {
            border-color: rgba(255, 255, 255, 0.24);
            transform: translateY(-1px);
        }
        .match-card.expanded {
            border-color: rgba(255, 101, 0, 0.5);
            background: rgba(30, 26, 28, 0.88);
            box-shadow: 0 12px 36px rgba(255, 101, 0, 0.18);
        }

        .match-top-row {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 8px;
            margin-bottom: 10px;
        }
        .match-league-name {
            font-size: 12px;
            font-weight: 800;
            color: var(--blue);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            max-width: 170px;
        }

        .match-teams-row {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 8px;
            padding: 4px 0;
        }
        .team-box {
            display: flex;
            align-items: center;
            gap: 10px;
            flex: 1;
            min-width: 0;
        }
        .team-box.away {
            flex-direction: row-reverse;
            text-align: right;
        }
        .team-logo-box {
            width: 40px;
            height: 40px;
            border-radius: 12px;
            background: rgba(255, 255, 255, 0.08);
            border: 0.5px solid rgba(255, 255, 255, 0.16);
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 4px;
            flex-shrink: 0;
            overflow: hidden;
            position: relative;
            box-shadow: inset 0 1px 2px rgba(255, 255, 255, 0.12);
        }
        .team-logo-img {
            width: 100%;
            height: 100%;
            object-fit: contain;
            filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.6));
        }
        .team-fallback {
            font-size: 12px;
            font-weight: 800;
            color: rgba(255, 255, 255, 0.95);
            letter-spacing: 0.04em;
        }
        .team-name {
            font-size: 13px;
            font-weight: 700;
            color: #ffffff;
            line-height: 1.3;
            display: -webkit-box;
            -webkit-line-clamp: 2;
            -webkit-box-orient: vertical;
            overflow: hidden;
            word-break: break-word;
        }

        .match-vs-pillar {
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 0 6px;
            flex-shrink: 0;
        }
        .vs-badge {
            font-family: monospace;
            font-size: 11px;
            font-weight: 800;
            color: var(--label-3);
        }
        .vs-time {
            font-family: monospace;
            font-size: 10px;
            font-weight: 700;
            color: #fbbf24;
            margin-top: 2px;
            letter-spacing: 0.03em;
        }

        .match-toggle-row {
            margin-top: 10px;
            padding-top: 8px;
            border-top: 1px solid rgba(255, 255, 255, 0.08);
            display: flex;
            align-items: center;
            justify-content: space-between;
            font-size: 11px;
        }
        .match-toggle-left {
            display: flex;
            align-items: center;
            gap: 6px;
            color: var(--label-2);
        }
        .stream-dot {
            width: 6px;
            height: 6px;
            border-radius: 50%;
            background: #34d399;
            box-shadow: 0 0 6px rgba(52, 211, 153, 0.6);
        }
        .match-toggle-right {
            display: flex;
            align-items: center;
            gap: 4px;
            color: var(--orange);
            font-weight: 700;
        }
        .chevron-icon {
            width: 14px;
            height: 14px;
            transition: transform 0.2s ease;
            stroke: currentColor;
        }
        .match-card.expanded .chevron-icon {
            transform: rotate(180deg);
        }

        .sources-box {
            margin-top: 10px;
            padding-top: 10px;
            border-top: 1px dashed rgba(255, 255, 255, 0.15);
            display: none;
        }
        .sources-box.show {
            display: block;
            animation: fadeIn 0.2s cubic-bezier(0.16, 1, 0.3, 1);
        }
        .sources-title-row {
            display: flex;
            align-items: center;
            justify-content: space-between;
            font-size: 11px;
            color: var(--label-3);
            margin-bottom: 8px;
        }
        .streams-flex-grid {
            display: flex;
            flex-wrap: wrap;
            gap: 6px;
        }
        .stream-pill-btn {
            padding: 8px 14px;
            border-radius: 12px;
            font-size: 12px;
            font-weight: 600;
            display: inline-flex;
            align-items: center;
            gap: 6px;
            background: rgba(28, 28, 34, 0.85);
            border: 0.5px solid rgba(255, 255, 255, 0.14);
            color: rgba(255, 255, 255, 0.9);
            cursor: pointer;
            transition: all 0.15s ease;
        }
        .stream-pill-btn:hover {
            background: rgba(255, 255, 255, 0.15);
            border-color: rgba(255, 255, 255, 0.28);
            color: #fff;
        }
        .stream-pill-btn.active {
            background: linear-gradient(135deg, #ff6500, #ff9f0a);
            border-color: rgba(255, 255, 255, 0.4);
            color: #fff;
            box-shadow: 0 4px 14px rgba(255, 101, 0, 0.45);
        }
</style>
</head>
<body>

    <!-- Ambient Dynamic Glow (Strand Backdrop Lighting) -->
    <div class="ambient-glow"></div>

    <!-- 1. Sticky Strand Header -->
    <header class="app-header-sticky">
        <div class="header-inner">
            <div class="brand-title">
                <div class="brand-logo-box">
                    <svg viewBox="0 0 24 24"><path d="M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z"/></svg>
                </div>
                <div>
                    <div class="brand-name">TV Play</div>
                    <div class="brand-sub">Web Remote</div>
                </div>
            </div>
            <div id="tvStatusBadge" class="tv-status-badge status-disconnected">
                <span class="status-dot"></span>
                <span id="tvStatusText">Chờ TV kết nối...</span>
            </div>
        </div>
    </header>

    <!-- 2. Now Playing Mini-Hero Card -->
    <div class="now-playing-wrap">
        <div class="now-playing-card">
            <div class="now-playing-left">
                <span class="live-badge">
                    <span class="status-dot"></span>
                    <span>ĐANG PHÁT</span>
                </span>
                <span id="bannerChName" class="ch-name">Chưa có luồng phát</span>
            </div>
            <button class="osd-pill-btn" onclick="sendKey('overlay')">Bật OSD 📺</button>
        </div>
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
                <button class="action-btn btn-mode-tv" onclick="sendKey('mode_tv')">📺 Xem TV</button>
                <button class="action-btn btn-mode-sports" onclick="sendKey('mode_sports')">⚽ Trực tiếp Thể Thao</button>
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
        <div class="search-wrap">
            <svg class="search-icon-svg" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/>
            </svg>
            <input type="text" id="channelSearch" class="search-box" placeholder="Tìm kiếm kênh truyền hình (VTV, K+, HBO...)" oninput="filterChannels()">
        </div>
        <div id="channelsContainer" class="channels-list">Đang nạp danh sách kênh...</div>
    </div>

    <!-- TAB 3: THỂ THAO -->
    <div id="tab-sports" class="tab-content">
        <div class="sports-header">
            <div class="sports-section-title">
                <span class="status-dot" style="background:var(--red); box-shadow:0 0 8px var(--red); animation: pulse-live 1.6s infinite;"></span>
                <span>TRỰC TIẾP THỂ THAO</span>
                <span id="sportsCountBadge" class="count-pill">0 trận</span>
            </div>
            <button class="refresh-btn" onclick="loadSports()">
                <svg style="width:12px; height:12px;" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/>
                </svg>
                <span>Tải lại</span>
            </button>
        </div>

        <!-- Horizontal League & Sport Filter Pills (matching ndkhoams.github.io) -->
        <div id="sportsFilters" class="sports-filter-scroll">
            <button class="sports-filter-pill active" onclick="filterSports('all')">Tất cả</button>
            <button class="sports-filter-pill" onclick="filterSports('live')">🔴 Đang trực tiếp</button>
        </div>

        <!-- Sports Matches Container -->
        <div id="sportsContainer">
            <div style="color:var(--label-2); padding:20px; text-align:center;">Đang nạp dữ liệu thể thao...</div>
        </div>
    </div>

    <!-- TAB 4: DANH SÁCH PHÁT (PLAYLISTS) -->
    <div id="tab-playlists" class="tab-content">
        <div class="form-card">
            <h3>🔗 Dán Link M3U Mới Từ Điện Thoại</h3>
            <div class="form-group">
                <label>Tên danh sách</label>
                <input type="text" id="newPlName" class="form-input" placeholder="Ví dụ: Kênh Thể Thao, Kênh Quốc Tế...">
            </div>
            <div class="form-group">
                <label>Đường link URL (M3U / M3U8)</label>
                <input type="url" id="newPlUrl" class="form-input" placeholder="http://... hoặc https://...">
            </div>
            <div style="margin-top:10px; margin-bottom:14px; display:flex; align-items:center; gap:8px;">
                <input type="checkbox" id="newPlSelectNow" checked style="width:18px; height:18px; accent-color:#FF6500; cursor:pointer;">
                <label for="newPlSelectNow" style="font-size:12px; color:var(--label); cursor:pointer;">Kích hoạt phát danh sách này ngay trên TV</label>
            </div>
            <button class="submit-btn" onclick="addNewPlaylist()">+ Thêm &amp; Lưu Vào TV</button>
        </div>

        <div class="form-card">
            <h3>📂 Tải File M3U Từ Điện Thoại Lên TV</h3>
            <p style="font-size:12px; color:var(--label-2); margin-bottom:12px; line-height:1.4;">Truyền file M3U/M3U8 từ bộ nhớ điện thoại hoặc máy tính sang TV qua Wi-Fi.</p>
            <div class="form-group">
                <label>Tên danh sách (tùy chọn)</label>
                <input type="text" id="uploadPlName" class="form-input" placeholder="Để trống sẽ lấy theo tên file">
            </div>
            <div class="form-group">
                <label>Chọn file (.m3u, .m3u8, .txt)</label>
                <input type="file" id="fileLocalM3u" accept=".m3u,.m3u8,.txt" class="form-input" style="padding:8px;">
            </div>
            <div style="margin-top:10px; margin-bottom:14px; display:flex; align-items:center; gap:8px;">
                <input type="checkbox" id="uploadPlSelectNow" checked style="width:18px; height:18px; accent-color:#10b981; cursor:pointer;">
                <label for="uploadPlSelectNow" style="font-size:12px; color:var(--label); cursor:pointer;">Kích hoạt phát danh sách này ngay trên TV</label>
            </div>
            <button class="submit-btn" style="background:linear-gradient(135deg, #10b981 0%, #059669 100%); box-shadow:0 6px 20px rgba(16, 185, 129, 0.3);" onclick="uploadLocalPlaylist()">⬆ Tải File Lên TV</button>
        </div>

        <h3 style="font-size:13px; margin: 18px 0 10px; color:var(--accent); font-weight:800; letter-spacing:0.04em;">📑 DANH SÁCH PLAYLIST ĐÃ CÓ:</h3>
        <div id="playlistsContainer">Đang tải danh sách playlist...</div>
    </div>

    <!-- TAB 5: CÀI ĐẶT & TIZEN -->
    <div id="tab-settings" class="tab-content">
        <!-- SAMSUNG TIZEN DEPLOYMENT CARD -->
        <div class="settings-card" style="border: 0.5px solid rgba(255, 101, 0, 0.4); border-top: 1px solid rgba(255, 150, 50, 0.6); background: linear-gradient(180deg, rgba(35, 20, 16, 0.7) 0%, rgba(20, 20, 26, 0.85) 100%);">
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px;">
                <h3 style="margin-bottom:0; display:flex; align-items:center; gap:6px; color:var(--accent); font-size:14px;">
                    <span>📺</span> SAMSUNG TIZEN TV (.WGT)
                </h3>
                <span id="tizenStatusBadge" class="tv-status-badge status-disconnected">
                    <span class="status-dot"></span>
                    <span id="tizenBadgeText">Sẵn sàng</span>
                </span>
            </div>

            <p style="font-size:12px; color:var(--label-2); margin-bottom:12px; line-height:1.4;">
                Cài đặt trực tiếp file <code>.wgt</code> từ máy chủ Armbian lên Samsung TV qua mạng LAN (cài ngầm 100%, không làm gián đoạn người đang xem YouTube trên TV).
            </p>

            <div class="form-group" style="margin-bottom:12px;">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
                    <label style="font-size:12px; color:var(--label-2); font-weight:600;">Địa chỉ IP Samsung TV</label>
                    <button class="action-btn" style="padding:4px 10px; font-size:11px; background:rgba(255,255,255,0.08);" onclick="checkTizenTv()">🔍 Kiểm Tra TV</button>
                </div>
                <input type="text" id="tizenTvIp" class="form-input" value="10.0.0.9" placeholder="10.0.0.9">
            </div>

            <!-- SECTION 1: CHỌN GÓI .WGT ĐỂ CÀI ĐẶT -->
            <div style="margin-top:14px; padding:12px; background:rgba(255,255,255,0.04); border:1px solid rgba(255,255,255,0.1); border-radius:14px;">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
                    <label style="font-size:12px; font-weight:700; color:var(--label); display:flex; align-items:center; gap:6px;">
                        <span>📦</span> Chọn gói .wgt để cài đặt:
                    </label>
                    <button style="background:none; border:none; color:var(--accent); font-size:11px; cursor:pointer;" onclick="loadTizenWgts()">🔄 Làm mới danh sách</button>
                </div>
                <select id="tizenWgtSelect" class="form-input" style="padding:10px; font-size:12px; font-weight:600; width:100%; margin-bottom:10px; background:#181820; color:#fff; border:1px solid rgba(255,255,255,0.18); border-radius:10px;">
                    <option value="/opt/samsung/TVplay.wgt">TVplay.wgt (Bản chuẩn máy chủ - 375 KB)</option>
                </select>
                <div style="display:flex; gap:8px;">
                    <button class="action-btn" style="flex:1; background:linear-gradient(135deg, #ff6500 0%, #ff8800 100%); border-color:rgba(255,255,255,0.3); color:#fff; font-weight:800; box-shadow:0 4px 16px rgba(255,101,0,0.35); padding:10px;" onclick="installSelectedTizenWgt()">
                        📦 Cài Đặt Gói Đã Chọn Lên TV
                    </button>
                    <button class="action-btn btn-mode-tv" style="padding:10px 14px;" onclick="launchTizenApp()" title="Mở app TV Play trên TV">
                        🚀 Mở App TV Play
                    </button>
                </div>
            </div>

            <!-- SECTION 2: TẢI LÊN & CÀI ĐẶT GÓI .WGT NGƯỜI DÙNG UPLOAD -->
            <div style="margin-top:14px; padding:12px; background:rgba(255,255,255,0.04); border:1px dashed rgba(255,255,255,0.22); border-radius:14px;">
                <label style="font-size:12px; font-weight:700; color:var(--label); display:flex; align-items:center; gap:6px; margin-bottom:6px;">
                    <span>⬆️</span> Tải lên file .wgt mới từ thiết bị của bạn:
                </label>
                <input type="file" id="tizenWgtUploadFile" accept=".wgt" onchange="onTizenFileSelected(event)" style="display:none;">
                <button type="button" onclick="document.getElementById('tizenWgtUploadFile').click()" class="action-btn" style="width:100%; padding:10px; font-size:12px; background:rgba(255,255,255,0.08); border:1px solid rgba(255,255,255,0.15);">
                    📁 Chọn file .wgt từ điện thoại / máy tính...
                </button>
                
                <div id="tizenFileSelectedBox" style="display:none; margin-top:10px; padding:10px 12px; background:rgba(255,101,0,0.12); border:1px solid rgba(255,101,0,0.35); border-radius:10px;">
                    <div style="font-size:12px; color:#fff; font-weight:700; margin-bottom:2px;" id="tizenFileNamePreview">Đã chọn: TVplay.wgt</div>
                    <div style="font-size:11px; color:var(--label-2); margin-bottom:10px;" id="tizenFileSizePreview">Dung lượng: 384 KB</div>
                    <div style="display:flex; gap:8px;">
                        <button class="action-btn" style="flex:1; background:linear-gradient(135deg, #10b981 0%, #059669 100%); color:#fff; font-weight:800; padding:8px; font-size:12px;" onclick="uploadAndInstallTizenWgt(true)">
                            ⚡ Tải Lên &amp; Cài Đặt Ngay
                        </button>
                        <button class="action-btn" style="background:rgba(255,255,255,0.12); color:#fff; font-weight:600; padding:8px 12px; font-size:12px;" onclick="uploadAndInstallTizenWgt(false)">
                            ⬆️ Chỉ Tải Lên Máy Chủ
                        </button>
                    </div>
                </div>
            </div>

            <!-- Deployment Log / Status Output -->
            <div id="tizenLogWrap" style="margin-top:12px; display:none;">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
                    <span style="font-size:11px; font-weight:700; color:var(--label-2);">TIẾN TRÌNH &amp; NHẬT KÝ CLI:</span>
                    <button style="background:none; border:none; color:var(--label-3); font-size:11px; cursor:pointer;" onclick="toggleTizenLog()">Ẩn/Hiện</button>
                </div>
                <pre id="tizenLogBox" style="background:#000000; border:0.5px solid rgba(255,255,255,0.14); border-radius:12px; padding:10px; font-size:11px; color:#30d158; max-height:160px; overflow-y:auto; white-space:pre-wrap; word-break:break-all; font-family:monospace;"></pre>
            </div>
        </div>

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

    <div class="strand-dock-container">
        <nav class="strand-liquid-dock">
            <button class="dock-tab active" onclick="switchTab('remote')">
                <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm-1-13h2v4h-2zm0 6h2v2h-2z"/></svg>
                <span>Điều Khiển</span>
            </button>
            <button class="dock-tab" onclick="switchTab('channels')">
                <svg viewBox="0 0 24 24"><path d="M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z"/></svg>
                <span>Kênh TV</span>
            </button>
            <button class="dock-tab" onclick="switchTab('sports')">
                <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/></svg>
                <span>Thể Thao</span>
            </button>
            <button class="dock-tab" onclick="switchTab('playlists')">
                <svg viewBox="0 0 24 24"><path d="M4 10h12v2H4zm0-4h12v2H4zm0 8h8v2H4zm10 0v6l5-3z"/></svg>
                <span>Danh Sách</span>
            </button>
            <button class="dock-tab" onclick="switchTab('settings')">
                <svg viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>
                <span>Cài Đặt</span>
            </button>
        </nav>
    </div>

    <!-- Toast Notification -->
    <div id="toast"></div>

    <script>
        var allChannels = [];
        var activeCat = 'Tất cả';
        var toastTimer = null;

        function showToast(msg) {
            var t = document.getElementById('toast');
            if (!t) return;
            t.textContent = msg;
            t.classList.add('show');
            clearTimeout(toastTimer);
            toastTimer = setTimeout(function() { t.classList.remove('show'); }, 2200);
        }

        function switchTab(tabId) {
            document.querySelectorAll('.tab-content').forEach(function(el) { el.classList.remove('active'); });
            document.querySelectorAll('.dock-tab').forEach(function(el) { el.classList.remove('active'); });
            var targetTab = document.getElementById('tab-' + tabId);
            if (targetTab) targetTab.classList.add('active');
            
            var btns = document.querySelectorAll('.dock-tab');
            if (tabId === 'remote' && btns[0]) btns[0].classList.add('active');
            if (tabId === 'channels' && btns[1]) { btns[1].classList.add('active'); if (!allChannels.length) loadChannels(); }
            if (tabId === 'sports' && btns[2]) { btns[2].classList.add('active'); loadSports(); }
            if (tabId === 'playlists' && btns[3]) { btns[3].classList.add('active'); loadPlaylists(); }
            if (tabId === 'settings' && btns[4]) { btns[4].classList.add('active'); loadTizenStatus(); }
        }

        /* PLAYLIST FUNCTIONS */
        var currentPlaylists = [];

        function loadPlaylists() {
            var container = document.getElementById('playlistsContainer');
            container.innerHTML = '<div style="color:var(--label-2); padding:10px;">Đang nạp danh sách...</div>';
            fetch('/api/playlists?t=' + Date.now())
            .then(function(res) { return res.json(); })
            .then(function(list) {
                currentPlaylists = list || [];
                if (!list || list.length === 0) {
                    container.innerHTML = '<div style="color:var(--label-2); padding:12px;">Chưa có playlist nào.</div>';
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
                showToast('Phát: ' + ch.name);
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
            container.innerHTML = '<div style="color:var(--label-2); padding:16px; text-align:center;">Đang nạp danh sách kênh...</div>';
            fetch('/api/channels').then(function(res) { return res.json(); }).then(function(channels) {
                allChannels = channels;
                buildCategories();
                renderChannels();
            }).catch(function(e) {
                container.innerHTML = '<div style="color:var(--red); padding:16px; text-align:center;">Lỗi nạp kênh từ Armbian Hub</div>';
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
                container.innerHTML = '<div style="color:var(--label-3); padding:24px; text-align:center;">Không tìm thấy kênh phù hợp</div>';
                return;
            }
            var html = '';
            list.forEach(function(ch, idx) {
                var numStr = ch.number < 10 ? '0' + ch.number : ch.number;
                html += '<div class="channel-row" onclick="onChannelClick(' + idx + ')">' +
                    '<div class="channel-left">' +
                        '<div class="ch-logo-box">' +
                            '<span class="ch-num">' + numStr + '</span>' +
                        '</div>' +
                        '<div style="overflow:hidden; flex:1;">' +
                            '<div class="ch-title">' + (ch.name || '') + '</div>' +
                            '<span class="ch-group">' + (ch.groupTitle || 'Kênh TV') + '</span>' +
                        '</div>' +
                    '</div>' +
                    '<button class="btn-play-pill">Phát ▶</button>' +
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

        var allLiveEvents = [];
        var activeSportsFilter = 'all';
        var expandedEventIdx = -1;
        var activeStreamEventIdx = -1;
        var activeStreamSourceIdx = -1;

        function normalizeName(str) {
            if (!str) return '';
            return str.toLowerCase()
                .normalize('NFD')
                .replace(/[\u0300-\u036f]/g, '')
                .replace(/[đĐ]/g, 'd')
                .replace(/[^a-z0-9]/g, '')
                .trim();
        }

        var countryFlagsMap = {};
        (function initCountryFlags() {
            var FLAGS = {
                vn: ['vietnam', 'viet nam'],
                tw: ['chinese taipei', 'dai loan', 'taiwan'],
                be: ['belgium', 'bi'],
                si: ['slovenia'],
                in: ['india', 'an do'],
                ru: ['russia', 'nga'],
                fi: ['finland', 'phan lan'],
                th: ['thailand', 'thai lan'],
                id: ['indonesia'],
                my: ['malaysia'],
                sg: ['singapore'],
                ph: ['philippines'],
                cn: ['china', 'trung quoc'],
                jp: ['japan', 'nhat ban'],
                kr: ['korea', 'south korea', 'han quoc'],
                gb: ['england', 'anh'],
                fr: ['france', 'phap'],
                de: ['germany', 'duc'],
                es: ['spain', 'tay ban nha'],
                it: ['italy', 'y'],
                pt: ['portugal', 'bo dao nha'],
                nl: ['netherlands', 'ha lan'],
                ar: ['argentina'],
                br: ['brazil'],
                co: ['colombia'],
                uy: ['uruguay'],
                hr: ['croatia'],
                ch: ['switzerland', 'thuy si'],
                se: ['sweden', 'thuy dien'],
                dk: ['denmark', 'dan mach'],
                no: ['norway', 'na uy'],
                pl: ['poland', 'ba lan'],
                tr: ['turkey', 'tho nhi ky'],
                ro: ['romania'],
                gr: ['greece', 'hy lap'],
                at: ['austria', 'ao'],
                cz: ['czech republic', 'sec'],
                sk: ['slovakia'],
                hu: ['hungary'],
                rs: ['serbia'],
                ua: ['ukraine'],
                ma: ['morocco', 'maroc'],
                sn: ['senegal'],
                sa: ['saudi arabia'],
                qa: ['qatar'],
                us: ['usa', 'united states', 'my'],
                am: ['armenia'],
                lv: ['latvia'],
                cy: ['sip', 'cyprus'],
                me: ['montenegro'],
                sm: ['san marino'],
                by: ['belarus'],
                al: ['albania']
            };
            for (var code in FLAGS) {
                var aliases = FLAGS[code];
                var url = 'https://flagcdn.com/w80/' + code + '.png';
                for (var i = 0; i < aliases.length; i++) {
                    countryFlagsMap[normalizeName(aliases[i])] = url;
                }
            }
        })();

        var premappedClubs = {
            arsenal: 'https://a.espncdn.com/i/teamlogos/soccer/500/359.png',
            chelsea: 'https://a.espncdn.com/i/teamlogos/soccer/500/363.png',
            liverpool: 'https://a.espncdn.com/i/teamlogos/soccer/500/364.png',
            manchestercity: 'https://a.espncdn.com/i/teamlogos/soccer/500/382.png',
            mancity: 'https://a.espncdn.com/i/teamlogos/soccer/500/382.png',
            manchesterunited: 'https://a.espncdn.com/i/teamlogos/soccer/500/360.png',
            manunited: 'https://a.espncdn.com/i/teamlogos/soccer/500/360.png',
            tottenham: 'https://a.espncdn.com/i/teamlogos/soccer/500/367.png',
            tottenhamhotspur: 'https://a.espncdn.com/i/teamlogos/soccer/500/367.png',
            astonvilla: 'https://a.espncdn.com/i/teamlogos/soccer/500/362.png',
            newcastle: 'https://a.espncdn.com/i/teamlogos/soccer/500/361.png',
            newcastleunited: 'https://a.espncdn.com/i/teamlogos/soccer/500/361.png',
            brighton: 'https://a.espncdn.com/i/teamlogos/soccer/500/331.png',
            westham: 'https://a.espncdn.com/i/teamlogos/soccer/500/371.png',
            everton: 'https://a.espncdn.com/i/teamlogos/soccer/500/368.png',
            fulham: 'https://a.espncdn.com/i/teamlogos/soccer/500/370.png',
            brentford: 'https://a.espncdn.com/i/teamlogos/soccer/500/337.png',
            wolves: 'https://a.espncdn.com/i/teamlogos/soccer/500/380.png',
            realmadrid: 'https://a.espncdn.com/i/teamlogos/soccer/500/86.png',
            barcelona: 'https://a.espncdn.com/i/teamlogos/soccer/500/83.png',
            atleticomadrid: 'https://a.espncdn.com/i/teamlogos/soccer/500/1068.png',
            sevilla: 'https://a.espncdn.com/i/teamlogos/soccer/500/243.png',
            realsociedad: 'https://a.espncdn.com/i/teamlogos/soccer/500/89.png',
            villarreal: 'https://a.espncdn.com/i/teamlogos/soccer/500/102.png',
            athleticbilbao: 'https://a.espncdn.com/i/teamlogos/soccer/500/96.png',
            valencia: 'https://a.espncdn.com/i/teamlogos/soccer/500/94.png',
            bayernmunich: 'https://a.espncdn.com/i/teamlogos/soccer/500/132.png',
            borussiadortmund: 'https://a.espncdn.com/i/teamlogos/soccer/500/124.png',
            dortmund: 'https://a.espncdn.com/i/teamlogos/soccer/500/124.png',
            bayerleverkusen: 'https://a.espncdn.com/i/teamlogos/soccer/500/131.png',
            rbleipzig: 'https://a.espncdn.com/i/teamlogos/soccer/500/11420.png',
            intermilan: 'https://a.espncdn.com/i/teamlogos/soccer/500/110.png',
            inter: 'https://a.espncdn.com/i/teamlogos/soccer/500/110.png',
            acmilan: 'https://a.espncdn.com/i/teamlogos/soccer/500/103.png',
            milan: 'https://a.espncdn.com/i/teamlogos/soccer/500/103.png',
            juventus: 'https://a.espncdn.com/i/teamlogos/soccer/500/111.png',
            napoli: 'https://a.espncdn.com/i/teamlogos/soccer/500/114.png',
            asroma: 'https://a.espncdn.com/i/teamlogos/soccer/500/104.png',
            psg: 'https://a.espncdn.com/i/teamlogos/soccer/500/160.png',
            marseille: 'https://a.espncdn.com/i/teamlogos/soccer/500/166.png'
        };

        
        var VN_TO_ENG = {
            "phap": "France", "tho nhi ky": "Turkey", "sip": "Cyprus", "tay ban nha": "Spain",
            "y": "Italy", "duc": "Germany", "anh": "England", "bo dao nha": "Portugal",
            "ha lan": "Netherlands", "bi": "Belgium", "thuy si": "Switzerland", "dan mach": "Denmark",
            "na uy": "Norway", "thuy dien": "Sweden", "ba lan": "Poland", "sec": "Czech Republic",
            "cong hoa sec": "Czech Republic", "ao": "Austria", "hy lap": "Greece", "my": "USA",
            "uc": "Australia", "nhat ban": "Japan", "nhat": "Japan", "han quoc": "South Korea",
            "han": "South Korea", "trung quoc": "China", "trung": "China", "trieu tien": "North Korea",
            "thai lan": "Thailand", "viet nam": "Vietnam", "vietnam": "Vietnam", "lao": "Laos",
            "campuchia": "Cambodia", "bo bien nga": "Ivory Coast", "nam phi": "South Africa",
            "a rap xe ut": "Saudi Arabia", "a rap saudi": "Saudi Arabia", "xu wales": "Wales",
            "wales": "Wales", "bac ireland": "Northern Ireland", "cong hoa ireland": "Ireland",
            "ireland": "Ireland", "nga": "Russia", "phan lan": "Finland", "dai loan": "Chinese Taipei",
            "dai bac": "Chinese Taipei", "an do": "India", "maroc": "Morocco", "ai cap": "Egypt",
            "mon-te-ne-gro": "Montenegro", "montenegro": "Montenegro", "armenia": "Armenia",
            "latvia": "Latvia", "finland": "Finland", "belarus": "Belarus", "albania": "Albania",
            "san marino": "San Marino", "kazakhstan": "Kazakhstan", "slovakia": "Slovakia",
            "hungary": "Hungary", "serbia": "Serbia", "georgia": "Georgia", "croatia": "Croatia",
            "scotland": "Scotland", "romania": "Romania", "slovenia": "Slovenia", "iceland": "Iceland",
            "luxembourg": "Luxembourg", "estonia": "Estonia", "bulgaria": "Bulgaria",
            "lithuania": "Lithuania", "liechtenstein": "Liechtenstein", "gibraltar": "Gibraltar",
            "malta": "Malta", "faroe islands": "Faroe Islands", "faroe": "Faroe Islands",
            "moldova": "Moldova", "north macedonia": "North Macedonia", "macedonia": "North Macedonia",
            "venezuela": "Venezuela", "turkiye": "Turkey"
        };

        function stripVn(str) {
            if (!str) return '';
            return str.normalize('NFD')
                .replace(/[\u0300-\u036f]/g, '')
                .replace(/[đĐ]/g, 'd')
                .replace(/[^a-zA-Z0-9\s]/g, ' ')
                .replace(/\s+/g, ' ')
                .trim();
        }

        function toEnglishTeam(name) {
            if (!name) return '';
            var clean = name.replace(/^(?:đội tuyển quốc gia|đội tuyển|đtqg|đt|tuyển|clb|fc)\s+/i, '').trim();
            var unacc = stripVn(clean).toLowerCase();
            if (VN_TO_ENG[unacc]) return VN_TO_ENG[unacc];
            for (var k in VN_TO_ENG) {
                if (unacc === k || (' ' + unacc + ' ').indexOf(' ' + k + ' ') !== -1) {
                    return VN_TO_ENG[k];
                }
            }
            return unacc.split(' ').map(function(w) {
                if (/^\d+[a-zA-Z]+$/.test(w)) return w.toLowerCase();
                return w.charAt(0).toUpperCase() + w.slice(1);
            }).join(' ');
        }

        function getSportInfo(evt) {
            var rawLeague = (evt.league || '').toUpperCase();
            var leagueStr = (evt.leagueName || '').toLowerCase();
            var homeStr = (evt.homeTeam || '').toLowerCase();
            var awayStr = (evt.awayTeam || '').toLowerCase();
            var combined = (rawLeague + ' ' + leagueStr + ' ' + homeStr + ' ' + awayStr).toLowerCase();

            if (rawLeague === 'RUGBY' || combined.indexOf('rugby') !== -1 || combined.indexOf('bóng bầu dục') !== -1) {
                return { name: 'Bóng bầu dục', icon: '🏉', badgeClass: 'sport-rugby', cleanLeague: evt.leagueName || 'Rugby' };
            }
            if (rawLeague === 'F1' || combined.indexOf('f1') !== -1 || combined.indexOf('grand prix') !== -1 || combined.indexOf('đua xe') !== -1) {
                return { name: 'Đua xe F1', icon: '🏎️', badgeClass: 'sport-f1', cleanLeague: evt.leagueName || 'Formula 1' };
            }
            if (rawLeague === 'TENNIS' || combined.indexOf('tennis') !== -1 || combined.indexOf('quần vợt') !== -1 || combined.indexOf('atp') !== -1 || combined.indexOf('wta') !== -1) {
                return { name: 'Quần vợt', icon: '🎾', badgeClass: 'sport-tennis', cleanLeague: evt.leagueName || 'Tennis' };
            }
            if (rawLeague === 'BASKETBALL' || combined.indexOf('basketball') !== -1 || combined.indexOf('bóng rổ') !== -1 || combined.indexOf('nba') !== -1) {
                return { name: 'Bóng rổ', icon: '🏀', badgeClass: 'sport-basketball', cleanLeague: evt.leagueName || 'Bóng rổ' };
            }
            if (rawLeague === 'BILLIARDS' || combined.indexOf('billiards') !== -1 || combined.indexOf('bi-a') !== -1 || combined.indexOf('snooker') !== -1) {
                return { name: 'Bi-a', icon: '🎱', badgeClass: 'sport-billiards', cleanLeague: evt.leagueName || 'Billiards' };
            }
            if (combined.indexOf('ufc') !== -1 || combined.indexOf('boxing') !== -1 || combined.indexOf('mma') !== -1 || combined.indexOf('võ thuật') !== -1) {
                return { name: 'Võ thuật', icon: '🥊', badgeClass: 'sport-martial', cleanLeague: evt.leagueName || 'Võ thuật' };
            }
            return {
                name: 'Bóng đá',
                icon: '⚽',
                badgeClass: 'sport-football',
                cleanLeague: evt.leagueName || 'Bóng đá'
            };
        }

        function getTeamLogo(teamName, evt, side) {
            if (!teamName) return '';
            var norm = normalizeName(teamName);
            var cNorm = normalizeName(side === 'home' ? evt.c_home : evt.c_away);

            if (side === 'home' && evt.homeLogo) return evt.homeLogo;
            if (side === 'away' && evt.awayLogo) return evt.awayLogo;

            if (countryFlagsMap[norm]) return countryFlagsMap[norm];
            if (cNorm && countryFlagsMap[cNorm]) return countryFlagsMap[cNorm];

            if (premappedClubs[norm]) return premappedClubs[norm];
            if (cNorm && premappedClubs[cNorm]) return premappedClubs[cNorm];

            if (side === 'home' && evt.sources && evt.sources.length > 0) {
                var srcLogo = evt.sources[0].logo;
                if (srcLogo && (srcLogo.indexOf('/team') !== -1 || srcLogo.indexOf('/badge') !== -1)) return srcLogo;
            }
            return '';
        }

        function formatStreamButtonLabel(rawName, index) {
            if (!rawName) return 'Luồng ' + (index + 1);
            var m = rawName.match(/\(([^)]+)\)/g);
            if (m && m.length > 0) {
                var clean = m[m.length - 1].replace(/[()]/g, '').trim();
                return 'Luồng ' + (index + 1) + ': ' + clean;
            }
            return 'Luồng ' + (index + 1) + ' (HD)';
        }

        function loadSports() {
            var container = document.getElementById('sportsContainer');
            container.innerHTML = '<div style="color:var(--label-2); padding:20px; text-align:center;">Đang nạp dữ liệu thể thao...</div>';

            fetch('/data/thethao-data.json?t=' + Date.now())
            .then(function(res) {
                if (!res.ok) throw new Error('Data status ' + res.status);
                return res.json();
            })
            .then(function(data) {
                var events = data.live_events || [];
                allLiveEvents = events;
                updateSportsBadge(events.length);
                buildSportsFilters(events);
                renderSports(events);
            })
            .catch(function(e) {
                // Fallback to /api/sports
                fetch('/api/sports?t=' + Date.now())
                .then(function(r) { return r.json(); })
                .then(function(d) {
                    var ev = d.live_events || [];
                    allLiveEvents = ev;
                    updateSportsBadge(ev.length);
                    buildSportsFilters(ev);
                    renderSports(ev);
                })
                .catch(function(err) {
                    container.innerHTML = '<div style="color:var(--red); padding:24px; text-align:center;">Không thể tải dữ liệu thể thao từ Hub Armbian</div>';
                });
            });
        }

        function updateSportsBadge(count) {
            var badge = document.getElementById('sportsCountBadge');
            if (badge) badge.textContent = count + ' trận';
        }

        function buildSportsFilters(events) {
            var filterBox = document.getElementById('sportsFilters');
            if (!filterBox) return;

            var leaguesMap = {};
            var liveCount = 0;
            events.forEach(function(e) {
                if (e.isOngoing) liveCount++;
                var l = (e.leagueName || 'Bóng đá').trim();
                if (l) leaguesMap[l] = (leaguesMap[l] || 0) + 1;
            });

            var html = '<button class="sports-filter-pill ' + (activeSportsFilter === 'all' ? 'active' : '') + '" onclick="filterSports(\'all\')">Tất cả (' + events.length + ')</button>';
            if (liveCount > 0) {
                html += '<button class="sports-filter-pill ' + (activeSportsFilter === 'live' ? 'active' : '') + '" onclick="filterSports(\'live\')">🔴 Đang trực tiếp (' + liveCount + ')</button>';
            }

            for (var lName in leaguesMap) {
                var isAct = (activeSportsFilter === lName);
                html += '<button class="sports-filter-pill ' + (isAct ? 'active' : '') + '" onclick="filterSports(\'' + lName.replace(/'/g, "\\'") + '\')">' + lName + ' (' + leaguesMap[lName] + ')</button>';
            }
            filterBox.innerHTML = html;
        }

        function filterSports(filterKey) {
            activeSportsFilter = filterKey;
            buildSportsFilters(allLiveEvents);

            if (filterKey === 'all') {
                renderSports(allLiveEvents);
                return;
            }
            if (filterKey === 'live') {
                var liveList = allLiveEvents.filter(function(e) { return e.isOngoing; });
                renderSports(liveList);
                return;
            }
            var filtered = allLiveEvents.filter(function(e) { return (e.leagueName || '').trim() === filterKey; });
            renderSports(filtered);
        }

        function toggleMatchSources(idx) {
            if (expandedEventIdx === idx) {
                expandedEventIdx = -1;
            } else {
                expandedEventIdx = idx;
            }
            var card = document.getElementById('match-card-' + idx);
            var box = document.getElementById('match-sources-' + idx);
            if (card && box) {
                var isExp = (expandedEventIdx === idx);
                card.classList.toggle('expanded', isExp);
                box.classList.toggle('show', isExp);
                var labelEl = card.querySelector('.match-toggle-right span');
                if (labelEl) labelEl.textContent = isExp ? 'Thu gọn' : 'Xem luồng';
            }
        }

        function renderSports(events) {
            var container = document.getElementById('sportsContainer');
            if (!container) return;

            if (!events || events.length === 0) {
                container.innerHTML = '<div style="color:var(--label-3); padding:30px; text-align:center; background:rgba(255,255,255,0.03); border-radius:18px; border:1px dashed rgba(255,255,255,0.1);">Không có trận đấu nào trong mục này</div>';
                return;
            }

            var html = '';
            events.forEach(function(evt, idx) {
                var isLive = evt.isOngoing !== false;
                var sport = getSportInfo(evt);
                var league = sport.cleanLeague;
                var home = toEnglishTeam(evt.homeTeam) || 'Home Team';
                var away = toEnglishTeam(evt.awayTeam) || 'Away Team';

                var time = evt.time || '';
                var date = evt.date || '';
                if (!time && evt.displayTitle) {
                    var m = evt.displayTitle.match(/\[(\d{1,2}:\d{2})(?:\s+([^\]]+))?\]/);
                    if (m) {
                        time = m[1];
                        if (!date && m[2]) date = m[2];
                    }
                }
                if (time === 'Trực tiếp') time = '';

                var homeInitial = home.substring(0, 3).toUpperCase();
                var awayInitial = away.substring(0, 3).toUpperCase();
                var homeLogo = getTeamLogo(home, evt, 'home');
                var awayLogo = getTeamLogo(away, evt, 'away');

                var sources = evt.sources || [];
                var isExpanded = (expandedEventIdx === idx);

                html += '<div id="match-card-' + idx + '" class="match-card ' + (isExpanded ? 'expanded' : '') + '" onclick="toggleMatchSources(' + idx + ')">';

                // Top Row: Sport Badge + Clean League + Kick-off Time & Live Tag
                html += '<div class="match-top-row">' +
                    '<div style="display:flex; align-items:center; gap:6px; min-width:0; flex:1;">' +
                        '<span class="sport-badge ' + sport.badgeClass + '">' +
                            '<span>' + sport.icon + '</span>' +
                            '<span>' + sport.name + '</span>' +
                        '</span>' +
                        '<span class="match-league-name">' + league + '</span>' +
                    '</div>' +
                    '<div style="display:flex; align-items:center; gap:6px; flex-shrink:0;">' +
                        (time ? '<span class="time-pill">⏰ ' + time + (date ? ' (' + date + ')' : '') + '</span>' : '') +
                        '<span class="' + (isLive ? 'live-tag' : 'time-pill') + '">' +
                            (isLive ? '<span class="status-dot"></span> LIVE' : 'Sắp đấu') +
                        '</span>' +
                    '</div>' +
                '</div>';

                // Teams Row: Home Team VS Away Team (matching ndkhoams.github.io)
                html += '<div class="match-teams-row">' +
                    // Home
                    '<div class="team-box">' +
                        '<div class="team-logo-box">' +
                            (homeLogo ? '<img src="' + homeLogo + '" alt="' + home + '" class="team-logo-img" loading="lazy" onerror="this.style.display=\'none\'; this.nextElementSibling.style.display=\'flex\';">' : '') +
                            '<span class="team-fallback" style="' + (homeLogo ? 'display:none;' : '') + '">' + homeInitial + '</span>' +
                        '</div>' +
                        '<span class="team-name">' + home + '</span>' +
                    '</div>' +
                    // Center VS
                    '<div class="match-vs-pillar">' +
                        '<span class="vs-badge">VS</span>' +
                        (time ? '<span class="vs-time">' + time + '</span>' : '') +
                    '</div>' +
                    // Away
                    '<div class="team-box away">' +
                        '<div class="team-logo-box">' +
                            (awayLogo ? '<img src="' + awayLogo + '" alt="' + away + '" class="team-logo-img" loading="lazy" onerror="this.style.display=\'none\'; this.nextElementSibling.style.display=\'flex\';">' : '') +
                            '<span class="team-fallback" style="' + (awayLogo ? 'display:none;' : '') + '">' + awayInitial + '</span>' +
                        '</div>' +
                        '<span class="team-name" style="text-align:right;">' + away + '</span>' +
                    '</div>' +
                '</div>';

                // Toggle Row
                html += '<div class="match-toggle-row">' +
                    '<div class="match-toggle-left">' +
                        '<span class="stream-dot"></span>' +
                        '<span style="font-weight:700; color:#fff;">' + sources.length + ' luồng phát</span>' +
                        '<span style="color:var(--label-3);">• Nhấn để chọn kênh phát</span>' +
                    '</div>' +
                    '<div class="match-toggle-right">' +
                        '<span>' + (isExpanded ? 'Thu gọn' : 'Xem luồng') + '</span>' +
                        '<svg class="chevron-icon" viewBox="0 0 24 24" fill="none" stroke-width="2.5"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7"/></svg>' +
                    '</div>' +
                '</div>';

                // Expanded Streams List
                html += '<div id="match-sources-' + idx + '" class="sources-box ' + (isExpanded ? 'show' : '') + '" onclick="event.stopPropagation()">' +
                    '<div class="sources-title-row">' +
                        '<span style="font-weight:700; color:#fff;">Danh sách luồng phát trực tiếp:</span>' +
                        '<span style="color:var(--orange); font-family:monospace;">Chạm luồng để phát lên TV</span>' +
                    '</div>' +
                    '<div class="streams-flex-grid">';

                if (!sources.length) {
                    html += '<div style="font-size:12px; color:var(--label-3); padding:8px 0;">Chưa cập nhật luồng phát trực tiếp</div>';
                } else {
                    sources.forEach(function(s, sIdx) {
                        var isAct = (activeStreamEventIdx === idx && activeStreamSourceIdx === sIdx);
                        var btnLabel = formatStreamButtonLabel(s.name, sIdx);
                        html += '<button type="button" class="stream-pill-btn ' + (isAct ? 'active' : '') + '" onclick="onSportsStreamClick(' + idx + ', ' + sIdx + ')">' +
                            '<span class="stream-dot" style="' + (isAct ? 'background:#fff;' : '') + '"></span>' +
                            '<span>' + btnLabel + '</span>' +
                            '<svg style="width:11px; height:11px; fill:currentColor; opacity:0.7;" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>' +
                        '</button>';
                    });
                }

                html += '</div></div>';
                html += '</div>';
            });

            container.innerHTML = html;
        }

        function onSportsStreamClick(eventIdx, sourceIdx) {
            var evt = allLiveEvents[eventIdx];
            if (!evt) return;
            var src = (evt.sources || [])[sourceIdx];
            if (!src) return;

            activeStreamEventIdx = eventIdx;
            activeStreamSourceIdx = sourceIdx;

            var title = (evt.displayTitle || (toEnglishTeam(evt.homeTeam) + ' vs ' + toEnglishTeam(evt.awayTeam)));
            var streamName = formatStreamButtonLabel(src.name, sourceIdx);

            showToast('▶ Đang phát lên TV: ' + title + ' (' + streamName + ')');

            // Send to TV
            playSportsSource(title + ' - ' + streamName, {
                streamUrl: src.url || src.streamUrl,
                name: title + ' (' + streamName + ')'
            });

            // Re-render streams to highlight active
            renderSports(allLiveEvents);
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
                    text.textContent = 'TV: ' + (data.clientType || 'Đã kết nối');
                    if (infoType) infoType.textContent = data.clientType || 'Samsung Tizen';
                } else {
                    badge.className = 'tv-status-badge status-disconnected';
                    text.textContent = 'Chờ TV kết nối...';
                    if (infoType) infoType.textContent = 'Chưa kết nối';
                }

                if (data.nowPlaying) {
                    banner.textContent = data.nowPlaying;
                    if (infoCh) infoCh.textContent = data.nowPlaying;
                }
                if (data.currentMode) {
                    if (infoMode) infoMode.textContent = data.currentMode.toUpperCase();
                }
            }).catch(function(e) {});
        }

        /* TIZEN DEPLOYMENT & WGT MANAGEMENT */
        var tizenPollTimer = null;
        var availableWgts = [];
        var selectedUploadFile = null;

        function getTizenIp() {
            var el = document.getElementById('tizenTvIp');
            var ip = (el ? el.value : '10.0.0.9').trim() || '10.0.0.9';
            try { localStorage.setItem('tvplay_tizen_ip', ip); } catch(e) {}
            return ip;
        }

        function loadTizenWgts() {
            fetch('/api/tizen/wgts?t=' + Date.now())
            .then(function(res) { return res.json(); })
            .then(function(d) {
                if (d && d.wgts) {
                    availableWgts = d.wgts;
                    renderTizenWgtSelect();
                }
            }).catch(function(e) {});
        }

        function renderTizenWgtSelect() {
            var sel = document.getElementById('tizenWgtSelect');
            if (!sel) return;
            if (!availableWgts.length) {
                sel.innerHTML = '<option value="/opt/samsung/TVplay.wgt">TVplay.wgt (Bản chuẩn máy chủ - 375 KB)</option>';
                return;
            }
            var html = '';
            availableWgts.forEach(function(w) {
                var label = w.name + ' (' + (w.tag || '') + ' - ' + w.sizeStr + ')';
                html += '<option value="' + w.path + '">' + label + '</option>';
            });
            sel.innerHTML = html;
        }

        function loadTizenStatus() {
            var ipInput = document.getElementById('tizenTvIp');
            var savedIp = null;
            try { savedIp = localStorage.getItem('tvplay_tizen_ip'); } catch(e) {}
            if (savedIp && ipInput && (!ipInput.value || ipInput.value === '10.0.0.9')) ipInput.value = savedIp;

            fetch('/api/tizen/status?t=' + Date.now())
            .then(function(res) { return res.json(); })
            .then(function(data) {
                var badge = document.getElementById('tizenStatusBadge');
                var badgeText = document.getElementById('tizenBadgeText');
                var logWrap = document.getElementById('tizenLogWrap');
                var logBox = document.getElementById('tizenLogBox');

                if (badge && badgeText) {
                    if (data.status === 'running') {
                        badge.className = 'tv-status-badge status-connected';
                        badgeText.textContent = 'Đang xử lý...';
                    } else if (data.status === 'success') {
                        badge.className = 'tv-status-badge status-connected';
                        badgeText.textContent = 'Hoàn tất';
                    } else if (data.status === 'error') {
                        badge.className = 'tv-status-badge status-disconnected';
                        badgeText.textContent = 'Lỗi';
                    } else {
                        badge.className = 'tv-status-badge status-disconnected';
                        badgeText.textContent = 'Sẵn sàng';
                    }
                }

                if (logWrap && logBox) {
                    if (data.log) {
                        logWrap.style.display = 'block';
                        logBox.textContent = data.log;
                        logBox.scrollTop = logBox.scrollHeight;
                    }
                }

                if (data.wgts) {
                    availableWgts = data.wgts;
                    renderTizenWgtSelect();
                }

                if (data.status === 'success' || data.status === 'error') {
                    if (tizenPollTimer) {
                        clearInterval(tizenPollTimer);
                        tizenPollTimer = null;
                    }
                }
            }).catch(function(e) {});
        }

        function checkTizenTv() {
            var ip = getTizenIp();
            showToast('Đang kiểm tra TV Samsung (' + ip + ')...');
            var logWrap = document.getElementById('tizenLogWrap');
            var logBox = document.getElementById('tizenLogBox');
            if (logWrap) logWrap.style.display = 'block';
            if (logBox) logBox.textContent = '[*] Đang gửi yêu cầu kiểm tra Samsung TV ' + ip + '...\n';

            fetch('/api/tizen/check', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ tvIp: ip })
            }).then(function(res) { return res.json(); })
            .then(function(d) {
                showToast('Đang kiểm tra kết nối TV...');
                if (!tizenPollTimer) tizenPollTimer = setInterval(loadTizenStatus, 1000);
            }).catch(function(e) {
                showToast('Lỗi gửi lệnh kiểm tra!');
            });
        }

        function installSelectedTizenWgt() {
            var ip = getTizenIp();
            var sel = document.getElementById('tizenWgtSelect');
            var wgtPath = sel ? sel.value : '/opt/samsung/TVplay.wgt';
            var wgtName = wgtPath.split('/').pop();

            if (!confirm('Cài đặt gói ' + wgtName + ' lên Samsung TV (' + ip + ')?\n\nQuá trình chạy ngầm hoàn toàn và không ảnh hưởng tới người đang xem YouTube trên TV.')) return;

            showToast('Bắt đầu cài đặt ' + wgtName + ' lên TV ' + ip + '...');
            var logWrap = document.getElementById('tizenLogWrap');
            var logBox = document.getElementById('tizenLogBox');
            if (logWrap) logWrap.style.display = 'block';
            if (logBox) logBox.textContent = '[*] Đang gửi lệnh cài đặt ' + wgtPath + ' lên TV ' + ip + '...\n';

            fetch('/api/tizen/install', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ tvIp: ip, wgtPath: wgtPath })
            }).then(function(res) { return res.json(); })
            .then(function(d) {
                showToast('Đang cài đặt ngầm lên TV...');
                if (!tizenPollTimer) tizenPollTimer = setInterval(loadTizenStatus, 1500);
            }).catch(function(e) {
                showToast('Lỗi gửi lệnh cài đặt!');
            });
        }

        function onTizenFileSelected(e) {
            var input = e.target;
            if (!input.files || input.files.length === 0) return;
            selectedUploadFile = input.files[0];
            var box = document.getElementById('tizenFileSelectedBox');
            var nameEl = document.getElementById('tizenFileNamePreview');
            var sizeEl = document.getElementById('tizenFileSizePreview');
            if (box && nameEl && sizeEl) {
                nameEl.textContent = 'Đã chọn: ' + selectedUploadFile.name;
                var sz = selectedUploadFile.size;
                var szStr = (sz < 1024*1024) ? Math.round(sz/1024) + ' KB' : (sz/(1024*1024)).toFixed(1) + ' MB';
                sizeEl.textContent = 'Dung lượng: ' + szStr;
                box.style.display = 'block';
            }
        }

        function uploadAndInstallTizenWgt(installImmediately) {
            if (!selectedUploadFile) {
                showToast('Vui lòng chọn file .wgt!');
                return;
            }
            var file = selectedUploadFile;
            var ip = getTizenIp();
            showToast('Đang tải file ' + file.name + ' lên máy chủ...');
            var reader = new FileReader();
            reader.onload = function(e) {
                var base64Data = e.target.result;
                fetch('/api/tizen/upload', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({
                        content: base64Data,
                        filename: file.name,
                        tvIp: ip,
                        installImmediately: installImmediately
                    })
                }).then(function(res) { return res.json(); })
                .then(function(d) {
                    if (d.success) {
                        if (installImmediately) {
                            showToast('Đã tải lên ' + file.name + '! Đang tiến hành cài đặt lên TV...');
                            if (!tizenPollTimer) tizenPollTimer = setInterval(loadTizenStatus, 1500);
                        } else {
                            showToast('Đã lưu file ' + file.name + ' lên máy chủ!');
                        }
                        loadTizenWgts();
                        var input = document.getElementById('tizenWgtUploadFile');
                        if (input) input.value = '';
                        var box = document.getElementById('tizenFileSelectedBox');
                        if (box) box.style.display = 'none';
                        selectedUploadFile = null;
                    } else {
                        showToast('Lỗi: ' + (d.error || 'Tải file thất bại'));
                    }
                }).catch(function(e) {
                    showToast('Lỗi kết nối khi tải file .wgt!');
                });
            };
            reader.readAsDataURL(file);
        }

        function launchTizenApp() {
            var ip = getTizenIp();
            showToast('Đang mở TV Play trên TV ' + ip + '...');
            fetch('/api/tizen/launch', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ tvIp: ip })
            }).then(function(res) { return res.json(); })
            .then(function(d) {
                showToast('Đã gửi lệnh mở app TV Play!');
                if (!tizenPollTimer) tizenPollTimer = setInterval(loadTizenStatus, 1000);
            }).catch(function(e) {
                showToast('Lỗi gửi lệnh mở app!');
            });
        }

        function toggleTizenLog() {
            var box = document.getElementById('tizenLogBox');
            if (box) {
                box.style.display = (box.style.display === 'none') ? 'block' : 'none';
            }
        }

        function initSSE() {
            if (!window.EventSource) return;
            try {
                var es = new EventSource('/events');
                es.addEventListener('tv_state_update', function(e) {
                    try {
                        var data = JSON.parse(e.data);
                        var badge = document.getElementById('tvStatusBadge');
                        var text = document.getElementById('tvStatusText');
                        var banner = document.getElementById('bannerChName');
                        var infoType = document.getElementById('infoTvType');
                        var infoCh = document.getElementById('infoTvChannel');
                        var infoMode = document.getElementById('infoTvMode');

                        if (badge && text) {
                            badge.className = 'tv-status-badge status-connected';
                            text.textContent = 'TV: ' + (data.clientType || 'Đã kết nối');
                        }
                        if (infoType) infoType.textContent = data.clientType || 'Samsung Tizen';

                        if (data.nowPlaying) {
                            if (banner) banner.textContent = data.nowPlaying;
                            if (infoCh) infoCh.textContent = data.nowPlaying;
                        }
                        if (data.currentMode && infoMode) {
                            infoMode.textContent = data.currentMode.toUpperCase();
                        }
                    } catch(err) {}
                });
                es.onerror = function() {
                    // Reconnects automatically by browser
                };
            } catch(e) {}
        }

                initSSE();
        checkTvStatus();
        loadTizenWgts();
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

        if path == "/api/tizen/status":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps(get_tizen_status(), ensure_ascii=False).encode("utf-8"))
            return

        if path == "/api/tizen/wgts":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "wgts": get_available_wgts()}, ensure_ascii=False).encode("utf-8"))
            return

        if path in ["/api/sports", "/api/sports/raw"]:
            content = get_local_or_remote_data("thethao-data.json", "https://raw.githubusercontent.com/ndkhoams/iptv/main/thethao-data.json", max_age_seconds=60)
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(content.encode("utf-8") if isinstance(content, str) else content)
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
                    get_local_or_remote_data("ktv.m3u", "https://raw.githubusercontent.com/ndkhoams/iptv/refs/heads/main/ktv.m3u", max_age_seconds=600)
                elif filename == "thethao-data.json":
                    get_local_or_remote_data("thethao-data.json", "https://raw.githubusercontent.com/ndkhoams/iptv/main/thethao-data.json", max_age_seconds=300)

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

        if path == "/api/tizen/check":
            tv_ip = (post_data.get("tvIp") or DEFAULT_TIZEN_IP).strip()
            execute_tizen_task("check", tv_ip)
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "message": "Đang kiểm tra TV"}).encode("utf-8"))
            return

        if path == "/api/tizen/install":
            tv_ip = (post_data.get("tvIp") or DEFAULT_TIZEN_IP).strip()
            wgt_path = post_data.get("wgtPath")
            wgt_file = post_data.get("wgtFile")
            if not wgt_path and wgt_file:
                candidates = [
                    os.path.join(TIZEN_UPLOADS_DIR, wgt_file),
                    os.path.join(os.path.dirname(TIZEN_WGT_PATH), wgt_file)
                ]
                for c in candidates:
                    if os.path.exists(c):
                        wgt_path = c
                        break
            execute_tizen_task("install", tv_ip, wgt_path)
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "message": "Đang cài đặt lên TV"}).encode("utf-8"))
            return

        if path == "/api/tizen/launch":
            tv_ip = (post_data.get("tvIp") or DEFAULT_TIZEN_IP).strip()
            execute_tizen_task("launch", tv_ip)
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "message": "Đang mở ứng dụng trên TV"}).encode("utf-8"))
            return

        if path == "/api/tizen/upload":
            content_b64 = post_data.get("content", "")
            filename = (post_data.get("filename") or "TVplay.wgt").strip()
            filename = os.path.basename(filename)
            if not filename.lower().endswith(".wgt"):
                filename += ".wgt"
            tv_ip = (post_data.get("tvIp") or DEFAULT_TIZEN_IP).strip()
            install_immediately = post_data.get("installImmediately", True)
            if content_b64:
                try:
                    if "," in content_b64:
                        content_b64 = content_b64.split(",", 1)[1]
                    file_bytes = base64.b64decode(content_b64)
                    
                    os.makedirs(TIZEN_UPLOADS_DIR, exist_ok=True)
                    target_saved_path = os.path.join(TIZEN_UPLOADS_DIR, filename)
                    with open(target_saved_path, "wb") as f_up:
                        f_up.write(file_bytes)
                    
                    # If file is TVplay.wgt, also overwrite system default and OTA
                    if filename.lower() == "tvplay.wgt":
                        target_saved_path = TIZEN_WGT_PATH
                        with open(TIZEN_WGT_PATH, "wb") as f_wgt:
                            f_wgt.write(file_bytes)
                        ota_wgt_path = os.path.join(DATA_DIR, "TVplay.wgt")
                        with open(ota_wgt_path, "wb") as f_ota:
                            f_ota.write(file_bytes)
                    
                    if install_immediately:
                        execute_tizen_task("install", tv_ip, target_saved_path)
                    
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_cors_headers()
                    self.end_headers()
                    self.wfile.write(json.dumps({
                        "success": True,
                        "filename": filename,
                        "path": target_saved_path,
                        "size": len(file_bytes),
                        "installed": install_immediately
                    }).encode("utf-8"))
                    return
                except Exception as ex:
                    self.send_response(400)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_cors_headers()
                    self.end_headers()
                    self.wfile.write(json.dumps({"success": False, "error": str(ex)}).encode("utf-8"))
                    return

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
