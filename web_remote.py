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
import gzip
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from threading import Lock, RLock

PORT = int(os.environ.get("PORT", "8080"))
DATA_DIR = os.environ.get("IPTV_DATA_DIR", "/opt/iptv-sports/data")
os.makedirs(DATA_DIR, exist_ok=True)

# ==============================================================================
# SAMSUNG SMART TV WEBSOCKET (samsungtvws, PORT 8002) DIRECT CONTROLLER
# ==============================================================================
try:
    from samsungtvws import SamsungTVWS
    HAS_SAMSUNGTVWS = True
except ImportError:
    HAS_SAMSUNGTVWS = False

SAMSUNG_DEFAULT_IP = "10.0.0.9"
SAMSUNG_TOKEN_FILE = os.path.join(DATA_DIR, "samsung_tv_token.txt")
SAMSUNG_SETTINGS_FILE = os.path.join(DATA_DIR, "samsung_tv_settings.json")
SAMSUNG_TVS_FILE = os.path.join(DATA_DIR, "samsung_tvs.json")

class SamsungTvManager:
    def __init__(self):
        self.lock = RLock()
        self.clients = {}         # tv_id -> SamsungTVWS
        self.status_cache = {}    # tv_id -> dict
        self.tvs = self.load_tvs()

    def load_tvs(self):
        """Tải danh sách TV từ samsung_tvs.json hoặc tự động chuyển đổi từ dữ liệu cũ."""
        if os.path.exists(SAMSUNG_TVS_FILE):
            try:
                with open(SAMSUNG_TVS_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, list) and len(data) > 0:
                        return data
                    elif isinstance(data, dict) and "tvs" in data and len(data["tvs"]) > 0:
                        return data["tvs"]
            except Exception:
                pass

        # Di chuyển từ samsung_tv_settings.json cũ nếu có
        saved_ip = SAMSUNG_DEFAULT_IP
        if os.path.exists(SAMSUNG_SETTINGS_FILE):
            try:
                with open(SAMSUNG_SETTINGS_FILE, "r", encoding="utf-8") as f:
                    d = json.load(f)
                    saved_ip = d.get("tvIp", SAMSUNG_DEFAULT_IP).strip()
            except Exception:
                pass

        initial_tvs = [
            {
                "id": "tv_phong_khach",
                "name": "Samsung TV (Phòng khách)",
                "ip": saved_ip
            }
        ]
        if saved_ip != SAMSUNG_DEFAULT_IP:
            initial_tvs.append({
                "id": "tv_samsung_10_0_0_9",
                "name": "Samsung TV (10.0.0.9)",
                "ip": SAMSUNG_DEFAULT_IP
            })

        self.save_tvs(initial_tvs)
        return initial_tvs

    def save_tvs(self, tvs=None):
        if tvs is not None:
            self.tvs = tvs
        try:
            with open(SAMSUNG_TVS_FILE, "w", encoding="utf-8") as f:
                json.dump({"tvs": self.tvs}, f, indent=2, ensure_ascii=False)
        except Exception:
            pass

    def get_token_file(self, tv_id):
        safe_id = "".join(c if c.isalnum() or c in "_-" else "_" for c in str(tv_id))
        return os.path.join(DATA_DIR, f"token_{safe_id}.txt")

    def get_tvs_list(self):
        with self.lock:
            res = []
            for tv in self.tvs:
                tid = tv["id"]
                cache = self.status_cache.get(tid, {})
                has_token = bool(self.get_token(tid))
                res.append({
                    "id": tid,
                    "name": tv.get("name") or f"Samsung TV ({tv.get('ip')})",
                    "ip": tv.get("ip", ""),
                    "online": cache.get("online", False),
                    "model": cache.get("model", "Samsung Smart TV"),
                    "paired": has_token,
                    "pairingRequested": cache.get("pairingRequested", False)
                })
            return res

    def get_tv(self, tv_id):
        with self.lock:
            for tv in self.tvs:
                if tv["id"] == tv_id:
                    return tv
            if self.tvs:
                return self.tvs[0]
            return {"id": "tv_default", "name": "Samsung TV", "ip": SAMSUNG_DEFAULT_IP}

    def add_or_update_tv(self, tv_id, name, ip):
        ip = (ip or "").strip()
        if not ip:
            return tv_id or "tv_default"
        name = (name or "").strip() or f"Samsung TV ({ip})"
        with self.lock:
            found = False
            for tv in self.tvs:
                if tv["id"] == tv_id:
                    if tv.get("ip") != ip:
                        self.clear_tv_token(tv_id)
                    tv["name"] = name
                    tv["ip"] = ip
                    found = True
                    break
            if not found:
                tv_id = tv_id or f"tv_{int(time.time()*1000)}"
                self.tvs.append({
                    "id": tv_id,
                    "name": name,
                    "ip": ip
                })
            self.save_tvs()
            self.reset_client(tv_id)
        return tv_id

    def delete_tv(self, tv_id):
        with self.lock:
            self.tvs = [t for t in self.tvs if t["id"] != tv_id]
            self.save_tvs()
            self.clear_tv_token(tv_id)
            self.reset_client(tv_id)
            return True

    def clear_tv_token(self, tv_id):
        with self.lock:
            tf = self.get_token_file(tv_id)
            if os.path.exists(tf):
                try: os.remove(tf)
                except Exception: pass
            if os.path.exists(SAMSUNG_TOKEN_FILE):
                try: os.remove(SAMSUNG_TOKEN_FILE)
                except Exception: pass
            self.reset_client(tv_id)
            if tv_id in self.status_cache:
                self.status_cache[tv_id]["pairingRequested"] = False
                self.status_cache[tv_id]["online"] = False
                self.status_cache[tv_id]["last_check"] = 0

    def get_token(self, tv_id):
        tf = self.get_token_file(tv_id)
        if os.path.exists(tf):
            try:
                with open(tf, "r", encoding="utf-8") as f:
                    return f.read().strip()
            except Exception: pass
        if os.path.exists(SAMSUNG_TOKEN_FILE):
            try:
                with open(SAMSUNG_TOKEN_FILE, "r", encoding="utf-8") as f:
                    return f.read().strip()
            except Exception: pass
        return None

    def get_client(self, tv_id):
        if not HAS_SAMSUNGTVWS:
            return None
        tv = self.get_tv(tv_id)
        if not tv or not tv.get("ip"):
            return None
        with self.lock:
            real_id = tv["id"]
            if real_id not in self.clients or self.clients[real_id] is None:
                token_file = self.get_token_file(real_id)
                # Copy token cũ sang nếu có
                if not os.path.exists(token_file) and os.path.exists(SAMSUNG_TOKEN_FILE):
                    try:
                        with open(SAMSUNG_TOKEN_FILE, "r") as fr, open(token_file, "w") as fw:
                            fw.write(fr.read())
                    except Exception: pass
                try:
                    self.clients[real_id] = SamsungTVWS(
                        host=tv["ip"],
                        port=8002,
                        token_file=token_file,
                        timeout=3,
                        name="TV Play Web Remote"
                    )
                except Exception:
                    return None
            return self.clients[real_id]

    def reset_client(self, tv_id):
        with self.lock:
            if tv_id in self.clients and self.clients[tv_id]:
                try: self.clients[tv_id].close()
                except Exception: pass
                self.clients[tv_id] = None

    def trigger_pairing(self, tv_id):
        """Gửi yêu cầu ghép đôi tới TV Samsung trong luồng ngầm."""
        def _do_pair():
            try:
                self.reset_client(tv_id)
                client = self.get_client(tv_id)
                if client:
                    client.open()
                    if tv_id in self.status_cache:
                        self.status_cache[tv_id]["pairingRequested"] = False
            except Exception as ex:
                err_str = str(ex).lower()
                if tv_id not in self.status_cache:
                    self.status_cache[tv_id] = {}
                if "unauthorized" in err_str or "pair" in err_str or "token" in err_str or "permission" in err_str:
                    self.status_cache[tv_id]["pairingRequested"] = True
                self.reset_client(tv_id)

        t = threading.Thread(target=_do_pair, daemon=True)
        t.start()
        return True

    def check_status(self, tv_id=None):
        now = time.time()
        with self.lock:
            tvs_copy = list(self.tvs)

        for tv in tvs_copy:
            tid = tv["id"]
            cache = self.status_cache.get(tid, {})
            if now - cache.get("last_check", 0) > 4:
                client = self.get_client(tid)
                is_online = False
                model_name = tv.get("name") or "Samsung Smart TV"
                if client:
                    try:
                        info = client.rest_device_info()
                        if info and "device" in info:
                            is_online = True
                            model_name = info["device"].get("modelName", model_name)
                    except Exception:
                        is_online = False
                cache = {
                    "online": is_online,
                    "model": model_name,
                    "last_check": now,
                    "paired": bool(self.get_token(tid)),
                    "pairingRequested": cache.get("pairingRequested", False)
                }
                self.status_cache[tid] = cache

        all_list = self.get_tvs_list()
        if tv_id and tv_id != "all":
            for item in all_list:
                if item["id"] == tv_id:
                    return item
            if all_list:
                return all_list[0]
            return {"id": "tv_default", "online": False, "ip": SAMSUNG_DEFAULT_IP, "paired": False}

        return {
            "tvs": all_list,
            "count": len(all_list),
            "onlineCount": sum(1 for x in all_list if x.get("online"))
        }

    def send_key(self, tv_id, key_name):
        KEY_MAP = {
            "power": "KEY_POWER",
            "up": "KEY_UP",
            "down": "KEY_DOWN",
            "left": "KEY_LEFT",
            "right": "KEY_RIGHT",
            "center": "KEY_ENTER",
            "ok": "KEY_ENTER",
            "back": "KEY_RETURN",
            "return": "KEY_RETURN",
            "home": "KEY_HOME",
            "menu": "KEY_MENU",
            "mute": "KEY_MUTE",
            "vol_up": "KEY_VOLUP",
            "vol_down": "KEY_VOLDOWN",
            "ch_next": "KEY_CHUP",
            "ch_prev": "KEY_CHDOWN",
            "prev": "KEY_PREVIOUS",
            "next": "KEY_NEXT",
            "play_pause": "KEY_PLAY",
            "play": "KEY_PLAY",
            "pause": "KEY_PAUSE",
            "overlay": "KEY_CHLIST",
            "guide": "KEY_GUIDE",
            "exit": "KEY_RETURN",
            "0": "KEY_0", "1": "KEY_1", "2": "KEY_2", "3": "KEY_3", "4": "KEY_4",
            "5": "KEY_5", "6": "KEY_6", "7": "KEY_7", "8": "KEY_8", "9": "KEY_9"
        }
        tizen_key = KEY_MAP.get(key_name.lower())
        if not tizen_key:
            if key_name.upper().startswith("KEY_"):
                tizen_key = key_name.upper()
            else:
                tizen_key = f"KEY_{key_name.upper()}"

        target_ids = []
        if tv_id == "all":
            with self.lock:
                target_ids = [t["id"] for t in self.tvs]
        else:
            tv = self.get_tv(tv_id)
            target_ids = [tv["id"]] if tv else []

        pairing_needed = False
        success = False
        for tid in target_ids:
            client = self.get_client(tid)
            if not client:
                continue
            try:
                client.send_key(tizen_key)
                success = True
            except Exception as ex:
                err_str = str(ex).lower()
                if "unauthorized" in err_str or "pair" in err_str or "token" in err_str or "permission" in err_str:
                    pairing_needed = True
                    if tid in self.status_cache:
                        self.status_cache[tid]["pairingRequested"] = True
                self.reset_client(tid)

        return success, pairing_needed

    def send_text(self, tv_id, text_str):
        target_ids = []
        if tv_id == "all":
            with self.lock: target_ids = [t["id"] for t in self.tvs]
        else:
            tv = self.get_tv(tv_id)
            target_ids = [tv["id"]] if tv else []
        success = False
        for tid in target_ids:
            client = self.get_client(tid)
            if client:
                try:
                    client.send_text(text_str)
                    success = True
                except Exception:
                    self.reset_client(tid)
        return success, "OK" if success else "Lỗi"

    def search_youtube(self, tv_id, query):
        target_ids = []
        if tv_id == "all":
            with self.lock: target_ids = [t["id"] for t in self.tvs]
        else:
            tv = self.get_tv(tv_id)
            target_ids = [tv["id"]] if tv else []
        for tid in target_ids:
            tv = self.get_tv(tid)
            if not tv: continue
            try:
                dial_url = f"http://{tv['ip']}:8080/ws/apps/YouTube"
                data_bytes = f"data={urllib.parse.quote(query)}".encode("utf-8")
                req = urllib.request.Request(dial_url, data=data_bytes, headers={"Content-Type": "application/x-www-form-urlencoded"})
                with urllib.request.urlopen(req, timeout=3) as resp:
                    pass
            except Exception: pass
            client = self.get_client(tid)
            if client:
                try:
                    client.open_app("111299001912")
                    time.sleep(1.0)
                    client.send_text(query)
                except Exception: pass
        return True, f"Tìm YouTube: {query}"

