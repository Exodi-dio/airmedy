package me.misa198.airmedy.sync

/**
 * User-staged artist artwork, stored under the app's local directory.
 * Purely local: unlike playlist artwork it is never synced to the desktop.
 */
data class StagedArtistArtwork(
    val artistId: String,
    val sha256: String,
    val mime: String,
    val size: Long,
    val relativePath: String,
)