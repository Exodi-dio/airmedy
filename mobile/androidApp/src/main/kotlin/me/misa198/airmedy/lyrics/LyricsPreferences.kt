package me.misa198.airmedy.lyrics

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.lyricsDataStore by preferencesDataStore("lyrics")
private val LrclibKey = booleanPreferencesKey("enable_lrclib")
private val KugouKey = booleanPreferencesKey("enable_kugou")

internal data class LyricsSettings(
    val lrclib: Boolean = true,
    val kugou: Boolean = true,
)

internal class LyricsPreferences(private val context: Context) {
    val settings = context.lyricsDataStore.data.map {
        LyricsSettings(
            lrclib = it[LrclibKey] ?: true,
            kugou = it[KugouKey] ?: true,
        )
    }
    suspend fun setLrclib(enabled: Boolean) = context.lyricsDataStore.edit { it[LrclibKey] = enabled }
    suspend fun setKugou(enabled: Boolean) = context.lyricsDataStore.edit { it[KugouKey] = enabled }
}