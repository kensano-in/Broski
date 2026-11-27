package com.verlyn.audio

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class EqPreset(val name: String, val gains: List<Float>, val preamp: Float = 0f)

private val Context.verlynPresetDataStore by preferencesDataStore(name = "verlyn_eq_presets")

object EqPresetStore {
    private val presetsKey = stringPreferencesKey("custom_presets_v1")

    fun observe(context: Context): Flow<List<EqPreset>> =
        context.applicationContext.verlynPresetDataStore.data.map { prefs -> decode(prefs[presetsKey].orEmpty()) }

    suspend fun save(context: Context, preset: EqPreset) {
        val clean = preset.copy(name = preset.name.trim().take(32), gains = preset.gains.take(10).map { it.coerceIn(-12f, 12f) })
        require(clean.name.isNotBlank() && clean.gains.size == 10)
        context.applicationContext.verlynPresetDataStore.edit { prefs ->
            val current = decode(prefs[presetsKey].orEmpty()).toMutableList()
            val index = current.indexOfFirst { it.name.equals(clean.name, ignoreCase = true) }
            if (index >= 0) current[index] = clean else current.add(clean)
            prefs[presetsKey] = encode(current)
        }
    }

    suspend fun delete(context: Context, name: String) {
        context.applicationContext.verlynPresetDataStore.edit { prefs ->
            prefs[presetsKey] = encode(decode(prefs[presetsKey].orEmpty()).filterNot { it.name.equals(name, ignoreCase = true) })
        }
    }

    private fun encode(items: List<EqPreset>): String = items.joinToString("\n") { item ->
        val encoded = Base64.encodeToString(item.name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
        encoded + "|" + item.gains.joinToString(",") + "|" + item.preamp
    }

    private fun decode(source: String): List<EqPreset> = source.lineSequence().mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size != 3) return@mapNotNull null
        try {
            val name = String(Base64.decode(parts[0], Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
            val gains = parts[1].split(",").map { it.toFloat() }
            val preamp = parts[2].toFloat()
            if (name.isBlank() || gains.size != 10) null
            else EqPreset(name, gains.map { it.coerceIn(-12f, 12f) }, preamp.coerceIn(-12f, 12f))
        } catch (_: Throwable) { null }
    }.toList()
}
