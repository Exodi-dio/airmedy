package me.misa198.airmedy.pairing

/**
 * Identity and trust contracts retained for playlist mutation reconciliation.
 * The QR pairing/MQTT surface was removed; these types now only describe the
 * local device identity and the desktop peer that reconcile requests target.
 */
data class MobileIdentity(val id: String, val name: String, val platform: String, val publicKey: ByteArray)

interface PairingIdentityProvider {
    suspend fun identity(): MobileIdentity
    suspend fun randomBytes(size: Int): ByteArray
    suspend fun sign(input: ByteArray): ByteArray
    suspend fun verify(publicKey: ByteArray, input: ByteArray, signature: ByteArray): Boolean
}

data class PairedDesktop(
    val desktopId: String,
    val displayName: String,
    val publicKey: ByteArray,
)

internal object Base64Url {
    private const val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index + 2 < bytes.size) {
            val value = ((bytes[index].toInt() and 0xff) shl 16) or
                ((bytes[index + 1].toInt() and 0xff) shl 8) or
                (bytes[index + 2].toInt() and 0xff)
            out.append(Alphabet[(value ushr 18) and 63]).append(Alphabet[(value ushr 12) and 63]).append(Alphabet[(value ushr 6) and 63]).append(Alphabet[value and 63])
            index += 3
        }
        when (bytes.size - index) {
            1 -> { val value = (bytes[index].toInt() and 0xff) shl 16; out.append(Alphabet[(value ushr 18) and 63]).append(Alphabet[(value ushr 12) and 63]) }
            2 -> { val value = ((bytes[index].toInt() and 0xff) shl 16) or ((bytes[index + 1].toInt() and 0xff) shl 8); out.append(Alphabet[(value ushr 18) and 63]).append(Alphabet[(value ushr 12) and 63]).append(Alphabet[(value ushr 6) and 63]) }
        }
        return out.toString()
    }

    fun decodeExact(value: String, length: Int): ByteArray? = runCatching { decode(value) }.getOrNull()?.takeIf { it.size == length }

    private fun decode(value: String): ByteArray {
        require(value.none { it == '=' || Alphabet.indexOf(it) < 0 }) { "Invalid base64url" }
        require(value.length % 4 != 1) { "Invalid base64url" }
        val out = mutableListOf<Byte>(); var index = 0
        while (index + 3 < value.length) {
            val number = (Alphabet.indexOf(value[index]) shl 18) or (Alphabet.indexOf(value[index + 1]) shl 12) or (Alphabet.indexOf(value[index + 2]) shl 6) or Alphabet.indexOf(value[index + 3])
            out += (number ushr 16).toByte(); out += (number ushr 8).toByte(); out += number.toByte(); index += 4
        }
        when (value.length - index) {
            2 -> { val number = (Alphabet.indexOf(value[index]) shl 18) or (Alphabet.indexOf(value[index + 1]) shl 12); out += (number ushr 16).toByte() }
            3 -> { val number = (Alphabet.indexOf(value[index]) shl 18) or (Alphabet.indexOf(value[index + 1]) shl 12) or (Alphabet.indexOf(value[index + 2]) shl 6); out += (number ushr 16).toByte(); out += (number ushr 8).toByte() }
        }
        return out.toByteArray()
    }
}