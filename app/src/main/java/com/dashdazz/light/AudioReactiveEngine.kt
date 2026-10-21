package com.dashdazz.light

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AudioFrame(
    val nowMs: Long,

    val level: Float,
    val bass: Float,
    val mid: Float,
    val treble: Float,

    val band: Float,

    val beat: Float,

    val beatCount: Long,
    val bpm: Int,
    val hasSignal: Boolean,

    val silent: Boolean,
    val silentForMs: Long,

    val beatsPerSecond: Int = 0
)

data class AudioDiagnostics(
    val waveform: FloatArray = FloatArray(48),
    val level: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,

    val targetEnergy: Float = 0f,

    val rms: Float = 0f,
    val isBeat: Boolean = false,
    val fps: Int = 0,
    val bpm: Int = 0,
    val silent: Boolean = false,

    val engineRunning: Boolean = false,

    val captureError: String? = null,

    val beatsPerSecond: Int = 0,

    val gainScale: Float = 1f,

    val threshold: Float = 1.18f,

    val autoSensitivity: Boolean = true,

    val activeBand: Int = -1,

    val bandScores: FloatArray = FloatArray(5),
    val sourceName: String = "Global Media Mix",
    val hasAudioSignal: Boolean = false
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioDiagnostics) return false
        return level == other.level && bass == other.bass && mid == other.mid &&
            treble == other.treble && targetEnergy == other.targetEnergy &&
            rms == other.rms && isBeat == other.isBeat && fps == other.fps &&
            bpm == other.bpm && silent == other.silent &&
            engineRunning == other.engineRunning && captureError == other.captureError &&
            activeBand == other.activeBand &&
            sourceName == other.sourceName && hasAudioSignal == other.hasAudioSignal &&
            beatsPerSecond == other.beatsPerSecond && gainScale == other.gainScale &&
            threshold == other.threshold && autoSensitivity == other.autoSensitivity &&
            waveform.contentEquals(other.waveform) && bandScores.contentEquals(other.bandScores)
    }

    override fun hashCode(): Int {
        var result = waveform.contentHashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + bass.hashCode()
        result = 31 * result + mid.hashCode()
        result = 31 * result + treble.hashCode()
        result = 31 * result + targetEnergy.hashCode()
        result = 31 * result + rms.hashCode()
        result = 31 * result + isBeat.hashCode()
        result = 31 * result + fps
        result = 31 * result + bpm
        result = 31 * result + silent.hashCode()
        result = 31 * result + engineRunning.hashCode()
        result = 31 * result + (captureError?.hashCode() ?: 0)
        result = 31 * result + activeBand
        result = 31 * result + beatsPerSecond
        result = 31 * result + gainScale.hashCode()
        result = 31 * result + threshold.hashCode()
        result = 31 * result + autoSensitivity.hashCode()
        result = 31 * result + sourceName.hashCode()
        result = 31 * result + hasAudioSignal.hashCode()
        result = 31 * result + bandScores.contentHashCode()
        return result
    }
}

internal val BAND_EDGES_HZ = arrayOf(
    floatArrayOf(20f, 20000f),
    floatArrayOf(55f, 140f),
    floatArrayOf(200f, 600f),
    floatArrayOf(800f, 2500f),
    floatArrayOf(5000f, 14000f)
)

private const val BAND_COUNT_FILE = 5

internal const val BS_BEAT_WINDOW_MS = 1000L
internal const val BS_SIGNAL_FLOOR = 0.02f

internal const val BS_GATE_MARGIN = 0.10f
internal const val BS_AUTO_IDLE_MS = 4000L
internal const val BS_HIT_MEMORY = 32

internal const val BS_BPM_MIN_MS = 150f
internal const val BS_BPM_FAST_MS = 250f
internal const val BS_BPM_SLOW_MS = 1000f

internal const val BS_AUTO_SCORE_WINDOW_MS = 4000L
internal const val BS_AUTO_HITS_PER_WINDOW = 6

private class BandInput {
    @Volatile var level = 0f
    @Volatile var bands = FloatArray(BAND_COUNT_FILE)

    @Volatile var manual = 0f

    fun setBand(index: Int, value: Float) {
        if (index in bands.indices) bands[index] = value
    }
}

internal class BandTracker(
    private val attackMs: Float = 22f,
    private val releaseMs: Float = 240f,
    private val agcTauMs: Float = 9000f,

    private val autoTargetLevel: Float = AUTO_TARGET_LEVEL
) {

    @Volatile
    var value: Float = 0f
        private set

    private var peak = PEAK_FLOOR

    private var reference = 0f
    private var autoScale = 1f

    @Volatile var gainScale: Float = 1f
        private set

    fun push(input: Float, dtMs: Float, autoGain: Boolean = false) {
        val clamped = input.coerceIn(0f, 1f)

        val decay = if (dtMs > 0f) exp(-dtMs / agcTauMs) else 1f
        peak = max(peak * decay, max(clamped, PEAK_FLOOR))

        var norm = (clamped / peak).coerceIn(0f, 1f)

        if (autoGain && dtMs > 0f) {
            reference += (norm - reference) * (1f - exp(-dtMs / REFERENCE_TAU_MS))
            val target = (autoTargetLevel / max(reference, REFERENCE_FLOOR))
                .coerceIn(MIN_AUTO_SCALE, MAX_AUTO_SCALE)
            autoScale += (target - autoScale) * (1f - exp(-dtMs / SCALE_TAU_MS))
            norm = (norm * autoScale).coerceIn(0f, 1f)
            gainScale = autoScale
        } else if (!autoGain) {
            if (reference != 0f || autoScale != 1f) {
                reference = 0f
                autoScale = 1f
            }
            gainScale = 1f
        }

        val tau = if (norm > value) attackMs else releaseMs
        val k = if (dtMs > 0f) 1f - exp(-dtMs / tau) else 1f
        value += (norm - value) * k
    }

    fun reset() {
        value = 0f
        peak = PEAK_FLOOR
        reference = 0f
        autoScale = 1f
        gainScale = 1f
    }

    internal companion object {
        const val PEAK_FLOOR = 0.04f

        const val AUTO_TARGET_LEVEL = 0.35f
        const val REFERENCE_TAU_MS = 4000f
        const val REFERENCE_FLOOR = 0.03f
        const val SCALE_TAU_MS = 2500f
        const val MIN_AUTO_SCALE = 0.25f
        const val MAX_AUTO_SCALE = 4f
    }
}

