package dev.fxloop.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/** Token generation, constant-time comparison and log redaction. */
object Tokens {
    private val random = SecureRandom()

    /** Random 128-bit token, hex encoded. */
    @JvmStatic
    fun generate(): String = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))

    @JvmStatic
    fun constantTimeEquals(expected: String?, actual: String?): Boolean =
        expected != null && actual != null && MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())

    /** Replaces any occurrence of [token] in [text] so it never reaches a log. */
    @JvmStatic
    fun redact(text: String?, token: String?): String? =
        if (text == null || token.isNullOrEmpty()) text else text.replace(token, "<redacted>")
}
