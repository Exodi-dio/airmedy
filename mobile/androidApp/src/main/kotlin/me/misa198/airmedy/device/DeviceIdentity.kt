package me.misa198.airmedy.device

import android.content.Context
import java.util.UUID

/** Stable per-install device id used to attribute local listening stats and insights. */
internal class DeviceIdentity(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val id: String = synchronized(lock) {
        preferences.getString(KEY, null) ?: UUID.randomUUID().toString().also { generated ->
            preferences.edit().putString(KEY, generated).apply()
        }
    }

    private companion object {
        val lock = Any()
        const val PREFERENCES = "device_identity"
        const val KEY = "device_id"
    }
}