package com.example.fold.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages EQ state and persistence.
 */
object EqManager {

    private const val PREFS_NAME = "eq_settings"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_BAND_PREFIX = "eq_band_"
    private const val KEY_BASS = "eq_bass"
    private const val KEY_TREBLE = "eq_treble"
    private const val KEY_PRESET = "eq_preset"

    data class EqState(
        val isEnabled: Boolean = true,
        val bandGains: FloatArray = FloatArray(DspEngine.EQ_BAND_COUNT),
        val bassDb: Float = 0f,
        val trebleDb: Float = 0f,
        val currentPreset: String = "custom"
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EqState) return false
            return isEnabled == other.isEnabled &&
                bandGains.contentEquals(other.bandGains) &&
                bassDb == other.bassDb &&
                trebleDb == other.trebleDb &&
                currentPreset == other.currentPreset
        }
        override fun hashCode(): Int {
            var result = isEnabled.hashCode()
            result = 31 * result + bandGains.contentHashCode()
            result = 31 * result + bassDb.hashCode()
            result = 31 * result + trebleDb.hashCode()
            return result
        }
    }

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    private var dspEngine: DspEngine? = null
    private var prefs: SharedPreferences? = null

    val presets = mapOf(
        "flat" to FloatArray(DspEngine.EQ_BAND_COUNT),  // all zeros
        "bass_boost" to floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "treble_boost" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 5f, 6f),
        "vocal" to floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -2f),
        "rock" to floatArrayOf(4f, 3f, 1f, 0f, -1f, -1f, 0f, 2f, 3f, 4f),
        "pop" to floatArrayOf(-1f, 1f, 3f, 4f, 3f, 1f, -1f, -1f, 1f, 2f),
        "jazz" to floatArrayOf(3f, 2f, 0f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "classical" to floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "electronic" to floatArrayOf(5f, 4f, 2f, 0f, -2f, 0f, 2f, 4f, 5f, 4f),
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadState()
    }

    fun createEngine(sampleRate: Int, channelCount: Int): DspEngine? {
        val engine = DspEngine()
        if (engine.create(sampleRate, channelCount)) {
            dspEngine = engine
            applyState(engine)
            return engine
        }
        return null
    }

    fun destroyEngine() {
        dspEngine?.destroy()
        dspEngine = null
    }

    fun getEngine(): DspEngine? = dspEngine

    fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(isEnabled = enabled)
        dspEngine?.setEqEnabled(enabled)
        saveState()
    }

    fun setBandGain(index: Int, gainDb: Float) {
        if (index !in 0 until DspEngine.EQ_BAND_COUNT) return
        val newGains = _state.value.bandGains.copyOf()
        newGains[index] = gainDb
        _state.value = _state.value.copy(
            bandGains = newGains,
            currentPreset = "custom"
        )
        applyToEngine()
        saveState()
    }

    fun setBassDb(db: Float) {
        _state.value = _state.value.copy(bassDb = db.coerceIn(-12f, 12f))
        applyToEngine()
        saveState()
    }

    fun setTrebleDb(db: Float) {
        _state.value = _state.value.copy(trebleDb = db.coerceIn(-12f, 12f))
        applyToEngine()
        saveState()
    }

    fun applyPreset(name: String) {
        val gains = presets[name] ?: return
        _state.value = _state.value.copy(
            bandGains = gains.copyOf(),
            currentPreset = name
        )
        applyToEngine()
        saveState()
    }

    fun resetToFlat() {
        _state.value = EqState()
        applyToEngine()
        saveState()
    }

    private fun applyToEngine() {
        val engine = dspEngine ?: return
        val s = _state.value
        engine.setEqBands(s.bandGains, s.bassDb, s.trebleDb)
        engine.setEqEnabled(s.isEnabled)
    }

    private fun applyState(engine: DspEngine) {
        val s = _state.value
        engine.setEqBands(s.bandGains, s.bassDb, s.trebleDb)
        engine.setEqEnabled(s.isEnabled)
    }

    private fun saveState() {
        val p = prefs ?: return
        val s = _state.value
        p.edit().apply {
            putBoolean(KEY_ENABLED, s.isEnabled)
            for (i in 0 until DspEngine.EQ_BAND_COUNT) {
                putFloat("$KEY_BAND_PREFIX$i", s.bandGains[i])
            }
            putFloat(KEY_BASS, s.bassDb)
            putFloat(KEY_TREBLE, s.trebleDb)
            putString(KEY_PRESET, s.currentPreset)
            apply()
        }
    }

    private fun loadState() {
        val p = prefs ?: return
        val gains = FloatArray(DspEngine.EQ_BAND_COUNT) { i ->
            p.getFloat("$KEY_BAND_PREFIX$i", 0f)
        }
        _state.value = EqState(
            isEnabled = p.getBoolean(KEY_ENABLED, true),
            bandGains = gains,
            bassDb = p.getFloat(KEY_BASS, 0f),
            trebleDb = p.getFloat(KEY_TREBLE, 0f),
            currentPreset = p.getString(KEY_PRESET, "custom") ?: "custom"
        )
    }
}
