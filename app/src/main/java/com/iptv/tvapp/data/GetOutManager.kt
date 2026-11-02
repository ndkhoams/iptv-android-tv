package com.iptv.tvapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.iptv.tvapp.util.NetworkUtils
import com.iptv.tvapp.util.GitHubCdnHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class GetOutManager(private val context: Context) {

    private val client = NetworkUtils.configureTrustAllSsl(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
    ).build()

    companion object {
        const val MASTER_KEY = "sHqvxUbI3HRHqHNdluQ5thFw5e8DCglJ"
        const val GITHUB_TXT_BASE = "https://raw.githubusercontent.com/leeshin5757/getout/main/txt/"

        val SPORT_PROVIDERS = listOf(
            "chuoichien",
            "xoilactv",
            "colatv",
            "gavangtv",
            "cakhia",
            "khandai",
            "s8",
            "tamquoc",
            "demnay"
        )
    }

    /**
     * Lấy danh sách domain thể thao động mới nhất từ GitHub
     */
    suspend fun fetchDynamicDomains(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, List<String>>()
        for (provider in SPORT_PROVIDERS) {
            val candidateUrls = GitHubCdnHelper.buildFallbackUrls("$GITHUB_TXT_BASE$provider")
            for (url in candidateUrls) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", PreferencesHelper.DEFAULT_USER_AGENT)
                        .build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()?.trim()
                        if (!body.isNullOrBlank()) {
                            val domains = body.split("|", "\n", "\r").map { it.trim() }.filter { it.isNotBlank() }
                            result[provider] = domains
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Thử CDN tiếp theo
                }
            }
        }
        result
    }

    /**
     * Giải mã dữ liệu OpenSSL / CryptoJS AES-256-CBC với Salted__
     */
    fun decryptAesCryptoJs(base64Cipher: String, password: String = MASTER_KEY): String? {
        return try {
            val cipherBytes = android.util.Base64.decode(base64Cipher.trim(), android.util.Base64.DEFAULT)
            if (cipherBytes.size < 16) return null

            // 8 bytes: 'Salted__' + 8 bytes salt
            val salt = cipherBytes.copyOfRange(8, 16)
            val encrypted = cipherBytes.copyOfRange(16, cipherBytes.size)

            val passwordBytes = password.toByteArray(Charsets.UTF_8)
            val md5 = MessageDigest.getInstance("MD5")

            // EVP_BytesToKey: D_0, D_1, D_2
            md5.update(passwordBytes)
            md5.update(salt)
            val d0 = md5.digest()

            md5.reset()
            md5.update(d0)
            md5.update(passwordBytes)
            md5.update(salt)
            val d1 = md5.digest()

            md5.reset()
            md5.update(d1)
            md5.update(passwordBytes)
            md5.update(salt)
            val d2 = md5.digest()

            val keyBytes = ByteArray(32)
            System.arraycopy(d0, 0, keyBytes, 0, 16)
            System.arraycopy(d1, 0, keyBytes, 16, 16)

            val ivBytes = ByteArray(16)
            System.arraycopy(d2, 0, ivBytes, 0, 16)

            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)

            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(encrypted)

            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

