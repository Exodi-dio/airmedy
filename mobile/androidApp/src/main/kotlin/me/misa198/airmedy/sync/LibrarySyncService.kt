package me.misa198.airmedy.sync

import android.content.Context

/**
 * Process-lifetime holder for the local Room library store.
 *
 * Originally this object owned the desktop-sync runtime (MQTT handoff, foreground
 * data-sync service, progress notifications). With desktop sync removed it only
 * owns the local data layer that the MediaStore scanner, playback, lyrics, and
 * listening trackers all consume.
 */
internal object AndroidSyncRuntime {
    private lateinit var store: AndroidLibrarySyncStore
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = AndroidLibrarySyncStore(SyncDatabase.create(appContext), appContext.filesDir)
    }

    internal fun syncStore(): AndroidLibrarySyncStore = store
}