# HỒ SƠ DỰ ÁN & BÀN GIAO NGỮ CẢNH (MASTER CONTEXT HANDOVER)
> **Tài liệu đồng bộ ngữ cảnh làm việc giữa Máy Nhà & Máy Công Ty**  
> Cập nhật lần cuối: **03/11/2026** - Phiên bản hệ thống: **v2.2.4**

---

## 1. TỔNG QUAN HỆ THỐNG & CÁC THIẾT BỊ

| Thành phần | Địa chỉ / Thiết bị | Tài khoản / Cổng | Vai trò & Trạng thái |
| :--- | :--- | :--- | :--- |
| **Máy chủ Armbian** | `10.0.0.222:22` | `root` / `armbian` | Server trung tâm chạy dịch vụ udpxy, web remote, và sports updater. |
| **Dịch vụ udpxy** | `10.0.0.222:4022` | HTTP Proxy | Chuyển đổi Multicast UDP/RTP sang HTTP TS. Đã cấu hình `-M 30` (gia hạn IGMP) & route `224.0.0.0/4`. |
| **Dịch vụ Web Remote** | `10.0.0.222:8080` | HTTP / SSE | Điều khiển TV từ xa qua web, LAN Hub cache dữ liệu M3U và thể thao. Service: `iptv-web-remote.service`. |
| **Sports Updater** | `10.0.0.222` | Python 3 / cron 15m | Cào lịch thi đấu trực tiếp, đẩy lên GitHub (`thethao-data.json`, `thethao.m3u`) và lưu cục bộ tại `/opt/iptv-sports/data/`. |
| **Samsung Smart TV** | `10.0.0.9:26101` | Tizen 5.5 (2020) | Model: UA55TU8500KXXV (8 Series 55"). DUID: `7XCGUU3YN4RCE`. Developer Mode: `10.0.0.222`. |
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

---

## 3. TIẾN ĐỘ & CÁC CÔNG VIỆC ĐÃ HOÀN TẤT GẦN ĐÂY

### A. Tối ưu phiên bản Samsung Tizen (v2.2.4)
- **Khởi động siêu tốc (< 0.5s):** Áp dụng kiến trúc Offline-First Cache với `localStorage`, nạp danh sách kênh và phát luồng cuối ngay tức thì, tải ngầm cập nhật sau.
- **Tối ưu CPU Samsung TV 2020:** 
  - Trì hoãn nạp EPG 5 giây sau khi mở ứng dụng.
  - Lọc giới hạn chương trình EPG trong khung `[-6h, +18h]`.
  - Giảm thời gian đệm `INITIAL_BUFFERING_TIME` trên AVPlay xuống `500ms` cho luồng udpxy nội mạng và `1000ms` cho Internet.
  - Thêm thuộc tính `defer` cho các script phụ (`hls.light.min.js`, `mpegts.min.js`).

### B. Hệ thống Web Remote & LAN Hub trên Armbian 10.0.0.222:8080
- Chạy ngầm 24/7 dưới dịch vụ systemd `iptv-web-remote.service`.
- Giao diện Web Remote tối ưu cho điện thoại: Điều khiển D-Pad, danh sách kênh TV (tìm kiếm + danh mục), trực tiếp thể thao (chọn trận + chọn luồng BLV).
- Đồng bộ thời gian thực qua Server-Sent Events (SSE) `/events`.
- Cung cấp dữ liệu nội mạng LAN siêu tốc: `/data/ktv.m3u` và `/data/thethao-data.json`.

---

## 4. HƯỚNG DẪN TIẾP TỤC CÔNG VIỆC TRÊN MÁY KHÁC

Khi chuyển sang máy mới (hoặc mở cuộc trò chuyện mới):
1. **Kiểm tra file chat:** Chạy `.\sync_chat.ps1 -Action Import` (nếu muốn khôi phục đoạn chat trước đó).
2. **Kích hoạt nhanh ngữ cảnh:** Người dùng chỉ cần gõ:
   > *"Đọc file PROJECT_CONTEXT.md và tiếp tục công việc"*
   Agent sẽ tự động nắm bắt toàn bộ trạng thái mà không cần người dùng phải giải thích lại bất cứ thông tin nào.

