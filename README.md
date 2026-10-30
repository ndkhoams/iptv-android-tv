# Ứng Dụng IPTV Player Cho Android TV 5.0+ (Lollipop, API 21)

Dự án ứng dụng xem truyền hình IPTV được thiết kế và tối ưu đặc thù cho các dòng **Android TV / Android TV Box chạy Android 5.0, 5.1 (API Level 21/22)** trở lên, tương thích tốt với điều khiển từ xa (Remote / D-Pad).

---

## 🌟 Tính Năng Nổi Bật

1. **Tương thích Android 5.0 (API 21+)**:
   - `minSdkVersion 21`: Chạy ổn định trên các thế hệ TV Box cũ (RAM 1GB, chip lõi tứ đời cũ) mà các ứng dụng IPTV mới thường từ chối cài đặt.
   - Hỗ trợ toàn diện giao thức HTTP Cleartext (`usesCleartextTraffic="true"` và `network_security_config.xml`) giúp xem được các luồng IPTV HTTP đời cũ không có HTTPS.
2. **Trình phát mạnh mẽ (ExoPlayer 2.19.1)**:
   - Hỗ trợ chuẩn HLS (`.m3u8`), MPEG-TS (`.ts`), MP4, RTSP.
   - Thiết lập User-Agent tiêu chuẩn tránh việc nhà mạng hoặc server stream chặn kết nối từ Android TV.
   - Tự động bắt lỗi stream, hiển thị gợi ý chuyển kênh khi luồng tín hiệu bị nghẽn hoặc gián đoạn.
3. **Giao diện TV Leanback thân thiện với Remote (D-Pad)**:
   - Điều hướng trực quan: Phím điều hướng Lên / Xuống / Trái / Phải có viền phát sáng và hiệu ứng phóng to rõ ràng trên màn hình lớn.
   - Chế độ xem Fullscreen: Danh sách kênh (OSD) tự động ẩn sau vài giây không thao tác.
   - Chuyển kênh siêu nhanh (Zapping): Khi đang xem toàn màn hình, bấm **▲ Lên** / **▼ Xuống** trên Remote để chuyển kênh kế tiếp tương tự TV truyền thống.
   - Bấm **OK / Enter**: Bật bảng danh mục và danh sách kênh.
   - Bấm **Menu**: Mở cửa sổ cấu hình đường link M3U.
4. **Bộ Phân Tích M3U / M3U8 Nhanh & Nhẹ**:
   - Tự động tách danh mục (`group-title`), tên kênh, logo (`tvg-logo`), số thứ tự kênh.
   - Tự động lưu cache offline: Nếu mở app lúc chưa có mạng, danh sách vẫn load ngay từ bộ nhớ đệm.
   - Tự động ghi nhớ kênh vừa xem gần nhất để mở lại ngay khi khởi động app.

---

## 📁 Cấu Trúc Dự Án

```
AndroidTV-IPTV/
├── app/
│   ├── build.gradle                # Cấu hình compileSdk 34, minSdk 21, ExoPlayer, Glide, OkHttp
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml # Cấu hình LEANBACK_LAUNCHER, Banner TV, quyền Internet
│           ├── java/com/iptv/tvapp/
│           │   ├── data/
│           │   │   ├── PlaylistManager.kt      # Tải & lưu cache playlist M3U
│           │   │   └── PreferencesHelper.kt    # Lưu link M3U & kênh vừa xem
│           │   ├── model/
│           │   │   ├── Category.kt             # Nhóm kênh (VTV, HTV, Thể Thao...)
│           │   │   └── Channel.kt              # Đối tượng kênh (Tên, Logo, Stream URL)
│           │   ├── parser/
│           │   │   └── M3UParser.kt            # Xử lý cú pháp #EXTINF và link stream
│           │   └── ui/
│           │       ├── MainActivity.kt         # Màn hình chính phát video & điều khiển TV
│           │       ├── adapter/
│           │       │   ├── CategoryAdapter.kt  # Danh sách danh mục bên trái
│           │       │   └── ChannelAdapter.kt   # Danh sách kênh bên phải
│           │       └── dialog/
│           │           └── PlaylistDialog.kt   # Hộp thoại nhập link M3U
│           └── res/
│               ├── drawable/       # Các selector hiệu ứng focus cho Remote TV
│               ├── layout/         # Layout TV chuẩn 16:9
│               ├── values/         # Màu sắc dark theme cho TV, chuỗi ngôn ngữ
│               └── xml/            # network_security_config.xml
├── build.gradle                    # Top-level build config
├── settings.gradle
└── README.md
```

