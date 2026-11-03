# Quy tắc làm việc dự án IPTV Android TV & Samsung Tizen

- **Quy trình thay đổi code & build APK:**
  - Khi người dùng đưa ra yêu cầu tính năng / sửa lỗi / tối ưu: **CHỈ sửa code và giải thích trước**.
  - **TUYỆT ĐỐI KHÔNG** tự ý kích hoạt build APK, GitHub Actions hoặc tạo GitHub Release.
  - **Chỉ build APK khi người dùng ra lệnh cụ thể** (ví dụ: "hãy build apk", "tạo release", "build đi").

- **Quy tắc đặt tên file APK release:**
  - Tên file APK đính kèm khi phát hành release trên GitHub luôn là `TV Play.apk` (thay vì `app-debug.apk`).

- **Cấu hình máy chủ Armbian:**
  - Địa chỉ máy chủ: `10.0.0.222:22`
  - Tài khoản: `root` / Mật khẩu: `armbian`
  - Dịch vụ `udpxy`: Cổng `4022` (chuyển đổi multicast UDP/RTP sang HTTP)
  - Dịch vụ `sports-updater`:
    - Thư mục chạy trên Armbian: `/opt/iptv-sports/sports_updater.py`
    - Dịch vụ systemd: `sports-updater.service` & `sports-updater.timer` (tự động chạy định kỳ 15 phút)
    - Bất cứ khi nào file `sports_updater.py` được cập nhật, hãy tự động đẩy file lên `/opt/iptv-sports/sports_updater.py` và chạy `systemctl start sports-updater.service`.
  - Dịch vụ `iptv-web-remote`:
    - Thư mục chạy trên Armbian: `/opt/iptv-sports/web_remote.py`
    - Dịch vụ systemd: `iptv-web-remote.service` (cổng 8080)
    - Cung cấp: Web Remote điều khiển TV qua web di động, EventSource SSE `/events`, LAN Hub cache M3U/thể thao, và Quản lý danh sách phát (Playlist Management).
    - Lưu trữ dữ liệu: `/opt/iptv-sports/data/playlists.json` và các tệp M3U tải lên tại `/opt/iptv-sports/data/custom_<id>.m3u`.
    - Khi file `web_remote.py` được cập nhật, tự động đẩy file lên `/opt/iptv-sports/web_remote.py` và chạy `systemctl restart iptv-web-remote.service`.

- **Cấu hình triển khai Samsung Tizen TV (10.0.0.9):**
  - TV: Samsung 8 Series 55" (2020), Tizen 5.5, IP: `10.0.0.9`, DUID: `7XCGUU3YN4RCE`.
  - Công cụ trên Armbian: `/opt/samsung/apps2samsung-cli` (đã có chứng chỉ Samsung PFX vĩnh viễn, không cần đăng nhập lại).
  - Thư mục chứa gói wgt: `/opt/samsung/TVplay.wgt`
  - Khi người dùng yêu cầu cài đặt lên TV:
    1. Đóng gói `TVplay.wgt` (zip từ thư mục `tizen_tvplay/`).
    2. Dùng `pscp.exe` hoặc Python `paramiko` upload `TVplay.wgt` lên `10.0.0.222` tại `/opt/samsung/TVplay.wgt`.
    3. Chạy lệnh cài đặt qua SSH:
       ```bash
       /opt/samsung/apps2samsung-cli install /opt/samsung/TVplay.wgt --tv 10.0.0.9 --yes
       /opt/samsung/apps2samsung-cli launch TVPlay0001.TVPlay --tv 10.0.0.9
       ```
    - Lưu ý: Lệnh `install` chạy ngầm hoàn toàn không ảnh hưởng tới người đang xem YouTube trên TV. Chỉ lệnh `launch` mới chuyển màn hình sang TV Play.

- **Xem trước giao diện Tizen trên Windows:**
  - Chạy `python preview_tizen.py` (cổng tự động `8150` trở lên, tránh dải `8041-8140` bị Windows Hyper-V/NAT chiếm quyền).

- **Hồ sơ ngữ cảnh dự án:**
  - Xem chi tiết đầy đủ tại file `ARMBIAN_SETUP.md` và `PROJECT_CONTEXT.md`.
