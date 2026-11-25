# Hướng Dẫn Build APK Tự Động Bằng GitHub Actions (CI/CD)

Tài liệu này hướng dẫn chi tiết cách cấu hình, vận hành và quản lý luồng tự động biên dịch (CI/CD) ứng dụng Android (`TV Play.apk` và `TV Remote.apk`) trên nền tảng đám mây **GitHub Actions**.

Với giải pháp này, bạn **không cần cài đặt Android Studio, Android SDK hay Java (JDK)** nặng nề trên máy tính cá nhân. Mọi quá trình tải thư viện, biên dịch mã nguồn và đóng gói APK đều được thực hiện tự động và miễn phí trên máy chủ Ubuntu của GitHub.

---

## 1. Nguyên lý hoạt động tổng quan

```
[Máy tính lập trình (Windows / Mac)]
      │
      │  git push tag v2.1.37 (hoặc push main)
      ▼
[GitHub Repository (ndkhoams/iptv-android-tv)]
      │
      │  Kích hoạt workflow .github/workflows/build.yml
      ▼
[GitHub Cloud Runner (Ubuntu 22.04 LTS)]
      ├─ 1. Tải mã nguồn (Checkout code)
      ├─ 2. Cài đặt Java 17 Temurin & Gradle
      ├─ 3. Chấp thuận bản quyền Android SDK (Licenses)
      ├─ 4. Biên dịch: ./gradlew assembleDebug
      ├─ 5. Đổi tên file chuẩn: "TV Play.apk" & "TV Remote.apk"
      └─ 6. Tải file lên GitHub Release / Artifacts
      │
      ▼
[Trang Tải Về Cho Người Dùng]
      https://github.com/ndkhoams/iptv-android-tv/releases
```

---

## 2. File cấu hình Workflow (`.github/workflows/build.yml`)

File cấu hình được đặt tại thư mục: `.github/workflows/build.yml`

```yaml
name: Build Android TV APK

on:
  push:
    branches: [ main, master ]
    tags:
      - 'v*'
  pull_request:
    branches: [ main, master ]
  workflow_dispatch:  # Cho phép bấm nút "Run workflow" thủ công trên web

permissions:
  contents: write  # Bắt buộc để Action có quyền tạo Release và upload file APK

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - name: Checkout code
      uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Setup Gradle
      uses: gradle/actions/setup-gradle@v4

    - name: Accept Android SDK Licenses
      run: yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses || true

    - name: Grant execute permission for gradlew
      run: chmod +x gradlew

    - name: Build Debug APK
      env:
        GRADLE_OPTS: "-Dorg.gradle.internal.http.socketTimeout=120000 -Dorg.gradle.internal.http.connectionTimeout=120000"
      run: |
        ./gradlew assembleDebug --stacktrace --no-daemon 2>&1 | tee build_log.txt
        echo "==================== BUILD LOG START ===================="
        cat build_log.txt
        echo "==================== BUILD LOG END ======================"
        
        # Đảm bảo file APK app chính đã được tạo
        if [ ! -f app/build/outputs/apk/debug/app-debug.apk ]; then
          echo "FAIL: app-debug.apk was not generated"
          exit 1
        fi
        cp app/build/outputs/apk/debug/app-debug.apk "app/build/outputs/apk/debug/TV Play.apk"
        
        # Tự động tìm và đổi tên APK module Remote (nếu có)
        for f in remote/build/outputs/apk/debug/*.apk; do
          if [ -f "$f" ]; then
            cp "$f" "remote/build/outputs/apk/debug/TV Remote.apk"
            break
          fi
        done

    # 1. Lưu bản lưu trữ TV Play trên Action Run (tồn tại 90 ngày)
    - name: Upload TV Play APK Artifact
      uses: actions/upload-artifact@v4
      with:
        name: TV-Play-debug
        path: "app/build/outputs/apk/debug/TV Play.apk"

    # 2. Lưu bản lưu trữ TV Remote trên Action Run
    - name: Upload TV Remote APK Artifact
      if: hashFiles('remote/build/outputs/apk/debug/TV Remote.apk') != ''
      uses: actions/upload-artifact@v4
      with:
        name: TV-Remote-debug
        path: "remote/build/outputs/apk/debug/TV Remote.apk"

    # 3. Tự động xuất bản Release công khai khi push Tag (ví dụ: v2.1.37)
    - name: Publish Official Release
      uses: softprops/action-gh-release@v2
      if: startsWith(github.ref, 'refs/tags/v')
      with:
        tag_name: ${{ github.ref_name }}
        name: "${{ github.ref_name }} - TV Play & TV Remote"
        body: |
          ### Bản cập nhật ${{ github.ref_name }}:
          - Ứng dụng Android TV: **TV Play.apk**
          - Ứng dụng điều khiển từ xa: **TV Remote.apk**
        files: |
          app/build/outputs/apk/debug/TV Play.apk
          remote/build/outputs/apk/debug/TV Remote.apk
        prerelease: false
        make_latest: true
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

---

## 3. Ba cách kích hoạt Build APK

### Cách 1: Build & Xuất bản Release chính thức bằng Git Tag (Khuyên Dùng)

Đây là cách chuẩn nhất khi bạn muốn phát hành một phiên bản hoàn chỉnh để tải trực tiếp file APK về điện thoại / TV.

1. Tăng số phiên bản trong `app/build.gradle` (nếu cần):
   ```groovy
   versionCode 95
   versionName "2.1.37"
   ```
2. Thực hiện commit thay đổi:
   ```bash
   git add -A
   git commit -m "chore(release): bump version to v2.1.37"
   ```
3. Tạo tag phiên bản bắt đầu bằng chữ `v`:
   ```bash
   git tag v2.1.37
   ```
4. Đẩy commit và tag lên GitHub:
   ```bash
   git push origin main v2.1.37
   ```
5. **Kết quả:** GitHub Actions sẽ tự động phát hiện tag `v2.1.37`, biên dịch mã nguồn và đính kèm 2 file **`TV Play.apk`** và **`TV Remote.apk`** trực tiếp tại trang:
   `https://github.com/<owner>/<repo>/releases`

