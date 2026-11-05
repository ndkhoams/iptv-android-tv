import os
import sys
import time
import socket
import threading
import subprocess
from http.server import HTTPServer, SimpleHTTPRequestHandler

PORT = 8150
DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tizen_tvplay")

def get_real_lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

LAN_IP = get_real_lan_ip()

class CORSRequestHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIR, **kwargs)

    def do_GET(self):
        if self.path == '/api/my_ip':
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(LAN_IP.encode('utf-8'))
            return
        super().do_GET()

    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
        self.send_header('Pragma', 'no-cache')
        self.send_header('Expires', '0')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.end_headers()

def bind_server(start=8150, max_tries=100):
    for host in ['127.0.0.1', '0.0.0.0']:
        for port in range(start, start + max_tries):
            try:
                server = HTTPServer((host, port), CORSRequestHandler)
                return server, host, port
            except (OSError, PermissionError):
                continue
    raise RuntimeError("Khong the bind vao bat ky cong nao tu 8150 tro len. Vui long kiem tra Windows Defender hoac tat phan mem dang dung port.")

def get_browser_cmd(url):
    chrome_path = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
    edge_path = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
    temp_profile = os.path.join(os.environ.get("TEMP", "C:\\Temp"), "tizen_preview_profile")

    flags = [
        "--disable-web-security",
        "--disable-cache",
        "--disk-cache-size=1",
        "--media-cache-size=1",
        "--test-type",
        f"--user-data-dir={temp_profile}",
        f"--app={url}",
        "--window-size=1920,1080",
        "--autoplay-policy=no-user-gesture-required"
    ]

    if os.path.exists(chrome_path):
        return [chrome_path] + flags
    elif os.path.exists(edge_path):
        return [edge_path] + flags
    else:
        return ["start", url]

def main():
    try:
        server, host, port = bind_server(8150, 100)
    except Exception as e:
        print(f"\n[LOI PORT] {e}")
        return
    
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()
    
    display_host = '127.0.0.1' if host == '127.0.0.1' else LAN_IP
    url = f"http://{display_host}:{port}/index.html"
    print(f"==================================================")
    print(f"  TV PLAY - TIZEN TV PREVIEW DÀNH CHO WINDOWS")
    print(f"==================================================")
    print(f" Detected LAN IP: {LAN_IP}")
    print(f" Server dang chay tai: {url}")
    print(f" Mo trinh duyet o che do khong chan CORS (nhu TV)...")
    
    cmd = get_browser_cmd(url)
    try:
        proc = subprocess.Popen(cmd)
        print(f" Cua so TV Play da mo!")
        print(f" Dieu khien bang ban phim:")
        print(f"  - Phim mui ten (Len / Xuong / Trai / Phai): Chuyen kenh & chuyen cot")
        print(f"  - Phim Sang Phai tren tran dau: Chon luong phat truc tiep o cot ben phai")
        print(f"  - Phim Enter / Space: Phat kenh / Chon luong")
        print(f"  - Phim Esc / Backspace: Dong menu / Tro lai")
        print(f"  - Phim Menu / M / S: Mo Cai dat & udpxy & quan ly playlist")
        print(f"  - Nhan Ctrl+C tai cua so nay de dung preview khi xem xong.")
        proc.wait()
    except Exception as e:
        print(f"Loi khoi chay trinh duyet: {e}")
        import webbrowser
        webbrowser.open(url)
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass

if __name__ == '__main__':
    main()
