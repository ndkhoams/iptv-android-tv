# Ứng Dụng IPTV Player Cho Android TV 5.0+ (Lollipop, API 21)

Dự án ứng dụng xem truyền hình IPTV được thiết kế và tối ưu đặc thù cho các dòng **Android TV / Android TV Box chạy Android 5.0, 5.1 (API Level 21/22)** trở lên, tương thích tốt với điều khiển từ xa (Remote / D-Pad).

---

## 🌟 Các Tính Năng Mới Bổ Sung

1. **Quản Lý Nhiều Danh Sách M3U (Multi-Playlist)**:
   - Thêm không giới hạn các link M3U / M3U8 từ nhiều nguồn khác nhau (Kênh Trong Nước, Kênh Thể Thao, Kênh Quốc Tế,...).
   - Đặt tên gợi nhớ cho từng danh sách, dễ dàng chuyển đổi qua lại chỉ với 1 click.
   - Quản lý và xóa danh sách không còn sử dụng.
2. **Kênh Yêu Thích (Favorites) ★**:
   - Nhấn giữ phím **OK** trên bất kỳ kênh nào để Thêm hoặc Xóa khỏi mục Yêu Thích.
   - Nhóm danh mục **★ KÊNH YÊU THÍCH** tự động xuất hiện ở vị trí đầu tiên của bảng danh mục để truy cập nhanh chóng.
3. **Kênh Mặc Định Khi Khởi Động**:
   - Lựa chọn linh hoạt giữa:
     - **Vào lại kênh vừa xem gần nhất** (tiếp tục theo dõi chương trình đang dở).
     - **Luôn mở một kênh cố định** (ví dụ: tự động bật ngay VTV1 HD mỗi lần mở app).
   - Thiết lập trực tiếp bằng cách nhấn giữ phím **OK** trên kênh muốn chọn.
4. **Tự Động Mở Ứng Dụng Khi Bật TV (Auto-start on Boot)**:
   - Tích hợp `BootReceiver` tự động kích hoạt app ngay khi Android TV vừa khởi động xong.
   - Tùy chọn BẬT / TẮT dễ dàng trong màn hình Cài Đặt.
5. **Vào Thẳng Kênh Xem Ngay**:
   - Ngay khi mở app (hoặc khi TV khởi động), app lập tức vào thẳng kênh và phát toàn màn hình không cần bấm thêm bất kỳ nút nào.

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
