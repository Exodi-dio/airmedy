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
 * - OGG/Opus (`OggS`): Vorbis-comment `LYRICS`/`UNSYNCEDLYRICS` text.
 * - MP3 ID3v2.2/2.3/2.4: `APIC`/`PIC` artwork and `USLT`/`ULT` lyrics frames.
 *
 * Only a bounded prefix of each file is read (tags live at the file head).
 */
internal object EmbeddedTagReader {

    private const val FlacReadLimit = 8 * 1024 * 1024
    private const val Id3ReadLimit = 24 * 1024 * 1024
    private const val OggReadLimit = 512 * 1024

    /** Embedded album-art bytes (front cover when available), or null. */
    fun embeddedArtworkBytes(path: String): ByteArray? = runCatching {
        val bytes = readPrefix(path, FlacReadLimit) ?: return null
        when {
            bytes.isFlac() -> flacPicture(bytes)
            bytes.isId3() -> id3Apic(bytes)
            else -> null
        }
    }.getOrNull()

    /** Embedded lyrics text (`LYRICS` -> `UNSYNCEDLYRICS` -> `USLT`), or null. */
    fun embeddedLyricsText(path: String): String? = runCatching {
        if (id3Magic(path)) {
            id3Uslt(readPrefix(path, Id3ReadLimit) ?: return null)
        } else {
            val head = readPrefix(path, OggReadLimit) ?: return null
            when {
                head.isFlac() -> flacVorbisLyrics(readPrefix(path, FlacReadLimit) ?: head)
                head.isOgg() -> oggVorbisLyrics(head)
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
            if (type == 4) bytes.copyOfRange(payloadStart, payloadEnd).vorbisComment()?.let { return it }
            if (last) return null
            offset = payloadEnd
            block++
        }
        return null
    }

    // ---------------------------------------------------------------- OGG

    private fun oggVorbisLyrics(bytes: ByteArray): String? {
        // The vorbis comment header packet starts with 0x03 "vorbis".
        val needle = byteArrayOf(0x03, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
        val start = bytes.indexOfNeedle(needle)
        if (start < 0) return null
        return bytes.copyOfRange(start + needle.size, bytes.size).vorbisComment()
    }

    /** Vorbis-comment block: vendor (LE), vendor data, count (LE), then "key=value" pairs. */
    private fun ByteArray.vorbisComment(): String? {
        if (size < 8) return null
        var pos = 0
        val vendorLength = readUInt32LE(pos); pos += 4
        if (vendorLength < 0 || pos + vendorLength > size) return null
        pos += vendorLength
        if (pos + 4 > size) return null
        val count = readUInt32LE(pos).coerceIn(0, 4096); pos += 4
        var lyrics: String? = null
        var unsynced: String? = null
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
                }
            }
        }
        return lyrics ?: unsynced
    }

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
        val (text, _) = id3TextField(this, pos, encoding)
        return text.trim().takeIf(String::isNotEmpty)
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

private fun ByteArray.indexOfByte(element: Byte, startIndex: Int): Int {
    for (pos in startIndex until size) if (this[pos] == element) return pos
    return -1
}