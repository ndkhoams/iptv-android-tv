# HƯỚNG DẪN HỆ THỐNG MÁY CHỦ ARMBIAN & TRIỂN KHAI SAMSUNG TIZEN TV

> **Tài liệu dành cho lập trình viên & các AI Agent:**  
> Tài liệu này tổng hợp toàn bộ cấu hình, dịch vụ đang chạy trên máy chủ Armbian (`10.0.0.222`), cách thức đóng gói - cài đặt ứng dụng lên TV Samsung (`10.0.0.9`), và kiến trúc Web Remote / LAN Hub.

---

## 1. THÔNG TIN MÁY CHỦ & THIẾT BỊ

| Thiết bị | Địa chỉ IP | Tài khoản / Mật khẩu | Hệ điều hành / Ghi chú |
| :--- | :--- | :--- | :--- |
| **Máy chủ Armbian** | `10.0.0.222:22` | `root` / `armbian` | Linux aarch64 (Debian/Ubuntu), chạy 24/7 trong mạng LAN |
| **Samsung Smart TV** | `10.0.0.9` | Developer Mode bật | Samsung Series 8 55" (2020), Tizen 5.5, DUID: `7XCGUU3YN4RCE` |
| **Máy tính phát triển** | Mạng nội bộ | Windows 11 | Chạy Antigravity / Python / ADB / Git |

---

## 2. CÁC DỊCH VỤ ĐANG CHẠY TRÊN ARMBIAN

### 2.1. Dịch vụ `udpxy` (Chuyển đổi IPTV Multicast sang Unicast HTTP)
- **Đường dẫn binary:** `/usr/local/bin/udpxy`
- **Cổng lắng nghe:** `4022`
- **Lệnh thực thi:**
  ```bash
  /usr/local/bin/udpxy -T -v -S -a 0.0.0.0 -m 10.0.0.222 -p 4022 -c 20 -B 1Mb -R 32 -H 2 -M 30
  ```
- **Mục đích:** Chuyển đổi các luồng IPTV multicast `udp://@...` hoặc `rtp://...` thành luồng HTTP `http://10.0.0.222:4022/udp/...` để Android TV và Samsung Tizen TV phát mượt mà, không bị giật lag.

---

### 2.2. Dịch vụ `sports-updater` (Cập nhật lịch thi đấu & trực tiếp thể thao)
- **File mã nguồn:** `/opt/iptv-sports/sports_updater.py`
- **Thư mục dữ liệu:** `/opt/iptv-sports/data/`
- **Systemd Service:** `sports-updater.service`
- **Systemd Timer:** `sports-updater.timer` (Tự động kích hoạt mỗi 15 phút một lần)
- **Mục đích:**
  - Tự động cào và chuẩn hóa lịch thi đấu, tỉ số trực tiếp các giải bóng đá lớn (EPL, La Liga, Serie A, Champions League, V-League...).
  - Quản lý trạng thái trận đấu: kiểm tra API để xác định trạng thái kết thúc (FT), hoặc dùng mốc 140 phút sau giờ bắt đầu.
  - Tạo file dữ liệu `thethao-data.json` và playlist trực tiếp M3U.
- **Lệnh điều khiển:**
  ```bash
  # Chạy thử thủ công ngay lập tức:
  systemctl start sports-updater.service
  # Xem log hoạt động:
  journalctl -u sports-updater.service -n 50 --no-pager
  ```

---

### 2.3. Dịch vụ `iptv-web-remote` (Web Remote & LAN Hub Hub)
- **File mã nguồn:** `/opt/iptv-sports/web_remote.py`
- **Systemd Service:** `iptv-web-remote.service`
- **Cổng dịch vụ:** `8080` (`http://10.0.0.222:8080`)
- **Mục đích & Kiến trúc:**
  1. **Mobile Web Remote:** Giao diện điều khiển từ xa responsive cho điện thoại / máy tính qua trình duyệt web:
     - Cụm điều hướng D-pad (Lên, Xuống, Trái, Phải, OK, Back, Menu).
     - Điều chỉnh âm lượng (Vol+, Vol-, Mute).
     - Bàn phím số chuyển kênh nhanh.
     - Chuyển chế độ: TV, Thể thao, Kênh yêu thích.
  2. **Real-time Event Streaming (SSE):** Endpoint `/api/remote_events` phát luồng sự kiện Server-Sent Events. Ứng dụng TV Play trên Tizen kết nối trực tiếp đến endpoint này để nhận lệnh tức thì khi người dùng bấm nút trên điện thoại.
  3. **Đồng bộ trạng thái:** Endpoint `/api/tv_state` nhận thông tin kênh đang phát, số kênh và chế độ từ TV để hiển thị trực tiếp lên Web Remote.
  4. **LAN Hub siêu tốc:** Cung cấp API nội bộ `/api/m3u` và `/api/sports_data` phục vụ playlist và dữ liệu cho các TV trong nhà mà không cần tải từ internet, giúp TV khởi động <0.5s.
