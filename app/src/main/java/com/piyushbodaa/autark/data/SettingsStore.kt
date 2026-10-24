package com.piyushbodaa.autark.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = 0.7f,
    val contextSize: Int = 4096,
    val maxTokens: Int = 1024,
    /** 0 means "pick automatically". */
    val threads: Int = 0,
    val thinking: Boolean = false,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Autark, a helpful assistant that runs privately on the user's phone. " +
                "Answer clearly and briefly. Reply in the language the user writes in. " +
                "Write maths in plain text, not LaTeX."

        /** Until 0.4.1. Phones that kept it get the new one. */
        const val OLD_SYSTEM_PROMPT =
            "You are Autark, a helpful assistant that runs privately on the user's phone. " +
                "Answer clearly and briefly. Reply in the language the user writes in."
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(change: (Settings) -> Settings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.edit {
            putString("systemPrompt", next.systemPrompt)
            putFloat("temperature", next.temperature)
            putInt("contextSize", next.contextSize)
            putInt("maxTokens", next.maxTokens)
            putInt("threads", next.threads)
            putBoolean("thinking", next.thinking)
        }
    }

    private fun read(): Settings {
        val d = Settings()
        // Every setting is saved whenever one changes, so an unedited default prompt is stored too.
        val prompt = prefs.getString("systemPrompt", d.systemPrompt)
            ?.takeIf { it != Settings.OLD_SYSTEM_PROMPT } ?: d.systemPrompt
        return Settings(
            systemPrompt = prompt,
            temperature = prefs.getFloat("temperature", d.temperature),
            contextSize = prefs.getInt("contextSize", d.contextSize),
            maxTokens = prefs.getInt("maxTokens", d.maxTokens),
            threads = prefs.getInt("threads", d.threads),
            thinking = prefs.getBoolean("thinking", d.thinking),
        )
    }
}
