# Ứng Dụng IPTV Player Cho Android TV 5.0+ (Lollipop, API 21)

Dự án ứng dụng xem truyền hình IPTV được thiết kế và tối ưu đặc thù cho các dòng **Android TV / Android TV Box chạy Android 5.0, 5.1 (API Level 21/22)** trở lên, tương thích tốt với điều khiển từ xa (Remote / D-Pad) và hỗ trợ **Quản lý / Điều khiển từ Điện thoại qua Web**.

---

## 🌟 Các Tính Năng Nổi Bật

1. **📱 Điều Khiển & Quản Lý Từ Điện Thoại (Web Remote Dashboard)**:
   - Tích hợp sẵn Web Server mini ngay trong ứng dụng (chạy cổng `8080`).
   - Mở trình duyệt trên điện thoại (cùng mạng Wi-Fi với TV), gõ địa chỉ: `http://<IP_TV>:8080` (địa chỉ này hiển thị rõ ràng trên thanh trên cùng của app).
   - **Giao diện Web trên điện thoại cho phép**:
     - **Remote cảm ứng**: Phím điều hướng D-Pad (Lên/Xuống/Trái/Phải/OK), Quay lại, Menu, Chuyển kênh.
     - **Dán Link M3U siêu nhanh**: Không còn cảnh phải dùng remote TV gõ từng chữ URL dài dằng dặc; chỉ cần copy link M3U trên điện thoại và dán vào web để lưu thẳng vào TV!
     - **Danh sách kênh & Tìm kiếm**: Xem toàn bộ kênh TV trên màn hình điện thoại, bấm vào kênh nào là TV lập tức phát kênh đó.
     - **Cài đặt khởi động**: Bật/tắt tự mở app khi TV khởi động ngay trên điện thoại.
2. **Quản Lý Nhiều Danh Sách M3U (Multi-Playlist)**:
   - Thêm không giới hạn các link M3U / M3U8 từ nhiều nguồn khác nhau.
   - Dễ dàng chuyển đổi qua lại chỉ với 1 thao tác.
3. **Kênh Yêu Thích (Favorites) ★**:
   - Nhấn giữ phím **OK** trên bất kỳ kênh nào để Thêm hoặc Xóa khỏi mục Yêu Thích.
   - Nhóm danh mục **★ KÊNH YÊU THÍCH** tự động ưu tiên xuất hiện đầu tiên.
4. **Kênh Mặc Định Khi Khởi Động**:
   - Tùy chọn: **Vào lại kênh vừa xem gần nhất** hoặc **Luôn mở một kênh cố định**.
5. **Tự Động Mở App Khi Bật TV (Auto-start on Boot)**:
   - Tích hợp `BootReceiver` tự động kích hoạt app ngay khi Android TV vừa khởi động xong.

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
