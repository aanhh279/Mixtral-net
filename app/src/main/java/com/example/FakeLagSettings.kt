package com.example

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FakeLagSettings {
    // Core Activation States
    val isVpnActive = MutableStateFlow(false)
    val isFreezeActive = MutableStateFlow(false)
    val isGhostActive = MutableStateFlow(false)
    
    // Teleport phase: 0 = OFF, 1 = Phase 1 (Accumulate), 2 = Phase 2 (Release)
    val teleportPhase = MutableStateFlow(0)

    // Floating overlay service status
    val isOverlayActive = MutableStateFlow(false)
    val buttonScale = MutableStateFlow(1.0f) // Scale multiplier (0.5 to 2.0)

    // User Authentication states
    val isLoggedIn = MutableStateFlow(false)
    val username = MutableStateFlow("")
    val isAdmin = MutableStateFlow(false)
    val remainingTimeSeconds = MutableStateFlow(1500) // 25 minutes = 1500 seconds
    val loginErrorMessage = MutableStateFlow<String?>(null)

    // Allowed applications to intercept (Free Fire and Free Fire Max by default)
    val allowedApps = MutableStateFlow<Set<String>>(setOf("com.dts.freefireth", "com.dts.freefiremax"))

    // Floating Button Visibility and Pin States
    val showVpnButton = MutableStateFlow(true)
    val showFreezeButton = MutableStateFlow(true)
    val showGhostButton = MutableStateFlow(true)
    val showTeleportButton = MutableStateFlow(true)

    val pinVpnButton = MutableStateFlow(false)
    val pinFreezeButton = MutableStateFlow(false)
    val pinGhostButton = MutableStateFlow(false)
    val pinTeleportButton = MutableStateFlow(false)
    val pinControllerButton = MutableStateFlow(false)

    // Button Size Setting (Small, Medium, Large, or custom via slider)
    val buttonSizeDp = MutableStateFlow(68) // Default is 68dp
    val buttonAlpha = MutableStateFlow(1.0f) // Default is 1.0f
    val borderThickness = MutableStateFlow(1.5f) // Default base border thickness in dp

    // Simulated network settings (from python GUI)
    val simulatedPing = MutableStateFlow(790) // ms (Default 790ms as requested)
    val pingJitter = MutableStateFlow(25) // ms
    val pingMode = MutableStateFlow("Jitter") // "Static", "Jitter", "Wave"
    val freezeDropRate = MutableStateFlow(100) // %
    val freezeMinSize = MutableStateFlow(20) // bytes
    val freezeMaxSize = MutableStateFlow(500) // bytes

    val ghostReplayCount = MutableStateFlow(3)
    val ghostMinSize = MutableStateFlow(0) // bytes (Default 0 as requested)
    val ghostMaxSize = MutableStateFlow(500) // bytes (Default 500 as requested)
    val ghostBlockThreshold = MutableStateFlow(80) // bytes
    val posUpdateInterval = MutableStateFlow(68) // ms

    val teleportReleaseWindow = MutableStateFlow(800) // Optimized for faster jump
    val telekillAutoDelay = MutableStateFlow(0.3f) // Near-instant teleport delay

    val bwBlockUpload = MutableStateFlow(false)
    val bwBlockDownload = MutableStateFlow(false)
    val bwUploadLimit = MutableStateFlow(0) // KB/s, 0 = unlimited
    val bwDownloadLimit = MutableStateFlow(0) // KB/s, 0 = unlimited

    // Live intercept logs
    data class LogEntry(
        val timestamp: String,
        val message: String,
        val type: LogType
    )

    enum class LogType {
        INFO, SUCCESS, WARNING, FREEZE, GHOST, TELEPORT, ERROR
    }

    // App Version
    const val APP_VERSION = "20.381.4"

    // Aimbot & Bypass Settings
    val aimbotEnabled = MutableStateFlow(false)
    val espEnabled = MutableStateFlow(false)
    val neuralBypassActive = MutableStateFlow(true)
    val aimBone = MutableStateFlow("Head")
    val aimFov = MutableStateFlow(90f)
    val aimSmooth = MutableStateFlow(5f)

    // User Data
    val currentUsername = MutableStateFlow("Guest_User")

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    init {
        scope.launch {
            while (isActive) {
                delay(200L) // Flush logs every 200ms to avoid freezing main thread
                if (logQueue.isNotEmpty()) {
                    val currentList = _logs.value.toMutableList()
                    var addedCount = 0
                    while (logQueue.isNotEmpty() && addedCount < 50) {
                        val entry = logQueue.poll()
                        if (entry != null) {
                            currentList.add(0, entry)
                            addedCount++
                        }
                    }
                    if (currentList.size > 150) {
                        _logs.value = currentList.take(150)
                    } else {
                        _logs.value = currentList
                    }
                }
            }
        }
    }

    fun log(message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(
            timestamp = sdf.format(Date()),
            message = message,
            type = type
        )
        logQueue.add(entry)
    }

    fun clearLogs() {
        logQueue.clear()
        _logs.value = emptyList()
    }

    private var toneGenerator: ToneGenerator? = null

    fun playBeep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        } catch (e: Throwable) {
            // Ignore completely to prevent any audio or ToneGenerator-related crashes
        }
    }

    fun toggleFreeze() {
        val newState = !isFreezeActive.value
        isFreezeActive.value = newState
        log(
            if (newState) "❄️ Kích hoạt Freeze Switch" else "❄️ Đã tắt Freeze Switch",
            if (newState) LogType.FREEZE else LogType.WARNING
        )
        playBeep()
    }

    fun toggleGhost() {
        val newState = !isGhostActive.value
        isGhostActive.value = newState
        log(
            if (newState) "👻 Kích hoạt Ghost Mode" else "👻 Đã tắt Ghost Mode",
            if (newState) LogType.GHOST else LogType.WARNING
        )
        playBeep()
    }

    fun toggleTeleport() {
        val current = teleportPhase.value
        val next = (current + 1) % 3
        teleportPhase.value = next
        
        when (next) {
            1 -> log("🌀 TELEPORT: Bật Phase 1 (Đang tích vị trí)", LogType.TELEPORT)
            2 -> log("🌀 TELEPORT: Kích hoạt Burst Release!", LogType.TELEPORT)
            0 -> log("🌀 TELEPORT: Đã tắt hoàn toàn.", LogType.WARNING)
        }
        playBeep()
    }
}
