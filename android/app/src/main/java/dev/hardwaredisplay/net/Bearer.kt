package dev.hardwaredisplay.net

import java.security.SecureRandom
import java.util.Base64

object Bearer {
    private const val TOKEN_BYTES = 32

    fun create(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
