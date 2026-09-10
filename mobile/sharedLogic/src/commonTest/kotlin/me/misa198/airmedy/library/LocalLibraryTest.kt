package me.misa198.airmedy.library

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.misa198.airmedy.sync.LibrarySyncProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalLibraryTest {

    private fun sampleTrack() = LocalTrack(
        id = "local:42",
        title = "  Sunrise  ",
        sortTitle = "Sunrise",
        artists = listOf(LocalArtistRef(id = "local:artist:a", name = "Nova", sortName = "Nova")),
        album = LocalAlbumRef(
            id = "local:album:key1",
            title = "Dawn",
            artworkKey = "album-key1",
            year = 2024,
            createdAt = "2024-01-01T00:00:00Z",
        ),
        albumArtists = listOf(LocalArtistRef(id = "local:artist:a", name = "Nova")),
        composers = listOf(LocalComposer(id = "local:composer:c", name = "Composer")),
        genres = listOf(LocalGenre(id = "local:genre:g", name = "Ambient")),
        durationMillis = 210_000,
        discNumber = 1,
        trackNumber = 3,
        playCount = 7,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-02-02T00:00:00Z",
        addedAt = "2024-01-01T00:00:00Z",
        artworkKey = "album-key1",
        format = "mpeg",
        bitrate = 320,
        sampleRate = 44100,
        bitDepth = 16,
        codec = "mpeg",
        fileSize = 8_200_000L,
        archived = false,
    )

    @Test
    fun emitsCanonicalTrackDocumentFieldsConsumedByReadLayer() {
        val document = LocalLibraryJson.trackDocument(sampleTrack())

        assertEquals("local:42", document.string("id"))
        assertEquals("Sunrise", document.string("title"))
        assertEquals("Sunrise", document.string("sort_title"))
        assertEquals("album-key1", document.string("artwork_key"))
        assertEquals(210_000L, document.long("duration"))
        assertEquals(1, document.int("disc_number"))
        assertEquals(3, document.int("track_number"))
        assertEquals(7, document.int("play_count"))
        assertEquals(false, document.bool("archived"))
        assertEquals("mpeg", document.string("format"))
        assertEquals(320, document.int("bitrate"))
        assertEquals(44100, document.int("sample_rate"))
        assertEquals(16, document.int("bit_depth"))
        assertEquals(8_200_000L, document.long("file_size"))
        assertEquals("2024-02-02T00:00:00Z", document.string("updated_at"))

        val artists = document.array("artists")
        assertEquals(1, artists?.size)
        val artist = artists?.first()?.jsonObject
        assertEquals("local:artist:a", artist?.string("id"))
        assertEquals("Nova", artist?.string("name"))
        assertEquals("Nova", artist?.string("sort_name"))

        val album = document["album"]?.jsonObject
        assertEquals("local:album:key1", album?.string("id"))
        assertEquals("Dawn", album?.string("title"))
        assertEquals("album-key1", album?.string("artwork_key"))
        assertEquals(2024, album?.int("year"))
        assertEquals("2024-01-01T00:00:00Z", album?.string("created_at"))

        val genre = document.array("genres")?.first()?.jsonObject
        assertEquals("Ambient", genre?.string("name"))
        assertEquals("ambient", genre?.string("normalization_key"))

        val composer = document.array("composers")?.first()?.jsonObject
        assertEquals("local:composer:c", composer?.string("id"))
        assertEquals("Composer", composer?.string("name"))
    }

    @Test
    fun trimsTitleAndOmitsBlankAndZeroFields() {
        val document = LocalLibraryJson.trackDocument(
            sampleTrack().copy(
                title = "  Untitled  ",
                sortTitle = "",
                createdAt = "",
                updatedAt = "",
                addedAt = "",
                artworkKey = null,
                album = sampleTrack().album.copy(artworkKey = null, year = 0, copyright = "", createdAt = ""),
                artists = listOf(LocalArtistRef(id = "local:artist:x", name = "X", sortName = "")),
                genres = emptyList(),
                composers = emptyList(),
                bitrate = 0,
                sampleRate = 0,
                bitDepth = 0,
                codec = "",
                format = "",
            ),
        )

        assertEquals("Untitled", document.string("title"))
        assertNull(document["sort_title"])
        assertNull(document["artwork_key"])
        assertNull(document["created_at"])
        assertNull(document["updated_at"])
        assertNull(document["added_at"])
        assertNull(document["bitrate"])
        assertNull(document["format"])
        assertEquals(1, document.array("artists")?.size)
        val album = document["album"]?.jsonObject
        assertNull(album?.string("artwork_key"))
        assertNull(album?.string("year"))
        assertEquals(1, document.int("disc_number"))
    }

    @Test
    fun skipsBlankNamedRefsAndSerializesDurably() {
        val document = LocalLibraryJson.trackDocument(
            sampleTrack().copy(
                artists = listOf(
                    LocalArtistRef(id = "", name = "  "),
                    LocalArtistRef(id = "local:artist:valid", name = "Valid"),
                ),
                albumArtists = emptyList(),
            ),
        )

        val artists = document.array("artists")
        assertEquals(1, artists?.size)
        assertEquals("Valid", artists?.first()?.jsonObject?.string("name"))

        val roundTrip = LibrarySyncProtocol.json.parseToJsonElement(LocalLibraryJson.trackDocumentJson(sampleTrack())).jsonObject
        assertEquals("local:42", roundTrip.string("id"))
        assertEquals("Sunrise", roundTrip.string("title"))
        assertTrue(document.array("album_artists").orEmpty().isEmpty())
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.content?.toLongOrNull()
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.content?.toIntOrNull()
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())