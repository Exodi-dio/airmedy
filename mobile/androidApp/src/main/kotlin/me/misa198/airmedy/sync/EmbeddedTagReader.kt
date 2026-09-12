package me.misa198.airmedy.sync

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Reads embedded tags directly from the audio file's own bytes. Purely JVM
 * (no Android APIs) so the parsers are host-testable.
 *
 * Covered formats:
 * - FLAC (`fLaC`): METADATA_BLOCK_PICTURE (front cover) artwork and the
 *   Vorbis-comment `LYRICS`/`UNSYNCEDLYRICS` tags. The `LYRICS` tag commonly
 *   holds LRC-formatted synced text.
 * - OGG/Opus (`OggS`): Vorbis-comment `LYRICS`/`UNSYNCEDLYRICS` text and
 *   `METADATA_BLOCK_PICTURE` artwork (base64 per RFC 7845).
 * - M4A/MP4 (`ftyp`): `moov > udta > meta > ilst` `covr` artwork, the
 *   `\u00A9lyr` lyrics atom, and the iTunes `----:com.apple.iTunes:LYRICS`
 *   free-form lyrics atom. Tags placed after a leading `mdat` (non-faststart
 *   files) live beyond the bounded prefix and are not read here.
 * - MP3 ID3v2.2/2.3/2.4: `APIC`/`PIC` artwork and `USLT`/`ULT` lyrics frames.
 * - WAV (`RIFF`/`WAVE`) and AIFF (`FORM`/`AIFF`|`AIFC`): `id3 `/`ID3 `
 *   chunks carrying an embedded ID3v2 tag; WAV files with a tag prepended
 *   directly to the file are handled by the ID3 branch.
 *
 * Only a bounded prefix of each file is read (tags live at the file head).
 */
internal object EmbeddedTagReader {

    private const val FlacReadLimit = 8 * 1024 * 1024
    private const val Id3ReadLimit = 24 * 1024 * 1024
    private const val OggReadLimit = 512 * 1024
    private const val Mp4ReadLimit = 24 * 1024 * 1024
    private const val RiffReadLimit = 24 * 1024 * 1024
    private const val MagicReadLimit = 32 * 1024

    /** Embedded album-art bytes (front cover when available), or null. */
    fun embeddedArtworkBytes(path: String): ByteArray? = runCatching {
        val head = readPrefix(path, MagicReadLimit) ?: return null
        when {
            head.isFlac() -> flacPicture(readPrefix(path, FlacReadLimit) ?: return null)
            head.isId3() -> id3Apic(readPrefix(path, Id3ReadLimit) ?: return null)
            head.isOgg() -> oggPicture(readPrefix(path, OggReadLimit) ?: return null)
            head.isMp4() -> mp4Covr(readPrefix(path, Mp4ReadLimit) ?: return null)
            head.isRiff() || head.isForm() -> riffId3Picture(readPrefix(path, RiffReadLimit) ?: return null)
            else -> null
        }
    }.getOrNull()

    /** Embedded lyrics text (`LYRICS` -> `UNSYNCEDLYRICS` -> `USLT` -> M4A/AIFF), or null. */
    fun embeddedLyricsText(path: String): String? = runCatching {
        if (id3Magic(path)) {
            id3Uslt(readPrefix(path, Id3ReadLimit) ?: return null)
        } else {
            val head = readPrefix(path, MagicReadLimit) ?: return null
            when {
                head.isFlac() -> flacVorbisLyrics(readPrefix(path, FlacReadLimit) ?: return null)
                head.isOgg() -> oggVorbisLyrics(readPrefix(path, OggReadLimit) ?: return null)
                head.isMp4() -> mp4Lyrics(readPrefix(path, Mp4ReadLimit) ?: return null)
                head.isRiff() || head.isForm() -> riffId3Lyrics(readPrefix(path, RiffReadLimit) ?: return null)
                else -> null
            }
        }
    }.getOrNull()