internal class BeatDetector(private val capacity: Int = 64) {
    private val values = FloatArray(capacity)
    private val times = LongArray(capacity)
    private var index = 0
    private var filled = false

    fun reset() {
        index = 0
        filled = false
        Arrays.fill(values, 0f)
    }

    fun push(value: Float, nowMs: Long) {
        values[index] = value
        times[index] = nowMs
        index = (index + 1) % capacity
        if (index == 0) filled = true
    }

    fun averageWithin(windowMs: Long, nowMs: Long): Float {
        val total = if (filled) capacity else index
        var sum = 0f
        var count = 0
        for (i in 0 until total) {
            if (nowMs - times[i] <= windowMs) {
                sum += values[i]
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }
}

internal class TempoTracker(private val capacity: Int = 8) {
    private val intervals = FloatArray(capacity)
    private var index = 0
    private var count = 0
    private var cachedMedianMs = 0f

    fun reset() {
        index = 0
        count = 0
        cachedMedianMs = 0f
    }

    fun push(gapMs: Float) {

        if (gapMs < 150f || gapMs > 2000f) return
        intervals[index] = gapMs
        index = (index + 1) % capacity
        count = min(count + 1, capacity)

        val sorted = FloatArray(count)
        for (i in 0 until count) {
            val src = (index - count + i + capacity * 2) % capacity
            sorted[i] = intervals[src]
        }
        Arrays.sort(sorted)
        cachedMedianMs = if (count % 2 == 1) {
            sorted[count / 2]
        } else {
            (sorted[count / 2 - 1] + sorted[count / 2]) / 2f
        }
    }

    fun medianMs(): Float = cachedMedianMs

    fun spreadMs(): Float {
        if (count < 2) return 0f
        var sum = 0f
        for (i in 0 until count) {
            val src = (index - count + i + capacity * 2) % capacity
            sum += intervals[src]
        }
        val mean = sum / count
        var variance = 0f
        for (i in 0 until count) {
            val src = (index - count + i + capacity * 2) % capacity
            val d = intervals[src] - mean
            variance += d * d
        }
        return sqrt(variance / count)
    }

    fun bpm(): Int = if (cachedMedianMs > 0f) (60000f / cachedMedianMs).toInt() else 0
}

internal class BandSignal(
    val id: Int,
    attackMs: Float = 8f,
    releaseMs: Float = 170f
) {
    private val tracker = BandTracker(attackMs = attackMs, releaseMs = releaseMs)
    private val history = BeatDetector()
    private val tempo = TempoTracker()

    private val hitTimes = LongArray(BS_HIT_MEMORY)
    private var hitIndex = 0
    private var hitsSeen = 0

    val value: Float get() = tracker.value

    fun medianMs(): Float = tempo.medianMs()

    @Volatile var score = 0f; private set

    private var lastBeatMs = 0L

    fun push(raw: Float, dtMs: Float, autoGain: Boolean = false) = tracker.push(raw, dtMs, autoGain)

    fun reset() {
        tracker.reset()
        history.reset()
        tempo.reset()
        score = 0f
        lastBeatMs = 0L
        hitIndex = 0
        hitsSeen = 0
        hitTimes.fill(0L)
    }

    fun hitsWithin(windowMs: Long, nowMs: Long): Int {        val total = minOf(hitsSeen, BS_HIT_MEMORY)
        var n = 0
        for (i in 0 until total) {
            if (nowMs - hitTimes[i] <= windowMs) n++
        }
        return n
    }

    fun detect(nowMs: Long, threshold: Float, refractoryMs: Long): Boolean {

        val baseline = history.averageWithin(BS_BEAT_WINDOW_MS, nowMs)
        history.push(value, nowMs)
        val gate = max(baseline * threshold, BS_SIGNAL_FLOOR + BS_GATE_MARGIN)
        val gap = nowMs - lastBeatMs
        if (value <= gate || gap < refractoryMs) return false
        lastBeatMs = nowMs
        tempo.push(gap.toFloat())
        hitTimes[hitIndex] = nowMs
        hitIndex = (hitIndex + 1) % BS_HIT_MEMORY
        hitsSeen++
        return true
    }

    fun updateScore(nowMs: Long) {
        val idle = nowMs - lastBeatMs
        if (lastBeatMs == 0L || idle > BS_AUTO_IDLE_MS) {
            score = 0f
            return
        }
        val median = tempo.medianMs()

        val spread = if (median > 0f) (tempo.spreadMs() / median).coerceIn(0f, 1.5f) else 1.5f
        val consistency = (1f - spread / 1.5f).coerceIn(0f, 1f)

        val plausibility = when {
            median <= 0f -> 0f
            median < BS_BPM_FAST_MS -> ((median - BS_BPM_MIN_MS) / (BS_BPM_FAST_MS - BS_BPM_MIN_MS))
                .coerceIn(0f, 1f)
            median <= BS_BPM_SLOW_MS -> 1f
            else -> (1f - (median - BS_BPM_SLOW_MS) / 2000f).coerceIn(0f, 1f)
        }
        val activity = (hitsWithin(BS_AUTO_SCORE_WINDOW_MS, nowMs) / BS_AUTO_HITS_PER_WINDOW.toFloat())
            .coerceIn(0f, 1f)
        val recency = 1f - (idle / BS_AUTO_IDLE_MS.toFloat())
        score = consistency * plausibility * activity * recency
    }
}

private class MicBands {
    @Volatile var bass = 0f; private set
    @Volatile var lowMid = 0f; private set
    @Volatile var highMid = 0f; private set
    @Volatile var air = 0f; private set

    private var lpLow = 0f
    private var lpMid = 0f
    private var lpHigh = 0f

    fun reset() {
        lpLow = 0f
        lpMid = 0f
        lpHigh = 0f
        bass = 0f; lowMid = 0f; highMid = 0f; air = 0f
    }

    fun analyse(pcm: ShortArray, count: Int, sampleRate: Int) {
        val dt = 1f / sampleRate.coerceAtLeast(8000)
        var bassMax = 0f; var lowMidMax = 0f; var highMidMax = 0f; var airMax = 0f
        for (i in 0 until count) {
            val x = pcm[i] / 32768f
            lpLow = onePole(lpLow, x, 220f, dt)
            lpMid = onePole(lpMid, x, 2200f, dt)
            lpHigh = onePole(lpHigh, x, 6000f, dt)
            val low = abs(lpLow)
            val lm = abs(lpMid - lpLow)
            val hm = abs(lpHigh - lpMid)
            val top = abs(x - lpHigh)
            if (low > bassMax) bassMax = low
            if (lm > lowMidMax) lowMidMax = lm
            if (hm > highMidMax) highMidMax = hm
            if (top > airMax) airMax = top
        }
        bass = bassMax; lowMid = lowMidMax; highMid = highMidMax; air = airMax
    }

    private fun onePole(prev: Float, x: Float, cutoffHz: Float, dt: Float): Float {
        val rc = 1f / (TWO_PI * cutoffHz)
        val a = dt / (rc + dt)
        return prev + a * (x - prev)
    }

    private companion object {
        const val TWO_PI = 2f * Math.PI.toFloat()
    }
}

object AudioReactiveEngine {

    private const val TAG = "AudioReactiveEngine"

    const val WAVE_POINTS = 48

    const val SOURCE_GLOBAL_MIX = 0
    const val SOURCE_MIC = 1

    const val BAND_MODE_AUTO = 0

    const val BAND_MODE_MANUAL = 1

    const val BAND_AUTO = -1
    const val BAND_BROADBAND = 0
    const val BAND_KICK = 1
    const val BAND_SNARE = 2
    const val BAND_VOCAL = 3
    const val BAND_HAT = 4
    const val BAND_COUNT = 5

    const val BAND_MANUAL = 5

    private const val BEAT_DECAY_MS = 130f

    private const val BEAT_VISIBLE_FLOOR = 0.5f
    private const val SIGNAL_FLOOR = 0.02f
    private const val SILENCE_TRIGGER_MS = 2500L
    private const val MIN_BEAT_GAP_MS = 150L
    private const val MAX_BEAT_GAP_MS = 430L
    private const val DIAGNOSTICS_INTERVAL_MS = 50L
    private const val MIN_PUMP_GAP_MS = 8L

    internal const val captureErrorGate = "audio_gate_closed"
    private const val BEAT_TIME_MEMORY = 16
    private const val BIN_SCALE = 1f / 128f
    private const val DEFAULT_CAPTURE_SIZE = 1024
    private const val START_RETRY_MAX = 3
    private const val START_RETRY_BASE_MS = 500L
    private const val MIC_SAMPLE_RATE = 22050
    private const val MIC_FRAME = 256



    private const val AUTO_SELECT_INTERVAL_MS = 1500L

    private const val AUTO_SWITCH_MARGIN = 1.15f

    private const val AUTO_MIN_FIRE_GAP_MS = 200L


    private const val AUTO_CONTROL_INTERVAL_MS = 1000L
    private const val AUTO_RATE_WINDOW_MS = 2000L
    private const val AUTO_TARGET_BPS_MIN = 1.2f
    private const val AUTO_TARGET_BPS_MAX = 3.2f
    private const val AUTO_STEP_DOWN = 0.96f
    private const val AUTO_STEP_UP = 1.05f
    private const val AUTO_THRESHOLD_FLOOR = 1.05f
    private const val AUTO_THRESHOLD_CEILING = 1.6f

    const val MIN_HZ_DEFAULT = 5000f
    const val MAX_HZ_DEFAULT = 14000f

    const val DEFAULT_DEGREES_PER_BEAT = 30f
    const val MIN_DEGREES_PER_BEAT = 5f
    const val MAX_DEGREES_PER_BEAT = 180f

    @Volatile var audioSource = SOURCE_GLOBAL_MIX; private set
    @Volatile var gainMultiplier = 0.7f; private set
    @Volatile var beatThreshold = 1.18f; private set

    @Volatile var autoSensitivity = true; private set
    @Volatile var minFreqHz = MIN_HZ_DEFAULT; private set
    @Volatile var maxFreqHz = MAX_HZ_DEFAULT; private set
    @Volatile var bandMode = BAND_MODE_AUTO; private set

    @Volatile var degreesPerBeat = DEFAULT_DEGREES_PER_BEAT; private set

    private val _diagnostics = MutableStateFlow(AudioDiagnostics())
    val diagnostics: StateFlow<AudioDiagnostics> = _diagnostics.asStateFlow()

    private val _sourceFlow = MutableStateFlow(SOURCE_GLOBAL_MIX)
    val sourceFlow: StateFlow<Int> = _sourceFlow.asStateFlow()

    private val _bandModeFlow = MutableStateFlow(BAND_MODE_AUTO)
    val bandModeFlow: StateFlow<Int> = _bandModeFlow.asStateFlow()

    private val _autoSensitivityFlow = MutableStateFlow(true)
    val autoSensitivityFlow: StateFlow<Boolean> = _autoSensitivityFlow.asStateFlow()

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val beatCount = AtomicLong(0)

    private val engineLock = Any()
    @Volatile private var visualizer: Visualizer? = null
    @Volatile private var micJob: Job? = null
    @Volatile private var retryJob: Job? = null
    @Volatile private var captureSize = DEFAULT_CAPTURE_SIZE
    @Volatile private var vizSampleRateHz = 44100

    @Volatile private var running = false
    @Volatile private var previewOwned = false

    @Volatile var captureError: String? = null; private set
    @Volatile private var captureErrorRetryable = true

    private val levelBand = BandTracker(attackMs = 12f, releaseMs = 190f)
    private val bassBand = BandTracker(attackMs = 18f, releaseMs = 220f)
    private val midBand = BandTracker(attackMs = 15f, releaseMs = 200f)
    private val trebleBand = BandTracker(attackMs = 10f, releaseMs = 150f)
    private val micBands = MicBands()
    private val tempo = TempoTracker()

    private val vizInput = BandInput()
    private val micInput = BandInput()
    private val waveformBuf = FloatArray(WAVE_POINTS)
    private val waveLock = Any()

    private val pumpLock = Any()

    private val bandSignals = listOf(
        BandSignal(BAND_BROADBAND, attackMs = 10f, releaseMs = 160f),
        BandSignal(BAND_KICK, attackMs = 6f, releaseMs = 200f),
        BandSignal(BAND_SNARE),
        BandSignal(BAND_VOCAL),
        BandSignal(BAND_HAT, attackMs = 5f, releaseMs = 130f)
    )

    private val manualSignal = BandSignal(BAND_MANUAL, attackMs = 6f, releaseMs = 170f)

    @Volatile private var activeBandId = BAND_BROADBAND
    private var lastAutoSelectMs = 0L

    private var lastFiredMs = 0L

    private val beatTimes = LongArray(BEAT_TIME_MEMORY)
    private var beatTimeIndex = 0
    private var beatTimesSeen = 0
    private var lastControlMs = 0L

    private var appContext: Context? = null

    private val autoScoreBuf = FloatArray(BAND_COUNT)

    private var beatEnv = 0f
    private var lastBeatMs = 0L
    private var lastFrameMs = 0L
    private var lastPumpMs = 0L

    private var level = 0f
    private var bandNorm = 0f
    private var silentSinceMs = 0L

    private var fpsFrames = 0
    private var fpsWindowStartMs = 0L
    private var lastFps = 0
    private var beatsThisSecond = 0
    private var beatWindowStartMs = 0L
    private var lastDiagPublishMs = 0L

    val isRunning: Boolean get() = running

    fun beatCountOf(): Long = beatCount.get()

    fun loadFromPrefs(context: Context) {
        appContext = context.applicationContext
        val cfg = Prefs.audioConfig(context)
        val sourceChanged = cfg.source != audioSource
        val bandChanged = cfg.bandMode != bandMode ||
                cfg.minHz != minFreqHz || cfg.maxHz != maxFreqHz
        audioSource = cfg.source
        gainMultiplier = cfg.gain
        beatThreshold = cfg.threshold
        minFreqHz = cfg.minHz
        maxFreqHz = cfg.maxHz
        bandMode = cfg.bandMode
        degreesPerBeat = cfg.degreesPerBeat
        autoSensitivity = cfg.autoSensitivity
        _sourceFlow.value = audioSource
        _bandModeFlow.value = bandMode
        _autoSensitivityFlow.value = autoSensitivity
        activeBandId = if (bandMode == BAND_MODE_AUTO) BAND_BROADBAND else BAND_AUTO
        lastControlMs = 0L
        if (sourceChanged || bandChanged) {

            synchronized(pumpLock) {
                bandSignals.forEach { it.reset() }
                manualSignal.reset()
                lastAutoSelectMs = 0L
            }
        }
    }

    fun setSource(context: Context, source: Int) {
        val next = if (source == SOURCE_MIC) SOURCE_MIC else SOURCE_GLOBAL_MIX
        if (next == audioSource) {
            engineScope.launch { restart() }
            return
        }

        if (next == SOURCE_MIC) {
            audioSource = next
            _sourceFlow.value = next
            Prefs.setAudioSource(context, audioSource)
            DashDazzService.refreshForegroundType(context)
            engineScope.launch { restart() }
        } else {
            audioSource = next
            _sourceFlow.value = next
            Prefs.setAudioSource(context, audioSource)
            engineScope.launch { restart() }
            DashDazzService.refreshForegroundType(context)
        }
    }

    fun setGain(context: Context, gain: Float) {
        gainMultiplier = gain.coerceIn(0.1f, 4f)
        Prefs.setAudioGain(context, gainMultiplier)

        setAutoSensitivityInPlace(false)
        Prefs.setAudioAutoSensitivity(context, false)
    }

    fun setAutoSensitivity(context: Context, enabled: Boolean) {
        setAutoSensitivityInPlace(enabled)
        Prefs.setAudioAutoSensitivity(context, enabled)
    }

    private fun setAutoSensitivityInPlace(enabled: Boolean) {
        autoSensitivity = enabled
        _autoSensitivityFlow.value = enabled
    }

    fun setBeatThreshold(context: Context, threshold: Float) {
        beatThreshold = threshold.coerceIn(1.02f, 2f)
        Prefs.setAudioBeatThreshold(context, beatThreshold)

        setAutoSensitivityInPlace(false)
        Prefs.setAudioAutoSensitivity(context, false)
    }

    private fun maybeControlSensitivity(now: Long) {
        if (!autoSensitivity) return
        if (now - lastControlMs < AUTO_CONTROL_INTERVAL_MS) return
        lastControlMs = now

        if (level < SIGNAL_FLOOR) return

        val bps = beatsPerSecond(now)
        val current = beatThreshold
        val next = when {
            bps < AUTO_TARGET_BPS_MIN -> (current * AUTO_STEP_DOWN)
                .coerceAtLeast(AUTO_THRESHOLD_FLOOR)
            bps > AUTO_TARGET_BPS_MAX -> (current * AUTO_STEP_UP)
                .coerceAtMost(AUTO_THRESHOLD_CEILING)
            else -> return
        }
        if (next == current) return
        beatThreshold = next

    }

    fun setBandMode(context: Context, mode: Int) {
        val next = if (mode == BAND_MODE_MANUAL) BAND_MODE_MANUAL else BAND_MODE_AUTO
        if (next == bandMode) return
        bandMode = next
        _bandModeFlow.value = next
        Prefs.setAudioBandMode(context, next)

        bandSignals.forEach { it.reset() }
        manualSignal.reset()
        lastAutoSelectMs = 0L
        activeBandId = if (next == BAND_MODE_MANUAL) BAND_AUTO else BAND_BROADBAND
    }

    fun setDegreesPerBeat(context: Context, degrees: Float) {
        degreesPerBeat = degrees.coerceIn(MIN_DEGREES_PER_BEAT, MAX_DEGREES_PER_BEAT)
        Prefs.setAudioDegreesPerBeat(context, degreesPerBeat)
    }

    fun setFrequencyRange(context: Context, minHz: Float, maxHz: Float) {
        val lo = minHz.coerceIn(20f, 16000f)
        val hi = maxHz.coerceIn(lo + 50f, 16000f)
        if (lo == minFreqHz && hi == maxFreqHz) return
        minFreqHz = lo
        maxFreqHz = hi
        Prefs.setAudioBand(context, lo, hi)

        synchronized(pumpLock) {
            tempo.reset()
            silentSinceMs = 0L
            levelBand.reset()
            bandSignals.forEach { it.reset() }
            manualSignal.reset()
            lastAutoSelectMs = 0L
        }
    }

    fun start(): Boolean = synchronized(engineLock) {
        if (running) return@synchronized true
        if (!isAudioSourceAllowed()) {
            captureError = captureErrorGate
            _diagnostics.value = AudioDiagnostics(engineRunning = false, captureError = captureError)
            return@synchronized false
        }
        synchronized(pumpLock) { resetDsp() }
        val ok = if (audioSource == SOURCE_MIC) startMicCapture() else startVisualizer()
        if (!ok) {

            _diagnostics.value = AudioDiagnostics(engineRunning = false, captureError = captureError)
            if (audioSource == SOURCE_GLOBAL_MIX && captureErrorRetryable) {
                scheduleStartRetry()
            }
        }
        ok
    }

    private fun scheduleStartRetry(attempt: Int = 1) {
        retryJob?.cancel()
        if (attempt > START_RETRY_MAX) return
        val mine = engineScope.launch {
            delay(START_RETRY_BASE_MS * attempt)
            if (running || retryJob !== coroutineContext[Job]) return@launch
            synchronized(engineLock) {
                if (running || !isAudioSourceAllowed()) return@launch
                if (audioSource != SOURCE_GLOBAL_MIX) return@launch
                if (startVisualizer()) return@launch
            }
            _diagnostics.value = AudioDiagnostics(engineRunning = false, captureError = captureError)
            if (captureErrorRetryable) {
                scheduleStartRetry(attempt + 1)
            }
        }
        retryJob = mine
    }

    fun restart() {
        stop()
        start()
    }

    fun isAudioSourceAllowed(): Boolean {
        if (audioSource == SOURCE_MIC) return true
        val ctx = appContext ?: return true
        if (!Prefs.isAudioAppsFilter(ctx)) return true
        if (!DashDazzNotificationListener.isNotificationAccessGranted(ctx)) return true
        val allowed = Prefs.getAudioApps(ctx)
        if (allowed.isEmpty()) return true
        return DashDazzNotificationListener.playingPackages.any { it in allowed }
    }

    fun refreshAudioGate() {
        if (!isAudioSourceAllowed()) {
            retryJob?.cancel()
            retryJob = null
            if (running && !previewOwned) stop()
            return
        }
        val driving = DashDazzService.isRunning &&
            DashDazzService.stateFlow.value.audioReactive &&
            (!DashDazzService.stateFlow.value.isScreenPaused ||
             DashDazzService.stateFlow.value.screenMode == DashDazzService.SCREEN_NOTIF_ONLY)
        if (driving && !running) restart()
    }

    fun ensurePreview(context: Context) {
        if (running) return
        loadFromPrefs(context)
        previewOwned = start()
    }

    fun endPreview() {
        if (!previewOwned) return
        previewOwned = false
        if (DashDazzService.isRunning && DashDazzService.stateFlow.value.audioReactive) return
        stop()
    }

    fun disownPreview() {
        previewOwned = false
    }

    fun stop(clearError: Boolean = true) = synchronized(engineLock) {
        val wasRunning = running
        micJob?.cancel()
        micJob = null
        retryJob?.cancel()
        retryJob = null
        val viz = visualizer
        visualizer = null
        running = false
        try {
            viz?.enabled = false
        } catch (_: Throwable) {
        }
        try {
            viz?.release()
        } catch (_: Throwable) {
        }

        if (autoSensitivity) {
            appContext?.let { Prefs.setAudioBeatThreshold(it, beatThreshold) }
        }
        if (clearError) captureError = null
        captureErrorRetryable = true
        synchronized(pumpLock) { resetDsp() }
        _diagnostics.value = AudioDiagnostics(captureError = captureError)
    }

    private fun resetDsp() {
        vizInput.level = 0f
        vizInput.manual = 0f
        vizInput.bands.fill(0f)
        micInput.level = 0f
        micInput.manual = 0f
        micInput.bands.fill(0f)
        levelBand.reset()
        bassBand.reset()
        midBand.reset()
        trebleBand.reset()
        micBands.reset()
        tempo.reset()
        bandSignals.forEach { it.reset() }
        manualSignal.reset()
        autoScoreBuf.fill(0f)
        lastAutoSelectMs = 0L
        activeBandId = if (bandMode == BAND_MODE_AUTO) BAND_BROADBAND else BAND_AUTO
        lastFiredMs = 0L
        beatEnv = 0f
        lastBeatMs = 0L
        lastFrameMs = 0L
        lastPumpMs = 0L
        level = 0f
        bandNorm = 0f
        silentSinceMs = 0L
        fpsFrames = 0
        fpsWindowStartMs = 0L
        lastFps = 0
        beatsThisSecond = 0
        beatWindowStartMs = 0L
        lastDiagPublishMs = 0L

        beatTimeIndex = 0
        beatTimesSeen = 0
        beatTimes.fill(0L)
        lastControlMs = 0L
    }

    private fun startVisualizer(): Boolean {
        var viz: Visualizer? = null
        return try {
            viz = Visualizer(0)
            val range = Visualizer.getCaptureSizeRange()

            val safeSize = if (range != null && range.size >= 2) {
                captureSize.coerceIn(range[0], range[1])
            } else {
                captureSize
            }
            viz.captureSize = safeSize
            captureSize = safeSize

            viz.setDataCaptureListener(captureListener, Visualizer.getMaxCaptureRate(), true, true)
            viz.enabled = true

            visualizer = viz
            running = true
            captureError = null
            captureErrorRetryable = true
            Log.d(TAG, "Visualizer active (captureSize=$safeSize, rate=${Visualizer.getMaxCaptureRate()}mHz)")
            true
        } catch (e: Throwable) {
            try {
                viz?.release()
            } catch (_: Throwable) {
            }
            if (visualizer == viz) visualizer = null
            Log.e(TAG, "Visualizer failed: ${e.message}", e)
            running = false
            captureError = e.message ?: e.javaClass.simpleName
            captureErrorRetryable = e !is SecurityException
            false
        }
    }

    private fun startMicCapture(): Boolean {
        val sources = ArrayList<Int>(3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {

            sources.add(MediaRecorder.AudioSource.UNPROCESSED)
        }
        sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        sources.add(MediaRecorder.AudioSource.MIC)

        for (source in sources) {
            val record = try {
                buildAudioRecord(source)
            } catch (_: Exception) {
                null
            }
            if (record != null) {
                launchMicLoop(record)
                return true
            }
        }
        Log.e(TAG, "No usable microphone input source")
        running = false
        captureError = "Microphone unavailable"
        return false
    }

    private fun buildAudioRecord(source: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                source,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(MIC_FRAME * 4)
            )
        } catch (_: Throwable) {
            return null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            try {
                record?.release()
            } catch (_: Throwable) {
            }
            return null
        }
        try {
            record.startRecording()
        } catch (e: Throwable) {
            try {
                record.release()
            } catch (_: Throwable) {
            }
            return null
        }
        return record
    }

    private fun launchMicLoop(record: AudioRecord) {
        running = true
        captureError = null
        micJob = engineScope.launch {
            val frame = ShortArray(MIC_FRAME)
            try {
                while (running) {
                    ensureActive()
                    val read = record.read(frame, 0, frame.size)
                    if (read > 0) {
                        processMicFrame(frame, read)
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord.read error: $read")
                        running = false
                        captureError = "Mic read error $read"
                        break
                    }
                }
            } catch (e: Exception) {

                if (e is CancellationException) throw e
                Log.e(TAG, "Mic loop failed: ${e.message}", e)
                running = false
                captureError = e.message ?: "Mic failed"
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                try {
                    record.release()
                } catch (_: Exception) {
                }
            }
        }
        Log.d(TAG, "Mic capture started (source=${record.audioSource})")
    }

    private fun processMicFrame(pcm: ShortArray, count: Int) {
        micBands.analyse(pcm, count, MIC_SAMPLE_RATE)

        synchronized(waveLock) {
            for (i in 0 until WAVE_POINTS) {
                val idx = if (WAVE_POINTS == 1) 0
                else ((i * (count - 1)) / (WAVE_POINTS - 1)).coerceIn(0, count - 1)
                waveformBuf[i] = (pcm[idx] / 32767f).coerceIn(-1f, 1f)
            }
        }

        var sumSquares = 0.0
        for (i in 0 until count) {
            val v = pcm[i] / 32768f
            sumSquares += v * v
        }
        val rms = sqrt(sumSquares / count).toFloat()

        micInput.setBand(BAND_BROADBAND, rms)
        micInput.setBand(BAND_KICK, micBands.bass)
        micInput.setBand(BAND_SNARE, micBands.lowMid)
        micInput.setBand(BAND_VOCAL, micBands.highMid)
        micInput.setBand(BAND_HAT, micBands.air)
        micInput.manual = when {
            (minFreqHz + maxFreqHz) / 2f < 400f -> micBands.bass
            (minFreqHz + maxFreqHz) / 2f < 1500f -> micBands.lowMid
            (minFreqHz + maxFreqHz) / 2f < 4000f -> micBands.highMid
            else -> micBands.air
        }
        micInput.level = rms
        pump()
    }

    private val captureListener = object : Visualizer.OnDataCaptureListener {

        override fun onWaveFormDataCapture(viz: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
            if (waveform == null || waveform.isEmpty()) return
            vizSampleRateHz = sampleRateToHz(samplingRate)

            synchronized(waveLock) {
                val stride = max(1, waveform.size / WAVE_POINTS)
                for (i in 0 until WAVE_POINTS) {
                    val idx = min(i * stride, waveform.size - 1)
                    waveformBuf[i] = (((waveform[idx].toInt() and 0xFF) - 128) / 128f).coerceIn(-1f, 1f)
                }
            }

            var sumSquares = 0.0
            for (b in waveform) {
                val v = ((b.toInt() and 0xFF) - 128) / 128f
                sumSquares += v * v
            }
            val rms = sqrt(sumSquares / waveform.size).toFloat()
            vizInput.level = rms
            pump()
        }

        override fun onFftDataCapture(viz: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft == null || fft.isEmpty()) return
            vizSampleRateHz = sampleRateToHz(samplingRate)
            analyseFft(fft)
            pump()
        }
    }

