# HỒ SƠ DỰ ÁN & BÀN GIAO NGỮ CẢNH (MASTER CONTEXT HANDOVER)
> **Tài liệu ghi nhớ ngữ cảnh làm việc đồng bộ giữa Máy Nhà & Máy Công Ty**  
> Cập nhật lần cuối: **03/11/2026** - Phiên bản hệ thống: **v2.2.6**

---

## 1. TỔNG QUAN HỆ THỐNG & CÁC THIẾT BỊ

| Thành phần | Địa chỉ / Thiết bị | Tài khoản / Cổng | Vai trò & Trạng thái |
| :--- | :--- | :--- | :--- |
| **Máy chủ Armbian** | `10.0.0.222:22` | `root` / `armbian` | Server trung tâm chạy dịch vụ udpxy, web remote, và sports updater. |
| **Dịch vụ udpxy** | `10.0.0.222:4022` | HTTP Proxy | Chuyển đổi Multicast UDP/RTP sang HTTP TS. Đã cấu hình `-M 30` (gia hạn IGMP) & route `224.0.0.0/4`. |
| **Dịch vụ Web Remote** | `10.0.0.222:8080` | HTTP / SSE | Điều khiển TV từ xa qua web di động, Quản lý Playlist M3U, LAN Hub cache dữ liệu. Service: `iptv-web-remote.service`. |
| **Sports Updater** | `10.0.0.222` | Python 3 / cron 15m | Cào lịch thi đấu trực tiếp, đẩy lên GitHub (`thethao-data.json`, `thethao.m3u`) và lưu cục bộ tại `/opt/iptv-sports/data/`. |
| **Samsung Smart TV** | `10.0.0.9:26101` | Tizen 5.5 (2020) | Model: UA55TU8500KXXV (8 Series 55"). DUID: `7XCGUU3YN4RCE`. Developer Mode: `10.0.0.222`. Đã cài chứng chỉ PFX vĩnh viễn trên Armbian. |
| **Ứng dụng Android TV** | Android TV App | Kotlin / ExoPlayer | Giao diện FPT Play Box G2 3 cột. Build APK với tên `TV Play.apk`. |

---

## 2. NGUYÊN TẮC BẮT BUỘC (RULES IN GEMINI.MD)

1. **Quy trình thay đổi code & build APK:**
   - **CHỈ sửa code và giải thích trước**.
   - **TUYỆT ĐỐI KHÔNG** tự ý kích hoạt build APK, GitHub Actions hoặc tạo GitHub Release.
   - **Chỉ build APK khi người dùng ra lệnh cụ thể** (ví dụ: *"hãy build apk"*, *"tạo release"*, *"build đi"*).
   - Tên file APK release trên GitHub luôn là: **`TV Play.apk`** (không dùng `app-debug.apk`).

2. **Quy trình cập nhật mã nguồn trên Armbian:**
   - Khi cập nhật `sports_updater.py`: tự động đẩy file lên `/opt/iptv-sports/sports_updater.py` và chạy `systemctl start sports-updater.service`.
   - Khi cập nhật `web_remote.py`: tự động đẩy file lên `/opt/iptv-sports/web_remote.py` và chạy `systemctl restart iptv-web-remote.service`.

3. **Quy trình cài đặt ứng dụng lên Samsung Tizen TV (10.0.0.9):**
   - Đóng gói file `.wgt` từ thư mục `tizen_tvplay/` thành file `TVplay.wgt`.
   - Đẩy file `TVplay.wgt` lên Armbian tại thư mục `/opt/samsung/TVplay.wgt`.
   - Chạy lệnh cài đặt và mở app qua công cụ đã tích hợp sẵn chứng chỉ vĩnh viễn trên Armbian:
     ```bash
     /opt/samsung/apps2samsung-cli install /opt/samsung/TVplay.wgt --tv 10.0.0.9 --yes
     /opt/samsung/apps2samsung-cli launch TVPlay0001.TVPlay --tv 10.0.0.9
     ```
   - **Lưu ý:** Lệnh `install` chạy ngầm 100% không ảnh hưởng tới người đang xem YouTube trên TV. Chỉ lệnh `launch` mới chuyển màn hình sang TV Play.

---

## 3. TIẾN ĐỘ & CÁC CÔNG VIỆC ĐÃ HOÀN TẤT (CẬP NHẬT v2.2.6)

### A. Quản lý Playlist trên Web Remote (`web_remote.py` & Armbian 10.0.0.222:8080)
- **Tab 📑 "Danh Sách" (Playlists) trên Web Remote:**
  - **Dán Link URL M3U:** Cho phép thêm link M3U mới từ điện thoại, lưu vào `/opt/iptv-sports/data/playlists.json`.
  - **Tải File M3U Lên TV:** Hỗ trợ chọn file `.m3u`, `.m3u8`, `.txt` trực tiếp từ bộ nhớ điện thoại/máy tính qua Wi-Fi. File được lưu tại `/opt/iptv-sports/data/custom_<id>.m3u` và cấp URL LAN.
  - **Quản lý danh sách:** Thẻ hiển thị tên, URL, nhãn xanh `✓ ĐANG PHÁT`, nút **Chọn** và nút **Xóa** (tự động dọn dẹp file local nếu có).
- **Bộ API Backend:**
  - `GET /api/playlists`: Trả về danh sách playlist hiện có.
  - `POST /api/playlist/add`: Thêm playlist mới qua URL.
  - `POST /api/playlist/upload`: Nhận nội dung file tải lên và lưu trữ cục bộ.
  - `POST /api/playlist/select`: Chọn playlist đang phát, đồng thời làm mới danh sách kênh trên tab "Kênh TV".
  - `POST /api/playlist/delete`: Xóa playlist và dọn dẹp file local.
- **Đồng bộ thời gian thực SSE (Server-Sent Events) `/events`:**
  - Bắn sự kiện `playlist_select` và `sync_playlists` để TV Samsung tự động chuyển nguồn phát ngay tức khắc khi có thao tác trên điện thoại.
- **Khắc phục lỗi đơ web remote:** Chuyển toàn bộ các event handler inline sang cơ chế index (`onChannelClick`, `onSportsSourceClick`, `onSelectPlaylistClick`, `onDeletePlaylistClick`) giúp mã JS không bao giờ bị lỗi cú pháp ký tự nháy `\'`.

### B. Phiên bản Samsung Tizen TV (`tizen_tvplay/` v2.2.6)
- **Khởi động siêu tốc (< 0.5s):** Áp dụng kiến trúc Offline-First Cache với `localStorage`, nạp danh sách kênh và phát luồng cuối ngay tức thì, tải ngầm cập nhật sau.
- **Tích hợp đồng bộ Web Remote:** Lắng nghe sự kiện `playlist_select` từ SSE Armbian để tự động đổi playlist mà không cần dùng remote vật lý.
- **Logo giải đấu & Bảng xếp hạng:**
  - Nhúng trực tiếp 11 Base64 Logo giải đấu chuẩn SVG/PNG (LaLiga, Premier League, Serie A, Champions League, V-League, v.v.).
  - Hiển thị bảng xếp hạng LaLiga, Ngoại Hạng Anh, Serie A ở cột thông tin bên phải trận đấu thể thao.
- **Tối ưu CPU Samsung TV 2020:** 
  - Trì hoãn nạp EPG 5 giây sau khi mở ứng dụng.
  - Lọc giới hạn chương trình EPG trong khung `[-6h, +18h]`.
  - Giảm thời gian đệm `INITIAL_BUFFERING_TIME` trên AVPlay xuống `500ms` cho luồng udpxy nội mạng và `1000ms` cho Internet.

### C. Công cụ xem trước trên máy tính (`preview_tizen.py`)
- Khởi chạy web server xem trước giao diện Tizen trên Windows.
- Tự động dò tìm cổng bắt đầu từ `8150` bằng hàm `is_port_bindable()` để tránh đụng độ dải cổng `8041 - 8140` bị Windows Hyper-V/NAT chiếm quyền (`WinError 10013`).

---

## 4. HƯỚNG DẪN TIẾP TỤC CÔNG VIỆC TRÊN MÁY KHÁC (NHÀ <-> CÔNG TY)

Khi chuyển sang máy mới (hoặc mở cuộc trò chuyện mới):
1. **Kiểm tra file chat:** Chạy `.\sync_chat.ps1 -Action Import` (nếu muốn khôi phục đoạn chat trước đó).
2. **Kích hoạt nhanh ngữ cảnh cho Agent:** Người dùng chỉ cần gõ:
   > *"Đọc file PROJECT_CONTEXT.md và tiếp tục công việc"*
   Agent sẽ tự động đọc hiểu toàn bộ trạng thái, nguyên tắc làm việc và tiếp tục công việc một cách chính xác nhất.