---

## 🛠 Hướng Dẫn Biên Dịch File APK

### Cách 1: Sử dụng Android Studio
1. Mở **Android Studio** (bản Electric Eel, Flamingo, Hedgehog hoặc mới hơn).
2. Chọn **Open** -> Trỏ tới thư mục dự án này (`d:\Dropbox\VS\antigr`).
3. Chờ Gradle đồng bộ (Sync Project with Gradle Files).
4. Vào menu **Build** -> **Build Bundle(s) / APK(s)** -> **Build APK(s)**.
5. File APK xuất ra tại thư mục: `app/build/outputs/apk/debug/app-debug.apk`.

### Cách 2: Sử dụng dòng lệnh (Terminal / Command Prompt)
Yêu cầu đã cài đặt JDK 17:
```bash
./gradlew assembleDebug
```
Hoặc trên Windows:
```cmd
gradlew.bat assembleDebug
```

---

## 📺 Hướng Dẫn Cài Đặt Lên Android TV 5.0

### Cách 1: Cài đặt qua USB (Dễ nhất)
1. Copy file `app-debug.apk` vừa build vào USB (định dạng FAT32).
2. Cắm USB vào cổng USB của Android TV / Android Box.
3. Trên Android TV, vào **Cài đặt (Settings)** -> **Bảo mật & Hạn chế** -> Bật **Nguồn không xác định (Unknown Sources)**.
4. Mở ứng dụng quản lý file (File Manager / ES File Explorer) trên TV, chọn file APK và nhấn **Cài đặt**.

### Cách 2: Cài đặt qua mạng Wi-Fi bằng ADB
1. Trên TV, vào **Cài đặt** -> **Giới thiệu** -> Nhấn liên tục 7 lần vào dòng **Số phiên bản build** để bật Chế độ nhà phát triển.
2. Bật **Gỡ lỗi USB / ADB Debugging**.
3. Xem địa chỉ IP của TV (ví dụ: `192.168.1.50`).
4. Trên máy tính gõ lệnh:
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 🎮 Hướng Dẫn Sử Dụng Điều Khiển (Remote Control)

| Phím Trên Remote | Hành Động |
| :--- | :--- |
| **OK / Enter** | Mở bảng danh mục & danh sách kênh (nếu đang ẩn), hoặc phát kênh đang chọn |
| **▲ Lên / ▼ Xuống** (khi xem Full) | Chuyển ngay kênh trước / kênh kế tiếp (Zapping) |
| **▲ Lên / ▼ Xuống** (trong Menu) | Di chuyển chọn danh mục hoặc chọn kênh trong danh sách |
| **◄ Trái / ► Phải** | Di chuyển tiêu điểm qua lại giữa cột Danh Mục và cột Kênh |
| **Menu** | Mở hộp thoại nhập đường dẫn Playlist M3U mới |
| **Quay Lại (Back)** | Ẩn bảng danh sách kênh quay về màn hình xem; Nhấn 2 lần để thoát ứng dụng |

---

## 🔗 Nguồn Link M3U Tham Khảo
- Trong ứng dụng đã tích hợp sẵn 2 nút chọn nhanh:
  - **Kênh Việt Nam**: Tải danh sách các kênh truyền hình công khai miễn phí tại VN từ iptv-org.
  - **Kênh Tin Tức Quốc Tế**: Tải danh sách kênh thời sự quốc tế.
- Bạn cũng có thể dán bất kỳ link playlist M3U/M3U8 nào từ nhà cung cấp dịch vụ IPTV của mình vào ô Cài Đặt.
