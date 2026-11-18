package dev.hardwaredisplay.protocol

data class ManualEntryFields(
    val pcName: String,
    val address: String,
    val port: String,
    val fingerprint: String,
    val code: String,
)

private val HOST_ID_IN_NAME = Regex("^pc-([0-9a-fA-F-]{36})\\.hardwaredisplay\\.test$", RegexOption.IGNORE_CASE)

/**
 * Ручной ввод повторяет поля экрана Windows, но проходит ту же проверку, что и QR:
 * собирается JSON приглашения и отдаётся общему разбору.
 */
fun parseManualEntry(fields: ManualEntryFields): InviteParse {
    val hostId = extractHostId(fields.pcName) ?: return InviteParse.Invalid("bad_host_id")

    val address = fields.address.trim()
    val parts = address.split(":")
    val ip = parts[0].trim()
    val portFromAddress = parts.getOrNull(1)?.trim()
    val port = portFromAddress?.takeIf { it.isNotEmpty() } ?: fields.port.trim()

    if (port.isEmpty() || port.toIntOrNull() == null) {
        return InviteParse.Invalid("bad_port")
    }

    val json = """{"v":1,"hostId":"$hostId","ip":"$ip","port":${port.toInt()},""" +
        """"certSha256":"${escape(fields.fingerprint)}","invite":"${escape(fields.code)}"}"""

    return parseInvite(json)
}

fun extractHostId(pcName: String): String? {
    val trimmed = pcName.trim()
    HOST_ID_IN_NAME.find(trimmed)?.let { return it.groupValues[1].lowercase() }
    return trimmed.takeIf { UUID_PATTERN.matches(it) }?.lowercase()
}

private fun escape(value: String): String = value.replace("\"", "")
