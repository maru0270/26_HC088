package com.bikeeyes.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

enum class ApproachVoiceType {
    HAZARD_ZONE,
    ACCIDENT_ZONE
}

object SafetyTtsManager {
    private var textToSpeech: TextToSpeech? = null
    private var initializing = false
    private var pending: Pair<String, String>? = null

    @Synchronized
    fun speak(context: Context, type: ApproachVoiceType, force: Boolean = false) {
        if (!force && !UserSettingsStore(context).loadNotifications().sound) return

        val text = when (type) {
            ApproachVoiceType.HAZARD_ZONE ->
                "전방에 위험 구간이 있습니다. 주의하세요."
            ApproachVoiceType.ACCIDENT_ZONE ->
                "전방에 사고 기록이 있는 구간입니다. 속도를 줄여주세요."
        }
        val utteranceId = "bikeeyes-${type.name.lowercase()}"
        val current = textToSpeech
        if (current != null) {
            current.speak(text, TextToSpeech.QUEUE_FLUSH, speechParameters(), utteranceId)
            return
        }

        pending = text to utteranceId
        if (initializing) return
        initializing = true
        val applicationContext = context.applicationContext
        val onInitListener = TextToSpeech.OnInitListener listener@ { status ->
            synchronized(this) {
                initializing = false
                val tts = textToSpeech
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    textToSpeech?.shutdown()
                    textToSpeech = null
                    pending = null
                    return@listener
                }
                val languageResult = tts.setLanguage(Locale.KOREAN)
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts.shutdown()
                    textToSpeech = null
                    pending = null
                    return@listener
                }
                tts.setSpeechRate(0.95f)
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                pending?.let { (pendingText, pendingId) ->
                    tts.speak(
                        pendingText,
                        TextToSpeech.QUEUE_FLUSH,
                        speechParameters(),
                        pendingId
                    )
                }
                pending = null
            }
        }
        textToSpeech = preferredEngine(applicationContext)?.let { engine ->
            TextToSpeech(applicationContext, onInitListener, engine)
        } ?: TextToSpeech(applicationContext, onInitListener)
    }

    private fun preferredEngine(context: Context): String? {
        val packageManager = context.packageManager

        return listOf("com.google.android.tts", "com.samsung.SMT").firstOrNull { packageName ->
            runCatching {
                packageManager.getApplicationInfo(packageName, 0).enabled
            }.getOrDefault(false)
        }
    }

    private fun speechParameters() = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
    }
}
