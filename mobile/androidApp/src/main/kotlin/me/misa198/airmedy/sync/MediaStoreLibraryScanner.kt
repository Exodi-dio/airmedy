package me.misa198.airmedy.sync

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import me.misa198.airmedy.library.LocalAlbumRef
import me.misa198.airmedy.library.LocalArtistRef
import me.misa198.airmedy.library.LocalComposer
import me.misa198.airmedy.library.LocalGenre
import me.misa198.airmedy.library.LocalLibrarySnapshot
import me.misa198.airmedy.library.LocalTrack

/** Audio asset row resolved for a scanned track, keyed by track id in the scan result. */
internal data class LocalScanAudio(
    val trackId: String,
    val absolutePath: String,
    val sha256: String,
    val size: Long,
)

/** Copied album artwork, persisted under the library store's artwork directory. */
internal data class LocalScanArtwork(
    val artworkKey: String,
    val relativePath: String,
    val sha256: String,
    val size: Long,
)

internal data class LocalLibraryScanResult(
    val snapshot: LocalLibrarySnapshot,
    val audio: Map<String, LocalScanAudio>,
    val artwork: List<LocalScanArtwork>,
)

/**
 * Reads the device MediaStore audio collection and synthesizes the platform-neutral
 * [LocalLibrarySnapshot] plus the audio/artwork rows [AndroidLibrarySyncStore] persists.
 *
 * Track ids are `local:<mediaStore _id>` so favorites and listening stats survive
 * rescans and re-installs. Album ids and all name-based reference ids are derived
 * deterministically so rescans do not churn the search index or favorite overlay.
 */
