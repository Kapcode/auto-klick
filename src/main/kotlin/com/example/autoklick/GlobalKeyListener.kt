package com.example.autoklick

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener

class GlobalKeyListener(var engine: ClickerEngine) : NativeKeyListener {
    var isRecordingToggleKey = false
    var recordingEngine: ClickerEngine? = null

    // We can't easily access the 'engines' list from Main.kt here without passing it in.
    // However, we can use a static/shared registry or have Main.kt update a list here.
    companion object {
        val allEngines = mutableListOf<ClickerEngine>()
    }

    override fun nativeKeyPressed(e: NativeKeyEvent) {
        val target = recordingEngine ?: engine
        
        if (isRecordingToggleKey && recordingEngine != null) {
            target.toggleNativeKeyCode = e.keyCode
            target.toggleKeyLabel = NativeKeyEvent.getKeyText(e.keyCode)
            isRecordingToggleKey = false
            recordingEngine = null
            target.saveSettings()
            return
        }

        // Check ALL enabled engines for their toggle keys
        allEngines.forEach { engine ->
            if (engine.isEnabled && e.keyCode == engine.toggleNativeKeyCode) {
                engine.toggleClicking()
            }
        }
    }
}