    private fun id3Magic(path: String): Boolean {
        val file = File(path)
        if (!file.isFile || file.length() < 10L) return false
        val head = runCatching { file.inputStream().use { readUpTo(it, 10) } }.getOrNull() ?: return false
        return head.isId3()
    }

    private fun readPrefix(path: String, limit: Int): ByteArray? {
        val file = File(path)
        val length = file.length()
        if (length <= 0L) return null
        return file.inputStream().use { input -> readUpTo(input, minOf(limit, length.toInt())) }
    }

    /** Loops raw reads so bounded prefix reads work on every supported API level. */
    private fun readUpTo(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val read = input.read(buffer, total, maxBytes - total)
            if (read < 0) break
            total += read
        }
        return if (total == maxBytes) buffer else buffer.copyOf(total)
    }

    // ---------------------------------------------------------------- FLAC

    private fun flacPicture(bytes: ByteArray): ByteArray? {
        var offset = 4
        var block = 0
        var firstPicture: ByteArray? = null
        while (offset + 4 <= bytes.size && block < 256) {
            val type = bytes[offset].toInt() and 0x7F
            val last = bytes[offset].toInt() and 0x80 != 0
            val length = bytes.readUInt24BE(offset + 1)
            val payloadStart = offset + 4
            val payloadEnd = payloadStart + length
            if (payloadEnd > bytes.size) break
            if (type == 6) {
                val picture = bytes.copyOfRange(payloadStart, payloadEnd).flacPicturePayload()
                if (picture != null) {
                    // Prefer the front-cover block; remember any other picture as a fallback.
                    if (bytes[payloadStart + 3].toInt() and 0xFF == 3) return picture
                    if (firstPicture == null) firstPicture = picture
                }
            }
            if (last) break
            offset = payloadEnd
            block++
        }
        return firstPicture
    }

    /** METADATA_BLOCK_PICTURE layout (all lengths big-endian). */
    private fun ByteArray.flacPicturePayload(): ByteArray? {
        if (size < 32) return null
        var pos = 0
        pos += 4 // picture type (prefer returning the first block; FLAC permits one front cover)
        val mimeLength = readUInt32BE(pos); pos += 4
        if (mimeLength < 0 || pos + mimeLength > size) return null
        pos += mimeLength
        val descriptionLength = readUInt32BE(pos); pos += 4
        if (descriptionLength < 0 || pos + descriptionLength > size) return null
        pos += descriptionLength
        pos += 16 // width, height, colour depth, colour count
        if (pos + 4 > size) return null
        val dataLength = readUInt32BE(pos); pos += 4
        if (dataLength <= 0 || pos + dataLength > size) return null
        return copyOfRange(pos, pos + dataLength)
    }

    private fun flacVorbisLyrics(bytes: ByteArray): String? {
        var offset = 4
        var block = 0
        while (offset + 4 <= bytes.size && block < 256) {
            val type = bytes[offset].toInt() and 0x7F
            val last = bytes[offset].toInt() and 0x80 != 0
            val length = bytes.readUInt24BE(offset + 1)
            val payloadStart = offset + 4
            val payloadEnd = payloadStart + length
            if (payloadEnd > bytes.size) return null
            if (type == 4) bytes.copyOfRange(payloadStart, payloadEnd).vorbisComment()?.let {
                return it.lyrics ?: it.unsynced
            }
            if (last) return null
            offset = payloadEnd
            block++
        }
        return null
    }

    // ---------------------------------------------------------------- OGG

    private fun oggVorbisLyrics(bytes: ByteArray): String? = oggVorbisComment(bytes)?.let { it.lyrics ?: it.unsynced }

    private fun oggPicture(bytes: ByteArray): ByteArray? = oggVorbisComment(bytes)?.picture

    private fun oggVorbisComment(bytes: ByteArray): VorbisComment? {
        // The vorbis comment header packet starts with 0x03 "vorbis".
        val needle = byteArrayOf(0x03, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
        val start = bytes.indexOfNeedle(needle)
        if (start < 0) return null
        return bytes.copyOfRange(start + needle.size, bytes.size).vorbisComment()
    }

    /** Parsed Vorbis-comment block; any of the fields may be null when absent. */
    private class VorbisComment(val lyrics: String?, val unsynced: String?, val picture: ByteArray?)

    /** Vorbis-comment block: vendor (LE), vendor data, count (LE), then "key=value" pairs. */
    private fun ByteArray.vorbisComment(): VorbisComment? {
        if (size < 8) return null
        var pos = 0
        val vendorLength = readUInt32LE(pos); pos += 4
        if (vendorLength < 0 || pos + vendorLength > size) return null
        pos += vendorLength
        if (pos + 4 > size) return null
        val count = readUInt32LE(pos).coerceIn(0, 4096); pos += 4
        var lyrics: String? = null
        var unsynced: String? = null
        var picture: ByteArray? = null
        repeat(count) {
            if (pos + 4 > size) return@repeat
            val length = readUInt32LE(pos); pos += 4
            if (length < 0 || pos + length > size) {
                pos = size
                return@repeat
            }
            val entry = copyOfRange(pos, pos + length)
            pos += length
            val equals = entry.indexOf('='.code.toByte())
            if (equals > 0) {
                val key = String(entry, 0, equals, Charsets.ISO_8859_1).trim().uppercase()
                // Values are UTF-8 in the Vorbis-comment spec; decode separately so
                // non-Latin lyrics (e.g. Korean) survive past the ASCII key scan.
                val entryValue = String(entry, equals + 1, entry.size - equals - 1, Charsets.UTF_8)
                when (key) {
                    "LYRICS" -> if (lyrics == null) lyrics = entryValue
                    "UNSYNCEDLYRICS" -> if (unsynced == null) unsynced = entryValue
                    "METADATA_BLOCK_PICTURE" -> if (picture == null) picture = entryValue.decodePictureBlock()
                }
            }
        }
        return VorbisComment(lyrics, unsynced, picture)
    }

    /** `METADATA_BLOCK_PICTURE` values are base64-encoded FLAC-style pictures (RFC 7845). */
    private fun String.decodePictureBlock(): ByteArray? = try {
        val base64 = trim()
        if (base64.isBlank()) null else java.util.Base64.getDecoder().decode(base64).flacPicturePayload()
    } catch (_: IllegalArgumentException) {
        null
    }

    // ---------------------------------------------------------------- MP4

    /** A well-formed MP4 box region: header bytes already skipped. */
    private class Mp4Box(val type: String, val start: Int, val end: Int)

    /** Enumerates the sibling boxes in [start, end), skipping extended-size boxes safely. */
    private fun ByteArray.mp4Boxes(start: Int, end: Int): List<Mp4Box> {
        val out = ArrayList<Mp4Box>(4)
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readUInt32BE(pos)
            val type = String(this, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            var boxEnd: Long
            if (size32 == 1) {
                if (pos + 16 > end) break
                val large = readUInt64BE(pos + 8)
                if (large < 16) break
                header = 16
                boxEnd = pos + large
            } else if (size32 == 0) {
                boxEnd = end.toLong()
            } else {
                boxEnd = pos.toLong() + size32
            }
            if (boxEnd < pos + header || boxEnd > end) break
            out += Mp4Box(type, pos + header, boxEnd.toInt())
            pos = boxEnd.toInt()
        }
        return out
    }

    /**
     * Locates `moov > udta > meta > ilst`. The `meta` box is formally a full
     * box, but many iTunes-authored files omit its version/flags quartet, so
     * both layouts are accepted.
     */
    private fun ByteArray.mp4IlstRegion(): Pair<Int, Int>? {
        for (moov in mp4Boxes(0, size)) {
            if (moov.type != "moov") continue
            for (udta in mp4Boxes(moov.start, moov.end)) {
                if (udta.type != "udta") continue
                for (meta in mp4Boxes(udta.start, udta.end)) {
                    if (meta.type != "meta") continue
                    for (ilst in mp4Boxes(metaChildrenStart(meta), meta.end)) {
                        if (ilst.type == "ilst") return ilst.start to ilst.end
                    }
                }
            }
        }
        return null
    }

    private fun ByteArray.metaChildrenStart(meta: Mp4Box): Int {
        fun plausible(offset: Int): Boolean =
            offset + 8 <= meta.end && readUInt32BE(offset) in 8..(meta.end - offset)
        if (plausible(meta.start)) return meta.start
        if (plausible(meta.start + 4)) return meta.start + 4
        return meta.start
    }

    private fun mp4Covr(bytes: ByteArray): ByteArray? {
        val (start, end) = bytes.mp4IlstRegion() ?: return null
        for (item in bytes.mp4Boxes(start, end)) {
            if (item.type != "covr") continue
            for (data in bytes.mp4Boxes(item.start, item.end)) {
                if (data.type != "data") continue
                val payloadStart = data.start + 8 // version/flags + locale
                if (payloadStart >= data.end) continue
                val picture = bytes.copyOfRange(payloadStart, data.end)
                if (picture.isNotEmpty()) return picture
            }
        }
        return null
    }

    private fun mp4Lyrics(bytes: ByteArray): String? {
        val (start, end) = bytes.mp4IlstRegion() ?: return null
        var lyricText: String? = null
        var itunesLyrics: String? = null
        for (item in bytes.mp4Boxes(start, end)) {
            when (item.type) {
                "\u00A9lyr" -> if (lyricText == null) bytes.mp4DataText(item.start, item.end)?.let { lyricText = it }
                "----" -> {
                    // iTunes free-form: mean=com.apple.iTunes, name=LYRICS.
                    val key = bytes.mp4FreeformKey(item.start, item.end)
                    val value = bytes.mp4DataText(item.start, item.end)
                    if (key != null && value != null && (key == "com.apple.iTunes.LYRICS" || key == "LYRICS")) {
                        if (itunesLyrics == null) itunesLyrics = value
                    }
                }
            }
        }
        return lyricText ?: itunesLyrics
    }

    /** First non-empty utf-8 text from a `data` atom (skips version/flags + locale). */
    private fun ByteArray.mp4DataText(start: Int, end: Int): String? {
        for (data in mp4Boxes(start, end)) {
            if (data.type != "data") continue
            val payloadStart = data.start + 8
            if (payloadStart >= data.end) continue
            val text = String(copyOfRange(payloadStart, data.end), Charsets.UTF_8).trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    /** `mean.name` key of a `----` atom (values are padded with NULs/spaces). */
    private fun ByteArray.mp4FreeformKey(start: Int, end: Int): String? {
        var mean: String? = null
        var name: String? = null
        for (atom in mp4Boxes(start, end)) {
            val value = atomText(atom)
            when (atom.type) {
                "mean" -> if (mean == null) mean = value
                "name" -> if (name == null) name = value
            }
        }
        return if (mean == null && name == null) null else listOfNotNull(mean, name).joinToString(".")
    }

    private fun ByteArray.atomText(box: Mp4Box): String? {
        if (box.end <= box.start) return null
        return String(copyOfRange(box.start, box.end), Charsets.UTF_8).trim('\u0000', ' ')
    }

    // ---------------------------------------------------------------- RIFF / AIFF

    /** Embedded ID3v2 tag carried by a WAV `id3 ` or AIFF `ID3 ` chunk, or null. */
    private fun riffId3Tag(bytes: ByteArray): ByteArray? = runCatching {
        if (bytes.size < 12) return@runCatching null
        val bigEndian = bytes.isForm() // AIFF is big-endian; WAV is little-endian.
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkSize = if (bigEndian) bytes.readUInt32BE(pos + 4) else bytes.readUInt32LE(pos + 4)
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            val payloadStart = pos + 8
            val chunkEnd = payloadStart + chunkSize
            if (chunkEnd > bytes.size) break
            if (id.equals("id3 ", ignoreCase = true) || id.equals("ID3 ", ignoreCase = true)) {
                val candidate = bytes.copyOfRange(payloadStart, chunkEnd)
                if (candidate.isId3()) return@runCatching candidate
            }
            // RIFF chunks are word-aligned.
            pos = chunkEnd + (chunkSize and 1)
        }
        null
    }.getOrNull()

    private fun riffId3Picture(bytes: ByteArray): ByteArray? = riffId3Tag(bytes)?.let { id3Apic(it) }

    private fun riffId3Lyrics(bytes: ByteArray): String? = riffId3Tag(bytes)?.let { id3Uslt(it) }

    // ---------------------------------------------------------------- ID3v2

    private fun id3Apic(bytes: ByteArray): ByteArray? {
        val (body, major) = bytes.id3Body() ?: return null
        var pos = 0
        var frames = 0
        if (major == 2) {
            while (pos + 6 <= body.size && frames < 512) {
                val id = String(body, pos, 3, Charsets.ISO_8859_1)
                val length = body.readUInt24BE(pos + 3)
                val dataStart = pos + 6
                val dataEnd = dataStart + length
                if (dataEnd > body.size) return null
                if (id == "PIC") body.copyOfRange(dataStart, dataEnd).parsePic()?.let { return it }
                pos = dataEnd
                frames++
            }
        } else {
            while (pos + 10 <= body.size && frames < 512) {
                val id = String(body, pos, 4, Charsets.ISO_8859_1)
                if (id == "\u0000\u0000\u0000\u0000") break
                val syncSafe = major == 4
                val length = if (syncSafe) body.syncsafeInt(pos + 4) else body.readUInt32BE(pos + 4)
                val flags = ((body[pos + 8].toInt() and 0xFF) shl 8) or (body[pos + 9].toInt() and 0xFF)
                val dataStart = pos + 10
                val rawEnd = dataStart + length.coerceAtMost(body.size - dataStart)
                var data = body.copyOfRange(dataStart, rawEnd)
                if (major == 4 && flags and 0x0002 != 0) data = data.unsync()
                if (id == "APIC") data.parseApic()?.let { return it }
                pos = rawEnd
                frames++
            }
        }
        return null
    }

    private fun id3Uslt(bytes: ByteArray): String? {
        val (body, major) = bytes.id3Body() ?: return null
        var pos = 0
        var frames = 0
        if (major == 2) {
            while (pos + 6 <= body.size && frames < 512) {
                val id = String(body, pos, 3, Charsets.ISO_8859_1)
                val length = body.readUInt24BE(pos + 3)
                val dataStart = pos + 6
                val dataEnd = dataStart + length
                if (dataEnd > body.size) return null
                if (id == "ULT") body.copyOfRange(dataStart, dataEnd).parseUslt()?.let { return it }
                pos = dataEnd
                frames++
            }
        } else {
            while (pos + 10 <= body.size && frames < 512) {
                val id = String(body, pos, 4, Charsets.ISO_8859_1)
                if (id == "\u0000\u0000\u0000\u0000") break
                val syncSafe = major == 4
                val length = if (syncSafe) body.syncsafeInt(pos + 4) else body.readUInt32BE(pos + 4)
                val flags = ((body[pos + 8].toInt() and 0xFF) shl 8) or (body[pos + 9].toInt() and 0xFF)
                val dataStart = pos + 10
                val rawEnd = dataStart + length.coerceAtMost(body.size - dataStart)
                var data = body.copyOfRange(dataStart, rawEnd)
                if (major == 4 && flags and 0x0002 != 0) data = data.unsync()
                if (id == "USLT") data.parseUslt()?.let { return it }
                pos = rawEnd
                frames++
            }
        }
        return null
    }

    /** Splits the tag into its frame payload, honouring v2.3 tag-level unsynchronisation. */
    private fun ByteArray.id3Body(): Pair<ByteArray, Int>? {
        if (size < 10 || this[0] != 'I'.code.toByte() || this[1] != 'D'.code.toByte() || this[2] != '3'.code.toByte()) return null
        val major = this[3].toInt() and 0xFF
        if (major != 2 && major != 3 && major != 4) return null
        val flags = this[5].toInt() and 0xFF
        val tagLength = this.syncsafeInt(6)
        if (tagLength <= 0) return null
        var body = copyOfRange(10, (10 + tagLength).coerceAtMost(size))
        if (major == 3 && flags and 0x80 != 0) body = body.unsync()
        return body to major
    }

    /** v2.2 `PIC`: encoding, image format (PNG/JPG), picture type, description, data. */
    private fun ByteArray.parsePic(): ByteArray? {
        if (size < 6) return null
        val encoding = this[0].toInt() and 0xFF
        if (encoding > 3) return null
        var pos = 1
        pos += 3 // image format
        pos += 1 // picture type
        if (pos >= size) return null
        val (_, end) = id3TextField(this, pos, encoding)
        return copyOfRange(end, size).takeIf { it.isNotEmpty() }
    }

    /** v2.3/2.4 `APIC`: encoding, mime, picture type, description, data. */
    private fun ByteArray.parseApic(): ByteArray? {
        if (size < 4) return null
        val encoding = this[0].toInt() and 0xFF
        if (encoding > 3) return null
        val mimeEnd = indexOfByte(0, 1)
        if (mimeEnd < 0) return null
        var pos = mimeEnd + 1
        if (pos >= size) return null
        pos += 1 // picture type
        if (pos >= size) return null
        val (_, end) = id3TextField(this, pos, encoding)
        return copyOfRange(end, size).takeIf { it.isNotEmpty() }
    }

    /** v2.2/2.3/2.4 `USLT`/`ULT`: encoding, language, content descriptor, text. */
    private fun ByteArray.parseUslt(): String? {
        if (size < 4) return null
        val encoding = this[0].toInt() and 0xFF
        if (encoding > 3) return null
        var pos = 1
        if (encoding == 1 && pos + 1 < size && isUtf16Bom(pos)) pos += 2
        pos += 3 // language
        if (pos >= size) return null
        val (_, afterDescriptor) = id3TextField(this, pos, encoding)
        if (afterDescriptor >= size) return null
        val text = decodeUsltText(copyOfRange(afterDescriptor, size), encoding)
        return text.trim().takeIf(String::isNotEmpty)
    }

    /** Lyrics text after the descriptor, using the frame's declared encoding. */
    private fun decodeUsltText(bytes: ByteArray, encoding: Int): String = when (encoding) {
        3 -> bytes.toString(Charsets.UTF_8)
        2 -> bytes.toString(Charsets.UTF_16BE)
        1 -> when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            else -> bytes.toString(Charsets.UTF_16BE)
        }
        else -> bytes.toString(Charsets.ISO_8859_1)
    }

    private fun ByteArray.isUtf16Bom(pos: Int): Boolean =
        (this[pos] == 0xFF.toByte() && this[pos + 1] == 0xFE.toByte()) ||
            (this[pos] == 0xFE.toByte() && this[pos + 1] == 0xFF.toByte())

    /** Reads an encoded, null-terminated ID3 text field, returning (text, indexAfterTerminator). */
    private fun id3TextField(bytes: ByteArray, start: Int, encoding: Int): Pair<String, Int> {
        var pos = start
        when (encoding) {
            1, 2 -> {
                while (pos + 1 < bytes.size) {
                    if (bytes[pos] == 0.toByte() && bytes[pos + 1] == 0.toByte()) {
                        return decodeId3Text(bytes.copyOfRange(start, pos), encoding) to pos + 2
                    }
                    pos += 2
                }
                return decodeId3Text(bytes.copyOfRange(start, bytes.size), encoding) to bytes.size
            }
            else -> {
                while (pos < bytes.size) {
                    if (bytes[pos] == 0.toByte()) {
                        return String(bytes, start, pos - start, if (encoding == 3) Charsets.UTF_8 else Charsets.ISO_8859_1) to pos + 1
                    }
                    pos++
                }
                return String(bytes, start, bytes.size - start, if (encoding == 3) Charsets.UTF_8 else Charsets.ISO_8859_1) to bytes.size
            }
        }
    }

    private fun decodeId3Text(bytes: ByteArray, encoding: Int): String = when {
        encoding >= 2 -> bytes.toString(Charsets.UTF_16BE)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        else -> bytes.toString(Charsets.UTF_16LE)
    }

    private fun ByteArray.unsync(): ByteArray {
        if (isEmpty()) return this
        val out = ByteArrayOutputStream(size)
        var pos = 0
        while (pos < size) {
            if (this[pos] == 0xFF.toByte() && pos + 1 < size && this[pos + 1] == 0.toByte()) {
                out.write(this[pos].toInt())
                pos += 2
            } else {
                out.write(this[pos].toInt())
                pos += 1
            }
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- magic

    private fun ByteArray.isFlac(): Boolean {
        if (size < 4) return false
        return this[0] == 'f'.code.toByte() && this[1] == 'L'.code.toByte() && this[2] == 'a'.code.toByte() && this[3] == 'C'.code.toByte()
    }

    private fun ByteArray.isOgg(): Boolean {
        if (size < 4) return false
        return this[0] == 'O'.code.toByte() && this[1] == 'g'.code.toByte() && this[2] == 'g'.code.toByte() && this[3] == 'S'.code.toByte()
    }

    private fun ByteArray.isId3(): Boolean {
        if (size < 10) return false
        return this[0] == 'I'.code.toByte() && this[1] == 'D'.code.toByte() && this[2] == '3'.code.toByte()
    }

    private fun ByteArray.isMp4(): Boolean {
        if (size < 12) return false
        return this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() && this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()
    }

    private fun ByteArray.isRiff(): Boolean {
        if (size < 12) return false
        return this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() && this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
            this[8] == 'W'.code.toByte() && this[9] == 'A'.code.toByte() && this[10] == 'V'.code.toByte() && this[11] == 'E'.code.toByte()
    }

    private fun ByteArray.isForm(): Boolean {
        if (size < 12) return false
        return this[0] == 'F'.code.toByte() && this[1] == 'O'.code.toByte() && this[2] == 'R'.code.toByte() && this[3] == 'M'.code.toByte() &&
            this[8] == 'A'.code.toByte() && this[9] == 'I'.code.toByte() && this[10] == 'F'.code.toByte() &&
            (this[11] == 'F'.code.toByte() || this[11] == 'C'.code.toByte())
    }

    private fun ByteArray.indexOfNeedle(needle: ByteArray, limit: Int = minOf(size, 262144)): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        val maxStart = minOf(size - needle.size, limit - needle.size)
        for (pos in 0..maxStart) {
            var matches = true
            for (i in needle.indices) {
                if (this[pos + i] != needle[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return pos
        }
        return -1
    }
}

// ---------------------------------------------------------------- primitives

private fun ByteArray.readUInt32BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readUInt24BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 16) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        (this[offset + 2].toInt() and 0xFF)

private fun ByteArray.readUInt32LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.syncsafeInt(offset: Int): Int {
    var value = 0
    for (i in 0 until 4) {
        value = (value shl 7) or (this[offset + i].toInt() and 0x7F)
    }
    return value
}

private fun ByteArray.readUInt64BE(offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) {
        value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
    }
    return value
}

private fun ByteArray.indexOfByte(element: Byte, startIndex: Int): Int {
    for (pos in startIndex until size) if (this[pos] == element) return pos
    return -1
}