---

### Cách 2: Build kiểm tra khi Push code lên nhánh `main`

Mỗi khi bạn commit code và push thông thường lên `main`:
```bash
git push origin main
```
- GitHub Actions vẫn tự động build để kiểm tra lỗi biên dịch.
- Sau khi build thành công, bạn vào mục **Actions** $\rightarrow$ chọn lần chạy mới nhất $\rightarrow$ kéo xuống phần **Artifacts** để tải file zip chứa APK về máy.

---

### Cách 3: Bấm nút "Run workflow" thủ công trên giao diện Web

Nếu không muốn tạo tag hay sửa code, bạn có thể chạy build bất kỳ lúc nào:

1. Mở trình duyệt vào trang GitHub Repository.
2. Bấm vào tab **Actions** ở menu trên cùng.
3. Ở danh sách bên trái, chọn workflow **Build Android TV APK**.
4. Bấm vào nút màu xám **Run workflow** (ở góc trên bên phải bảng danh sách).
5. Chọn nhánh `main` và bấm nút xanh **Run workflow**.

---

## 4. Cấu hình quyền hạn (Permissions) bắt buộc trên GitHub

Để GitHub Actions có quyền tự động tạo Release và đính kèm file APK, bạn cần đảm bảo quyền ghi đã được bật trong kho lưu trữ:

1. Truy cập: `https://github.com/<owner>/<repo>/settings/actions`
2. Kéo xuống mục **Workflow permissions**.
3. Chọn tùy chọn: **Read and write permissions** *(Cho phép Actions tạo commit, tag, release)*.
4. Tích chọn ô: **Allow GitHub Actions to create and approve pull requests**.
5. Bấm nút **Save**.

---

## 5. Xử lý các sự cố thường gặp (Troubleshooting)

### 1. Lỗi `Resource not accessible by integration` hoặc `403 Forbidden` ở bước Release
- **Nguyên nhân:** Token của GitHub Actions chưa được cấp quyền ghi vào repository.
- **Cách khắc phục:** Kiểm tra lại cài đặt ở **Mục 4** (`Workflow permissions` $\rightarrow$ `Read and write permissions`) và kiểm tra xem trong file `build.yml` đã có khai báo `permissions: contents: write` chưa.

### 2. Lỗi `attribute android:layout_maxWidth not found`
- **Nguyên nhân:** Dùng thuộc tính không thuộc bộ thuộc tính chuẩn của View/Layout (ví dụ `android:layout_maxWidth` không tồn tại trong `LinearLayout`).
- **Cách khắc phục:** Dùng `android:layout_width="match_parent"` kèm padding hoặc dùng `ConstraintLayout` với `app:layout_constraintWidth_max`.

### 3. Lỗi `resource drawable/ic_launcher_* not found`
- **Nguyên nhân:** Trong file adaptive icon `mipmap-anydpi-v26/ic_launcher.xml` có tham chiếu đến `@drawable/ic_launcher_background` và `@drawable/ic_launcher_foreground`, nhưng các file này chưa được tạo trong thư mục `res/drawable/`.
- **Cách khắc phục:** Tạo 2 file vector drawable tương ứng trong `res/drawable/`.

### 4. Lỗi mạng tải Gradle Wrapper (`services.gradle.org` timeout 504)
- **Nguyên nhân:** Máy chủ Gradle chính thức bị quá tải hoặc chặn kết nối.
- **Cách khắc phục:** Trong bước `Setup Gradle` của file `build.yml`, sử dụng action chính thức `gradle/actions/setup-gradle@v4` và cấu hình tăng timeout mạng trong `GRADLE_OPTS`.
