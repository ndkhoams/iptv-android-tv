# Ứng Dụng IPTV Player Cho Android TV 5.0+ (Lollipop, API 21)

Dự án ứng dụng xem truyền hình IPTV được thiết kế và tối ưu đặc thù cho các dòng **Android TV / Android TV Box chạy Android 5.0, 5.1 (API Level 21/22)** trở lên, tương thích tốt với điều khiển từ xa (Remote / D-Pad), hỗ trợ **Quản lý / Điều khiển từ Điện thoại qua Web** và **Tối ưu hóa luồng UDP Multicast (FPT / VNPT / Viettel) chống sọc ngang 1080i**.

---

## 🌟 Các Tính Năng Nổi Bật

1. **⚡ Tối Ưu Hóa Luồng UDP Multicast & Chống Sọc Ngang (Deinterlace 1080i)**:
   - **Wi-Fi Multicast Lock (`CHANGE_WIFI_MULTICAST_STATE`)**: Kích hoạt khóa Multicast phần cứng, ngăn chip Wi-Fi của Android TV lọc bỏ gói tin UDP dẫn đến mất gói, rách hình và vỡ pixel.
   - **Tối ưu bộ giải mã & Deinterlace**: Bật chế độ deinterlacing và `setEnableDecoderFallback(true)` để khử răng cưa/sọc ngang của luồng phát truyền hình 1080i (như VTV1 HD của FPT).
   - **Bộ đệm UDP chống rớt gói**: Mở rộng dung lượng đệm nhận luồng TS (15s - 60s) để xử lý hoàn hảo hiện tượng giật lag khi mạng biến động.
   - **Tối ưu bộ phân tích MPEG-TS**: Tự động nhận diện Access Units, bỏ qua thông tin splice lỗi và xử lý khung hình Non-IDR để luồng video luôn mượt mà.
   - **Hỗ trợ định dạng link UDP**: Tự động nhận diện và chuẩn hóa các dạng link `udp://@225.x.x.x:port`, `rtp://@225.x.x.x:port`, `udp://...` và luồng qua Udpxy `http://.../udp/...`.

2. **📱 Điều Khiển & Quản Lý Từ Điện Thoại (Web Remote Dashboard)**:
   - Tích hợp sẵn Web Server mini ngay trong ứng dụng (chạy cổng `8080`).
   - Mở trình duyệt trên điện thoại (cùng mạng Wi-Fi với TV), gõ: `http://<IP_TV>:8080` (hiển thị trên thanh OSD của TV).
   - Remote D-Pad cảm ứng, tìm kiếm kênh, bấm phát trên TV tức thì, dán link M3U siêu nhanh.

3. **Quản Lý Nhiều Danh Sách M3U (Multi-Playlist)**:
   - Thêm không giới hạn các link M3U / M3U8 từ nhiều nguồn khác nhau.
   - Dễ dàng chuyển đổi qua lại chỉ với 1 thao tác.

4. **Kênh Yêu Thích (Favorites) ★ & Kênh Mặc Định Khởi Động**:
   - Nhấn giữ phím **OK** trên bất kỳ kênh nào để Thêm/Xóa mục Yêu Thích hoặc Đặt làm kênh mở mặc định.
   - Tự động mở ứng dụng ngay khi bật TV (`BootReceiver`).

---

## 🎮 Hướng Dẫn Thao Tác Remote TV

| Phím Trên Remote | Hành Động |
| :--- | :--- |
| **Nhấn OK / Enter** | Mở bảng danh sách kênh (nếu đang xem full); hoặc phát kênh đang chọn |
| **Nhấn GIỮ OK** (trên kênh) | Mở menu tùy chọn: **Thêm/Bỏ Yêu Thích ★** hoặc **Đặt làm Kênh Mặc Định** |
| **▲ Lên / ▼ Xuống** (khi xem Full) | Chuyển ngay kênh trước / kênh kế tiếp (Zapping như TV truyền thống) |
| **▲ Lên / ▼ Xuống** (trong Menu) | Di chuyển chọn danh mục hoặc chọn kênh trong danh sách |
| **◄ Trái / ► Phải** | Di chuyển tiêu điểm qua lại giữa cột Danh Mục và cột Kênh |
| **Menu** | Mở bảng Cài Đặt (Quản lý nhiều danh sách M3U, Khởi động cùng TV) |
| **Quay Lại (Back)** | Ẩn bảng danh sách kênh quay về màn hình xem; Nhấn 2 lần để thoát ứng dụng |

---

## 🛠 Hướng Dẫn Tải & Cài Đặt File APK

Mã nguồn được liên kết với GitHub Actions để tự động build APK:
1. Truy cập: [https://github.com/ndkhoams/iptv-android-tv/actions](https://github.com/ndkhoams/iptv-android-tv/actions)
2. Chọn lần build mới nhất có dấu tích xanh ✅ **Passed**.
3. Cuộn xuống phần **Artifacts** và tải file **`AndroidTV-IPTV-debug`**.
4. Cài file APK vào Android TV / Box qua USB hoặc lệnh ADB.
