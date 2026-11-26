package com.samsung.tvremote

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
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
    private var voiceDialog: AlertDialog? = null

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
                    val model = tvClient.tvModelName ?: getString(R.string.tv_status_online)
                    binding.tvTvStatus.text = model
                    binding.tvTvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tv_online))
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot_offline)
                    binding.tvTvStatus.text = getString(R.string.tv_status_offline)
                    binding.tvTvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
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
        // 1. Header: Nút Nguồn đỏ ở góc phải & Nhấn tên TV để cài đặt
        binding.layoutTvStatus.setOnClickListener {
            hapticHelper.click()
            showSettingsDialog()
        }
        binding.btnPower.setOnClickListener {
            handleKeyClick("KEY_POWER", "Nguồn")
        }

        // 2. Hàng phím Media (5 nút tròn): Prev, Rewind, Play/Pause, FF, Next
        binding.btnMediaPrev.setOnClickListener {
            handleKeyClick("KEY_PREVIOUS", "Bài trước")
        }
        binding.btnMediaRewind.setOnClickListener {
            handleKeyClick("KEY_REWIND", "Tua lùi")
        }
        binding.btnMediaPlayPause.setOnClickListener {
            handleKeyClick("KEY_PLAY", "Phát / Tạm dừng")
        }
        binding.btnMediaFF.setOnClickListener {
            handleKeyClick("KEY_FF", "Tua tới")
        }
        binding.btnMediaNext.setOnClickListener {
            handleKeyClick("KEY_NEXT", "Bài kế tiếp")
        }

        // 3. Cụm điều hướng trung tâm (D-Pad tròn lớn)
        binding.btnUp.setOnClickListener { handleKeyClick("KEY_UP", "Lên") }
        binding.btnDown.setOnClickListener { handleKeyClick("KEY_DOWN", "Xuống") }
        binding.btnLeft.setOnClickListener { handleKeyClick("KEY_LEFT", "Trái") }
        binding.btnRight.setOnClickListener { handleKeyClick("KEY_RIGHT", "Phải") }
        binding.btnOk.setOnClickListener { handleKeyClick("KEY_ENTER", "OK") }

        // 4. Khu vực điều khiển bên dưới
        // Bên trái: Con nhộng VOL (+ / -)
        binding.btnVolUp.setOnClickListener { handleKeyClick("KEY_VOLUP", "VOL +") }
        binding.btnVolDown.setOnClickListener { handleKeyClick("KEY_VOLDOWN", "VOL −") }

        // Bên phải: Con nhộng CH (∧ / ∨)
        binding.btnChUp.setOnClickListener { handleKeyClick("KEY_CHUP", "CH ▲") }
        binding.btnChDown.setOnClickListener { handleKeyClick("KEY_CHDOWN", "CH ▼") }

        // Ở giữa: Lưới 6 phím tròn (2 hàng x 3 cột)
        // Hàng trên: Bàn phím gõ chữ (Keyboard), Phím Home, Phím bàn phím số (Numpad)
        binding.btnKeyboard.setOnClickListener {
            hapticHelper.click()
            showKeyboardDialog()
        }
        binding.btnHome.setOnClickListener {
            handleKeyClick("KEY_HOME", "Home")
        }
        binding.btnNumpad.setOnClickListener {
            hapticHelper.click()
            showNumpadDialog()
        }

        // Hàng dưới: Tắt tiếng (Mute), Nút Nói (Micro tìm kiếm giọng nói), Phím Quay lại (Back)
        binding.btnMute.setOnClickListener {
            handleKeyClick("KEY_MUTE", "Tắt tiếng")
        }
        binding.btnMic.setOnClickListener {
            hapticHelper.click()
            if (isVoiceActive) {
                stopVoiceSearch()
            } else {
                checkMicPermissionAndStart()
            }
        }
        binding.btnBack.setOnClickListener {
            handleKeyClick("KEY_RETURN", "Back")
        }
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

    private fun showKeyboardDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val input = EditText(this).apply {
            hint = getString(R.string.dialog_keyboard_hint)
            setSingleLine(false)
            maxLines = 3
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_keyboard_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_keyboard_send) { dialog, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    sendTextInputToTv(text)
                }
                dialog.dismiss()
            }
            .setNeutralButton(R.string.dialog_keyboard_youtube) { dialog, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    executeYouTubeSearch(text)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun sendTextInputToTv(text: String) {
        hapticHelper.click()
        binding.tvToast.text = "Đang gửi: \"$text\"..."
        lifecycleScope.launch {
            val result = tvClient.sendInputString(text)
            result.onSuccess {
                binding.tvToast.text = "Đã gửi lên TV: \"$text\""
            }.onFailure { err ->
                binding.tvToast.text = "Lỗi gửi: ${err.localizedMessage ?: "TV chưa nhận"}"
            }
        }
    }

    private fun showNumpadDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(30, 24, 30, 24)
        }

        var enteredNumbers = ""
        val tvDisplay = TextView(this).apply {
            text = "Bấm số chọn kênh TV"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setPadding(0, 10, 0, 20)
        }
        dialogView.addView(tvDisplay)

        val numpadRows = arrayOf(
            arrayOf("1" to "KEY_1", "2" to "KEY_2", "3" to "KEY_3"),
            arrayOf("4" to "KEY_4", "5" to "KEY_5", "6" to "KEY_6"),
            arrayOf("7" to "KEY_7", "8" to "KEY_8", "9" to "KEY_9"),
            arrayOf("Xóa" to "DEL", "0" to "KEY_0", "OK" to "KEY_ENTER")
        )

        var dialogRef: AlertDialog? = null

        for (row in numpadRows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 12
                layoutParams = lp
            }

            for ((label, key) in row) {
                val btn = androidx.appcompat.widget.AppCompatButton(this).apply {
                    text = label
                    textSize = if (label.length > 2) 14f else 20f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
                    setBackgroundResource(R.drawable.btn_circle_selector)
                    val btnLp = LinearLayout.LayoutParams(140, 140)
                    btnLp.marginStart = 10
                    btnLp.marginEnd = 10
                    layoutParams = btnLp

                    setOnClickListener {
                        when (key) {
                            "DEL" -> {
                                hapticHelper.click()
                                if (enteredNumbers.isNotEmpty()) {
                                    enteredNumbers = enteredNumbers.dropLast(1)
                                    tvDisplay.text = if (enteredNumbers.isEmpty()) "Bấm số chọn kênh TV" else "Kênh: $enteredNumbers"
                                }
                            }
                            "KEY_ENTER" -> {
                                handleKeyClick("KEY_ENTER", "OK")
                                dialogRef?.dismiss()
                            }
                            else -> {
                                hapticHelper.click()
                                enteredNumbers += label
                                tvDisplay.text = "Kênh: $enteredNumbers"
                                handleKeyClick(key, label)
                            }
                        }
                    }
                }
                rowLayout.addView(btn)
            }
            dialogView.addView(rowLayout)
        }

        dialogRef = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_numpad_title)
            .setView(dialogView)
            .setNegativeButton("Đóng", null)
            .show()
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
        showVoiceListeningDialog()
    }

    private fun showVoiceListeningDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_voice_search, null)
        val waveView = dialogView.findViewById<VoiceWaveView>(R.id.voiceWaveView)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvVoiceStatus)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvVoiceSubtitle)
        val btnFinish = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnVoiceFinish)
        val btnCancel = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnVoiceCancel)
        var currentRecognizedQuery = ""

        voiceDialog?.dismiss()
        voiceDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .setOnDismissListener {
                stopVoiceSearch()
            }
            .create().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }

        btnCancel.setOnClickListener {
            voiceDialog?.dismiss()
        }

        btnFinish?.setOnClickListener {
            val query = currentRecognizedQuery.trim()
            if (query.isNotEmpty()) {
                hapticHelper.longClick()
                tvStatus.text = "Đã nhận dạng!"
                tvSubtitle.text = "🔍 \"$query\""
                waveView.stopAnimation()
                lifecycleScope.launch {
                    delay(350)
                    voiceDialog?.dismiss()
                    executeYouTubeSearch(query)
                }
            } else {
                voiceSearchManager.stopListening()
                tvSubtitle.text = "⚠️ Chưa nhận được từ khóa, hãy nói lại hoặc gõ phím!"
            }
        }

        voiceDialog?.show()
        waveView.startAnimation()

        if (!voiceSearchManager.isAvailable()) {
            tvStatus.text = "Chưa có dịch vụ giọng nói"
            tvSubtitle.text = "Thiết bị chưa cài đặt Google Speech.\n\nBấm nút bên dưới để nhập chữ tìm kiếm trên TV."
            waveView.stopAnimation()
            btnFinish?.visibility = View.GONE
            btnCancel.text = "Gõ chữ tìm kiếm"
            btnCancel.setOnClickListener {
                voiceDialog?.dismiss()
                showKeyboardDialog()
            }
            return
        }

        isVoiceActive = true
        binding.btnMic.isActivated = true
        binding.tvToast.text = "Đang lắng nghe giọng nói..."

        voiceSearchManager.startListening(object : VoiceSearchListener {
            override fun onReady() {
                runOnUiThread {
                    tvStatus.text = "Đang lắng nghe..."
                    tvSubtitle.text = "Hãy nói từ khóa bạn muốn xem (VD: VTV1, Ca nhạc, Thời sự...)"
                    waveView.startAnimation()
                }
            }

            override fun onListening() {
                runOnUiThread {
                    tvStatus.text = "Đang nghe bạn nói..."
                }
            }

            override fun onSpeechRms(rmsdB: Float) {
                runOnUiThread {
                    waveView.setRms(rmsdB)
                }
            }

            override fun onPartialResult(query: String) {
                runOnUiThread {
                    currentRecognizedQuery = query
                    tvSubtitle.text = "🔍 \"$query\""
                }
            }

            override fun onSpeechResult(query: String) {
                runOnUiThread {
                    currentRecognizedQuery = query
                    hapticHelper.longClick()
                    tvStatus.text = "Đã nhận dạng!"
                    tvSubtitle.text = "🔍 \"$query\""
                    waveView.stopAnimation()

                    lifecycleScope.launch {
                        delay(600)
                        voiceDialog?.dismiss()
                        executeYouTubeSearch(query)
                    }
                }
            }

            override fun onError(errorMessage: String) {
                runOnUiThread {
                    tvStatus.text = "Không nhận diện được"
                    tvSubtitle.text = errorMessage
                    waveView.stopAnimation()
                    binding.tvToast.text = errorMessage
                }
            }

            override fun onFinished() {
                runOnUiThread {
                    isVoiceActive = false
                    binding.btnMic.isActivated = false
                }
            }
        })
    }

    private fun executeYouTubeSearch(query: String) {
        hapticHelper.longClick()
        binding.tvToast.text = "🔍 Tìm YouTube: \"$query\"..."

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
        voiceDialog?.dismiss()
        voiceDialog = null
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
                val model = tvClient.tvModelName ?: getString(R.string.tv_status_online)
                binding.tvTvStatus.text = model
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
        voiceDialog?.dismiss()
        voiceDialog = null
        voiceSearchManager.destroy()
        statusPollJob?.cancel()
    }
}
