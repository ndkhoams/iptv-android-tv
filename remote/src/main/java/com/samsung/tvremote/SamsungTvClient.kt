package com.samsung.tvremote

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

interface TvConnectionListener {
    fun onConnected(token: String?)
    fun onDisconnected()
    fun onPairingRequested()
    fun onError(error: String)
}

/**
 * Direct client for Samsung Smart TV (Tizen 5.5 / 2016+):
 * - DIAL on port 8080 (YouTube Voice Search & App Launch)
 * - REST API on port 8001 (Device Info & Power Status)
 * - Secure WebSocket on port 8002 (Remote Key Navigation & Pairing)
 *
 * Connects directly from Android phone to TV (e.g. 10.0.0.9) without any proxy!
 */
class SamsungTvClient(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("samsung_tv_prefs", Context.MODE_PRIVATE)

    var tvIp: String
        get() = prefs.getString("tv_ip", DEFAULT_TV_IP) ?: DEFAULT_TV_IP
        set(value) {
            val cleanIp = value.trim().replace("http://", "").replace("https://", "").replace("/", "")
            prefs.edit().putString("tv_ip", cleanIp).apply()
            reconnectWebSocket()
        }

    var pairingToken: String?
        get() = prefs.getString("pairing_token", null)
        set(value) {
            prefs.edit().putString("pairing_token", value).apply()
        }

    var tvModelName: String?
        get() = prefs.getString("tv_model_name", "Samsung Smart TV")
        private set(value) {
            prefs.edit().putString("tv_model_name", value).apply()
        }

    private val httpClient: OkHttpClient = createUnsafeOkHttpClient()
    private var webSocket: WebSocket? = null
    private var isWebSocketConnected = false
    private var connectionListener: TvConnectionListener? = null

    fun setConnectionListener(listener: TvConnectionListener?) {
        this.connectionListener = listener
    }

    /**
     * Start WebSocket connection to TV port 8002
     */
    @Synchronized
    fun connectWebSocket() {
        if (isWebSocketConnected && webSocket != null) return

        try {
            val appNameBase64 = Base64.encodeToString(APP_NAME.toByteArray(), Base64.NO_WRAP)
            var wsUrl = "wss://$tvIp:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64"
            pairingToken?.let { token ->
                if (token.isNotEmpty()) {
                    wsUrl += "&token=$token"
                }
            }

            Log.d(TAG, "Connecting WebSocket to: $wsUrl")
            val request = Request.Builder()
                .url(wsUrl)
                .build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket opened with TV")
                    isWebSocketConnected = true
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "TV message: $text")
                    try {
                        val json = JSONObject(text)
                        val event = json.optString("event")

                        when (event) {
                            "ms.channel.connect" -> {
                                val data = json.optJSONObject("data")
                                val token = data?.optString("token")
                                if (!token.isNullOrEmpty()) {
                                    pairingToken = token
                                    Log.d(TAG, "Received & saved pairing token: $token")
                                }
                                isWebSocketConnected = true
                                connectionListener?.onConnected(token)
                            }
                            "ms.channel.unauthorized" -> {
                                Log.w(TAG, "TV reported unauthorized, resetting token")
                                pairingToken = null
                                connectionListener?.onPairingRequested()
                            }
                            "ms.channel.clientDisconnect" -> {
                                isWebSocketConnected = false
                                connectionListener?.onDisconnected()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Parse message error", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.localizedMessage}")
                    isWebSocketConnected = false
                    this@SamsungTvClient.webSocket = null
                    connectionListener?.onError("Mất kết nối TV: ${t.localizedMessage ?: "Timeout"}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $reason")
                    isWebSocketConnected = false
                    this@SamsungTvClient.webSocket = null
                    connectionListener?.onDisconnected()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "connectWebSocket exception", e)
            connectionListener?.onError("Không thể kết nối TV: ${e.localizedMessage}")
        }
    }

    @Synchronized
    fun disconnectWebSocket() {
        try {
            webSocket?.close(1000, "App closed")
        } catch (_: Exception) {}
        webSocket = null
        isWebSocketConnected = false
    }

    fun reconnectWebSocket() {
        disconnectWebSocket()
        connectWebSocket()
    }

    /**
     * Send remote key to TV:
     * e.g. KEY_POWER, KEY_VOLUP, KEY_VOLDOWN, KEY_UP, KEY_DOWN, KEY_ENTER, KEY_RETURN, KEY_HOME, KEY_PLAY
     */
    suspend fun sendKey(key: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isWebSocketConnected || webSocket == null) {
            connectWebSocket()
            // Wait briefly for handshake
            var waitCount = 0
            while (!isWebSocketConnected && waitCount < 15) {
                kotlinx.coroutines.delay(100)
                waitCount++
            }
        }

        val ws = webSocket
        if (ws == null || !isWebSocketConnected) {
            return@withContext Result.failure(
                Exception("Chưa kết nối được với TV $tvIp:8002. Hãy kiểm tra TV đã bật chưa.")
            )
        }

        try {
            val keyPayload = JSONObject().apply {
                put("method", "ms.remote.control")
                put("params", JSONObject().apply {
                    put("Cmd", "Click")
                    put("DataOfCmd", key)
                    put("Option", "false")
                    put("TypeOfRemote", "SendRemoteKey")
                })
            }

            val sent = ws.send(keyPayload.toString())
            if (sent) {
                Result.success("OK")
            } else {
                Result.failure(Exception("Không gửi được phím $key"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Send text input string to TV (via WebSocket SendInputString):
     */
    suspend fun sendInputString(text: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isWebSocketConnected || webSocket == null) {
            connectWebSocket()
            var waitCount = 0
            while (!isWebSocketConnected && waitCount < 15) {
                kotlinx.coroutines.delay(100)
                waitCount++
            }
        }

        val ws = webSocket
        if (ws == null || !isWebSocketConnected) {
            return@withContext Result.failure(
                Exception("Chưa kết nối được với TV $tvIp:8002.")
            )
        }

        try {
            val base64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val inputPayload = JSONObject().apply {
                put("method", "ms.remote.control")
                put("params", JSONObject().apply {
                    put("Cmd", "SendInputString")
                    put("DataOfCmd", base64Text)
                    put("Option", "false")
                    put("TypeOfRemote", "SendRemoteKey")
                })
            }

            val sent = ws.send(inputPayload.toString())
            if (sent) {
                Result.success("Đã gửi: \"$text\"")
            } else {
                Result.failure(Exception("Không gửi được chuỗi tới TV"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Direct DIAL YouTube Search:
     * POST http://10.0.0.9:8080/ws/apps/YouTube with launch=search&q=<query>
     */
    suspend fun searchYouTube(query: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val bodyString = "launch=search&q=$encodedQuery"
            val body = bodyString.toRequestBody(FORM_MEDIA_TYPE)

            val request = Request.Builder()
                .url("http://$tvIp:8080/ws/apps/YouTube")
                .header("Origin", "https://www.youtube.com")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 201 || response.code == 200) {
                    Result.success("Đã mở YouTube: \"$query\"")
                } else {
                    Result.failure(Exception("Lỗi TV DIAL: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Direct DIAL Launch App (YouTube / Netflix / TVPlay):
     * POST http://10.0.0.9:8080/ws/apps/<AppId>
     */
    suspend fun launchApp(appName: String): Result<String> = withContext(Dispatchers.IO) {
        val appId = when (appName.lowercase()) {
            "youtube" -> "YouTube"
            "netflix" -> "Netflix"
            "tvplay" -> "TVPlay0001.TVPlay"
            else -> appName
        }

        try {
            val body = "".toRequestBody(FORM_MEDIA_TYPE)
            val request = Request.Builder()
                .url("http://$tvIp:8080/ws/apps/$appId")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 201 || response.code == 200) {
                    Result.success("Đã mở ứng dụng $appId")
                } else {
                    // Fallback: try sending app key via WebSocket
                    val keyFallback = if (appId == "YouTube") "KEY_YOUTUBE" else "KEY_NETFLIX"
                    sendKey(keyFallback)
                }
            }
        } catch (e: Exception) {
            // Fallback via WebSocket
            val keyFallback = if (appName.lowercase() == "youtube") "KEY_YOUTUBE" else "KEY_NETFLIX"
            sendKey(keyFallback)
        }
    }

    /**
     * Check TV power & status directly on port 8001:
     * GET http://10.0.0.9:8001/api/v2/
     */
    suspend fun checkTvStatus(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("http://$tvIp:8001/api/v2/")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(bodyStr)
                        val device = json.optJSONObject("device")
                        val model = device?.optString("modelName")
                        if (!model.isNullOrEmpty()) {
                            tvModelName = model
                        }
                    } catch (_: Exception) {}
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun clearPairingToken() {
        pairingToken = null
        reconnectWebSocket()
    }

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val DEFAULT_TV_IP = "10.0.0.9"
        private const val APP_NAME = "AndroidTVRemote"
        private const val TAG = "SamsungTvClient"
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}
