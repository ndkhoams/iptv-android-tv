# Quy tắc làm việc dự án IPTV Android TV

- **Quy trình thay đổi code & build APK:**
  - Khi người dùng đưa ra yêu cầu tính năng / sửa lỗi / tối ưu: **CHỈ sửa code và giải thích trước**.
  - **TUYỆT ĐỐI KHÔNG** tự ý kích hoạt build APK, GitHub Actions hoặc tạo GitHub Release.
  - **Chỉ build APK khi người dùng ra lệnh cụ thể** (ví dụ: "hãy build apk", "tạo release", "build đi").

- **Quy tắc đặt tên file APK release:**
  - Tên file APK đính kèm khi phát hành release trên GitHub luôn là `TV Play.apk` (thay vì `app-debug.apk`).

- **Cấu hình tự động triển khai `sports_updater.py` lên Armbian:**
  - Địa chỉ máy chủ: `10.0.0.222:22`
  - Tài khoản: `root` / Mật khẩu: `armbian`
  - Thư mục chạy trên Armbian: `/opt/iptv-sports/sports_updater.py`
  - Dịch vụ systemd: `sports-updater.service` & `sports-updater.timer` (tự động chạy định kỳ)
  - Lệnh đẩy file bằng PuTTY pscp:
    ```powershell
    & "C:\Program Files\PuTTY\pscp.exe" -batch -pw armbian -hostkey "ssh-ed25519 255 SHA256:vHTgGqm8MDox9U0qgdvuk/tzd3DgFg48tNczaEK6Vsc" sports_updater.py root@10.0.0.222:/opt/iptv-sports/sports_updater.py
    ```
  - Lệnh kích hoạt chạy thử qua plink:
    ```powershell
    & "C:\Program Files\PuTTY\plink.exe" -ssh -l root -pw armbian -batch -hostkey "ssh-ed25519 255 SHA256:vHTgGqm8MDox9U0qgdvuk/tzd3DgFg48tNczaEK6Vsc" 10.0.0.222 "chmod +x /opt/iptv-sports/sports_updater.py; systemctl start sports-updater.service"
    ```
  - Bất cứ khi nào file `sports_updater.py` được cập nhật, hãy tự động đẩy file lên máy chủ Armbian theo cấu hình trên và chạy thử để xác nhận thành công.