- **Lệnh điều khiển:**
  ```bash
  systemctl restart iptv-web-remote.service
  systemctl status iptv-web-remote.service
  journalctl -u iptv-web-remote.service -f
  ```

---

## 3. CÔNG CỤ CÀI ĐẶT LÊN TV SAMSUNG (`apps2samsung-cli`)

Samsung Smart TV chạy hệ điều hành Tizen OS không cho phép cài đặt file APK của Android. Thay vào đó, ứng dụng Tizen được đóng gói thành file **`.wgt`** (Web Package Widget).

- **Thư mục cài đặt trên Armbian:** `/opt/samsung/`
- **Binary:** `/opt/samsung/apps2samsung-cli` (đã được symlink ra `/usr/local/bin/apps2samsung-cli`)
- **Chứng chỉ Samsung (Public Certificate / PFX):**
  - Đã được đăng ký và ký thành công qua tài khoản Samsung của chủ sở hữu vào ngày 03/11/2026.
  - Chứng chỉ đã được lưu cache vĩnh viễn trên Armbian cho TV có DUID `7XCGUU3YN4RCE`.
  - **Khi cài đặt các bản build mới, hoàn toàn KHÔNG CẦN đăng nhập lại tài khoản Samsung!**

### Các kịch bản có sẵn trên Armbian:
- Kiểm tra kết nối tới TV:
  ```bash
  /opt/samsung/check_tv.sh 10.0.0.9
  ```
- Ký và cài đặt file `/opt/samsung/TVplay.wgt` lên TV:
  ```bash
  /opt/samsung/install_tvplay.sh 10.0.0.9
  ```
- Hoặc dùng trực tiếp lệnh của CLI:
  ```bash
  /opt/samsung/apps2samsung-cli install /opt/samsung/TVplay.wgt --tv 10.0.0.9 --yes
  /opt/samsung/apps2samsung-cli launch TVPlay0001.TVPlay --tv 10.0.0.9
  ```

---

## 4. QUY TRÌNH BUILD & CÀI ĐẶT TVPLAY.WGT DÀNH CHO AGENT

Bất cứ khi nào Agent chỉnh sửa code trong thư mục `tizen_tvplay/`, hãy thực hiện đúng quy trình sau:

### Bước 1: Kiểm tra cú pháp JavaScript
```powershell
node -c tizen_tvplay/js/app.js
```

### Bước 2: Đóng gói lại file `TVplay.wgt` (chuẩn ZIP)
```python
import os, zipfile

wgt_path = 'TVplay.wgt'
src_dir = 'tizen_tvplay'
if os.path.exists(wgt_path):
    os.remove(wgt_path)

with zipfile.ZipFile(wgt_path, 'w', zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk(src_dir):
        for f in files:
            full_path = os.path.join(root, f)
            arcname = os.path.relpath(full_path, src_dir)
            zf.write(full_path, arcname)
```

### Bước 3: Đẩy file `TVplay.wgt` lên Armbian & Cài đặt vào TV qua Python Paramiko
```python
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('10.0.0.222', username='root', password='armbian', timeout=10)

# 1. Upload file wgt lên Armbian
sftp = ssh.open_sftp()
sftp.put('TVplay.wgt', '/opt/samsung/TVplay.wgt')
sftp.close()

# 2. Ký và cài đặt lên TV Samsung (10.0.0.9)
cmd_install = '/opt/samsung/apps2samsung-cli install /opt/samsung/TVplay.wgt --tv 10.0.0.9 --yes'
stdin, stdout, stderr = ssh.exec_command(cmd_install)
print(stdout.read().decode('utf-8', errors='replace'))

# 3. Khởi chạy ứng dụng trên TV
cmd_launch = '/opt/samsung/apps2samsung-cli launch TVPlay0001.TVPlay --tv 10.0.0.9'
stdin, stdout, stderr = ssh.exec_command(cmd_launch)
print(stdout.read().decode('utf-8', errors='replace'))

ssh.close()
```

---

## 5. QUY TRÌNH ĐỒNG BỘ MÃ NGUỒN LÊN ARMBIAN

Khi sửa đổi các file kịch bản dịch vụ trên máy chủ:

1. **Khi cập nhật `sports_updater.py`:**
   - Đẩy file lên `/opt/iptv-sports/sports_updater.py`.
   - Khởi chạy dịch vụ test: `systemctl start sports-updater.service`.

2. **Khi cập nhật `web_remote.py`:**
   - Đẩy file lên `/opt/iptv-sports/web_remote.py`.
   - Khởi động lại dịch vụ: `systemctl restart iptv-web-remote.service`.

---

## 6. QUY TẮC BẮT BUỘC (CRITICAL CONSTRAINTS)
1. **TUYỆT ĐỐI KHÔNG** tự ý kích hoạt GitHub Release hoặc tự ý build APK của Android TV trừ khi người dùng ra lệnh cụ thể bằng lời (ví dụ: "build apk", "tạo release").
2. Mọi thay đổi mã nguồn phải được kiểm tra cú pháp và đẩy lên nhánh `main` của GitHub repo `ndkhoams/iptv-android-tv`.
