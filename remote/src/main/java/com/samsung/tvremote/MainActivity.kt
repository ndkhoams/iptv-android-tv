package com.samsung.tvremote

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.samsung.tvremote.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tvClient: SamsungTvClient
    private lateinit var hapticHelper: HapticHelper
    private lateinit var voiceSearchManager: VoiceSearchManager

    private var statusPollJob: Job? = null
    private var isVoiceActive = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceSearch()
        } else {
            showToast(getString(R.string.permission_mic_required))
        }
    }

    private val speechIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val query = matches[0].trim()
                executeYouTubeSearch(query)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tvClient = SamsungTvClient(this)
        hapticHelper = HapticHelper(this)
        voiceSearchManager = VoiceSearchManager(this)

        setupTvConnectionListener()
        setupButtonListeners()
        setupSettingsButton()
        startTvStatusPolling()
    }

    override fun onStart() {
        super.onStart()
        tvClient.connectWebSocket()
    }

    override fun onStop() {
        super.onStop()
        tvClient.disconnectWebSocket()
    }

    private fun setupTvConnectionListener() {
        tvClient.setConnectionListener(object : TvConnectionListener {
            override fun onConnected(token: String?) {
                runOnUiThread {
                    binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot_online)
                    val model = tvClient.tvModelName ?: "Samsung TV"
                    binding.tvTvStatus.text = "$model (${tvClient.tvIp})"
                    binding.tvTvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tv_online))
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot_offline)
                }
            }

            override fun onPairingRequested() {
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Ghép đôi với TV Samsung")
                        .setMessage("TV đang hiển thị thông báo yêu cầu kết nối trên màn hình.\n\nHãy dùng remote TV bấm 'Cho phép' để hoàn tất ghép đôi.")
                        .setPositiveButton("Đã hiểu", null)
                        .show()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    // Log or subtle status
                }
            }
        })
    }

    private fun setupButtonListeners() {
        // Power button (Red circle with ic_power vector)
        binding.btnPower.setOnClickListener {
            handleKeyClick("KEY_POWER", "Nguồn")
        }

        // Voice button (Blue circle with ic_mic vector)
        binding.btnMic.setOnClickListener {
            hapticHelper.click()
            if (isVoiceActive) {
                stopVoiceSearch()
            } else {
                checkMicPermissionAndStart()
            }
        }

        // D-pad navigation
        binding.btnUp.setOnClickListener { handleKeyClick("KEY_UP", "Lên") }
        binding.btnDown.setOnClickListener { handleKeyClick("KEY_DOWN", "Xuống") }
        binding.btnLeft.setOnClickListener { handleKeyClick("KEY_LEFT", "Trái") }
        binding.btnRight.setOnClickListener { handleKeyClick("KEY_RIGHT", "Phải") }
        binding.btnOk.setOnClickListener { handleKeyClick("KEY_ENTER", "OK") }

        // Mid row buttons
        binding.btnBack.setOnClickListener { handleKeyClick("KEY_RETURN", "Back") }
        binding.btnHome.setOnClickListener { handleKeyClick("KEY_HOME", "Home") }
        binding.btnPlayPause.setOnClickListener { handleKeyClick("KEY_PLAY", "Play/Pause") }

        // Rocker buttons: Volume & Channel
        binding.btnVolUp.setOnClickListener { handleKeyClick("KEY_VOLUP", "VOL +") }
        binding.btnVolDown.setOnClickListener { handleKeyClick("KEY_VOLDOWN", "VOL −") }
        binding.btnChUp.setOnClickListener { handleKeyClick("KEY_CHUP", "CH ▲") }
        binding.btnChDown.setOnClickListener { handleKeyClick("KEY_CHDOWN", "CH ▼") }
    }

    private fun handleKeyClick(key: String, label: String) {
        hapticHelper.click()
        binding.tvToast.text = "Bấm $label..."
        lifecycleScope.launch {
            val result = tvClient.sendKey(key)
            result.onSuccess {
                binding.tvToast.text = "OK ($label)"
            }.onFailure { err ->
                binding.tvToast.text = "Lỗi: ${err.localizedMessage ?: "TV chưa phản hồi"}"
            }
        }
    }

    private fun checkMicPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                startVoiceSearch()
            }
            else -> {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startVoiceSearch() {
        if (!voiceSearchManager.isAvailable()) {
            fallbackVoiceSearch()
            return
        }

        isVoiceActive = true
        binding.btnMic.isActivated = true
        binding.tvVoiceFeedback.text = getString(R.string.hint_voice_listening)
        binding.tvVoiceFeedback.setTextColor(ContextCompat.getColor(this, R.color.btn_mic_listening))

        voiceSearchManager.startListening(object : VoiceSearchListener {
            override fun onReady() {
                binding.tvVoiceFeedback.text = "Đang lắng nghe giọng nói tiếng Việt..."
            }

            override fun onListening() {
                binding.tvVoiceFeedback.text = "Đang nghe... Nói từ khóa muốn xem"
            }

            override fun onSpeechRms(rmsdB: Float) {}

            override fun onSpeechResult(query: String) {
                stopVoiceSearch()
                executeYouTubeSearch(query)
            }

            override fun onError(errorMessage: String) {
                stopVoiceSearch()
                fallbackVoiceSearch()
            }

            override fun onFinished() {
                stopVoiceSearch()
            }
        })
    }

    private fun fallbackVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói hoặc nhập từ khóa tìm YouTube trên TV...")
        }

        try {
            speechIntentLauncher.launch(intent)
        } catch (e: Exception) {
            showKeyboardSearchDialog()
        }
    }

    private fun showKeyboardSearchDialog() {
        val input = EditText(this).apply {
            hint = "Nhập tên video / bài hát cần tìm trên TV..."
            setSingleLine(true)
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Tìm kiếm YouTube trên TV")
            .setMessage("Nhập từ khóa bằng bàn phím hoặc dùng mic bàn phím (Gboard):")
            .setView(input)
            .setPositiveButton("Tìm trên TV") { dialog, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) {
                    executeYouTubeSearch(q)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun executeYouTubeSearch(query: String) {
        hapticHelper.longClick()
        binding.tvVoiceFeedback.text = "🔍 Tìm: \"$query\""
        binding.tvVoiceFeedback.setTextColor(ContextCompat.getColor(this, R.color.text_white))
        binding.tvToast.text = "Đang gửi tìm kiếm tới TV: \"$query\"..."

        // Send directly to TV via DIAL (port 8080)
        lifecycleScope.launch {
            val result = tvClient.searchYouTube(query)
            result.onSuccess { msg ->
                binding.tvToast.text = msg
            }.onFailure { err ->
                binding.tvToast.text = "Lỗi DIAL: ${err.localizedMessage ?: "Không thể mở YouTube trên TV"}"
            }
        }
    }

    private fun stopVoiceSearch() {
        isVoiceActive = false
        binding.btnMic.isActivated = false
        voiceSearchManager.stopListening()
    }

    private fun setupSettingsButton() {
        binding.btnSettings.setOnClickListener {
            hapticHelper.click()
            showSettingsDialog()
        }
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val tvInfo = TextView(this).apply {
            text = "Địa chỉ IP TV Samsung trong mạng LAN:"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setPadding(0, 0, 0, 16)
        }
        container.addView(tvInfo)

        val input = EditText(this).apply {
            setText(tvClient.tvIp)
            hint = SamsungTvClient.DEFAULT_TV_IP
            setSingleLine(true)
        }
        container.addView(input)

        val tokenStatus = TextView(this).apply {
            val hasToken = !tvClient.pairingToken.isNullOrEmpty()
            text = if (hasToken) "Trạng thái: Đã có mã ghép đôi (Token OK)" else "Trạng thái: Chưa ghép đôi (Sẽ hỏi khi bấm phím)"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, if (hasToken) R.color.tv_online else R.color.text_dim))
            setPadding(0, 20, 0, 10)
        }
        container.addView(tokenStatus)

        AlertDialog.Builder(this)
            .setTitle(R.string.tv_settings_title)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { dialog, _ ->
                val newIp = input.text.toString().trim()
                if (newIp.isNotEmpty()) {
                    tvClient.tvIp = newIp
                    showToast("Đã lưu IP TV: $newIp")
                    checkTvStatusOnce()
                }
                dialog.dismiss()
            }
            .setNeutralButton(R.string.btn_reset_token) { dialog, _ ->
                tvClient.clearPairingToken()
                showToast("Đã xóa token ghép đôi. Lần tới TV sẽ hỏi lại quyền.")
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun startTvStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = lifecycleScope.launch {
            while (isActive) {
                checkTvStatusOnce()
                delay(8000) // Poll every 8 seconds
            }
        }
    }

    private fun checkTvStatusOnce() {
        lifecycleScope.launch {
            val isOnline = tvClient.checkTvStatus()
            if (isOnline) {
                binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot_online)
                val model = tvClient.tvModelName ?: "Samsung TV"
                binding.tvTvStatus.text = "$model (${tvClient.tvIp})"
                binding.tvTvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tv_online))
            } else {
                binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot_offline)
                binding.tvTvStatus.text = getString(R.string.tv_status_offline)
                binding.tvTvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            }
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceSearchManager.destroy()
        statusPollJob?.cancel()
    }
}
