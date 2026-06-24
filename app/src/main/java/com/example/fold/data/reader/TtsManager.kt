package com.example.fold.data.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.fold.util.FoldLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

private const val TAG = "Fold_TTS"

data class TtsState(
    val isPlaying: Boolean = false,
    val isInitialized: Boolean = false,
    val isAvailable: Boolean = true,
    val currentParagraph: Int = 0,
    val totalParagraphs: Int = 0,
    val speed: Float = 1.0f,
    val speakingCharStart: Int = -1,  // 当前正在朗读的字符起始位置
    val speakingCharEnd: Int = -1     // 当前正在朗读的字符结束位置
)

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private var paragraphs: List<String> = emptyList()
    private var currentIndex = 0
    private var shouldStop = false

    override fun onInit(status: Int) {
        FoldLogger.i(TAG, "onInit: status=$status (${if (status == TextToSpeech.SUCCESS) "SUCCESS" else "ERROR"})")
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                val langResult = tts.setLanguage(Locale.CHINESE)
                FoldLogger.d(TAG, "onInit: setLanguage(CHINESE)=$langResult")
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    FoldLogger.w(TAG, "onInit: Chinese not supported, using default")
                    tts.setLanguage(Locale.getDefault())
                }
                tts.setSpeechRate(_state.value.speed)
                tts.setOnUtteranceProgressListener(utteranceListener)
                ttsInitFinish = true
                _state.value = _state.value.copy(isInitialized = true)
                FoldLogger.i(TAG, "onInit: TTS ready, voice=${tts.voice?.name}, locale=${tts.voice?.locale}")

                // 引擎就绪后，如果有待朗读的内容立即开始
                if (_state.value.isPlaying && paragraphs.isNotEmpty()) {
                    FoldLogger.d(TAG, "onInit: starting speak from paragraph $currentIndex")
                    speakCurrent()
                }
            }
        } else {
            FoldLogger.e(TAG, "onInit FAILED: status=$status")
            // 尝试切换引擎重试
            retryWithNextEngine()
        }
    }

    /** 可用引擎列表，用于失败时逐个尝试 */
    private var engineQueue: List<String> = emptyList()

    private fun initTts() {
        ttsInitFinish = false
        textToSpeech?.runCatching { stop(); shutdown() }
        textToSpeech = null

        val engine = engineQueue.firstOrNull()
        textToSpeech = kotlin.runCatching {
            if (engine != null) {
                engineQueue = engineQueue.drop(1)
                FoldLogger.i(TAG, "initTts: trying engine '$engine'")
                TextToSpeech(context, this, engine)
            } else {
                FoldLogger.i(TAG, "initTts: using system default")
                TextToSpeech(context, this)
            }
        }.onFailure {
            FoldLogger.e(TAG, "initTts: constructor exception: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()

        if (textToSpeech == null) {
            FoldLogger.e(TAG, "initTts: TextToSpeech creation returned null")
            retryWithNextEngine()
        }
    }

    private fun retryWithNextEngine() {
        if (engineQueue.isEmpty()) {
            FoldLogger.e(TAG, "all engines exhausted")
            _state.value = _state.value.copy(isAvailable = false)
            return
        }
        initTts()
    }

    private fun clearTTS() {
        textToSpeech?.runCatching { stop(); shutdown() }
        textToSpeech = null
        ttsInitFinish = false
    }

    /** 开始朗读 */
    fun startSpeaking(paragraphs: List<String>, startParagraph: Int = 0) {
        val filtered = paragraphs.filter { it.isNotBlank() }
        FoldLogger.i(TAG, "startSpeaking: ${paragraphs.size} paragraphs, filtered=${filtered.size}, ttsInitFinish=$ttsInitFinish")

        this.paragraphs = filtered
        if (this.paragraphs.isEmpty()) {
            FoldLogger.w(TAG, "startSpeaking: all paragraphs blank")
            return
        }

        currentIndex = startParagraph.coerceIn(0, this.paragraphs.size - 1)
        shouldStop = false
        _state.value = _state.value.copy(
            isPlaying = true,
            currentParagraph = currentIndex,
            totalParagraphs = this.paragraphs.size
        )

        filtered.take(3).forEachIndexed { i, s ->
            FoldLogger.d(TAG, "startSpeaking: para[$i]='${s.take(40)}'")
        }

        if (ttsInitFinish) {
            speakCurrent()
        } else {
            // 枚举引擎（legado 的方式：用 null listener 创建临时实例）
            if (engineQueue.isEmpty()) {
                val engines = mutableListOf<String>()
                kotlin.runCatching {
                    val probe = TextToSpeech(context, null)
                    for (info in probe.engines) {
                        FoldLogger.d(TAG, "found engine: ${info.name}")
                        engines.add(info.name)
                    }
                    probe.shutdown()
                }.onFailure {
                    FoldLogger.w(TAG, "engine probe failed: ${it.message}")
                }
                // 常见引擎兜底
                for (fallback in listOf("com.google.android.tts", "com.samsung.android.tts")) {
                    if (fallback !in engines) engines.add(fallback)
                }
                FoldLogger.i(TAG, "engine queue: $engines")
                engineQueue = engines
            }
            initTts()
        }
    }

    fun updateParagraphs(newParagraphs: List<String>) {
        val filtered = newParagraphs.filter { it.isNotBlank() }
        if (filtered == paragraphs) return
        paragraphs = filtered
        _state.value = _state.value.copy(totalParagraphs = paragraphs.size)
        if (_state.value.isPlaying) {
            if (paragraphs.isEmpty()) { stop(); return }
            if (currentIndex >= paragraphs.size) currentIndex = paragraphs.size - 1
            textToSpeech?.stop()
            _state.value = _state.value.copy(currentParagraph = currentIndex)
            if (ttsInitFinish) speakCurrent()
        }
    }

    fun pause() {
        shouldStop = true
        textToSpeech?.stop()
        _state.value = _state.value.copy(isPlaying = false)
        FoldLogger.d(TAG, "pause: paragraph=$currentIndex")
    }

    fun resume() {
        if (paragraphs.isEmpty()) return
        shouldStop = false
        _state.value = _state.value.copy(isPlaying = true)
        if (ttsInitFinish) speakCurrent()
        FoldLogger.d(TAG, "resume: paragraph=$currentIndex")
    }

    fun stop() {
        FoldLogger.d(TAG, "stop")
        shouldStop = true
        textToSpeech?.stop()
        currentIndex = 0
        _state.value = _state.value.copy(isPlaying = false, currentParagraph = 0)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        textToSpeech?.setSpeechRate(clamped)
        _state.value = _state.value.copy(speed = clamped)
    }

    fun skipToNext() {
        if (currentIndex + 1 < paragraphs.size) {
            textToSpeech?.stop()
            currentIndex++
            _state.value = _state.value.copy(currentParagraph = currentIndex)
            if (!shouldStop && ttsInitFinish) speakCurrent()
        }
    }

    fun skipToPrevious() {
        if (currentIndex > 0) {
            textToSpeech?.stop()
            currentIndex--
            _state.value = _state.value.copy(currentParagraph = currentIndex)
            if (!shouldStop && ttsInitFinish) speakCurrent()
        }
    }

    fun isPlaying(): Boolean = _state.value.isPlaying

    private fun speakCurrent() {
        val tts = textToSpeech ?: return
        if (currentIndex >= paragraphs.size) {
            _state.value = _state.value.copy(isPlaying = false)
            FoldLogger.i(TAG, "finished all paragraphs")
            return
        }
        val text = paragraphs[currentIndex]
        _state.value = _state.value.copy(currentParagraph = currentIndex)
        FoldLogger.d(TAG, "speakCurrent[$currentIndex/${paragraphs.size}]: len=${text.length}")

        val result = tts.runCatching {
            speak(text, TextToSpeech.QUEUE_FLUSH, null, "para_$currentIndex")
        }.getOrElse {
            FoldLogger.e(TAG, "speak exception: ${it.message}")
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            FoldLogger.e(TAG, "speak returned ERROR, reinitializing TTS")
            clearTTS()
            initTts()
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            FoldLogger.d(TAG, "onStart: id=$utteranceId")
            _state.value = _state.value.copy(speakingCharStart = 0, speakingCharEnd = 0)
        }
        override fun onDone(utteranceId: String?) {
            FoldLogger.d(TAG, "onDone: id=$utteranceId, shouldStop=$shouldStop")
            _state.value = _state.value.copy(speakingCharStart = -1, speakingCharEnd = -1)
            if (!shouldStop) {
                currentIndex++
                speakCurrent()
            }
        }
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            _state.value = _state.value.copy(speakingCharStart = start, speakingCharEnd = end)
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            FoldLogger.e(TAG, "onError: id=$utteranceId")
            if (!shouldStop) {
                currentIndex++
                speakCurrent()
            }
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            FoldLogger.e(TAG, "onError: id=$utteranceId, errorCode=$errorCode")
            if (!shouldStop) {
                currentIndex++
                speakCurrent()
            }
        }
    }

    fun release() {
        FoldLogger.i(TAG, "release")
        clearTTS()
    }
}