    private fun sampleRateToHz(milliHz: Int): Int {
        val hz = if (milliHz > 0) milliHz / 1000 else 0
        return if (hz in 8000..192000) hz else 44100
    }

    private fun analyseFft(fft: ByteArray) {
        val numBins = captureSize / 2
        if (numBins < 8) return

        if (fft.size < numBins * 2) return
        val nyquist = vizSampleRateHz / 2f

        val startBin = ((minFreqHz / nyquist) * numBins).toInt().coerceIn(1, numBins - 2)
        val endBin = ((maxFreqHz / nyquist) * numBins).toInt().coerceIn(startBin + 1, numBins - 1)
        val sums = FloatArray(BAND_COUNT)
        val counts = IntArray(BAND_COUNT)
        var manualSum = 0f
        var manualN = 0

        for (i in 1 until numBins) {
            val re = fft[2 * i].toFloat()
            val im = fft[2 * i + 1].toFloat()
            val mag = sqrt(re * re + im * im) * BIN_SCALE
            val freq = i * nyquist / numBins
            for (b in 0 until BAND_COUNT) {
                val edges = BAND_EDGES_HZ[b]
                if (freq >= edges[0] && freq <= edges[1]) {
                    sums[b] += mag
                    counts[b]++
                }
            }
            if (i in startBin..endBin) {
                manualSum += mag
                manualN++
            }
        }

        for (b in 0 until BAND_COUNT) {
            vizInput.setBand(b, if (counts[b] > 0) sums[b] / counts[b] else 0f)
        }
        vizInput.manual = if (manualN > 0) manualSum / manualN else 0f
    }

