package dev.pi.ai.auth.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun computesRfc7636TestVector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", sha256Base64Url(verifier))
    }

    @Test
    fun computesKnownSha256Vectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).toHex(),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256(
                ("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq").encodeToByteArray(),
            ).toHex(),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            sha256("a".repeat(1_000_000).encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun base64UrlHasNoPaddingAndUsesUrlAlphabet() {
        assertEquals("Zg", base64UrlEncode(byteArrayOf(0x66)))
        assertEquals("_w", base64UrlEncode(byteArrayOf(0xFF.toByte())))
        assertEquals("AQID", base64UrlEncode(byteArrayOf(1, 2, 3)))
        assertEquals("", base64UrlEncode(ByteArray(0)))
    }

    @Test
    fun generatePkceUsesPlatformSecureRandomByDefault() {
        val first = generatePkce()
        val second = generatePkce()
        assertEquals(43, first.verifier.length)
        assertEquals(43, first.challenge.length)
        assertEquals(sha256Base64Url(first.verifier), first.challenge)
        assertTrue(first.verifier != second.verifier)
        assertTrue(first.challenge != second.challenge)
        assertTrue(first.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun generatePkceUsesChallengeOfVerifier() {
        val random = { count: Int -> ByteArray(count) { index -> (index + 1).toByte() } }
        val pkce = generatePkce(random)
        assertEquals(base64UrlEncode(random(32)), pkce.verifier)
        assertEquals(sha256Base64Url(pkce.verifier), pkce.challenge)
        assertEquals(43, pkce.verifier.length)
        assertEquals(43, pkce.challenge.length)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    val value = byte.toInt() and 0xFF
    "0123456789abcdef"[value shr 4].toString() + "0123456789abcdef"[value and 0x0F]
}
