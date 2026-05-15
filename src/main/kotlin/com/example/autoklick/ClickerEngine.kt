package com.example.autoklick

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.*
import java.util.concurrent.locks.LockSupport

enum class ActionType { PRESS, RELEASE, BOTH }

@Serializable
sealed class ActionItem {
    var skip: Int = 0
    var actionType: ActionType = ActionType.BOTH

    @Transient var currentAps = 0L
    @Transient var minAps = Long.MAX_VALUE
    @Transient var maxAps = 0L
    @Transient var avgAps = 0L
    @Transient var historySum = 0L
    @Transient var historyCount = 0L

    @Transient val executionCount = AtomicLong(0L)
    @Transient val sessionTotalCount = AtomicLong(0L)

    fun resetEngineMetrics() {
        executionCount.set(0L)
    }
    
    fun resetSessionStats() {
        currentAps = 0L
        minAps = Long.MAX_VALUE
        maxAps = 0L
        avgAps = 0L
        historySum = 0L
        historyCount = 0L
        sessionTotalCount.set(0L) // Also reset total count for this action
    }

    @Serializable
    class MouseButton(val mask: Int, val label: String) : ActionItem() {
        override fun toString() = "Mouse $label"
    }
    @Serializable
    class MouseScroll(val direction: Int) : ActionItem() {
        override fun toString() = if (direction < 0) "Scroll Up" else "Scroll Down"
    }
    @Serializable
    class KeyPress(val keyCode: Int, val label: String) : ActionItem() {
        override fun toString() = "Key $label"
    }
}

@Serializable
data class AppSettings(
    val timeoutMs: Long = 10000L,
    val actions: List<ActionItem> = emptyList(),
    val shouldLockLocation: Boolean = true,
    val lockedX: Int = 0,
    val lockedY: Int = 0,
    val toggleNativeKeyCode: Int = NativeKeyEvent.VC_F8,
    val toggleKeyLabel: String = "F8",
    val statsPollingIntervalMs: Long = 1000L,
    val exitToTray: Boolean = true,
    val targetTps: Long = 0L,
    val keepControlMode: Boolean = false,
    val keepControlDelayMs: Long = 100L,
    val isEnabled: Boolean = true
)

class ClickerEngine(var profileName: String = "Default") {
    private val _isClicking = MutableStateFlow(false)
    val isClicking = _isClicking.asStateFlow()

    private val clicking = AtomicBoolean(false)
    private var clickThread: Thread? = null
    private val robot = Robot()
    
    val totalTicks = AtomicLong(0L)
    val totalClicks = AtomicLong(0L)
    
