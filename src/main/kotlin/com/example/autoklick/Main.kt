@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.autoklick

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp

// Global state for exitToTray since it was moved to the top bar
val GlobalExitToTray = mutableStateOf(false)

@Composable
fun ProfileTabContent(engine: ClickerEngine, listener: GlobalKeyListener) {
    val focusManager = LocalFocusManager.current
    val secFormat = remember { DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.US)) }
    
    var profileNameInput by remember(engine) { mutableStateOf(engine.profileName) }
    var isEnabledChecked by remember(engine) { mutableStateOf(engine.isEnabled) }
    var timeoutInput by remember(engine) { mutableStateOf(engine.timeoutMs.toString()) }
    var timeoutSecInput by remember(engine) { mutableStateOf(secFormat.format(engine.timeoutMs / 1000.0)) }
    var pollingIntervalInput by remember(engine) { mutableStateOf(engine.statsPollingIntervalMs.toString()) }
    var pollingIntervalSecInput by remember(engine) { mutableStateOf(secFormat.format(engine.statsPollingIntervalMs / 1000.0)) }
    var targetTpsInput by remember(engine) { mutableStateOf(engine.targetTps.toString()) }
    
    var currentTps by remember { mutableLongStateOf(0L) }
    var minTps by remember { mutableLongStateOf(Long.MAX_VALUE) }
    var maxTps by remember { mutableLongStateOf(0L) }
    var avgTps by remember { mutableLongStateOf(0L) }
    var tpsHistoryCount by remember { mutableLongStateOf(0L) }
    var totalTpsSum by remember { mutableLongStateOf(0L) }
    
    var totalClicksFormatted by remember { mutableStateOf("0") }
    var statsVersion by remember { mutableLongStateOf(0L) }

    var lockChecked by remember(engine) { mutableStateOf(engine.shouldLockLocation) }
    var keepControlChecked by remember(engine) { mutableStateOf(engine.keepControlMode) }
    var keepControlDelayInput by remember { mutableStateOf(engine.keepControlDelayMs.toString()) }
    var keepControlDelaySecInput by remember { mutableStateOf(secFormat.format(engine.keepControlDelayMs / 1000.0)) }
    
    var xInput by remember(engine) { mutableStateOf(engine.lockedX.toString()) }
    var yInput by remember(engine) { mutableStateOf(engine.lockedY.toString()) }
    val actionItems = remember(engine) { mutableStateListOf<ActionItem>().apply { addAll(engine.actions) } }
    val isClicking by engine.isClicking.collectAsState()
    var toggleKeyLabel by remember(engine) { mutableStateOf(engine.toggleKeyLabel) }
    
    // We don't want this state tied just to remember block because listener properties can change outside composition
    // However, recomposition is triggered by polling interval currently.
    var isRecording by remember { mutableStateOf(false) }

    var actionTextInput by remember { mutableStateOf("") }
    val scientificFormat = remember { DecimalFormat("0.###E0") }

    val keyMap = remember {
        val map = mutableMapOf<String, Int>()
        for (i in KeyEvent.VK_A..KeyEvent.VK_Z) map[i.toChar().toString().lowercase()] = i
        for (i in KeyEvent.VK_0..KeyEvent.VK_9) map[(i - KeyEvent.VK_0).toString()] = i
        for (i in 1..12) map["f$i"] = KeyEvent.VK_F1 + i - 1
        map["space"] = KeyEvent.VK_SPACE
        map["enter"] = KeyEvent.VK_ENTER
        map["tab"] = KeyEvent.VK_TAB
        map["esc"] = KeyEvent.VK_ESCAPE
        map["backspace"] = KeyEvent.VK_BACK_SPACE
        map["shift"] = KeyEvent.VK_SHIFT
        map["ctrl"] = KeyEvent.VK_CONTROL
        map["alt"] = KeyEvent.VK_ALT
        map["win"] = KeyEvent.VK_META
        map["leftclick"] = -1
        map["rightclick"] = -2
        map["middleclick"] = -3
        map["scrollup"] = -4
        map["scrolldown"] = -5
        map
    }

    fun tryAddAction(input: String) {
        val cleanInput = input.trim().lowercase()
        val code = keyMap[cleanInput]
        if (code != null) {
            val newAction = when (code) {
                -1 -> ActionItem.MouseButton(InputEvent.BUTTON1_DOWN_MASK, "Left Click")
                -2 -> ActionItem.MouseButton(InputEvent.BUTTON3_DOWN_MASK, "Right Click")
                -3 -> ActionItem.MouseButton(InputEvent.BUTTON2_DOWN_MASK, "Middle Click")
                -4 -> ActionItem.MouseScroll(-1)
                -5 -> ActionItem.MouseScroll(1)
                else -> ActionItem.KeyPress(code, cleanInput.uppercase())
            }
            actionItems.add(newAction)
            engine.actions = actionItems.toMutableList()
            engine.saveSettings()
            actionTextInput = ""
        }
    }

    LaunchedEffect(isClicking) { if (isClicking) focusManager.clearFocus() }

    var timeLeftProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(engine, engine.statsPollingIntervalMs) {
        var lastTotalTicks = 0L
        while (true) {
            val startTime = System.currentTimeMillis()
            val currentTotal = engine.totalTicks.get()
            val deltaTicks = if (currentTotal >= lastTotalTicks) currentTotal - lastTotalTicks else currentTotal
            lastTotalTicks = currentTotal
            val intervalSeconds = engine.statsPollingIntervalMs / 1000.0
            currentTps = (deltaTicks / intervalSeconds).toLong()
            
            if (isClicking && currentTps > 0) {
                if (currentTps < minTps) minTps = currentTps
                if (currentTps > maxTps) maxTps = currentTps
                totalTpsSum += currentTps
                tpsHistoryCount++
                avgTps = totalTpsSum / tpsHistoryCount
            }

            actionItems.forEach { action ->
                val executed = action.executionCount.getAndSet(0L)
                action.currentAps = (executed / intervalSeconds).toLong()
                if (isClicking && action.currentAps > 0) {
                    if (action.currentAps < action.minAps) action.minAps = action.currentAps
                    if (action.currentAps > action.maxAps) action.maxAps = action.currentAps
                    action.historySum += action.currentAps
                    action.historyCount++
                    action.avgAps = action.historySum / action.historyCount
                }
            }
            statsVersion++
            val clicks = engine.totalClicks.get()
            totalClicksFormatted = if (clicks > 9999) scientificFormat.format(clicks) else clicks.toString()
            toggleKeyLabel = engine.toggleKeyLabel
            
            // Fix: Constantly poll the listener state to update the UI
            isRecording = listener.isRecordingToggleKey && listener.recordingEngine == engine
            
            xInput = engine.lockedX.toString()
            yInput = engine.lockedY.toString()
            lockChecked = engine.shouldLockLocation
            keepControlChecked = engine.keepControlMode
            timeoutInput = engine.timeoutMs.toString()
            timeoutSecInput = secFormat.format(engine.timeoutMs / 1000.0)
            pollingIntervalInput = engine.statsPollingIntervalMs.toString()
            pollingIntervalSecInput = secFormat.format(engine.statsPollingIntervalMs / 1000.0)
            keepControlDelayInput = engine.keepControlDelayMs.toString()
            keepControlDelaySecInput = secFormat.format(engine.keepControlDelayMs / 1000.0)
            isEnabledChecked = engine.isEnabled

            // Update time left progress
            if (isClicking && engine.timeoutMs > 0 && engine.clickStartTimeNanos > 0) {
                val elapsedNanos = System.nanoTime() - engine.clickStartTimeNanos
                val totalTimeoutNanos = engine.timeoutMs * 1_000_000L
                timeLeftProgress = (elapsedNanos.toFloat() / totalTimeoutNanos.toFloat()).coerceIn(0f, 1f)
            } else {
                timeLeftProgress = 0f
            }

            val elapsed = System.currentTimeMillis() - startTime
            delay((engine.statsPollingIntervalMs - elapsed).coerceAtLeast(1L))
        }
    }

    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(value = profileNameInput, onValueChange = { profileNameInput = it; engine.renameProfile(it) }, label = { Text("Profile Name") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isEnabledChecked, onCheckedChange = { isEnabledChecked = it; engine.isEnabled = it; if (!it) engine.stopClicking(); engine.saveSettings() })
                Text("Enabled")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { 
                focusManager.clearFocus()
                listener.recordingEngine = engine
                listener.isRecordingToggleKey = true 
                isRecording = true // Optimistically update UI
            }) {
                Text(if (isRecording) "???" else "Key: $toggleKeyLabel")
            }
        }
        
        Spacer(Modifier.height(8.dp))
        Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Global TPS: $currentTps")
                    IconButton(onClick = { focusManager.clearFocus(); minTps = Long.MAX_VALUE; maxTps = 0; avgTps = 0; tpsHistoryCount = 0; totalTpsSum = 0 }, modifier = Modifier.size(16.dp)) { Icon(Icons.Default.Refresh, null) }
                }
                Text("Min: ${if (minTps == Long.MAX_VALUE) 0 else minTps} | Max: $maxTps | Avg: $avgTps", style = MaterialTheme.typography.caption)
                Divider(Modifier.padding(vertical = 4.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Total Clicks: $totalClicksFormatted")
                    IconButton(onClick = { focusManager.clearFocus(); engine.totalClicks.set(0) }, modifier = Modifier.size(16.dp)) { Icon(Icons.Default.Refresh, null) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // TPS Limit
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(value = targetTpsInput, onValueChange = { targetTpsInput = it; it.toLongOrNull()?.let { engine.targetTps = it; engine.saveSettings() } }, label = { Text("Limit (TPS)") }, modifier = Modifier.width(150.dp))
                Spacer(Modifier.width(16.dp))
                Slider(
                    value = engine.targetTps.toFloat(),
                    onValueChange = { engine.targetTps = it.toLong(); targetTpsInput = engine.targetTps.toString(); engine.saveSettings() },
                    valueRange = 0f..5000f,
                    modifier = Modifier.weight(1f),
                    steps = 50
                )
                IconButton(onClick = { engine.targetTps = 0L; targetTpsInput = "0"; engine.saveSettings() }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Refresh, "Reset to Default (0)")
                }
            }
            Text("Maximum Ticks Per Second. Set to 0 for unlimited speed.", style = MaterialTheme.typography.caption, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }

        Spacer(Modifier.height(8.dp))

        // Polling Interval
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(value = pollingIntervalInput, onValueChange = { pollingIntervalInput = it; it.toLongOrNull()?.let { ms -> engine.statsPollingIntervalMs = ms.coerceAtLeast(10L); pollingIntervalSecInput = secFormat.format(ms / 1000.0); engine.saveSettings() } }, label = { Text("Poll (ms)") }, modifier = Modifier.width(120.dp))
                Spacer(Modifier.width(8.dp))
                TextField(value = pollingIntervalSecInput, onValueChange = { pollingIntervalSecInput = it; it.toDoubleOrNull()?.let { sec -> val ms = (sec * 1000).toLong().coerceAtLeast(10L); engine.statsPollingIntervalMs = ms; pollingIntervalInput = ms.toString(); engine.saveSettings() } }, label = { Text("Poll (s)") }, modifier = Modifier.width(100.dp))
                Spacer(Modifier.width(16.dp))
                Slider(
                    value = engine.statsPollingIntervalMs.toFloat(),
                    onValueChange = { 
                        val ms = it.toLong().coerceAtLeast(10L)
                        engine.statsPollingIntervalMs = ms
                        pollingIntervalInput = ms.toString()
                        pollingIntervalSecInput = secFormat.format(ms / 1000.0)
                        engine.saveSettings() 
                    },
                    valueRange = 10f..5000f,
                    modifier = Modifier.weight(1f),
                    steps = 49
                )
                IconButton(onClick = { 
                    engine.statsPollingIntervalMs = 1000L
                    pollingIntervalInput = "1000"
                    pollingIntervalSecInput = "1"
                    engine.saveSettings() 
                }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Refresh, "Reset to Default (1000ms)")
                }
            }
            Text("How often to refresh the statistics and UI (ms/s).", style = MaterialTheme.typography.caption, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }

        Spacer(Modifier.height(8.dp))

        // Timeout
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(value = timeoutInput, onValueChange = { timeoutInput = it; it.toLongOrNull()?.let { ms -> engine.timeoutMs = ms; timeoutSecInput = secFormat.format(ms / 1000.0); engine.saveSettings() } }, label = { Text("Timeout (ms)") }, modifier = Modifier.width(120.dp))
                Spacer(Modifier.width(8.dp))
                TextField(value = timeoutSecInput, onValueChange = { timeoutSecInput = it; it.toDoubleOrNull()?.let { sec -> val ms = (sec * 1000).toLong(); engine.timeoutMs = ms; timeoutInput = ms.toString(); engine.saveSettings() } }, label = { Text("Timeout (s)") }, modifier = Modifier.width(100.dp))
                Spacer(Modifier.width(16.dp))
                Slider(
                    value = engine.timeoutMs.toFloat(),
                    onValueChange = { 
                        val ms = it.toLong()
                        engine.timeoutMs = ms
                        timeoutInput = ms.toString()
                        timeoutSecInput = secFormat.format(ms / 1000.0)
                        engine.saveSettings() 
                    },
                    valueRange = 0f..600000f, // up to 10 mins
                    modifier = Modifier.weight(1f),
                    steps = 60
                )
                IconButton(onClick = { 
                    engine.timeoutMs = 10000L
                    timeoutInput = "10000"
                    timeoutSecInput = "10"
                    engine.saveSettings() 
                }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Refresh, "Reset to Default (10s)")
                }
            }
            Text("Automatically stop the clicker after this duration. Set to 0 to disable.", style = MaterialTheme.typography.caption, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = lockChecked, onCheckedChange = { focusManager.clearFocus(); lockChecked = it; engine.shouldLockLocation = it; engine.saveSettings() })
            Text("Lock Location")
            Spacer(Modifier.width(12.dp))
            TextField(value = xInput, onValueChange = { xInput = it; it.toIntOrNull()?.let { engine.lockedX = it; engine.saveSettings() } }, label = { Text("X") }, modifier = Modifier.width(90.dp))
            Spacer(Modifier.width(4.dp))
            TextField(value = yInput, onValueChange = { yInput = it; it.toIntOrNull()?.let { engine.lockedY = it; engine.saveSettings() } }, label = { Text("Y") }, modifier = Modifier.width(90.dp))
            Spacer(Modifier.width(4.dp))
            Button(onClick = { focusManager.clearFocus(); engine.captureCurrentLocation(); xInput = engine.lockedX.toString(); yInput = engine.lockedY.toString(); engine.saveSettings() }) { Text("Cap") }
        }
        Text("Locks mouse to X/Y coordinates while clicking.", style = MaterialTheme.typography.caption, modifier = Modifier.padding(start = 36.dp))

        Spacer(Modifier.height(8.dp))
        // KeepControl
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = keepControlChecked, onCheckedChange = { focusManager.clearFocus(); keepControlChecked = it; engine.keepControlMode = it; engine.saveSettings() })
                Text("KeepControl")
                Spacer(Modifier.width(12.dp))
                TextField(value = keepControlDelayInput, onValueChange = { keepControlDelayInput = it; it.toLongOrNull()?.let { ms -> engine.keepControlDelayMs = ms; keepControlDelaySecInput = secFormat.format(ms / 1000.0); engine.saveSettings() } }, label = { Text("Delay (ms)") }, modifier = Modifier.width(100.dp))
                Spacer(Modifier.width(4.dp))
                TextField(value = keepControlDelaySecInput, onValueChange = { keepControlDelaySecInput = it; it.toDoubleOrNull()?.let { sec -> val ms = (sec * 1000).toLong(); engine.keepControlDelayMs = ms; keepControlDelayInput = ms.toString(); engine.saveSettings() } }, label = { Text("(s)") }, modifier = Modifier.width(90.dp))
                Spacer(Modifier.width(16.dp))
                Slider(
                    value = engine.keepControlDelayMs.toFloat(),
                    onValueChange = { 
                        val ms = it.toLong()
                        engine.keepControlDelayMs = ms
                        keepControlDelayInput = ms.toString()
                        keepControlDelaySecInput = secFormat.format(ms / 1000.0)
                        engine.saveSettings() 
                    },
                    valueRange = 0f..2000f,
                    modifier = Modifier.weight(1f),
                    steps = 20
                )
                IconButton(onClick = { 
                    engine.keepControlDelayMs = 100L
                    keepControlDelayInput = "100"
                    keepControlDelaySecInput = "0.1"
                    engine.saveSettings() 
                }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Refresh, "Reset to Default (100ms)")
                }
            }
            Text("Pause clicking when you move the mouse manually. Resumes when idle.", style = MaterialTheme.typography.caption, modifier = Modifier.padding(start = 36.dp))
        }

        Spacer(Modifier.height(12.dp))
        Text("Actions", style = MaterialTheme.typography.h6)
        
        // Off-color background shade for actions area
        Surface(
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.03f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            Box(Modifier.padding(4.dp)) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(actionItems) { index, action ->
                        key(action, statsVersion) {
                            Card(modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(), elevation = 1.dp) {
                                Column(Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(action.toString())
                                            if (action !is ActionItem.MouseScroll) {
                                                var expanded by remember { mutableStateOf(false) }
                                                Box {
                                                    Text(action.actionType.toString(), modifier = Modifier.clickable { focusManager.clearFocus(); expanded = true }, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption)
                                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                                        ActionType.values().forEach { type ->
                                                            DropdownMenuItem(onClick = { action.actionType = type; actionItems[index] = action; engine.actions = actionItems.toMutableList(); engine.saveSettings(); expanded = false }) { Text(type.toString()) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        var skipText by remember { mutableStateOf(action.skip.toString()) }
                                        TextField(value = skipText, onValueChange = { skipText = it; it.toIntOrNull()?.let { action.skip = it; actionItems[index] = action; engine.actions = actionItems.toMutableList(); engine.saveSettings() } }, label = { Text("Skip") }, modifier = Modifier.width(70.dp))
                                        IconButton(onClick = { focusManager.clearFocus(); actionItems.removeAt(index); engine.actions = actionItems.toMutableList(); engine.saveSettings() }) { Icon(Icons.Default.Delete, null) }
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        val apsTotal = if (action.sessionTotalCount.get() > 9999) scientificFormat.format(action.sessionTotalCount.get()) else action.sessionTotalCount.get().toString()
                                        Text(text = "APS: ${action.currentAps} (Min: ${if (action.minAps == Long.MAX_VALUE) 0 else action.minAps} | Max: ${action.maxAps} | Avg: ${action.avgAps}) | Total: $apsTotal", style = MaterialTheme.typography.overline, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { focusManager.clearFocus(); action.sessionTotalCount.set(0) }, modifier = Modifier.size(16.dp)) { Icon(Icons.Default.Refresh, "Reset Total", tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(4.dp))
        TextField(value = actionTextInput, onValueChange = { if (it.endsWith(" ")) tryAddAction(it) else actionTextInput = it }, placeholder = { Text("Quick Add (Space)") }, modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) tryAddAction(actionTextInput) }, singleLine = true)
        Spacer(Modifier.height(8.dp))

        // Time Left Progress Bar (Pie Chart)
        if (isClicking && engine.timeoutMs > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val circleColor = MaterialTheme.colors.onSurface.copy(alpha = 0.1f)
                val progressColor = MaterialTheme.colors.primary
                
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 8.dp.toPx()
                    val sweepAngle = timeLeftProgress * 360f

                    // Background circle
                    drawArc(
                        color = circleColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(strokeWidth)
                    )

                    // Progress arc
                    drawArc(
                        color = progressColor,
                        startAngle = -90f, // Start from top
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(strokeWidth)
                    )
                }
                Text(
                    text = "${(timeLeftProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.h6,
                    fontSize = 18.sp,
                    color = MaterialTheme.colors.onSurface
                )
            }
        }

        Button(onClick = { focusManager.clearFocus(); engine.toggleClicking() }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(backgroundColor = if (isClicking) MaterialTheme.colors.error else MaterialTheme.colors.primary)) {
            Text(if (isClicking) { "STOP ($toggleKeyLabel)" } else { "START ($toggleKeyLabel)" })
        }
    }
}

@Composable
fun App(listener: GlobalKeyListener) {
    var isDarkTheme by remember { mutableStateOf(true) }
    
    val colors = if (isDarkTheme) {
        darkColors(
            primary = Color(0xFFBB86FC),
            primaryVariant = Color(0xFF3700B3),
            secondary = Color(0xFF03DAC6),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
        )
    } else {
        lightColors(
            primary = Color(0xFF6200EE),
            primaryVariant = Color(0xFF3700B3),
            secondary = Color(0xFF03DAC6),
            background = Color.White,
            surface = Color.White,
            onPrimary = Color.White,
            onSecondary = Color.Black,
            onBackground = Color.Black,
            onSurface = Color.Black,
        )
    }

    val engines = remember { 
        val list = mutableStateListOf<ClickerEngine>()
        val profiles = ClickerEngine.listProfiles()
        if (profiles.isEmpty()) {
            val e = ClickerEngine("Default")
            e.loadSettings()
            list.add(e)
        } else {
            profiles.forEach { name ->
                val e = ClickerEngine(name)
                e.loadSettings()
                list.add(e)
            }
        }
        GlobalKeyListener.allEngines.clear()
        GlobalKeyListener.allEngines.addAll(list)
        
        // initialize the global toggle state
        GlobalExitToTray.value = list.firstOrNull()?.exitToTray ?: false
        
        list
    }
    
    val coroutineScope = rememberCoroutineScope()
    var selectedTabIndex by remember { mutableStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf<ClickerEngine?>(null) }
    var uiFps by remember { mutableIntStateOf(0) }
    var exitToTrayChecked by GlobalExitToTray

    val deleteAction = {
        val engine = showDeleteConfirm
        if (engine != null) {
            engine.deleteProfile()
            engines.remove(engine)
            GlobalKeyListener.allEngines.remove(engine)
            if (selectedTabIndex >= engines.size) selectedTabIndex = (engines.size - 1).coerceAtLeast(0)
            if (engines.isEmpty()) {
                val default = ClickerEngine("Default")
                default.saveSettings()
                engines.add(default)
                GlobalKeyListener.allEngines.add(default)
                selectedTabIndex = 0
            }
            showDeleteConfirm = null
        }
    }

    LaunchedEffect(Unit) {
        var frames = 0
        var lastTime = System.currentTimeMillis()
        while(true) {
            withFrameMillis { 
                frames++
                val now = System.currentTimeMillis()
                if (now - lastTime >= 1000) { uiFps = frames; frames = 0; lastTime = now }
            }
        }
    }

    MaterialTheme(colors = colors) {
        Surface(color = MaterialTheme.colors.background) {
            Column {
                if (engines.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, 
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colors.surface).padding(horizontal = 8.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .onPointerEvent(PointerEventType.Scroll) {
                                    val delta = it.changes.first().scrollDelta
                                    coroutineScope.launch {
                                        scrollState.scrollBy(delta.y * 50f) 
                                    }
                                }
                                .horizontalScroll(scrollState)
                                .padding(vertical = 4.dp)
                        ) {
                            engines.forEachIndexed { index, engine ->
                                val selected = selectedTabIndex == index
                                Box(
                                    modifier = Modifier
                                        .clickable { selectedTabIndex = index }
                                        .padding(horizontal = 4.dp)
                                        .background(
                                            if (selected) MaterialTheme.colors.primary.copy(alpha = 0.1f) 
                                            else Color.Transparent,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            BorderStroke(
                                                if (selected) 1.dp else 0.dp,
                                                if (selected) MaterialTheme.colors.primary else Color.Transparent
                                            ),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = engine.isEnabled,
                                            onCheckedChange = { engine.isEnabled = it; if (!it) engine.stopClicking(); engine.saveSettings() },
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = engine.profileName,
                                            style = if (selected) MaterialTheme.typography.subtitle2 else MaterialTheme.typography.body2,
                                            maxLines = 1
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { showDeleteConfirm = engine },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(Icons.Default.Close, "Delete", modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                        }
                        
                        IconButton(onClick = { 
                            var newName = "New Profile ${engines.size + 1}"
                            var suffix = 1
                            while (engines.any { it.profileName == newName }) {
                                newName = "New Profile ${engines.size + 1} ($suffix)"
                                suffix++
                            }
                            val newEngine = ClickerEngine(newName)
                            newEngine.saveSettings()
                            engines.add(newEngine)
                            GlobalKeyListener.allEngines.add(newEngine)
                            selectedTabIndex = engines.size - 1
                        }) {
                            Icon(Icons.Default.Add, "Add Profile")
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = exitToTrayChecked, onCheckedChange = { 
                                exitToTrayChecked = it
                                // apply globally to all profiles
                                engines.forEach { engine ->
                                    engine.exitToTray = it
                                    engine.saveSettings()
                                }
                            })
                            Text("To Tray")
                        }
                        
                        IconButton(onClick = { isDarkTheme = !isDarkTheme }) {
                            Icon(if (isDarkTheme) Icons.Default.LightMode else Icons.Default.Nightlight, "Toggle Theme")
                        }

                        Text("UI FPS: $uiFps", style = MaterialTheme.typography.caption, modifier = Modifier.padding(start = 8.dp))
                    }

                    if (selectedTabIndex in engines.indices) {
                        ProfileTabContent(engines[selectedTabIndex], listener)
                    }
                }
            }
        }

        if (showDeleteConfirm != null) {
            val confirmFocusRequester = remember { FocusRequester() }
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = null },
                title = { Text("Delete Profile") },
                text = { Text("Are you sure you want to delete profile '${showDeleteConfirm?.profileName}'?") },
                confirmButton = {
                    Button(
                        onClick = { deleteAction() }, 
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                        modifier = Modifier
                            .focusRequester(confirmFocusRequester)
                            .onPreviewKeyEvent { 
                                if (it.key == Key.Delete && it.type == KeyEventType.KeyDown) {
                                    deleteAction()
                                    true
                                } else false
                            }
                    ) {
                        Text("Delete [DEL]")
                    }
                },
                dismissButton = {
                    Button(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
                }
            )
            LaunchedEffect(showDeleteConfirm) {
                confirmFocusRequester.requestFocus()
            }
        }
    }
}

fun main() {
    application {
        val logger = Logger.getLogger(GlobalScreen::class.java.`package`.name)
        logger.level = Level.OFF
        logger.useParentHandlers = false
        
        val initialEngine = ClickerEngine("Default")
        val listener = GlobalKeyListener(initialEngine)
        
        try { GlobalScreen.registerNativeHook() } catch (ex: NativeHookException) { exitProcess(1) }
        GlobalScreen.addNativeKeyListener(listener)

        val icon = remember {
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.color = java.awt.Color.RED
            g.fillOval(4, 4, 24, 24)
            g.dispose()
            BitmapPainter(image.toComposeImageBitmap())
        }

        var isVisible by remember { mutableStateOf(true) }

        if (isVisible) {
            Window(
                onCloseRequest = {
                    if (GlobalExitToTray.value) {
                        isVisible = false
                    } else {
                        try { GlobalScreen.unregisterNativeHook() } catch (e: Exception) {} 
                        exitApplication()
                    }
                },
                title = "Auto Klick",
                state = rememberWindowState(placement = WindowPlacement.Maximized)
            ) {
                App(listener)
            }
        }

        // Only show tray if the setting is checked, or optionally always show it.
        // We'll leave it always shown since it might be useful, but since we are specifically changing
        // behavior based on "To Tray" checked, let's keep it here so they can reopen it.
        Tray(
            icon = icon,
            tooltip = "Auto Klick",
            onAction = { isVisible = true },
            menu = {
                Item("Open", onClick = { isVisible = true })
                Item("Exit", onClick = { 
                    try { GlobalScreen.unregisterNativeHook() } catch (e: Exception) {}
                    exitApplication() 
                })
            }
        )
    }
}
