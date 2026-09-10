package me.misa198.airmedy.library

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.misa198.airmedy.sync.LibrarySyncProtocol

/**
 * A locally scanned library, produced by a platform media scanner and written
 * into the mobile library store by the Android adapter. The model is
 * platform-neutral: track/album/artist IDs are opaque strings assigned by the
 * platform adapter, and [LocalLibraryJson] synthesizes the canonical per-track
 * document that the existing Android read layer (library lists, insight
 * aggregation, mood radio, lyrics, FTS index) persists and parses.
 */

interface LocalNamedRef {
    val id: String
    val name: String
}

data class LocalArtistRef(
    val id: String,
    val name: String,
    val sortName: String = "",
) : LocalNamedRef

data class LocalAlbumRef(
    val id: String,
    val title: String,
    val artworkKey: String? = null,
    val year: Int = 0,
    val copyright: String = "",
    val createdAt: String = "",
)

data class LocalComposer(
    val id: String,
    val name: String,
) : LocalNamedRef

data class LocalGenre(
    val id: String,
    val name: String,
) : LocalNamedRef

data class LocalTrack(
    val id: String,
    val title: String,
    val artists: List<LocalArtistRef>,
    val album: LocalAlbumRef,
    val albumArtists: List<LocalArtistRef> = emptyList(),
    val composers: List<LocalComposer> = emptyList(),
    val genres: List<LocalGenre> = emptyList(),
    val durationMillis: Long = 0L,
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val playCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val addedAt: String = "",
    val artworkKey: String? = null,
    val archived: Boolean = false,
    val sortTitle: String = "",
    val format: String = "",
    val bitrate: Int = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val codec: String = "",
)

data class LocalLibrarySnapshot(
    val scannedAtMillis: Long,
    val tracks: List<LocalTrack>,
)

/** Synthesizes the canonical per-track document consumed by the Android read layer. */
object LocalLibraryJson {

    fun trackDocument(track: LocalTrack): JsonObject = buildJsonObject {
        put("id", track.id)
        put("title", track.title.trim())
        if (track.sortTitle.isNotBlank()) put("sort_title", track.sortTitle)
        track.artworkKey?.takeIf(String::isNotBlank)?.let { put("artwork_key", it) }
        put("duration", track.durationMillis)
        put("disc_number", track.discNumber)
        put("track_number", track.trackNumber)
        put("play_count", track.playCount)
        if (track.createdAt.isNotBlank()) put("created_at", track.createdAt)
        if (track.updatedAt.isNotBlank()) put("updated_at", track.updatedAt)
        if (track.addedAt.isNotBlank()) put("added_at", track.addedAt)
        put("archived", track.archived)
        if (track.format.isNotBlank()) put("format", track.format)
        if (track.bitrate > 0) put("bitrate", track.bitrate)
        if (track.sampleRate > 0) put("sample_rate", track.sampleRate)
        if (track.bitDepth > 0) put("bit_depth", track.bitDepth)
        if (track.codec.isNotBlank()) put("codec", track.codec)
        put("artists", buildJsonArray {
            track.artists.forEachValid { add(it.toJson()) }
        })
        put("album_artists", buildJsonArray {
            track.albumArtists.forEachValid { add(it.toJson()) }
        })
        put("composers", buildJsonArray {
            track.composers.forEachValid { add(it.toJson()) }
        })
        put("genres", buildJsonArray {
            track.genres.forEachValid { add(it.toJson()) }
        })
        put("album", buildJsonObject {
            put("id", track.album.id)
            put("title", track.album.title.trim())
            track.album.artworkKey?.takeIf(String::isNotBlank)?.let { put("artwork_key", it) }
            if (track.album.year > 0) put("year", track.album.year)
            if (track.album.copyright.isNotBlank()) put("copyright", track.album.copyright)
            if (track.album.createdAt.isNotBlank() || track.createdAt.isNotBlank()) {
                put("created_at", track.album.createdAt.ifBlank { track.createdAt })
            }
        })
    }

    fun trackDocumentJson(track: LocalTrack): String = LibrarySyncProtocol.json.encodeToString(trackDocument(track))
}

private inline fun <T : LocalNamedRef> List<T>.forEachValid(block: (T) -> Unit) {
    forEach { candidate ->
        if (candidate.id.isNotBlank() && candidate.name.trim().isNotEmpty()) block(candidate)
    }
}

private fun LocalArtistRef.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name.trim())
    if (sortName.isNotBlank()) put("sort_name", sortName)
}

private fun LocalComposer.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name.trim())
}

private fun LocalGenre.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name.trim())
    put("normalization_key", name.trim().lowercase())
}