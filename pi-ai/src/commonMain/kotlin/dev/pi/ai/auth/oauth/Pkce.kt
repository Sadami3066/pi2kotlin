package dev.pi.ai.auth.oauth

data class PkcePair(
    val verifier: String,
    val challenge: String,
)

fun base64UrlEncode(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val output = StringBuilder((bytes.size * 4 + 2) / 3)
    var index = 0
    while (index + 2 < bytes.size) {
        val triple = ((bytes[index].toInt() and 0xFF) shl 16) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            (bytes[index + 2].toInt() and 0xFF)
        output.append(alphabet[(triple shr 18) and 0x3F])
        output.append(alphabet[(triple shr 12) and 0x3F])
        output.append(alphabet[(triple shr 6) and 0x3F])
        output.append(alphabet[triple and 0x3F])
        index += 3
    }
    val remaining = bytes.size - index
    if (remaining == 1) {
        val value = (bytes[index].toInt() and 0xFF) shl 16
        output.append(alphabet[(value shr 18) and 0x3F])
        output.append(alphabet[(value shr 12) and 0x3F])
    } else if (remaining == 2) {
        val value = ((bytes[index].toInt() and 0xFF) shl 16) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
        output.append(alphabet[(value shr 18) and 0x3F])
        output.append(alphabet[(value shr 12) and 0x3F])
        output.append(alphabet[(value shr 6) and 0x3F])
    }
    return output.toString()
}

fun generatePkce(randomBytes: (Int) -> ByteArray = ::oauthRandomBytes): PkcePair {
    val verifier = base64UrlEncode(randomBytes(32))
    return PkcePair(verifier = verifier, challenge = sha256Base64Url(verifier))
}

fun sha256Base64Url(text: String): String = base64UrlEncode(sha256(text.encodeToByteArray()))

fun sha256(data: ByteArray): ByteArray {
    val k = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    )
    var h0 = 0x6A09E667L
    var h1 = 0xBB67AE85L
    var h2 = 0x3C6EF372L
    var h3 = 0xA54FF53AL
    var h4 = 0x510E527FL
    var h5 = 0x9B05688CL
    var h6 = 0x1F83D9ABL
    var h7 = 0x5BE0CD19L

    val bitLength = data.size.toLong() * 8
    val paddingLength = (64 - ((data.size + 9) % 64)) % 64
    val message = ByteArray(data.size + 1 + paddingLength + 8)
    data.copyInto(message)
    message[data.size] = 0x80.toByte()
    val lengthIndex = message.size - 8
    for (index in 0 until 8) {
        message[lengthIndex + index] = (bitLength shr (56 - index * 8)).toByte()
    }

    val w = LongArray(64)
    var blockOffset = 0
    while (blockOffset < message.size) {
        for (index in 0 until 16) {
            var word = 0L
            for (byteIndex in 0 until 4) {
                word = (word shl 8) or (message[blockOffset + index * 4 + byteIndex].toLong() and 0xFF)
            }
            w[index] = word
        }
        for (index in 16 until 64) {
            val s0 = rotateRight(w[index - 15], 7) xor rotateRight(w[index - 15], 18) xor (w[index - 15] ushr 3)
            val s1 = rotateRight(w[index - 2], 17) xor rotateRight(w[index - 2], 19) xor (w[index - 2] ushr 10)
            w[index] = (w[index - 16] + s0 + w[index - 7] + s1) and MASK_32
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7

        for (index in 0 until 64) {
            val s1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choice = (e and f) xor (e.inv() and g)
            val temp1 = (h + s1 + choice + k[index] + w[index]) and MASK_32
            val s0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = (s0 + majority) and MASK_32

            h = g
            g = f
            f = e
            e = (d + temp1) and MASK_32
            d = c
            c = b
            b = a
            a = (temp1 + temp2) and MASK_32
        }

        h0 = (h0 + a) and MASK_32
        h1 = (h1 + b) and MASK_32
        h2 = (h2 + c) and MASK_32
        h3 = (h3 + d) and MASK_32
        h4 = (h4 + e) and MASK_32
        h5 = (h5 + f) and MASK_32
        h6 = (h6 + g) and MASK_32
        h7 = (h7 + h) and MASK_32

        blockOffset += 64
    }

    val digest = ByteArray(32)
    val state = longArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
    for (index in 0 until 8) {
        for (byteIndex in 0 until 4) {
            digest[index * 4 + byteIndex] = ((state[index] shr (24 - byteIndex * 8)) and 0xFF).toByte()
        }
    }
    return digest
}

private const val MASK_32 = 0xFFFFFFFFL

private fun rotateRight(value: Long, bits: Int): Long =
    ((value ushr bits) or (value shl (32 - bits))) and MASK_32
