package dev.hardwaredisplay.net

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Факты о сертификате вынесены из TrustManager, чтобы решение проверялось юнит-тестами
 * без генерации настоящих сертификатов в тестах.
 */
data class CertificateFacts(
    val derSha256: String,
    val expectedSha256: String,
    val validityOk: Boolean,
    val selfSigned: Boolean,
    val hasServerAuthUsage: Boolean,
    val dnsNames: List<String>,
    val expectedName: String,
    val chainLength: Int,
)

enum class CertificateFailure(val wireName: String) {
    FINGERPRINT("pin_mismatch"),
    EXPIRED("expired"),
    NOT_SELF_SIGNED("not_self_signed"),
    MISSING_SERVER_AUTH("missing_server_auth"),
    NAME_MISMATCH("name_mismatch"),
    EXTRA_CHAIN("unexpected_chain"),
}

fun verifyCertificate(facts: CertificateFacts): CertificateFailure? {
    if (facts.chainLength != 1) {
        return CertificateFailure.EXTRA_CHAIN
    }
    if (!facts.derSha256.equals(facts.expectedSha256, ignoreCase = true)) {
        return CertificateFailure.FINGERPRINT
    }
    if (!facts.validityOk) {
        return CertificateFailure.EXPIRED
    }
    if (!facts.selfSigned) {
        return CertificateFailure.NOT_SELF_SIGNED
    }
    if (!facts.hasServerAuthUsage) {
        return CertificateFailure.MISSING_SERVER_AUTH
    }
    if (facts.dnsNames.none { it.equals(facts.expectedName, ignoreCase = true) }) {
        return CertificateFailure.NAME_MISMATCH
    }
    return null
}

/**
 * Доверяет единственному ожидаемому самоподписанному leaf. Отпечаток пришёл из приглашения,
 * поэтому несовпадение — отказ до отправки токена, а не предупреждение.
 */
class PinnedTrustManager(
    private val expectedSha256: String,
    private val expectedName: String,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificates = chain ?: throw CertificateException("missing_chain")
        val leaf = certificates.firstOrNull() ?: throw CertificateException("missing_leaf")

        val facts = CertificateFacts(
            derSha256 = sha256Hex(leaf.encoded),
            expectedSha256 = expectedSha256,
            validityOk = runCatching { leaf.checkValidity() }.isSuccess,
            selfSigned = isSelfSigned(leaf),
            hasServerAuthUsage = leaf.extendedKeyUsage?.contains(SERVER_AUTH_OID) == true,
            dnsNames = dnsNamesOf(leaf),
            expectedName = expectedName,
            chainLength = certificates.size,
        )

        verifyCertificate(facts)?.let { failure ->
            throw CertificateException("pin_" + failure.wireName)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
        const val DNS_NAME_TYPE = 2

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun isSelfSigned(certificate: X509Certificate): Boolean {
            if (certificate.subjectX500Principal != certificate.issuerX500Principal) {
                return false
            }
            return runCatching { certificate.verify(certificate.publicKey) }.isSuccess
        }

        @Suppress("UNCHECKED_CAST")
        fun dnsNamesOf(certificate: X509Certificate): List<String> {
            val names = certificate.subjectAlternativeNames ?: return emptyList()
            return names.mapNotNull { entry ->
                val list = entry as? List<Any?> ?: return@mapNotNull null
                if (list.size < 2 || (list[0] as? Int) != DNS_NAME_TYPE) {
                    return@mapNotNull null
                }
                list[1]?.toString()
            }
        }
    }
}
