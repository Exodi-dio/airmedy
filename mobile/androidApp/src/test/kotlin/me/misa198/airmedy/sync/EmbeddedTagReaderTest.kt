package me.misa198.airmedy.sync

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EmbeddedTagReaderTest {

    private val lrc = "[00:01.00]Hello world\n[00:02.50]Second line"
    private val image = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
    )

    @Test fun `flac vorbis comment lyrics`() {
        val file = flac(picture = null, comments = listOf("LYRICS=$lrc"))
        assertEquals(lrc, EmbeddedTagReader.embeddedLyricsText(file.path))
    }

    @Test fun `flac prefers lyrics over unsynced`() {
        val file = flac(picture = null, comments = listOf("UNSYNCEDLYRICS=plain", "LYRICS=$lrc"))
        assertEquals(lrc, EmbeddedTagReader.embeddedLyricsText(file.path))
    }

    @Test fun `flac vorbis lyrics decode as utf8`() {
        val korean = "[00:01.00]안녕하세요\n[00:02.00]세계"
        val file = flac(picture = null, comments = listOf("LYRICS=$korean"))
        assertEquals(korean, EmbeddedTagReader.embeddedLyricsText(file.path))
    }

    @Test fun `flac picture block artwork`() {
        val file = flac(picture = image, comments = emptyList())
        val bytes = EmbeddedTagReader.embeddedArtworkBytes(file.path)
        assertNotNull(bytes)
        assertEquals(image.toList(), bytes.toList())
    }

    @Test fun `flac prefers a later front cover over an earlier back cover`() {
        val back = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val file = flac(picture = back, pictureType = 4, secondPicture = image, comments = emptyList())
        val bytes = EmbeddedTagReader.embeddedArtworkBytes(file.path)
        assertNotNull(bytes)
        assertEquals(image.toList(), bytes.toList())
    }

    @Test fun `flac falls back to a non-front picture when no front cover exists`() {
        val file = flac(picture = image, pictureType = 4, comments = emptyList())
        val bytes = EmbeddedTagReader.embeddedArtworkBytes(file.path)
        assertNotNull(bytes)
        assertEquals(image.toList(), bytes.toList())
    }

    @Test fun `flac without picture returns null artwork`() {
        val file = flac(picture = null, comments = listOf("LYRICS=$lrc"))
        assertNull(EmbeddedTagReader.embeddedArtworkBytes(file.path))
    }

    @Test fun `id3 v2_3 uslt lyrics`() {
        val file = temp("id3-uslt", id3v23(frames = listOf(uslt("eng", lrc))))
        assertContains(EmbeddedTagReader.embeddedLyricsText(file.path)!!, "Second line")
    }

    @Test fun `id3 v2_3 apic artwork`() {
        val file = temp("id3-apic", id3v23(frames = listOf(apic(image))))
        val bytes = EmbeddedTagReader.embeddedArtworkBytes(file.path)
        assertNotNull(bytes)
        assertEquals(image.toList(), bytes.toList())
    }

    @Test fun `id3 v2_2 ult and pic`() {
        val file = temp("id3-v22", id3v22(frames = listOf(ult("eng", lrc), pic(image))))
        assertContains(EmbeddedTagReader.embeddedLyricsText(file.path)!!, "Hello world")
        assertEquals(image.toList(), EmbeddedTagReader.embeddedArtworkBytes(file.path)!!.toList())
    }

    @Test fun `id3 without tags returns null`() {
        val file = temp("no-tags", byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00))
        assertNull(EmbeddedTagReader.embeddedLyricsText(file.path))
        assertNull(EmbeddedTagReader.embeddedArtworkBytes(file.path))
    }

    @Test fun `ogg vorbis lyrics`() {
        val comment = vorbisComment(comments = listOf("LYRICS=$lrc"))
        val bytes = ByteArrayOutputStream().apply {
            write("OggS".toByteArray(Charsets.ISO_8859_1))
            write(byteArrayOf(0x00, 0x00, 0x00))
            write(0x03)
            write("vorbis".toByteArray(Charsets.ISO_8859_1))
            write(comment)
        }.toByteArray()
        val file = temp("ogg", bytes)
        assertEquals(lrc, EmbeddedTagReader.embeddedLyricsText(file.path))
    }

    @Test fun `ogg metadata block picture artwork`() {
        val b64 = java.util.Base64.getEncoder().encodeToString(pictureBlock(image))
        val comment = vorbisComment(comments = listOf("METADATA_BLOCK_PICTURE=$b64"))
        val bytes = ByteArrayOutputStream().apply {
            write("OggS".toByteArray(Charsets.ISO_8859_1))
            write(byteArrayOf(0x00, 0x00, 0x00))
            write(0x03)
            write("vorbis".toByteArray(Charsets.ISO_8859_1))
            write(comment)
        }.toByteArray()
        val file = temp("ogg-picture", bytes)
        assertEquals(image.toList(), EmbeddedTagReader.embeddedArtworkBytes(file.path)!!.toList())
    }

    @Test fun `m4a covr artwork`() {
        val file = m4a(ilst = covr(image).toByteArray())
        val bytes = EmbeddedTagReader.embeddedArtworkBytes(file.path)
        assertNotNull(bytes)
        assertEquals(image.toList(), bytes.toList())
    }

    @Test fun `m4a copyright lyric atom lyrics`() {
        val file = m4a(ilst = lyricAtom(lrc).toByteArray())
        assertEquals(lrc, EmbeddedTagReader.embeddedLyricsText(file.path))
    }

    @Test fun `m4a itunes freeform lyrics`() {
        val file = m4a(ilst = itunesLyrics(lrc).toByteArray())
        assertEquals(lrc, EmbeddedTagReader.embeddedLyricsText(file.path))
    }

    @Test fun `m4a prefers copyright lyric over itunes freeform`() {
        val file = m4a(ilst = (lyricAtom("plain lyrics") + itunesLyrics(lrc)).toByteArray())
        assertEquals("plain lyrics", EmbeddedTagReader.embeddedLyricsText(file.path))
    }

    @Test fun `m4a without ilst returns null`() {
        val file = m4a(ilst = byteArrayOf())
        assertNull(EmbeddedTagReader.embeddedLyricsText(file.path))
        assertNull(EmbeddedTagReader.embeddedArtworkBytes(file.path))
    }

    @Test fun `wav id3 chunk artwork and lyrics`() {
        val file = wav(id3v23(frames = listOf(uslt("eng", lrc), apic(image))))
        assertContains(EmbeddedTagReader.embeddedLyricsText(file.path)!!, "Hello world")
        assertEquals(image.toList(), EmbeddedTagReader.embeddedArtworkBytes(file.path)!!.toList())
    }

    @Test fun `aiff id3 chunk artwork`() {
        val file = aiff(id3v23(frames = listOf(apic(image))))
        assertEquals(image.toList(), EmbeddedTagReader.embeddedArtworkBytes(file.path)!!.toList())
    }

    @Test fun `wav without id3 chunk returns null`() {
        val fmt = riffChunk("fmt ", byteArrayOf(0x01, 0x00), bigEndian = false)
        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray(Charsets.ISO_8859_1))
            write(fmt)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        out.writeIntLE(4 + body.size)
        out.write(body)
        val file = temp("wav-no-id3", out.toByteArray())
        assertNull(EmbeddedTagReader.embeddedLyricsText(file.path))
        assertNull(EmbeddedTagReader.embeddedArtworkBytes(file.path))
    }

    private fun temp(name: String, bytes: ByteArray): File =
        File.createTempFile(name, ".test").apply { writeBytes(bytes) }

    private fun flac(picture: ByteArray?, comments: List<String>, pictureType: Int = 3, secondPicture: ByteArray? = null): File {
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        picture?.let { blocks += FLAC_PICTURE to pictureBlock(it, pictureType) }
        secondPicture?.let { blocks += FLAC_PICTURE to pictureBlock(it, 3) }
        blocks += FLAC_VORBIS_COMMENT to vorbisComment(comments)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        blocks.forEachIndexed { index, (type, payload) ->
            out.write((if (index == blocks.lastIndex) 0x80 else 0x00) or type)
            out.write(byteArrayOf((payload.size shr 16 and 0xFF).toByte(), (payload.size shr 8 and 0xFF).toByte(), (payload.size and 0xFF).toByte()))
            out.write(payload)
        }
        return temp("flac", out.toByteArray())
    }

    private fun pictureBlock(imageBytes: ByteArray, pictureType: Int = 3): ByteArray {
        val mime = "image/png".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        out.writeIntBE(pictureType)
        out.writeIntBE(mime.size); out.write(mime)
        out.writeIntBE(0)
        out.writeIntBE(0); out.writeIntBE(0); out.writeIntBE(24); out.writeIntBE(0)
        out.writeIntBE(imageBytes.size); out.write(imageBytes)
        return out.toByteArray()
    }

    private fun vorbisComment(comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeIntLE(0)
        out.writeIntLE(comments.size)
        comments.forEach { entry ->
            val bytes = entry.toByteArray(Charsets.UTF_8)
            out.writeIntLE(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun id3v23(frames: List<ByteArray>): ByteArray = id3(3, 0, frames)
    private fun id3v22(frames: List<ByteArray>): ByteArray = id3(2, 0, frames)

    private fun id3(major: Int, revision: Int, frames: List<ByteArray>): ByteArray {
        val body = ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major); out.write(revision); out.write(0)
        out.writeSyncSafe(body.size)
        out.write(body)
        return out.toByteArray()
    }

    private fun uslt(lang: String, text: String): ByteArray {
        val content = ByteArrayOutputStream()
        content.write(3)
        content.write(lang.toByteArray(Charsets.ISO_8859_1))
        content.write(0)
        content.write(text.toByteArray(Charsets.UTF_8))
        return frame32(code = "USLT", content.toByteArray(), syncsafe = true)
    }

    private fun apic(imageBytes: ByteArray): ByteArray {
        val content = ByteArrayOutputStream()
        content.write(0)
        content.write("image/png".toByteArray(Charsets.ISO_8859_1)); content.write(0)
        content.write(3)
        content.write(0)
        content.write(imageBytes)
        return frame32(code = "APIC", content.toByteArray(), syncsafe = true)
    }

    private fun ult(lang: String, text: String): ByteArray {
        val content = ByteArrayOutputStream()
        content.write(3)
        content.write(lang.toByteArray(Charsets.ISO_8859_1))
        content.write(0)
        content.write(text.toByteArray(Charsets.UTF_8))
        return frame32(code = "ULT", content.toByteArray(), syncsafe = false)
    }

    private fun pic(imageBytes: ByteArray): ByteArray {
        val content = ByteArrayOutputStream()
        content.write(0)
        content.write("PNG".toByteArray(Charsets.ISO_8859_1))
        content.write(3)
        content.write(0)
        content.write(imageBytes)
        return frame32(code = "PIC", content.toByteArray(), syncsafe = false)
    }

    private fun frame32(code: String, data: ByteArray, syncsafe: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(code.toByteArray(Charsets.ISO_8859_1))
        if (syncsafe) out.writeSyncSafe(data.size) else out.writeIntBE(data.size)
        out.write(0); out.write(0)
        out.write(data)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeIntBE(value: Int) {
        write(value shr 24 and 0xFF)
        write(value shr 16 and 0xFF)
        write(value shr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
        write(value shr 16 and 0xFF)
        write(value shr 24 and 0xFF)
    }

    private fun ByteArrayOutputStream.writeSyncSafe(value: Int) {
        write(value shr 21 and 0x7F)
        write(value shr 14 and 0x7F)
        write(value shr 7 and 0x7F)
        write(value and 0x7F)
    }

    // --------------------------------------------------- MP4 / RIFF / AIFF fixtures

    /** Builds an MP4 container: 4-byte big-endian size + type + payload. */
    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeIntBE(8 + payload.size)
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun m4a(ilst: ByteArray): File {
        val ilstBox = box("ilst", ilst)
        val meta = box("meta", (ByteArray(4) + box("hdlr", ByteArray(8))).let { it + ilstBox })
        val udta = box("udta", meta)
        val moov = box("moov", udta)
        val ftyp = box("ftyp", ("M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(8)))
        return temp("m4a", ftyp + moov)
    }

    private fun covr(imageBytes: ByteArray): ByteArray {
        val dataPayload = ByteArrayOutputStream()
        dataPayload.write(ByteArray(8))
        dataPayload.write(imageBytes)
        return box("covr", box("data", dataPayload.toByteArray()))
    }

    private fun lyricAtom(text: String): ByteArray {
        val dataPayload = ByteArrayOutputStream()
        dataPayload.write(ByteArray(8))
        dataPayload.write(text.toByteArray(Charsets.UTF_8))
        return box("\u00A9lyr", box("data", dataPayload.toByteArray()))
    }

    private fun itunesLyrics(text: String): ByteArray {
        val mean = box("mean", "com.apple.iTunes".toByteArray(Charsets.UTF_8))
        val name = box("name", "LYRICS".toByteArray(Charsets.UTF_8))
        val dataPayload = ByteArrayOutputStream()
        dataPayload.write(ByteArray(8))
        dataPayload.write(text.toByteArray(Charsets.UTF_8))
        return box("----", mean + name + box("data", dataPayload.toByteArray()))
    }

    /** RIFF/AIFF chunk: id + size (chosen endianness) + payload + pad to even bounds. */
    private fun riffChunk(id: String, payload: ByteArray, bigEndian: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        if (bigEndian) out.writeIntBE(payload.size) else out.writeIntLE(payload.size)
        out.write(payload)
        if (payload.size and 1 != 0) out.write(0)
        return out.toByteArray()
    }

    private fun wav(id3Tag: ByteArray): File {
        val fmt = riffChunk("fmt ", byteArrayOf(0x01, 0x00), bigEndian = false)
        val id3 = riffChunk("id3 ", id3Tag, bigEndian = false)
        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray(Charsets.ISO_8859_1))
            write(fmt)
            write(id3)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        out.writeIntLE(4 + body.size)
        out.write(body)
        return temp("wav", out.toByteArray())
    }

    private fun aiff(id3Tag: ByteArray): File {
        val comm = riffChunk("COMM", ByteArray(18), bigEndian = true)
        val id3 = riffChunk("ID3 ", id3Tag, bigEndian = true)
        val body = ByteArrayOutputStream().apply {
            write("AIFF".toByteArray(Charsets.ISO_8859_1))
            write(comm)
            write(id3)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("FORM".toByteArray(Charsets.ISO_8859_1))
        out.writeIntBE(4 + body.size)
        out.write(body)
        return temp("aiff", out.toByteArray())
    }

    private companion object {
        const val FLAC_PICTURE = 6
        const val FLAC_VORBIS_COMMENT = 4
    }
}