    private fun pump() {
        synchronized(pumpLock) {
            pumpLocked()
        }
    }

    private fun pumpLocked() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPumpMs < MIN_PUMP_GAP_MS) return
        lastPumpMs = now

        val dt = if (lastFrameMs == 0L) 22f else (now - lastFrameMs).coerceIn(1L, 400L).toFloat()
        lastFrameMs = now

        val input = if (audioSource == SOURCE_MIC) micInput else vizInput

        val userGain = if (autoSensitivity) 1f else gainMultiplier
        levelBand.push(input.level * userGain, dt, autoSensitivity)

        bassBand.push((input.bands[BAND_KICK] + input.bands[BAND_SNARE]) * 0.5f * userGain, dt, autoSensitivity)
        midBand.push(input.bands[BAND_VOCAL] * userGain, dt, autoSensitivity)
        trebleBand.push(input.bands[BAND_HAT] * userGain, dt, autoSensitivity)

        level = levelBand.value

        var activeFired = false
        val globalRef = refractoryMs()
        for (signal in bandSignals) {
            signal.push(input.bands[signal.id] * userGain, dt, autoSensitivity)
            val own = signal.medianMs()
            val ref = if (own > 0f) (own * 0.42f).toLong().coerceIn(MIN_BEAT_GAP_MS, MAX_BEAT_GAP_MS)
            else globalRef
            val fired = signal.detect(now, beatThreshold, ref)
            if (bandMode == BAND_MODE_AUTO && signal.id == activeBandId && fired) {
                activeFired = true
            }
        }
        manualSignal.push(input.manual * userGain, dt, autoSensitivity)
        val manualOwn = manualSignal.medianMs()
        val manualRef = if (manualOwn > 0f) (manualOwn * 0.42f).toLong().coerceIn(MIN_BEAT_GAP_MS, MAX_BEAT_GAP_MS)
        else globalRef
        if (bandMode == BAND_MODE_MANUAL &&
            manualSignal.detect(now, beatThreshold, manualRef)
        ) {
            activeFired = true
        }

        bandNorm = activeSignal()?.value ?: 0f
        if (activeFired) onBeat(now, now - lastBeatMs)

        maybeSelectAutoBand(now)
        maybeControlSensitivity(now)

        silentSinceMs = if (level < SIGNAL_FLOOR) {
            if (silentSinceMs == 0L) now else silentSinceMs
        } else {
            0L
        }

        fpsFrames++
        maybePublishDiagnostics(now)
    }

    private fun activeSignal(): BandSignal? =
        if (bandMode == BAND_MODE_MANUAL) {
            manualSignal
        } else {
            bandSignals.firstOrNull { it.id == activeBandId }
        }

    private fun beatsPerSecond(nowMs: Long): Float {
        val total = minOf(beatTimesSeen, BEAT_TIME_MEMORY)
        var n = 0
        for (i in 0 until total) {
            if (nowMs - beatTimes[i] <= AUTO_RATE_WINDOW_MS) n++
        }
        return n * (1000f / AUTO_RATE_WINDOW_MS)
    }

    private fun refractoryMs(): Long {
        val median = tempo.medianMs()
        if (median <= 0f) return 220L
        return (median * 0.42f).toLong().coerceIn(MIN_BEAT_GAP_MS, MAX_BEAT_GAP_MS)
    }

    private fun maybeSelectAutoBand(nowMs: Long) {
        if (bandMode != BAND_MODE_AUTO) return
        if (nowMs - lastAutoSelectMs < AUTO_SELECT_INTERVAL_MS) return
        lastAutoSelectMs = nowMs

        var bestId = -1
        var bestScore = 0f
        for (signal in bandSignals) {
            signal.updateScore(nowMs)
            autoScoreBuf[signal.id] = signal.score
            if (signal.score > bestScore) {
                bestScore = signal.score
                bestId = signal.id
            }
        }

        if (bestId < 0 || bestScore <= 0f) return
        val current = bandSignals.firstOrNull { it.id == activeBandId }
        if (current == null) {
            activeBandId = bestId
            return
        }

        if (bestScore >= current.score * AUTO_SWITCH_MARGIN) {
            activeBandId = bestId
            lastBeatMs = 0L
            tempo.reset()
        }
    }

    private fun onBeat(now: Long, gap: Long) {

        if (lastFiredMs != 0L && now - lastFiredMs < AUTO_MIN_FIRE_GAP_MS) return
        if (lastBeatMs != 0L) tempo.push(gap.toFloat())
        lastBeatMs = now
        lastFiredMs = now
        beatsThisSecond++
        beatTimes[beatTimeIndex] = now
        beatTimeIndex = (beatTimeIndex + 1) % BEAT_TIME_MEMORY
        beatTimesSeen++
        beatEnv = 1f
        beatCount.incrementAndGet()
    }

    private fun maybePublishDiagnostics(now: Long) {

        val observed = _diagnostics.subscriptionCount.value > 0
        val interval = if (observed) DIAGNOSTICS_INTERVAL_MS else 1000L
        if (now - lastDiagPublishMs < interval) return
        lastDiagPublishMs = now

        var fps = lastFps
        if (fpsWindowStartMs == 0L) {
            fpsWindowStartMs = now
        } else if (now - fpsWindowStartMs >= 1000L) {

            lastFps = fpsFrames
            fps = lastFps
            fpsFrames = 0
            fpsWindowStartMs = now
        }

        if (beatWindowStartMs == 0L) {
            beatWindowStartMs = now
        } else if (now - beatWindowStartMs >= 1000L) {
            beatsThisSecond = 0
            beatWindowStartMs = now
        }

        synchronized(waveLock) {
            _diagnostics.value = AudioDiagnostics(
                waveform = waveformBuf.copyOf(),
                level = level,
                bass = bassBand.value,
                mid = midBand.value,
                treble = trebleBand.value,
                targetEnergy = bandNorm,
                rms = level,
                isBeat = decayedBeat(now) > BEAT_VISIBLE_FLOOR,
                fps = fps,
                bpm = tempo.bpm(),
                silent = isSilent(now),
                activeBand = if (bandMode == BAND_MODE_AUTO) activeBandId else BAND_AUTO,
                bandScores = autoScoreBuf.copyOf(),
                engineRunning = running,
                captureError = captureError,
                sourceName = if (audioSource == SOURCE_MIC) "Microphone" else "Global Media Mix",
                hasAudioSignal = level > SIGNAL_FLOOR,
                beatsPerSecond = beatsThisSecond,
                gainScale = levelBand.gainScale,
                threshold = beatThreshold,
                autoSensitivity = autoSensitivity
            )
        }
    }

    private fun isSilent(now: Long): Boolean =
        silentSinceMs != 0L && now - silentSinceMs >= SILENCE_TRIGGER_MS

    private fun decayedBeat(nowMs: Long): Float {
        val sinceBeat = (nowMs - lastBeatMs).coerceIn(0L, 60_000L).toFloat()
        return (beatEnv * exp(-sinceBeat / BEAT_DECAY_MS)).coerceIn(0f, 1f)
    }

    fun sample(nowMs: Long = SystemClock.elapsedRealtime()): AudioFrame =
        synchronized(pumpLock) {
            AudioFrame(
                nowMs = nowMs,
                level = level,
                bass = bassBand.value,
                mid = midBand.value,
                treble = trebleBand.value,
                band = bandNorm,
                beat = decayedBeat(nowMs),
                beatCount = beatCount.get(),
                bpm = tempo.bpm(),
                hasSignal = level > SIGNAL_FLOOR,
                silent = isSilent(nowMs),
                silentForMs = if (silentSinceMs == 0L) 0L else (nowMs - silentSinceMs).coerceAtLeast(0L),
                beatsPerSecond = beatsPerSecond(nowMs).toInt()
            )
        }
}