    var timeoutMs: Long = 10_000L
    var actions = mutableListOf<ActionItem>()
    var shouldLockLocation = true
    var lockedX = 0
    var lockedY = 0
    var toggleNativeKeyCode = NativeKeyEvent.VC_F8
    var toggleKeyLabel = "F8"
    var statsPollingIntervalMs: Long = 1000L
    var exitToTray: Boolean = true
    var targetTps: Long = 0L // 0 means unlimited
    var keepControlMode: Boolean = false
    var keepControlDelayMs: Long = 100L
    var isEnabled: Boolean = true

    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
        serializersModule = SerializersModule {
            polymorphic(ActionItem::class) {
                subclass(ActionItem.MouseButton::class)
                subclass(ActionItem.MouseScroll::class)
                subclass(ActionItem.KeyPress::class)
            }
        }
    }

    companion object {
        val configDir: File by lazy {
            val os = System.getProperty("os.name").lowercase()
            val dir = when {
                os.contains("win") -> File(System.getenv("APPDATA"), "auto-klick")
                os.contains("mac") -> File(System.getProperty("user.home"), "Library/Application Support/auto-klick")
                else -> File(System.getProperty("user.home"), ".config/auto-klick")
            }
            if (!dir.exists()) dir.mkdirs()
            dir
        }

        fun listProfiles(): List<String> {
            return configDir.listFiles { _, name -> name.endsWith(".json") }
                ?.map { it.nameWithoutExtension }
                ?.distinct()
                ?.sorted()
                ?: emptyList()
        }
    }

    private val settingsFile get() = File(configDir, "$profileName.json")

    fun loadSettings() {
        if (settingsFile.exists()) {
            try {
                val text = settingsFile.readText()
                val settings = json.decodeFromString<AppSettings>(text)
                timeoutMs = settings.timeoutMs
                actions = settings.actions.toMutableList()
                shouldLockLocation = settings.shouldLockLocation
                lockedX = settings.lockedX
                lockedY = settings.lockedY
                toggleNativeKeyCode = settings.toggleNativeKeyCode
                toggleKeyLabel = settings.toggleKeyLabel
                statsPollingIntervalMs = settings.statsPollingIntervalMs
                exitToTray = settings.exitToTray
                targetTps = settings.targetTps
                keepControlMode = settings.keepControlMode
                keepControlDelayMs = settings.keepControlDelayMs
                isEnabled = settings.isEnabled
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveSettings() {
        try {
            val settings = AppSettings(timeoutMs, actions.toList(), shouldLockLocation, lockedX, lockedY, toggleNativeKeyCode, toggleKeyLabel, statsPollingIntervalMs, exitToTray, targetTps, keepControlMode, keepControlDelayMs, isEnabled)
            val text = json.encodeToString(settings)
            settingsFile.writeText(text)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteProfile() {
        stopClicking()
        if (settingsFile.exists()) {
            settingsFile.delete()
        }
    }

    fun renameProfile(newName: String) {
        val oldFile = settingsFile
        if (oldFile.exists()) {
            oldFile.delete()
        }
        profileName = newName
        saveSettings()
    }

    fun toggleClicking() {
        if (clicking.get()) stopClicking() else startClicking()
    }

    private fun startClicking() {
        if (!isEnabled) return
        if (!clicking.compareAndSet(false, true)) return
        _isClicking.value = true
        totalTicks.set(0L)
        totalClicks.set(0L) 
        actions.forEach { 
            it.resetEngineMetrics()
            it.resetSessionStats()
        }

        clickThread = Thread {
            Thread.sleep(500)
            if (shouldLockLocation && lockedX == 0 && lockedY == 0) {
                val current = MouseInfo.getPointerInfo()?.location
                lockedX = current?.x ?: 0
                lockedY = current?.y ?: 0
            }

            val startTime = System.nanoTime()
            val timeoutNanos = timeoutMs * 1_000_000L
            var tickIndex = 0L

            try {
                while (clicking.get() && isEnabled) {
                    val loopStartTime = System.nanoTime()
                    
                    if (timeoutMs > 0 && (loopStartTime - startTime) >= timeoutNanos) break

                    // KeepControl Mode Logic
                    if (keepControlMode && shouldLockLocation) {
                        val currentMouse = MouseInfo.getPointerInfo()?.location
                        if (currentMouse != null && (currentMouse.x != lockedX || currentMouse.y != lockedY)) {
                            // Mouse has moved away from the locked location, delay this tick
                            Thread.sleep(keepControlDelayMs)
                            // Skip actions for this tick, but still apply rate limit
                            tickIndex++
                            totalTicks.set(tickIndex)
                            applyRateLimit(loopStartTime)
                            continue // Skip to next tick
                        }
                    }
                    
                    // If not in KeepControl mode, or if mouse is at locked location, move it
                    if (shouldLockLocation) {
                        robot.mouseMove(lockedX, lockedY)
                    }

                    for (action in actions) {
                        if (tickIndex % (action.skip + 1) == 0L) {
                            when (action) {
                                is ActionItem.MouseButton -> {
                                    if (action.actionType != ActionType.RELEASE) robot.mousePress(action.mask)
                                    if (action.actionType != ActionType.PRESS) robot.mouseRelease(action.mask)
                                }
                                is ActionItem.MouseScroll -> robot.mouseWheel(action.direction)
                                is ActionItem.KeyPress -> {
                                    if (action.actionType != ActionType.RELEASE) robot.keyPress(action.keyCode)
                                    if (action.actionType != ActionType.PRESS) robot.keyRelease(action.keyCode)
                                }
                            }
                            totalClicks.incrementAndGet()
                            action.executionCount.incrementAndGet()
                            action.sessionTotalCount.incrementAndGet()
                        }
                    }
                    tickIndex++
                    totalTicks.set(tickIndex)
                    
                    applyRateLimit(loopStartTime)
                }
            } finally { stopClicking() }
        }.apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun applyRateLimit(loopStartTime: Long) {
        if (targetTps > 0) {
            val targetIntervalNanos = 1_000_000_000L / targetTps
            val elapsed = System.nanoTime() - loopStartTime
            val sleepNanos = targetIntervalNanos - elapsed
            if (sleepNanos > 0) {
                LockSupport.parkNanos(sleepNanos)
            }
        }
    }

    fun stopClicking() {
        if (clicking.compareAndSet(true, false)) {
            _isClicking.value = false
            clickThread?.interrupt()
            clickThread = null
        }
    }

    fun captureCurrentLocation() {
        val current = MouseInfo.getPointerInfo()?.location
        lockedX = current?.x ?: 0
        lockedY = current?.y ?: 0
    }
}