internal class MediaStoreLibraryScanner(
    private val contentResolver: ContentResolver,
    private val artworkDir: File,
) {
    fun scan(): LocalLibraryScanResult {
        val genresByTrack = genresByTrackId()
        val audio = linkedMapOf<String, LocalScanAudio>()
        val tracks = mutableListOf<LocalTrack>()
        val albumRepresentatives = linkedMapOf<String, Uri>()

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            Projection,
            Selection,
            null,
            SortOrder,
        )?.use { cursor ->
            val columns = Projection.associateWith { name -> cursor.getColumnIndex(name) }
            fun text(name: String): String? = columns[name]?.takeIf { it >= 0 }?.let(cursor::getString)?.trim()
            fun number(name: String): Long? = columns[name]?.takeIf { it >= 0 }?.let(cursor::getLong)

            while (cursor.moveToNext()) {
                val mediaId = number(ColumnId) ?: continue
                val data = text(ColumnData) ?: continue
                val title = text(ColumnTitle) ?: ""
                if (title.isBlank()) continue
                val trackId = "local:$mediaId"
                val artistName = text(ColumnArtist) ?: ""
                val albumName = text(ColumnAlbum) ?: ""
                val key = albumKey(text(ColumnAlbumKey), artistName, albumName)
                val dateAdded = number(ColumnDateAdded) ?: 0L
                val dateModified = number(ColumnDateModified) ?: 0L
                val size = number(ColumnSize) ?: 0L
                val mime = text(ColumnMimeType) ?: ""
                if (size > 0L) {
                    audio[trackId] = LocalScanAudio(
                        trackId = trackId,
                        absolutePath = data,
                        sha256 = identityHash("$data|$size|$dateModified"),
                        size = size,
                    )
                }
                albumRepresentatives.putIfAbsent(
                    key,
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId),
                )
                tracks += LocalTrack(
                    id = trackId,
                    title = title,
                    sortTitle = text(ColumnTitleKey) ?: "",
                    artists = artistsOf(artistName),
                    album = LocalAlbumRef(
                        id = "local:album:$key",
                        title = albumName,
                        artworkKey = "album-$key",
                        year = 0,
                        copyright = "",
                        createdAt = isoDate(dateAdded),
                    ),
                    albumArtists = artistsOf(artistName),
                    composers = composersOf(text(ColumnComposer) ?: ""),
                    genres = genresByTrack[mediaId].orEmpty().mapNotNull { raw ->
                        raw.trim().takeIf(String::isNotEmpty)?.let { LocalGenre(genreId(it), it) }
                    },
                    durationMillis = number(ColumnDuration)?.coerceAtLeast(0L) ?: 0L,
                    discNumber = number(ColumnDiscNumber)?.toInt() ?: 0,
                    trackNumber = number(ColumnTrackNumber)?.toInt() ?: 0,
                    playCount = number(ColumnPlayCount)?.toInt() ?: 0,
                    createdAt = isoDate(dateAdded),
                    updatedAt = isoDate(dateModified),
                    addedAt = isoDate(dateAdded),
                    artworkKey = "album-$key",
                    archived = false,
                    format = mime.substringAfter("audio/", mime).ifBlank { data.substringAfterLast('.', "").lowercase() },
                    bitrate = number(ColumnBitrate)?.toInt() ?: 0,
                    sampleRate = number(ColumnSampleRate)?.toInt() ?: 0,
                    bitDepth = number(ColumnBitsPerSample)?.toInt() ?: 0,
                    codec = mime.substringAfter("audio/", mime),
                )
            }
        }

        val artwork = albumRepresentatives.mapNotNull { (key, uri) -> copyArtwork(key, uri) }
        val sorted = tracks.sortedWith(
            compareBy<LocalTrack> { it.album.title.lowercase() }
                .thenBy { it.discNumber }
                .thenBy { it.trackNumber }
                .thenBy { it.title.lowercase() },
        )
        return LocalLibraryScanResult(
            snapshot = LocalLibrarySnapshot(scannedAtMillis = System.currentTimeMillis(), tracks = sorted),
            audio = audio,
            artwork = artwork,
        )
    }

    private fun artistsOf(raw: String): List<LocalArtistRef> = raw
        .split(';')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { name -> LocalArtistRef(id = artistId(name), name = name, sortName = "") }
        .ifEmpty { listOf(LocalArtistRef(id = artistId(raw), name = raw, sortName = "")) }

    private fun composersOf(raw: String): List<LocalComposer> = raw
        .split(';')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { name -> LocalComposer(id = composerId(name), name = name) }

    /** MediaStore has no per-track genre projection; read the genre membership table once. */
    private fun genresByTrackId(): Map<Long, List<String>> {
        val names = mutableMapOf<Long, String>()
        contentResolver.query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.Audio.Genres._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.Audio.Genres.NAME)
            while (cursor.moveToNext()) {
                val id = if (idIndex >= 0) cursor.getLong(idIndex) else continue
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                if (id >= 0L && !name.isNullOrBlank()) names[id] = name.trim()
            }
        }
        val members = mutableMapOf<Long, MutableList<String>>()
        names.forEach { (genreId, name) ->
            val memberUri = MediaStore.Audio.Genres.Members.getContentUri(MediaStore.VOLUME_EXTERNAL, genreId)
            contentResolver.query(memberUri, arrayOf(MediaStore.Audio.Genres.Members._ID), null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Audio.Genres.Members._ID)
                if (idIndex >= 0) while (cursor.moveToNext()) {
                    val trackId = cursor.getLong(idIndex)
                    if (trackId >= 0L) members.getOrPut(trackId) { mutableListOf() }.add(name)
                }
            }
        }
        return members
    }

    private fun copyArtwork(albumKey: String, mediaUri: Uri): LocalScanArtwork? {
        val bitmap = runCatching { contentResolver.loadThumbnail(mediaUri, ArtworkTargetSize, null) }.getOrNull() ?: return null
        val file = File(artworkDir, "$albumKey.jpg")
        file.parentFile?.mkdirs()
        val wrote = file.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream) }
        if (!wrote || !file.isFile || file.length() <= 0L) return null
        return LocalScanArtwork(
            artworkKey = albumKey,
            relativePath = "artwork/${file.name}",
            sha256 = fileSha256(file),
            size = file.length(),
        )
    }

    companion object {
        const val ColumnId = MediaStore.Audio.Media._ID
        const val ColumnData = MediaStore.Audio.Media.DATA
        const val ColumnTitle = MediaStore.Audio.Media.TITLE
        const val ColumnTitleKey = MediaStore.Audio.Media.TITLE_KEY
        const val ColumnArtist = MediaStore.Audio.Media.ARTIST
        const val ColumnAlbum = MediaStore.Audio.Media.ALBUM
        const val ColumnAlbumKey = MediaStore.Audio.Media.ALBUM_KEY
        const val ColumnComposer = MediaStore.Audio.Media.COMPOSER
        const val ColumnDuration = MediaStore.Audio.Media.DURATION
        const val ColumnDiscNumber = MediaStore.Audio.Media.DISC_NUMBER
        const val ColumnTrackNumber = MediaStore.Audio.Media.TRACK
        const val ColumnPlayCount = MediaStore.Audio.Media.PLAY_COUNT
        const val ColumnDateAdded = MediaStore.Audio.Media.DATE_ADDED
        const val ColumnDateModified = MediaStore.Audio.Media.DATE_MODIFIED
        const val ColumnSize = MediaStore.Audio.Media.SIZE
        const val ColumnBitrate = MediaStore.Audio.Media.BITRATE
        const val ColumnSampleRate = MediaStore.Audio.Media.SAMPLE_RATE
        const val ColumnBitsPerSample = MediaStore.Audio.Media.BITS_PER_SAMPLE
        const val ColumnMimeType = MediaStore.Audio.Media.MIME_TYPE

        val Projection: Array<String> = arrayOf(
            ColumnId,
            ColumnData,
            ColumnTitle,
            ColumnTitleKey,
            ColumnArtist,
            ColumnAlbum,
            ColumnAlbumKey,
            ColumnComposer,
            ColumnDuration,
            ColumnDiscNumber,
            ColumnTrackNumber,
            ColumnPlayCount,
            ColumnDateAdded,
            ColumnDateModified,
            ColumnSize,
            ColumnBitrate,
            ColumnSampleRate,
            ColumnBitsPerSample,
            ColumnMimeType,
        )

        const val Selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        const val SortOrder = "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, ${MediaStore.Audio.Media.DISC_NUMBER}, ${MediaStore.Audio.Media.TRACK}, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
        val ArtworkTargetSize = Size(1024, 1024)
    }
}

/** Deterministic album key: MediaStore's own key when present, else derived from artist + album. */
internal fun albumKey(columnValue: String?, artist: String, album: String): String =
    columnValue?.takeIf { it.isNotBlank() } ?: sha256Hex("$artist|$album").take(12)

internal fun artistId(name: String): String = "local:artist:" + sha256Hex(name.trim().lowercase()).take(16)

internal fun genreId(name: String): String = "local:genre:" + sha256Hex(name.trim().lowercase()).take(16)

internal fun composerId(name: String): String = "local:composer:" + sha256Hex(name.trim().lowercase()).take(16)

internal fun isoDate(epochSeconds: Long): String = if (epochSeconds > 0L) Instant.ofEpochSecond(epochSeconds).toString() else ""

internal fun identityHash(value: String): String = sha256Hex(value)

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun fileSha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}