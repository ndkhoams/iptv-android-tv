package com.samsung.tvremote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

interface VoiceSearchListener {
    fun onReady()
    fun onListening()
    fun onSpeechRms(rmsdB: Float)
    fun onSpeechResult(query: String)
    fun onError(errorMessage: String)
    fun onFinished()
}

/**
 * Manages native Android Speech Recognition configured for Vietnamese (vi-VN).
 */
class VoiceSearchManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening(listener: VoiceSearchListener) {
        if (!isAvailable()) {
            listener.onError("Thiết bị không hỗ trợ dịch vụ giọng nói Google Speech.")
            return
        }

        stopListening()

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        listener.onReady()
                    }

                    override fun onBeginningOfSpeech() {
                        listener.onListening()
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        listener.onSpeechRms(rmsdB)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListening = false
                        listener.onFinished()
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        listener.onFinished()
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Lỗi thu âm từ micro"
                            SpeechRecognizer.ERROR_CLIENT -> "Lỗi kết nối từ ứng dụng"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa cấp quyền truy cập micro"
                            SpeechRecognizer.ERROR_NETWORK -> "Lỗi mạng nhận diện giọng nói"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Hết thời gian chờ mạng"
                            SpeechRecognizer.ERROR_NO_MATCH -> "Không nhận dạng được giọng nói, hãy thử lại"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Dịch vụ giọng nói đang bận"
                            SpeechRecognizer.ERROR_SERVER -> "Lỗi từ máy chủ nhận dạng Google"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Không nghe thấy tiếng nói"
                            else -> "Lỗi giọng nói (#$error)"
                        }
                        listener.onError(message)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        listener.onFinished()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val recognizedText = matches[0].trim()
                            listener.onSpeechResult(recognizedText)
                        } else {
                            listener.onError("Không nhận diện được nội dung giọng nói.")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val partial = matches[0].trim()
                            if (partial.isNotEmpty()) {
                                listener.onSpeechRms(10f)
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói từ khóa tìm kiếm trên TV...")
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            listener.onError("Không thể khởi động mic: ${e.localizedMessage}")
        }
    }

    fun stopListening() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            isListening = false
        }
    }

    fun destroy() {
        stopListening()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }
}
