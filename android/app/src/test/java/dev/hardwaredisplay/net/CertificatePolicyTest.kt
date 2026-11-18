package dev.hardwaredisplay.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificatePolicyTest {

    private val name = "pc-1482a7c6-1d42-40af-869e-6e09ceab6de2.hardwaredisplay.test"
    private val fingerprint = "a".repeat(64)

    private fun facts(
        derSha256: String = fingerprint,
        validityOk: Boolean = true,
        selfSigned: Boolean = true,
        hasServerAuthUsage: Boolean = true,
        dnsNames: List<String> = listOf(name),
        chainLength: Int = 1,
    ) = CertificateFacts(
        derSha256 = derSha256,
        expectedSha256 = fingerprint,
        validityOk = validityOk,
        selfSigned = selfSigned,
        hasServerAuthUsage = hasServerAuthUsage,
        dnsNames = dnsNames,
        expectedName = name,
        chainLength = chainLength,
    )

    @Test
    fun acceptsMatchingCertificate() {
        assertNull(verifyCertificate(facts()))
    }

    @Test
    fun rejectsDifferentFingerprint() {
        assertEquals(CertificateFailure.FINGERPRINT, verifyCertificate(facts(derSha256 = "b".repeat(64))))
    }

    @Test
    fun rejectsExpiredCertificate() {
        assertEquals(CertificateFailure.EXPIRED, verifyCertificate(facts(validityOk = false)))
    }

    @Test
    fun rejectsCertificateThatIsNotSelfSigned() {
        assertEquals(CertificateFailure.NOT_SELF_SIGNED, verifyCertificate(facts(selfSigned = false)))
    }

    @Test
    fun rejectsMissingServerAuthUsage() {
        assertEquals(CertificateFailure.MISSING_SERVER_AUTH, verifyCertificate(facts(hasServerAuthUsage = false)))
    }

    @Test
    fun rejectsWrongName() {
        assertEquals(
            CertificateFailure.NAME_MISMATCH,
            verifyCertificate(facts(dnsNames = listOf("pc-other.hardwaredisplay.test"))),
        )
    }

    @Test
    fun rejectsCertificateChain() {
        assertEquals(CertificateFailure.EXTRA_CHAIN, verifyCertificate(facts(chainLength = 2)))
    }

    @Test
    fun bearerIsUrlSafeAndUnique() {
        val token = Bearer.create()
        val characters = token.toSet()

        assertEquals(43, token.length)
        assertTrue(characters.none { it == '+' || it == '/' || it == '=' })
        assertTrue(token != Bearer.create())
    }
}
