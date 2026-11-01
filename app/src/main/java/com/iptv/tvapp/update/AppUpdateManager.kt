package com.iptv.tvapp.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.iptv.tvapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val body: String,
    val apkDownloadUrl: String,
    val apkSize: Long
)

class AppUpdateManager(private val context: Context) {

    companion object {
        private const val GITHUB_LATEST_RELEASE_URL =
            "https://api.github.com/repos/ndkhoams/iptv-android-tv/releases/latest"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getAppVersion(ctx: Context = context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            }
            pInfo.versionName ?: "1.9.28"
        } catch (e: Exception) {
            "1.9.28"
        }
    }

    /**
     * So sánh phiên bản semver: Trả về true nếu remoteVersion lớn hơn currentVersion
     */
    fun isNewerVersion(remoteTag: String, currentVersion: String = getAppVersion()): Boolean {
        try {
            val remoteClean = remoteTag.trim().removePrefix("v").removePrefix("V")
            val currentClean = currentVersion.trim().removePrefix("v").removePrefix("V")
            val rParts = remoteClean.split(".").mapNotNull { it.toIntOrNull() }
            val cParts = currentClean.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * Kiểm tra GitHub Release để lấy thông tin phiên bản mới nhất
     */
    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_LATEST_RELEASE_URL)
                .header("User-Agent", "TVPlay-IPTV-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val jsonString = response.body?.string() ?: return@withContext null
                val json = JSONObject(jsonString)

                val tagName = json.optString("tag_name", "")
                val title = json.optString("name", tagName)
                val body = json.optString("body", "")

                // Tìm asset file .apk (ưu tiên file .apk)
                val assets = json.optJSONArray("assets")
                var downloadUrl = ""
                var apkSize = 0L

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (tagName.isNotBlank() && downloadUrl.isNotBlank()) {
                    return@withContext ReleaseInfo(
                        tagName = tagName,
                        title = title,
                        body = body,
                        apkDownloadUrl = downloadUrl,
                        apkSize = apkSize
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    /**
     * Hiển thị hộp thoại thông báo có phiên bản mới
     */
    fun showUpdateDialog(
        activity: Activity,
        scope: CoroutineScope,
        release: ReleaseInfo
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null)
        val dialog = AlertDialog.Builder(activity, R.style.TvDialogTheme)
            .setView(view)
            .setCancelable(true)
            .create()

        val tvTitle = view.findViewById<TextView>(R.id.tvUpdateTitle)
        val tvVersionBadge = view.findViewById<TextView>(R.id.tvUpdateVersionBadge)
        val tvChangelog = view.findViewById<TextView>(R.id.tvUpdateChangelog)
        val layoutProgress = view.findViewById<View>(R.id.layoutUpdateProgress)
        val progressBar = view.findViewById<ProgressBar>(R.id.pbUpdateProgress)
        val tvProgressStatus = view.findViewById<TextView>(R.id.tvUpdateProgressStatus)
        val btnUpdateNow = view.findViewById<Button>(R.id.btnUpdateNow)
        val btnDismiss = view.findViewById<Button>(R.id.btnUpdateDismiss)

        val currentVer = getAppVersion(activity)
        tvTitle.text = release.title.ifBlank { "Phiên bản mới ${release.tagName}" }
        tvVersionBadge.text = "Hiện tại: v$currentVer ➔ Mới nhất: ${release.tagName}"
        tvChangelog.text = if (release.body.isNotBlank()) release.body else "Bản cập nhật bao gồm các cải tiến hiệu năng và sửa lỗi."

        var downloadJob: Job? = null

        btnUpdateNow.setOnClickListener {
            btnUpdateNow.isEnabled = false
            btnDismiss.text = "Hủy tải"
            layoutProgress.visibility = View.VISIBLE
            progressBar.isIndeterminate = false
            progressBar.progress = 0

            downloadJob = scope.launch(Dispatchers.IO) {
                val downloadedFile = downloadApk(release.apkDownloadUrl) { percent, downloadedBytes, totalBytes ->
                    scope.launch(Dispatchers.Main) {
                        progressBar.progress = percent
                        val mbDownloaded = downloadedBytes / (1024 * 1024)
                        val mbTotal = totalBytes / (1024 * 1024)
                        tvProgressStatus.text = "Đang tải: $percent% ($mbDownloaded MB / $mbTotal MB)..."
                    }
                }

                withContext(Dispatchers.Main) {
                    if (downloadedFile != null && downloadedFile.exists()) {
                        tvProgressStatus.text = "Tải thành công! Đang mở trình cài đặt..."
                        dialog.dismiss()
                        installApk(activity, downloadedFile)
                    } else {
                        btnUpdateNow.isEnabled = true
                        btnDismiss.text = "Đóng"
                        layoutProgress.visibility = View.GONE
                        Toast.makeText(activity, "Tải bản cập nhật thất bại. Vui lòng thử lại!", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnDismiss.setOnClickListener {
            downloadJob?.cancel()
            dialog.dismiss()
        }

        dialog.show()
        btnUpdateNow.requestFocus()
    }

    /**
     * Tải file APK với tiến trình %
     */
    private suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(downloadUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body ?: return@withContext null
            val contentLength = body.contentLength()

            val apkFile = File(context.cacheDir, "update_tvplay.apk")
            if (apkFile.exists()) apkFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalRead * 100) / contentLength).toInt()
                            onProgress(percent, totalRead, contentLength)
                        }
                    }
                    output.flush()
                }
            }
            return@withContext apkFile
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    /**
     * Mở trình cài đặt PackageInstaller của Android
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(
                        context,
                        "Vui lòng bật quyền 'Cài đặt ứng dụng không rõ nguồn' cho TVPlay",
                        Toast.LENGTH_LONG
                    ).show()
                    val manageIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(manageIntent)
                    return
                }
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Không thể mở trình cài đặt: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