samsung_tv_mgr = SamsungTvManager()

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

        #tab-remote {
            padding-top: max(calc(env(safe-area-inset-top, 0px) + 8px), 10px);
            padding-bottom: 10px;
            padding-left: 10px;
            padding-right: 10px;
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
        /* TAB 1: MODERN TV REMOTE CONTROLLER (MATCHING TV REMOTE APP)               */
        /* ========================================================================= */
        .remote-wrapper {
            max-width: 440px;
            width: 100%;
            margin: 0 auto;
            padding: 0 4px;
        }

        /* Remote Header: Settings (Left) - Status (Center) - Power (Right) */
        .remote-header-grid {
            display: grid;
            grid-template-columns: repeat(3, 1fr);
            align-items: center;
            height: 52px;
            padding: 0 24px;
            margin-bottom: 8px;
        }

        .remote-header-col {
            display: flex;
            align-items: center;
            justify-content: center;
        }

        .remote-header-btn {
            width: 48px;
            height: 48px;
            border-radius: 50%;
            background: radial-gradient(circle at 35% 30%, #323236 0%, #242427 100%);
            border: 1px solid rgba(255, 255, 255, 0.12);
            border-top: 1px solid rgba(255, 255, 255, 0.25);
            color: #8E8E93;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            user-select: none;
            -webkit-user-select: none;
            -webkit-touch-callout: none;
            touch-action: manipulation;
            box-shadow: 
                0 4px 0 #121214,
                0 6px 14px rgba(0, 0, 0, 0.45);
            transition: transform 0.06s ease, box-shadow 0.06s ease, background 0.06s ease;
        }

        .remote-header-btn:active, .remote-header-btn.pressed {
            transform: translateY(3px);
            box-shadow: 
                0 1px 0 #121214,
                inset 0 2px 5px rgba(0, 0, 0, 0.6);
            background: #18181a;
            color: #FFFFFF;
        }

        .remote-power-btn {
            width: 48px;
            height: 48px;
            border-radius: 50%;
            background: radial-gradient(circle at 35% 30%, #ff5247 0%, #FF3B30 60%, #b81b12 100%);
            border: 1px solid rgba(255, 255, 255, 0.25);
            border-top: 1px solid rgba(255, 255, 255, 0.4);
            color: #FFFFFF;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            user-select: none;
            -webkit-user-select: none;
            -webkit-touch-callout: none;
            touch-action: manipulation;
            box-shadow: 
                0 4px 0 #850f08,
                0 8px 18px rgba(255, 59, 48, 0.45),
                inset 0 1px 1px rgba(255, 255, 255, 0.35);
            transition: transform 0.06s ease, box-shadow 0.06s ease;
        }

        .remote-power-btn:active, .remote-power-btn.pressed {
            transform: translateY(3px);
            background: radial-gradient(circle at 50% 50%, #b81b12 0%, #850f08 100%);
            box-shadow: 
                0 1px 0 #850f08,
                0 2px 6px rgba(0, 0, 0, 0.4),
                inset 0 3px 6px rgba(0, 0, 0, 0.7);
        }

        .remote-status-box {
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: 13px;
            font-weight: 700;
            color: #8E8E93;
        }

        .remote-status-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: #636366;
            transition: all 0.3s;
        }

        .remote-status-dot.online {
            background: #30D158;
            box-shadow: 0 0 8px #30D158;
        }

        .remote-status-dot.pairing {
            background: #FF9F0A;
            box-shadow: 0 0 8px #FF9F0A;
            animation: pulse-pairing 1.5s infinite;
        }

        .remote-status-dot.offline {
            background: #636366;
            box-shadow: none;
        }

        @keyframes pulse-pairing {
            0% { transform: scale(1); opacity: 1; }
            50% { transform: scale(1.3); opacity: 0.6; }
            100% { transform: scale(1); opacity: 1; }
        }

        /* Remote Body Box (Bo góc 36px, padding rộng rãi, gap 16px để điều khiển tràn đẹp) */
        .remote-body-box {
            background: #1c1c1e;
            border: 1px solid rgba(255, 255, 255, 0.09);
            border-radius: 36px;
            padding: 22px 24px 18px;
            box-shadow: 0 16px 44px -4px rgba(0, 0, 0, 0.85);
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 16px;
        }

        /* 3-Column Grid Rows (Thẳng hàng tuyệt đối 100%) */
        .remote-grid-row {
            width: 100%;
            display: grid;
            grid-template-columns: repeat(3, 1fr);
            align-items: center;
        }

        .remote-grid-col {
            display: flex;
            align-items: center;
            justify-content: center;
        }

        /* Nút tròn chung với hiệu ứng 3D lún vật lý */
        .btn-circle {
            border-radius: 50%;
            background: radial-gradient(circle at 35% 30%, #3a3a3e 0%, #262629 60%, #1e1e21 100%);
            border: 1px solid rgba(255, 255, 255, 0.12);
            border-top: 1px solid rgba(255, 255, 255, 0.28);
            color: #FFFFFF;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            user-select: none;
            -webkit-user-select: none;
            -webkit-touch-callout: none;
            touch-action: manipulation;
            box-shadow: 
                0 5px 0 #121214,
                0 8px 18px rgba(0, 0, 0, 0.6),
                inset 0 1px 1px rgba(255, 255, 255, 0.25);
            transition: transform 0.06s ease, box-shadow 0.06s ease, background 0.06s ease;
        }

        .btn-circle:active, .btn-circle.pressed {
            transform: translateY(4px);
            background: radial-gradient(circle at 50% 50%, #1c1c1f 0%, #161618 100%);
            box-shadow: 
                0 1px 0 #121214,
                0 2px 6px rgba(0, 0, 0, 0.4),
                inset 0 3px 6px rgba(0, 0, 0, 0.7);
            border-color: rgba(255, 159, 10, 0.5);
            color: #FF9F0A;
        }

        /* Hàng Media (54px x 54px - To hơn) */
        .btn-media {
            width: 54px;
            height: 54px;
            font-size: 18px;
        }

        /* Hàng Chức năng 1 & 2 (58px x 58px - To hơn) */
        .btn-func {
            width: 58px;
            height: 58px;
            font-size: 18px;
        }

        /* Nút Mic đặc biệt (màu xanh dương có hiệu ứng lún 3D) */
        .btn-mic-special {
            background: radial-gradient(circle at 35% 30%, #1a8cff 0%, #007AFF 60%, #0056cc 100%);
            border-color: rgba(255, 255, 255, 0.35);
            box-shadow: 
                0 5px 0 #003d8f,
                0 8px 20px rgba(0, 122, 255, 0.5),
                inset 0 1px 1px rgba(255, 255, 255, 0.4);
        }

        .btn-mic-special:active, .btn-mic-special.pressed {
            transform: translateY(4px);
            background: radial-gradient(circle at 50% 50%, #0056cc 0%, #003d8f 100%);
            box-shadow: 
                0 1px 0 #003d8f,
                0 2px 6px rgba(0, 0, 0, 0.4),
                inset 0 3px 6px rgba(0, 0, 0, 0.7);
            color: #FFFFFF;
        }

        .btn-mic-special.listening {
            background: #30D158;
            border-color: #30D158;
            box-shadow: 0 0 18px rgba(48, 209, 88, 0.7);
            animation: pulse-mic 1.4s infinite;
        }

        @keyframes pulse-mic {
            0% { transform: scale(1); box-shadow: 0 0 8px rgba(48, 209, 88, 0.5); }
            50% { transform: scale(1.05); box-shadow: 0 0 20px rgba(48, 209, 88, 0.9); }
            100% { transform: scale(1); box-shadow: 0 0 8px rgba(48, 209, 88, 0.5); }
        }

        /* D-Pad Container (Đĩa tròn 236px - To, dễ bấm ngón cái) */
        .dpad-container {
            width: 236px;
            height: 236px;
            margin: 2px auto;
            position: relative;
            background: radial-gradient(circle at 50% 50%, #2a2a2e 0%, #1a1a1d 70%, #121215 100%);
            border-radius: 50%;
            border: 1px solid rgba(255, 255, 255, 0.12);
            box-shadow: 
                inset 0 2px 6px rgba(255, 255, 255, 0.1),
                inset 0 -3px 8px rgba(0, 0, 0, 0.8),
                0 12px 36px rgba(0, 0, 0, 0.75);
        }

        .dpad-btn {
            position: absolute;
            background: transparent;
            border: none;
            color: rgba(255, 255, 255, 0.85);
            font-size: 20px;
            font-weight: 800;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            user-select: none;
            -webkit-user-select: none;
            -webkit-touch-callout: none;
            touch-action: manipulation;
            transition: transform 0.06s ease, color 0.06s ease, filter 0.06s ease;
        }

        .dpad-up {
            top: 4px;
            left: 78px;
            width: 80px;
            height: 60px;
        }

        .dpad-up:active, .dpad-up.pressed {
            transform: translateY(3px) scale(0.94);
            color: #FF9F0A;
            filter: drop-shadow(0 0 8px rgba(255, 159, 10, 0.85));
        }

        .dpad-down {
            bottom: 4px;
            left: 78px;
            width: 80px;
            height: 60px;
        }

        .dpad-down:active, .dpad-down.pressed {
            transform: translateY(-3px) scale(0.94);
            color: #FF9F0A;
            filter: drop-shadow(0 0 8px rgba(255, 159, 10, 0.85));
        }

        .dpad-left {
            top: 78px;
            left: 4px;
            width: 60px;
            height: 80px;
        }

        .dpad-left:active, .dpad-left.pressed {
            transform: translateX(3px) scale(0.94);
            color: #FF9F0A;
            filter: drop-shadow(0 0 8px rgba(255, 159, 10, 0.85));
        }

        .dpad-right {
            top: 78px;
            right: 4px;
            width: 60px;
            height: 80px;
        }

        .dpad-right:active, .dpad-right.pressed {
            transform: translateX(-3px) scale(0.94);
            color: #FF9F0A;
            filter: drop-shadow(0 0 8px rgba(255, 159, 10, 0.85));
        }

        .dpad-ok {
            top: 78px;
            left: 78px;
            width: 80px;
            height: 80px;
            border-radius: 50%;
            background: radial-gradient(circle at 35% 30%, #38383c 0%, #252528 65%, #1c1c1e 100%);
            color: #ffffff;
            font-size: 19px;
            font-weight: 800;
            border: 1px solid rgba(255, 255, 255, 0.18);
            border-top: 1px solid rgba(255, 255, 255, 0.35);
            cursor: pointer;
            user-select: none;
            -webkit-user-select: none;
            -webkit-touch-callout: none;
            touch-action: manipulation;
            box-shadow: 
                0 5px 0 #101012,
                0 10px 24px rgba(0, 0, 0, 0.7),
                inset 0 1px 2px rgba(255, 255, 255, 0.3);
            transition: transform 0.06s ease, box-shadow 0.06s ease, background 0.06s ease;
        }

        .dpad-ok:active, .dpad-ok.pressed {
            transform: translateY(4px);
            background: radial-gradient(circle at 50% 50%, #1a1a1d 0%, #121214 100%);
            box-shadow: 
                0 1px 0 #101012,
                0 2px 6px rgba(0, 0, 0, 0.5),
                inset 0 3px 8px rgba(0, 0, 0, 0.85);
            border-color: rgba(255, 159, 10, 0.6);
            color: #FF9F0A;
        }

        /* Cụm con nhộng VOL và CH (Dọc 148px, rộng 58px, bo tròn 29px với hiệu ứng lún 3D) */
        .capsule-box {
            width: 58px;
            height: 148px;
            background: linear-gradient(180deg, #28282b 0%, #1e1e21 100%);
            border: 1px solid rgba(255, 255, 255, 0.12);
            border-top: 1px solid rgba(255, 255, 255, 0.28);
            border-radius: 29px;
            display: flex;
            flex-direction: column;
            align-items: center;
            overflow: hidden;
            box-shadow: 
                0 5px 0 #121214,
                0 8px 20px rgba(0, 0, 0, 0.55),
                inset 0 1px 1px rgba(255, 255, 255, 0.2);
        }

        .capsule-btn-top, .capsule-btn-bottom {
            width: 100%;
            height: 58px;
            background: transparent;
            border: none;
            color: #FFFFFF;
            font-size: 22px;
            font-weight: bold;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            user-select: none;
            -webkit-user-select: none;
            -webkit-touch-callout: none;
            touch-action: manipulation;
            transition: transform 0.06s ease, background 0.06s ease, box-shadow 0.06s ease;
        }

        .capsule-btn-top:active, .capsule-btn-top.pressed {
            transform: translateY(3px);
            background: rgba(0, 0, 0, 0.35);
            box-shadow: inset 0 3px 8px rgba(0, 0, 0, 0.7);
            color: #FF9F0A;
        }

        .capsule-btn-bottom:active, .capsule-btn-bottom.pressed {
            transform: translateY(-3px);
            background: rgba(0, 0, 0, 0.35);
            box-shadow: inset 0 -3px 8px rgba(0, 0, 0, 0.7);
            color: #FF9F0A;
        }

        .capsule-label {
            width: 100%;
            height: 32px;
            display: flex;
            align-items: center;
            justify-content: center;
            color: #8E8E93;
            font-size: 12px;
            font-weight: 800;
            letter-spacing: 0.1em;
            user-select: none;
        }

        /* ========================================================================= */
        /* MODALS (NUMPAD, KEYBOARD, VOICE DIALOG)                                   */
        /* ========================================================================= */
        .remote-modal-backdrop {
            display: none;
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0, 0, 0, 0.75);
            backdrop-filter: blur(8px);
            -webkit-backdrop-filter: blur(8px);
            z-index: 1000;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }

        .remote-modal-backdrop.show {
            display: flex;
        }

        .remote-modal-content {
            background: #222225;
            border: 1px solid rgba(255, 255, 255, 0.12);
            border-radius: 28px;
            width: 100%;
            max-width: 340px;
            padding: 24px 20px;
            box-shadow: 0 20px 50px rgba(0, 0, 0, 0.9);
            display: flex;
            flex-direction: column;
            align-items: center;
            animation: modal-pop 0.2s cubic-bezier(0.16, 1, 0.3, 1);
        }

        @keyframes modal-pop {
            0% { transform: scale(0.9); opacity: 0; }
            100% { transform: scale(1); opacity: 1; }
        }

        .modal-title {
            color: #FFFFFF;
            font-size: 17px;
            font-weight: 800;
            margin-bottom: 6px;
            text-align: center;
        }

        .modal-sub {
            color: #8E8E93;
            font-size: 12px;
            margin-bottom: 16px;
            text-align: center;
        }

        .modal-close-btn {
            margin-top: 18px;
            padding: 10px 24px;
            background: rgba(255, 255, 255, 0.08);
            border: 1px solid rgba(255, 255, 255, 0.14);
            border-radius: 12px;
            color: #FFFFFF;
            font-size: 13px;
            font-weight: 700;
            cursor: pointer;
            transition: all 0.15s;
        }

        .modal-close-btn:active {
            background: rgba(255, 255, 255, 0.2);
            transform: scale(0.95);
        }

        /* Numpad Grid inside Modal */
        .modal-numpad-grid {
            display: grid;
            grid-template-columns: repeat(3, 1fr);
            gap: 12px;
            width: 100%;
            max-width: 260px;
        }

        .modal-num-btn {
            width: 68px;
            height: 68px;
            border-radius: 50%;
            background: #2c2c2e;
            border: 1px solid rgba(255, 255, 255, 0.1);
            color: #FFFFFF;
            font-size: 22px;
            font-weight: 700;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            transition: all 0.1s;
        }

        .modal-num-btn:active, .modal-num-btn.pressed {
            transform: scale(0.92);
            background: #FF9F0A;
        }

        .modal-num-display {
            font-size: 20px;
            font-weight: 800;
            color: #FFFFFF;
            margin-bottom: 16px;
            min-height: 28px;
            letter-spacing: 0.1em;
        }

        /* Keyboard Form inside Modal */
        .modal-input {
            width: 100%;
            padding: 14px 16px;
            background: rgba(0, 0, 0, 0.35);
            border: 1px solid rgba(255, 255, 255, 0.15);
            border-radius: 14px;
            color: #FFFFFF;
            font-size: 15px;
            margin-bottom: 14px;
            outline: none;
        }

        .modal-input:focus {
            border-color: #007AFF;
        }

        /* Multi-TV Selector & Management */
        .tv-picker-list {
            display: flex;
            flex-direction: column;
            gap: 8px;
            width: 100%;
            max-height: 280px;
            overflow-y: auto;
            margin-bottom: 14px;
        }

        .tv-picker-item {
            display: flex;
            align-items: center;
            justify-content: space-between;
            background: #2a2a2e;
            border: 1px solid rgba(255, 255, 255, 0.08);
            border-radius: 14px;
            padding: 12px 14px;
            cursor: pointer;
            transition: all 0.15s ease;
            text-align: left;
            user-select: none;
            -webkit-user-select: none;
            -webkit-touch-callout: none;
            touch-action: manipulation;
        }

        .tv-picker-item:hover, .tv-picker-item:active, .tv-picker-item.pressed {
            background: #34343a;
            transform: scale(0.98);
        }

        .tv-picker-item.active {
            background: rgba(0, 122, 255, 0.22);
            border-color: #007AFF;
        }

        .tv-item-title {
            font-size: 14px;
            font-weight: 700;
            color: #FFFFFF;
            display: flex;
            align-items: center;
            gap: 6px;
        }

        .tv-item-sub {
            font-size: 11px;
            color: #8E8E93;
            margin-top: 2px;
        }

        .tv-item-check {
            color: #007AFF;
            font-size: 16px;
            font-weight: bold;
        }

        .tv-manage-card {
            background: #2a2a2e;
            border: 1px solid rgba(255, 255, 255, 0.08);
            border-radius: 14px;
            padding: 10px 12px;
            margin-bottom: 8px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            width: 100%;
            text-align: left;
        }

        .modal-btn-row {
            display: flex;
            gap: 10px;
            width: 100%;
        }

        .modal-btn-primary {
            flex: 1;
            padding: 12px 10px;
            background: #007AFF;
            border: none;
            border-radius: 14px;
            color: #FFFFFF;
            font-size: 13px;
            font-weight: 700;
            cursor: pointer;
        }

        .modal-btn-yt {
            flex: 1;
            padding: 12px 10px;
            background: #FF0000;
            border: none;
            border-radius: 14px;
            color: #FFFFFF;
            font-size: 13px;
            font-weight: 700;
            cursor: pointer;
        }

        /* Voice Visualizer Waveform Canvas */
        .voice-wave-canvas {
            width: 200px;
            height: 60px;
            margin: 12px 0;
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
<body ontouchstart="">

    <!-- Ambient Dynamic Glow (Strand Backdrop Lighting) -->
    <div class="ambient-glow"></div>

    <!-- Hidden compatibility elements for background SSE/Status update -->
    <div style="display:none;" aria-hidden="true">
        <div id="tvStatusBadge"><span id="tvStatusText"></span></div>
        <span id="bannerChName"></span>
    </div>

    <!-- TAB 1: REMOTE CONTROLLER -->
    <div id="tab-remote" class="tab-content active">
        <div class="remote-wrapper">
            <!-- Header: Cài đặt (trái) - Tên TV + Status (giữa) - Nguồn đỏ (phải) -->
            <div class="remote-header-grid">
                <div class="remote-header-col">
                    <button class="remote-header-btn" onclick="openTvSettingsModal()" title="Quản lý & Cài đặt danh sách TV">
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>
                    </button>
                </div>
                <div class="remote-header-col">
                    <div id="btnTvPicker" class="remote-status-box" onclick="openTvPickerModal()" style="cursor: pointer;" title="Chạm để chuyển đổi TV">
                        <span id="remoteHeaderDot" class="remote-status-dot online"></span>
                        <span id="remoteHeaderStatus">Đang nạp TV...</span>
                        <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" style="margin-left: 4px; opacity: 0.6;"><path d="M7 10l5 5 5-5z"/></svg>
                    </div>
                </div>
                <div class="remote-header-col">
                    <button class="remote-power-btn" onclick="sendKey('power')" title="Nguồn">
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M13 3h-2v10h2V3zm4.83 2.17l-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z"/></svg>
                    </button>
                </div>
            </div>

            <!-- Remote Body Container (Bo góc 36px, tràn màn hình) -->
            <div class="remote-body-box">

                <!-- 1. Hàng Media: Prev (|◀), Play/Pause (▶||), Next (▶|) -->
                <div class="remote-grid-row">
                    <div class="remote-grid-col">
                        <button class="btn-circle btn-media" onclick="sendKey('ch_prev')" title="Kênh trước / Lùi">
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/></svg>
                        </button>
                    </div>
                    <div class="remote-grid-col">
                        <button class="btn-circle btn-media" onclick="sendKey('play_pause')" title="Phát / Tạm dừng">
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9V8h2v8zm4 0h-2V8h2v8z"/></svg>
                        </button>
                    </div>
                    <div class="remote-grid-col">
                        <button class="btn-circle btn-media" onclick="sendKey('ch_next')" title="Kênh tiếp / Tới">
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
                        </button>
                    </div>
                </div>

                <!-- 2. Cụm D-Pad tròn lớn (236px) -->
                <div class="dpad-container">
                    <button class="dpad-btn dpad-up" onclick="sendKey('up')">▲</button>
                    <button class="dpad-btn dpad-down" onclick="sendKey('down')">▼</button>
                    <button class="dpad-btn dpad-left" onclick="sendKey('left')">◄</button>
                    <button class="dpad-btn dpad-right" onclick="sendKey('right')">►</button>
                    <button class="dpad-btn dpad-ok" onclick="sendKey('center')">OK</button>
                </div>

                <!-- 3. Hàng chức năng 1: Back (↩), Home (🏠), Numpad (🔢) -->
                <div class="remote-grid-row">
                    <div class="remote-grid-col">
                        <button class="btn-circle btn-func" onclick="sendKey('back')" title="Quay lại">
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>
                        </button>
                    </div>
                    <div class="remote-grid-col">
                        <button class="btn-circle btn-func" onclick="sendKey('home')" title="Trang chủ">
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"/></svg>
                        </button>
                    </div>
                    <div class="remote-grid-col">
                        <button class="btn-circle btn-func" onclick="openNumpadModal()" title="Bàn phím số">
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M4 4h4v4H4zm6 0h4v4h-4zm6 0h4v4h-4zM4 10h4v4H4zm6 0h4v4h-4zm6 0h4v4h-4zM4 16h4v4H4zm6 0h4v4h-4zm6 0h4v4h-4z"/></svg>
                        </button>
                    </div>
                </div>

                <!-- 4. Hàng chức năng 2: Mute (🔇), Mic (🎤), Keyboard (⌨) -->
                <div class="remote-grid-row">
                    <div class="remote-grid-col">
                        <button class="btn-circle btn-func" onclick="sendKey('mute')" title="Tắt tiếng">
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>
                        </button>
                    </div>
                    <div class="remote-grid-col">
                        <button id="btnWebMic" class="btn-circle btn-func btn-mic-special" onclick="openVoiceModal()" title="Tìm kiếm giọng nói">
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5-3c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/></svg>
                        </button>
                    </div>
                    <div class="remote-grid-col">
                        <button class="btn-circle btn-func" onclick="openKeyboardModal()" title="Bàn phím nhập chữ">
                            <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z"/></svg>
                        </button>
                    </div>
                </div>

                <!-- 5. Hàng con nhộng VOL và CH (thẳng cột 100% với Cột 1 và Cột 3) -->
                <div class="remote-grid-row" style="margin-top: 4px;">
                    <!-- Cột 1: VOL (+ / VOL / −) -->
                    <div class="remote-grid-col">
                        <div class="capsule-box">
                            <button class="capsule-btn-top" onclick="sendKey('vol_up')" title="Tăng âm lượng">+</button>
                            <span class="capsule-label">VOL</span>
                            <button class="capsule-btn-bottom" onclick="sendKey('vol_down')" title="Giảm âm lượng">−</button>
                        </div>
                    </div>
                    <!-- Cột 2: Khoảng đệm giữa (đã bỏ nút OSD) -->
                    <div class="remote-grid-col"></div>
                    <!-- Cột 3: CH (∧ / CH / ∨) -->
                    <div class="remote-grid-col">
                        <div class="capsule-box">
                            <button class="capsule-btn-top" onclick="sendKey('ch_next')" title="Kênh tiếp">∧</button>
                            <span class="capsule-label">CH</span>
                            <button class="capsule-btn-bottom" onclick="sendKey('ch_prev')" title="Kênh trước">∨</button>
                        </div>
                    </div>
                </div>
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

    <!-- Modal 1: Bàn phím số Numpad -->
    <div id="remoteNumpadModal" class="remote-modal-backdrop" onclick="if(event.target===this)closeNumpadModal()">
        <div class="remote-modal-content">
            <div class="modal-title">Bàn Phím Số TV</div>
            <div id="numpadEnteredText" class="modal-num-display">Bấm số chọn kênh</div>
            <div class="modal-numpad-grid">
                <button class="modal-num-btn" onclick="onNumpadPress('1')">1</button>
                <button class="modal-num-btn" onclick="onNumpadPress('2')">2</button>
                <button class="modal-num-btn" onclick="onNumpadPress('3')">3</button>
                <button class="modal-num-btn" onclick="onNumpadPress('4')">4</button>
                <button class="modal-num-btn" onclick="onNumpadPress('5')">5</button>
                <button class="modal-num-btn" onclick="onNumpadPress('6')">6</button>
                <button class="modal-num-btn" onclick="onNumpadPress('7')">7</button>
                <button class="modal-num-btn" onclick="onNumpadPress('8')">8</button>
                <button class="modal-num-btn" onclick="onNumpadPress('9')">9</button>
                <button class="modal-num-btn" style="font-size: 15px;" onclick="onNumpadPress('DEL')">Xóa</button>
                <button class="modal-num-btn" onclick="onNumpadPress('0')">0</button>
                <button class="modal-num-btn" style="background:#FF9F0A; font-size:16px;" onclick="onNumpadPress('OK')">OK</button>
            </div>
            <button class="modal-close-btn" onclick="closeNumpadModal()">Đóng</button>
        </div>
    </div>

    <!-- Modal 2: Bàn phím gõ chữ & Tìm YouTube -->
    <div id="remoteKeyboardModal" class="remote-modal-backdrop" onclick="if(event.target===this)closeKeyboardModal()">
        <div class="remote-modal-content">
            <div class="modal-title">Bàn Phím TV</div>
            <div class="modal-sub">Gõ nội dung văn bản để gửi lên TV hoặc tìm kiếm YouTube</div>
            <input type="text" id="keyboardTextInput" class="modal-input" placeholder="Nhập từ khóa hoặc văn bản..." onkeydown="if(event.key==='Enter')sendKeyboardText()">
            <div class="modal-btn-row">
                <button class="modal-btn-primary" onclick="sendKeyboardText()">Gửi TV</button>
                <button class="modal-btn-yt" onclick="searchYouTubeFromKeyboard()">🔍 YouTube</button>
            </div>
            <button class="modal-close-btn" onclick="closeKeyboardModal()">Đóng</button>
        </div>
    </div>

    <!-- Modal 3: Tìm kiếm giọng nói tiếng Việt với Sóng âm -->
    <div id="remoteVoiceModal" class="remote-modal-backdrop" onclick="if(event.target===this)closeVoiceModal()">
        <div class="remote-modal-content">
            <div style="width:68px; height:68px; border-radius:50%; background:#2c2c2e; display:flex; align-items:center; justify-content:center; margin-bottom:12px; cursor:pointer;" onclick="finishVoiceSearch()" title="Chạm để kết thúc nói">
                <svg width="34" height="34" viewBox="0 0 24 24" fill="#007AFF"><path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5-3c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/></svg>
            </div>
            <canvas id="voiceWaveCanvas" class="voice-wave-canvas" width="200" height="60"></canvas>
            <div id="voiceModalStatus" class="modal-title">Đang lắng nghe...</div>
            <div id="voiceModalSub" class="modal-sub">Nói tên video, ca sĩ, kênh hoặc phim...</div>
            <div class="modal-btn-row" style="margin-top: 16px; width: 100%;">
                <button id="btnVoiceFinish" class="modal-btn-primary" style="flex: 1.4; background: linear-gradient(135deg, #007AFF 0%, #0051C7 100%); font-weight: 700; font-size: 14px;" onclick="finishVoiceSearch()">
                    ✓ Kết thúc nói
                </button>
                <button class="modal-close-btn" style="flex: 1; margin: 0; padding: 12px 10px;" onclick="closeVoiceModal()">
                    Hủy
                </button>
            </div>
        </div>
    </div>

    <!-- Modal 4: Quản lý & Thêm TV Samsung -->
    <div id="remoteSettingsModal" class="remote-modal-backdrop" onclick="if(event.target===this)closeTvSettingsModal()">
        <div class="remote-modal-content" style="max-height: 85vh; overflow-y: auto;">
            <div class="modal-title">Quản Lý Danh Sách TV</div>
            <div class="modal-sub">Danh sách các TV Samsung trong nhà đã kết nối</div>
            
            <div id="tvManageList" style="width: 100%; margin-bottom: 12px;">
                <!-- Rendered dynamically by JS -->
            </div>

            <div style="background: rgba(255,255,255,0.05); border-radius: 16px; padding: 14px; width: 100%; text-align: left; margin-bottom: 12px; border: 1px solid rgba(255,255,255,0.1);">
                <div style="font-size: 13px; font-weight: 700; color: #fff; margin-bottom: 8px;" id="tvFormTitle">➕ Thêm TV Samsung Mới</div>
                <input type="hidden" id="editTvId" value="">
                <label style="font-size: 11px; color: #8E8E93; margin-bottom: 3px; display: block; font-weight: 600;">Tên TV (ví dụ: TV Phòng Khách, TV Phòng Ngủ):</label>
                <input type="text" id="editTvName" class="modal-input" style="margin-bottom: 8px;" placeholder="TV Phòng Ngủ">
                <label style="font-size: 11px; color: #8E8E93; margin-bottom: 3px; display: block; font-weight: 600;">Địa chỉ IP TV Samsung (LAN):</label>
                <input type="text" id="editTvIp" class="modal-input" style="margin-bottom: 10px;" placeholder="192.168.1.18">
                <button class="modal-btn-primary" style="width: 100%; padding: 12px;" onclick="saveTvFromForm()">Lưu & Ghép đôi với TV</button>
            </div>

            <button class="modal-close-btn" style="width: 100%; margin-top: 4px;" onclick="closeTvSettingsModal()">Đóng</button>
        </div>
    </div>

    <!-- Modal 5: Chọn TV Muốn Điều Khiển (Độc lập cho từng điện thoại / người dùng) -->
    <div id="remoteTvPickerModal" class="remote-modal-backdrop" onclick="if(event.target===this)closeTvPickerModal()">
        <div class="remote-modal-content">
            <div class="modal-title">Chọn TV Muốn Điều Khiển</div>
            <div class="modal-sub">Mỗi điện thoại tự chọn điều khiển TV riêng biệt</div>
            
            <div id="tvPickerList" class="tv-picker-list">
                <!-- Rendered dynamically by JS -->
            </div>

            <div class="modal-btn-row" style="flex-direction: column; gap: 8px; width: 100%;">
                <button class="modal-btn-primary" style="width: 100%; padding: 12px;" onclick="openTvSettingsModal()">
                    ⚙️ Quản lý & Thêm TV mới
                </button>
                <button class="modal-close-btn" style="width: 100%; margin: 0; padding: 10px;" onclick="closeTvPickerModal()">
                    Đóng
                </button>
            </div>
        </div>
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
            if (tabId === 'remote' && btns[0]) { btns[0].classList.add('active'); pollTvStatus(); }
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

        /* ========================================================================= */
        /* TV REMOTE MODAL & CONTROLLER FUNCTIONS                                   */
        /* ========================================================================= */
        var currentNumpadValue = '';
        function openNumpadModal() {
            var m = document.getElementById('remoteNumpadModal');
            if (m) {
                currentNumpadValue = '';
                document.getElementById('numpadEnteredText').textContent = 'Bấm số chọn kênh';
                m.classList.add('show');
            }
        }

        function closeNumpadModal() {
            var m = document.getElementById('remoteNumpadModal');
            if (m) m.classList.remove('show');
        }

        function onNumpadPress(val) {
            if (val === 'DEL') {
                currentNumpadValue = currentNumpadValue.slice(0, -1);
                document.getElementById('numpadEnteredText').textContent = currentNumpadValue ? ('Kênh: ' + currentNumpadValue) : 'Bấm số chọn kênh';
            } else if (val === 'OK') {
                sendKey('center');
                closeNumpadModal();
                currentNumpadValue = '';
            } else {
                currentNumpadValue += val;
                document.getElementById('numpadEnteredText').textContent = 'Kênh: ' + currentNumpadValue;
                sendKey(val);
            }
        }

        function openKeyboardModal() {
            var m = document.getElementById('remoteKeyboardModal');
            if (m) {
                document.getElementById('keyboardTextInput').value = '';
                m.classList.add('show');
                setTimeout(function() { document.getElementById('keyboardTextInput').focus(); }, 150);
            }
        }

        function closeKeyboardModal() {
            var m = document.getElementById('remoteKeyboardModal');
            if (m) m.classList.remove('show');
        }

        function sendKeyboardText() {
            var txt = document.getElementById('keyboardTextInput').value.trim();
            if (txt) {
                fetch('/api/key', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ key: 'text:' + txt, tvId: getActiveTvId() })
                });
                closeKeyboardModal();
            }
        }

        function searchYouTubeFromKeyboard() {
            var txt = document.getElementById('keyboardTextInput').value.trim();
            if (txt) {
                showToast('🔍 Tìm YouTube: ' + txt);
                fetch('/api/key', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ key: 'search:' + txt, tvId: getActiveTvId() })
                });
                closeKeyboardModal();
            }
        }

        var voiceWaveAnimId = null;
        var voiceSpeechRec = null;
        var currentVoiceTranscript = '';
        var isVoiceHandled = false;

        function openVoiceModal() {
            var m = document.getElementById('remoteVoiceModal');
            if (!m) return;
            m.classList.add('show');
            var statusEl = document.getElementById('voiceModalStatus');
            var subEl = document.getElementById('voiceModalSub');
            var btnFinish = document.getElementById('btnVoiceFinish');
            
            currentVoiceTranscript = '';
            isVoiceHandled = false;
            statusEl.textContent = 'Đang lắng nghe...';
            subEl.textContent = 'Nói tên kênh, video hoặc phim tiếng Việt...';
            if (btnFinish) btnFinish.textContent = '✓ Kết thúc nói';
            
            startVoiceWaveCanvas();

            var SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
            if (SpeechRecognition) {
                try {
                    voiceSpeechRec = new SpeechRecognition();
                    voiceSpeechRec.lang = 'vi-VN';
                    voiceSpeechRec.continuous = false;
                    voiceSpeechRec.interimResults = true;

                    voiceSpeechRec.onresult = function(event) {
                        var transcript = '';
                        for (var i = event.resultIndex; i < event.results.length; ++i) {
                            transcript += event.results[i][0].transcript;
                        }
                        transcript = transcript.trim();
                        if (transcript) {
                            currentVoiceTranscript = transcript;
                            subEl.textContent = '🔍 "' + transcript + '"';
                            if (event.results[0].isFinal) {
                                finishVoiceSearch();
                            }
                        }
                    };

                    voiceSpeechRec.onerror = function(e) {
                        if (!isVoiceHandled && !currentVoiceTranscript) {
                            statusEl.textContent = 'Không nhận dạng được';
                            subEl.textContent = 'Lỗi mic hoặc chưa cấp quyền. Hãy thử lại.';
                        }
                    };

                    voiceSpeechRec.onend = function() {
                        if (!isVoiceHandled && currentVoiceTranscript) {
                            finishVoiceSearch();
                        }
                    };

                    voiceSpeechRec.start();
                } catch(ex) {
                    subEl.textContent = 'Trình duyệt không hỗ trợ nhận diện giọng nói.';
                }
            } else {
                subEl.textContent = 'Vui lòng dùng trình duyệt Chrome/Safari có hỗ trợ mic.';
            }
        }

        function finishVoiceSearch() {
            if (isVoiceHandled) return;
            var txt = (currentVoiceTranscript || '').trim();
            if (!txt) {
                var subEl = document.getElementById('voiceModalSub');
                if (subEl && subEl.textContent && subEl.textContent.indexOf('🔍 "') !== -1) {
                    txt = subEl.textContent.replace('🔍 "', '').replace('"', '').trim();
                }
            }

            if (voiceSpeechRec) {
                try { voiceSpeechRec.stop(); } catch(e){}
                voiceSpeechRec = null;
            }
            stopVoiceWaveCanvas();

            if (txt) {
                isVoiceHandled = true;
                var statusEl = document.getElementById('voiceModalStatus');
                if (statusEl) statusEl.textContent = 'Đã nhận dạng!';
                var btnFinish = document.getElementById('btnVoiceFinish');
                if (btnFinish) btnFinish.textContent = 'Đang tìm kiếm...';

                setTimeout(function() {
                    closeVoiceModal();
                    showToast('🔍 Tìm YouTube: ' + txt);
                    fetch('/api/key', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ key: 'search:' + txt, tvId: getActiveTvId() })
                    });
                }, 350);
            } else {
                var subEl = document.getElementById('voiceModalSub');
                if (subEl) subEl.textContent = '⚠️ Chưa nhận dạng được nội dung. Hãy thử nói lại!';
                showToast('Chưa nghe rõ, hãy nói lại!');
            }
        }

        function closeVoiceModal() {
            var m = document.getElementById('remoteVoiceModal');
            if (m) m.classList.remove('show');
            isVoiceHandled = true;
            if (voiceSpeechRec) {
                try { voiceSpeechRec.stop(); } catch(e){}
                voiceSpeechRec = null;
            }
            stopVoiceWaveCanvas();
        }

        function startVoiceWaveCanvas() {
            var cvs = document.getElementById('voiceWaveCanvas');
            if (!cvs) return;
            var ctx = cvs.getContext('2d');
            var phase = 0;

            function render() {
                ctx.clearRect(0, 0, cvs.width, cvs.height);
                phase += 0.08;
                var barCount = 7;
                var baseFractions = [0.3, 0.52, 0.8, 1.0, 0.8, 0.52, 0.3];
                var totalSpacing = cvs.width * 0.38;
                var spacing = totalSpacing / (barCount + 1);
                var barWidth = (cvs.width - totalSpacing) / barCount;
                var minBarHeight = cvs.height * 0.16;
                var maxAvailable = cvs.height * 0.88;

                for (var i = 0; i < barCount; i++) {
                    var cx = spacing * (i + 1) + barWidth * (i + 0.5);
                    var baseFrac = baseFractions[i];
                    var waveOffset = Math.sin(phase + i * 0.85) * 0.28;
                    var dynamicFactor = Math.min(1.0, Math.max(0.12, baseFrac * (0.4 + waveOffset)));
                    var barHeight = minBarHeight + (maxAvailable - minBarHeight) * dynamicFactor;

                    var top = (cvs.height - barHeight) / 2;
                    var left = cx - barWidth / 2;

                    var grad = ctx.createLinearGradient(cx, top, cx, top + barHeight);
                    grad.addColorStop(0, '#00E5FF');
                    grad.addColorStop(1, '#007AFF');
                    ctx.fillStyle = grad;

                    ctx.beginPath();
                    if (ctx.roundRect) {
                        ctx.roundRect(left, top, barWidth, barHeight, barWidth / 2);
                    } else {
                        ctx.rect(left, top, barWidth, barHeight);
                    }
                    ctx.fill();
                }
                voiceWaveAnimId = requestAnimationFrame(render);
            }
            stopVoiceWaveCanvas();
            render();
        }

        function stopVoiceWaveCanvas() {
            if (voiceWaveAnimId) {
                cancelAnimationFrame(voiceWaveAnimId);
                voiceWaveAnimId = null;
            }
        }

        var cachedTvs = [];

        function getActiveTvId() {
            var saved = localStorage.getItem('tvplay_active_tv_id');
            if (saved) return saved;
            if (cachedTvs && cachedTvs.length > 0) return cachedTvs[0].id;
            return 'all';
        }

        function setActiveTvId(tvId) {
            localStorage.setItem('tvplay_active_tv_id', tvId);
            updateHeaderTvDisplay();
        }

        function getActiveTv() {
            var curId = getActiveTvId();
            if (curId === 'all') {
                return { id: 'all', name: 'Tất Cả TV (Phát đồng thời)', ip: 'Broadcast', online: true };
            }
            for (var i = 0; i < cachedTvs.length; i++) {
                if (cachedTvs[i].id === curId) return cachedTvs[i];
            }
            return cachedTvs.length > 0 ? cachedTvs[0] : null;
        }

        function updateHeaderTvDisplay() {
            var curTv = getActiveTv();
            var text = document.getElementById('remoteHeaderStatus');
            var dot = document.getElementById('remoteHeaderDot');
            if (curTv) {
                if (text) text.textContent = curTv.name;
                if (dot) {
                    if (curTv.id === 'all') {
                        dot.className = 'remote-status-dot online';
                    } else if (curTv.online) {
                        dot.className = curTv.paired ? 'remote-status-dot online' : 'remote-status-dot pairing';
                    } else {
                        dot.className = 'remote-status-dot offline';
                    }
                }
            }
        }

        function loadTvs(callback) {
            fetch('/api/samsung/tvs')
                .then(function(res) { return res.json(); })
                .then(function(data) {
                    if (data && data.tvs) {
                        cachedTvs = data.tvs;
                        updateHeaderTvDisplay();
                        if (callback) callback(cachedTvs);
                    }
                })
                .catch(function(e) {});
        }

        function pollTvStatus() {
            loadTvs(function() {
                var pickerModal = document.getElementById('remoteTvPickerModal');
                if (pickerModal && pickerModal.classList.contains('show')) {
                    renderTvPickerList();
                }
                var manageModal = document.getElementById('remoteSettingsModal');
                if (manageModal && manageModal.classList.contains('show')) {
                    renderTvManageList();
                }
            });
        }

        // Modal 5: TV Picker
        function openTvPickerModal() {
            var m = document.getElementById('remoteTvPickerModal');
            if (m) m.classList.add('show');
            loadTvs(function() {
                renderTvPickerList();
            });
        }

        function closeTvPickerModal() {
            var m = document.getElementById('remoteTvPickerModal');
            if (m) m.classList.remove('show');
        }

        function renderTvPickerList() {
            var container = document.getElementById('tvPickerList');
            if (!container) return;
            var activeId = getActiveTvId();
            var html = '';

            // Tùy chọn Broadcast: Tất cả TV
            var isAllActive = (activeId === 'all');
            html += '<div class="tv-picker-item ' + (isAllActive ? 'active' : '') + '" onclick="selectTv(\'all\')">' +
                '<div>' +
                    '<div class="tv-item-title">📡 Tất Cả TV (Phát đồng thời)</div>' +
                    '<div class="tv-item-sub">Gửi lệnh cùng lúc tới toàn bộ TV đang bật</div>' +
                '</div>' +
                (isAllActive ? '<div class="tv-item-check">✓</div>' : '') +
            '</div>';

            if (!cachedTvs || cachedTvs.length === 0) {
                html += '<div style="text-align:center; padding:16px; color:#8E8E93; font-size:12px;">' +
                    'Chưa có TV nào được thêm.<br>Bấm "⚙️ Quản lý danh sách TV..." để thêm TV mới.' +
                '</div>';
            } else {
                cachedTvs.forEach(function(tv) {
                    var isSelected = (activeId === tv.id);
                    var dotColor = tv.online ? (tv.paired ? '#30D158' : '#FF9F0A') : '#8E8E93';
                    var statusText = tv.online ? (tv.paired ? 'Đang bật • Đã ghép đôi' : 'Đang bật • Chưa ghép đôi') : 'Ngoại tuyến hoặc tắt';
                    var pairBadge = tv.paired ? '<span style="color:#30D158; font-size:10px; border:1px solid rgba(48,209,88,0.4); border-radius:4px; padding:1px 5px; margin-left:6px;">Đã ghép</span>' : '<span style="color:#FF9F0A; font-size:10px; border:1px solid rgba(255,159,10,0.4); border-radius:4px; padding:1px 5px; margin-left:6px;">Chưa ghép</span>';

                    html += '<div class="tv-picker-item ' + (isSelected ? 'active' : '') + '" style="display:flex; align-items:center; justify-content:space-between;">' +
                        '<div style="flex:1; cursor:pointer;" onclick="selectTv(\'' + tv.id + '\')">' +
                            '<div class="tv-item-title">' +
                                '<span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:' + dotColor + '; margin-right:6px;"></span>' +
                                escapeHtml(tv.name) + pairBadge +
                            '</div>' +
                            '<div class="tv-item-sub">' + escapeHtml(tv.ip) + ' • ' + statusText + '</div>' +
                        '</div>' +
                        '<div style="display:flex; align-items:center; gap:6px;">' +
                            (isSelected ? '<div class="tv-item-check" style="cursor:pointer;" onclick="selectTv(\'' + tv.id + '\')">✓</div>' : '') +
                            '<button title="Xóa TV đã ghép này" style="background:rgba(255,69,58,0.15); border:1px solid rgba(255,69,58,0.3); color:#FF453A; border-radius:6px; font-size:11px; cursor:pointer; padding:4px 8px;" onclick="event.stopPropagation(); deleteTv(\'' + tv.id + '\')">Xóa</button>' +
                        '</div>' +
                    '</div>';
                });
            }

            container.innerHTML = html;
        }

        function selectTv(tvId) {
            setActiveTvId(tvId);
            closeTvPickerModal();
            var cur = getActiveTv();
            showToast('Đã chọn: ' + (cur ? cur.name : tvId));
        }

        // Modal 4: TV Management
        function openTvSettingsModal() {
            closeTvPickerModal();
            var m = document.getElementById('remoteSettingsModal');
            if (m) m.classList.add('show');
            resetTvForm();
            loadTvs(function() {
                renderTvManageList();
            });
        }

        function closeTvSettingsModal() {
            var m = document.getElementById('remoteSettingsModal');
            if (m) m.classList.remove('show');
            updateHeaderTvDisplay();
        }

        function renderTvManageList() {
            var container = document.getElementById('tvManageList');
            if (!container) return;
            if (!cachedTvs || cachedTvs.length === 0) {
                container.innerHTML = '<div style="text-align:center; padding:18px; color:#8E8E93; font-size:12px;">' +
                    'Chưa có TV nào trong danh sách.<br>Vui lòng điền thông tin bên dưới để thêm TV mới.' +
                '</div>';
                return;
            }
            var html = '';
            cachedTvs.forEach(function(tv) {
                var dotColor = tv.online ? (tv.paired ? '#30D158' : '#FF9F0A') : '#8E8E93';
                var statusText = tv.online ? (tv.paired ? 'Sẵn sàng' : 'Chưa có mã ghép') : 'Tắt';
                var pairBadge = tv.paired ? '<span style="color:#30D158; font-size:10px; border:1px solid rgba(48,209,88,0.4); border-radius:4px; padding:1px 5px; margin-left:6px;">Đã ghép</span>' : '<span style="color:#FF9F0A; font-size:10px; border:1px solid rgba(255,159,10,0.4); border-radius:4px; padding:1px 5px; margin-left:6px;">Chưa ghép</span>';

                html += '<div class="tv-manage-card">' +
                    '<div style="overflow:hidden; padding-right:8px; flex:1;">' +
                        '<div style="font-weight:700; font-size:13px; color:#fff; display:flex; align-items:center; gap:6px;">' +
                            '<span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:' + dotColor + ';"></span>' +
                            escapeHtml(tv.name) + pairBadge +
                        '</div>' +
                        '<div style="font-size:11px; color:#8E8E93; margin-top:2px;">' + escapeHtml(tv.ip) + ' (' + statusText + ')</div>' +
                    '</div>' +
                    '<div style="display:flex; gap:5px; flex-wrap:wrap; justify-content:flex-end;">' +
                        '<button class="modal-close-btn" style="margin:0; padding:6px 9px; font-size:11px;" onclick="editTvForm(\'' + tv.id + '\')">Sửa</button>' +
                        (tv.paired ? '<button class="modal-close-btn" style="margin:0; padding:6px 9px; font-size:11px; color:#FF9F0A;" onclick="unpairTv(\'' + tv.id + '\')">Hủy ghép</button>' : '<button class="modal-close-btn" style="margin:0; padding:6px 9px; font-size:11px; color:#0A84FF;" onclick="rePairTv(\'' + tv.id + '\')">Ghép đôi</button>') +
                        '<button class="modal-close-btn" style="margin:0; padding:6px 9px; font-size:11px; color:#FF453A;" onclick="deleteTv(\'' + tv.id + '\')">Xóa TV</button>' +
                    '</div>' +
                '</div>';
            });
            container.innerHTML = html;
        }

        function resetTvForm() {
            var titleEl = document.getElementById('tvFormTitle');
            if (titleEl) titleEl.textContent = '➕ Thêm TV Samsung Mới';
            var idEl = document.getElementById('editTvId');
            if (idEl) idEl.value = '';
            var nameEl = document.getElementById('editTvName');
            if (nameEl) nameEl.value = '';
            var ipEl = document.getElementById('editTvIp');
            if (ipEl) ipEl.value = '';
        }

        function editTvForm(tvId) {
            for (var i = 0; i < cachedTvs.length; i++) {
                if (cachedTvs[i].id === tvId) {
                    var tv = cachedTvs[i];
                    var titleEl = document.getElementById('tvFormTitle');
                    if (titleEl) titleEl.textContent = '✏️ Chỉnh Sửa TV: ' + tv.name;
                    var idEl = document.getElementById('editTvId');
                    if (idEl) idEl.value = tv.id;
                    var nameEl = document.getElementById('editTvName');
                    if (nameEl) nameEl.value = tv.name;
                    var ipEl = document.getElementById('editTvIp');
                    if (ipEl) ipEl.value = tv.ip;
                    break;
                }
            }
        }

        function saveTvFromForm() {
            var tvId = document.getElementById('editTvId').value.trim();
            var name = document.getElementById('editTvName').value.trim();
            var ip = document.getElementById('editTvIp').value.trim();
            if (!ip) {
                showToast('Vui lòng nhập IP TV!');
                return;
            }
            showToast('Đang lưu TV: ' + (name || ip) + '...');
            fetch('/api/samsung/settings', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ tvId: tvId, name: name, tvIp: ip, pairNow: true, resetToken: true })
            })
            .then(function(res) { return res.json(); })
            .then(function(d) {
                showToast('Đã lưu! Hãy nhìn màn hình TV và bấm [Cho phép]');
                resetTvForm();
                loadTvs(function() {
                    renderTvManageList();
                    renderTvPickerList();
                });
            })
            .catch(function() { showToast('Lỗi lưu TV'); });
        }

        function rePairTv(tvId) {
            if (!confirm('Yêu cầu TV hiện popup ghép đôi lại?')) return;
            showToast('Đang gửi yêu cầu ghép đôi...');
            fetch('/api/samsung/settings', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ tvId: tvId, resetToken: true, pairNow: true })
            })
            .then(function(res) { return res.json(); })
            .then(function(d) {
                showToast('Đã gửi yêu cầu! Bấm [Cho phép] trên TV');
                pollTvStatus();
            })
            .catch(function() { showToast('Lỗi gửi yêu cầu ghép đôi'); });
        }

        function unpairTv(tvId) {
            var tvName = 'TV này';
            for (var i = 0; i < cachedTvs.length; i++) {
                if (cachedTvs[i].id === tvId) {
                    tvName = cachedTvs[i].name;
                    break;
                }
            }
            if (!confirm('Hủy ghép đôi với TV "' + tvName + '"?\n(Mã token ghép đôi sẽ bị xóa, TV sẽ hỏi cấp quyền lại khi bấm phím)')) return;
            showToast('Đang hủy ghép đôi...');
            fetch('/api/samsung/settings', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ action: 'unpair', tvId: tvId })
            })
            .then(function(res) { return res.json(); })
            .then(function(d) {
                showToast('Đã hủy ghép đôi TV: ' + tvName);
                loadTvs(function() {
                    renderTvManageList();
                    renderTvPickerList();
                    pollTvStatus();
                });
            })
            .catch(function() { showToast('Lỗi hủy ghép đôi TV'); });
        }

        function deleteTv(tvId) {
            var tvName = 'TV này';
            for (var i = 0; i < cachedTvs.length; i++) {
                if (cachedTvs[i].id === tvId) {
                    tvName = cachedTvs[i].name;
                    break;
                }
            }
            if (!confirm('Xác nhận XÓA TV "' + tvName + '" khỏi hệ thống?\n(Bao gồm xóa IP và token đã ghép)')) return;
            showToast('Đang xóa TV: ' + tvName + '...');
            fetch('/api/samsung/settings', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ action: 'delete', tvId: tvId })
            })
            .then(function(res) { return res.json(); })
            .then(function(d) {
                showToast('Đã xóa TV: ' + tvName);
                if (getActiveTvId() === tvId) {
                    setActiveTvId('all');
                }
                loadTvs(function() {
                    renderTvManageList();
                    renderTvPickerList();
                    pollTvStatus();
                });
            })
            .catch(function() { showToast('Lỗi xóa TV'); });
        }

        function escapeHtml(str) {
            if (!str) return '';
            return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
        }

        function sendKey(key) {
            if (navigator.vibrate) navigator.vibrate(28);
            var activeTvId = getActiveTvId();
            fetch('/api/key', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ key: key, tvId: activeTvId })
            }).then(function(res) { return res.json(); }).then(function(data) {
                if (data.pairingRequested) {
                    var curTv = getActiveTv();
                    var tvName = (curTv && curTv.id !== 'all') ? curTv.name : 'TV';
                    showToast('⚠️ VUI LÒNG BẤM [CHO PHÉP] TRÊN MÀN HÌNH ' + tvName.toUpperCase());
                    var dot = document.getElementById('remoteHeaderDot');
                    if (dot) dot.className = 'remote-status-dot pairing';
                }
            }).catch(function(e) {});
        }

        // Tự động kiểm tra trạng thái TV định kỳ & khi mở trang
        setTimeout(pollTvStatus, 800);
        setInterval(function() {
            var remTab = document.getElementById('tab-remote');
            if (remTab && remTab.classList.contains('active')) {
                pollTvStatus();
            }
        }, 10000);

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

                // Xử lý hiệu ứng lún ấn xúc giác vật lý tức thì (0ms latency) trên iOS & Mobile Touch
        function initTactileFeedback() {
            var selector = '.remote-header-btn, .remote-power-btn, .btn-circle, .dpad-btn, .capsule-btn-top, .capsule-btn-bottom, .modal-num-btn, .modal-close-btn, .dock-tab';
            
            document.addEventListener('touchstart', function(e) {
                var btn = e.target.closest(selector);
                if (btn) {
                    btn.classList.add('pressed');
                }
            }, {passive: true});

            document.addEventListener('touchend', function(e) {
                var pressedElements = document.querySelectorAll('.pressed');
                pressedElements.forEach(function(el) {
                    el.classList.remove('pressed');
                });
            }, {passive: true});

            document.addEventListener('touchcancel', function(e) {
                var pressedElements = document.querySelectorAll('.pressed');
                pressedElements.forEach(function(el) {
                    el.classList.remove('pressed');
                });
            }, {passive: true});
        }

        initTactileFeedback();
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
        self.send_header("Access-Control-Allow-Private-Network", "true")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")

    def send_compressed_response(self, content_bytes, content_type="application/json; charset=utf-8"):
        if isinstance(content_bytes, str):
            content_bytes = content_bytes.encode("utf-8")
        accept_encoding = self.headers.get("Accept-Encoding", "").lower()
        use_gzip = "gzip" in accept_encoding and len(content_bytes) > 512
        if use_gzip:
            content_bytes = gzip.compress(content_bytes)

        self.send_response(200)
        self.send_header("Content-Type", content_type)
        if use_gzip:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(content_bytes)))
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(content_bytes)

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
            self.send_compressed_response(WEB_REMOTE_HTML.encode("utf-8"), "text/html; charset=utf-8")
            return

        # Direct udpxy proxy with CORS headers for browser players
        if path.startswith("/udp/"):
            udpxy_url = f"http://127.0.0.1:4022{path}"
            try:
                req = urllib.request.Request(udpxy_url, headers={"User-Agent": "TVPlay-WebRemote/1.0"})
                with urllib.request.urlopen(req, timeout=8) as resp:
                    self.send_response(200)
                    self.send_header("Content-Type", "video/mp2t")
                    self.send_cors_headers()
                    self.end_headers()
                    while True:
                        chunk = resp.read(65536)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
            except Exception:
                pass
            return

        if path == "/api/samsung/tvs":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({
                "tvs": samsung_tv_mgr.get_tvs_list(),
                "status": samsung_tv_mgr.check_status()
            }, ensure_ascii=False).encode("utf-8"))
            return

        if path == "/api/samsung/status":
            query = urllib.parse.parse_qs(url_parts.query)
            query_tv_id = query.get("tvId", [None])[0]
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps(samsung_tv_mgr.check_status(query_tv_id), ensure_ascii=False).encode("utf-8"))
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
            self.send_compressed_response(content.encode("utf-8") if isinstance(content, str) else content, "application/json; charset=utf-8")
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
                ct = "text/plain; charset=utf-8"
                if filename.endswith(".m3u"):
                    ct = "audio/x-mpegurl; charset=utf-8"
                elif filename.endswith(".json"):
                    ct = "application/json; charset=utf-8"
                with open(filepath, "rb") as f:
                    content_bytes = f.read()
                self.send_compressed_response(content_bytes, ct)
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

        if path == "/api/samsung/tvs":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({
                "tvs": samsung_tv_mgr.get_tvs_list(),
                "status": samsung_tv_mgr.check_status()
            }, ensure_ascii=False).encode("utf-8"))
            return

        if path == "/api/samsung/status":
            target_tv_id = post_data.get("tvId")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps(samsung_tv_mgr.check_status(target_tv_id), ensure_ascii=False).encode("utf-8"))
            return

        if path == "/api/samsung/settings":
            action = post_data.get("action", "save")
            tv_id = (post_data.get("tvId") or "").strip()
            name = (post_data.get("name") or "").strip()
            new_ip = (post_data.get("tvIp") or "").strip()
            reset_token = bool(post_data.get("resetToken", False))
            pair_now = bool(post_data.get("pairNow", False))

            if action == "delete" and tv_id:
                samsung_tv_mgr.delete_tv(tv_id)
            elif action == "unpair" and tv_id:
                samsung_tv_mgr.clear_tv_token(tv_id)
            else:
                if new_ip or name:
                    tv_id = samsung_tv_mgr.add_or_update_tv(tv_id, name, new_ip)
                if reset_token and tv_id:
                    samsung_tv_mgr.clear_tv_token(tv_id)
                if (pair_now or reset_token) and tv_id:
                    samsung_tv_mgr.trigger_pairing(tv_id)

            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({
                "success": True,
                "tvs": samsung_tv_mgr.get_tvs_list(),
                "status": samsung_tv_mgr.check_status(tv_id)
            }, ensure_ascii=False).encode("utf-8"))
            return

        if path == "/api/key":
            raw_key = post_data.get("key", "").strip()
            target_tv_id = (post_data.get("tvId") or "").strip()
            tv_result = ""
            tv_ok = True
            pairing_requested = False
            if raw_key:
                print(f"[Remote] Key: {raw_key} -> TV: {target_tv_id or 'all'}")
                if raw_key.startswith("text:"):
                    tv_ok, tv_result = samsung_tv_mgr.send_text(target_tv_id, raw_key[5:])
                elif raw_key.startswith("search:"):
                    tv_ok, tv_result = samsung_tv_mgr.search_youtube(target_tv_id, raw_key[7:])
                else:
                    tv_ok, pairing_requested = samsung_tv_mgr.send_key(target_tv_id, raw_key)
                    tv_result = f"Key {raw_key}"

                # 2. Đồng thời broadcast SSE cho TV Play app (nếu app đang mở)
                broadcast_event("key", {"key": raw_key.lower()})

            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({
                "success": tv_ok,
                "key": raw_key,
                "tvAction": tv_result,
                "pairingRequested": pairing_requested
            }, ensure_ascii=False).encode("utf-8"))
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
