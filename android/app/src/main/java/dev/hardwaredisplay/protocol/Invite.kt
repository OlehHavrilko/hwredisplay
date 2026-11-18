package dev.hardwaredisplay.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

const val MAX_INVITE_BYTES = 1024

@Serializable
data class Invite(
    val hostId: String,
    val ip: String,
    val port: Int,
    val certSha256: String,
    val invite: String,
) {
    val canonicalName: String get() = canonicalNameFor(hostId)
    val baseUrl: String get() = "https://$canonicalName:$port/"
}

sealed interface InviteParse {
    data class Valid(val invite: Invite) : InviteParse
    data class Invalid(val reason: String) : InviteParse
}

fun canonicalNameFor(hostId: String): String = "pc-$hostId.hardwaredisplay.test"

internal val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val HEX_PATTERN = Regex("^[0-9a-fA-F]+$")

/**
 * Приглашение принимается только как доверенный JSON без ссылок, путей и имён хостов.
 * Адрес обязан быть числовым IPv4 из RFC 1918: маршрут до ПК задаёт пользователь, а не QR.
 */
fun parseInvite(text: String): InviteParse {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) {
        return InviteParse.Invalid("empty")
    }

    if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_INVITE_BYTES) {
        return InviteParse.Invalid("too_large")
    }

    val root = try {
        Json.parseToJsonElement(trimmed) as? JsonObject
    } catch (error: Exception) {
        null
    } ?: return InviteParse.Invalid("bad_json")

    val version = (root["v"] as? JsonPrimitive)?.intOrNull
        ?: return InviteParse.Invalid("missing_version")
    if (version != PROTOCOL_VERSION) {
        return InviteParse.Invalid("unsupported_version")
    }

    val hostId = (root["hostId"] as? JsonPrimitive)?.content?.trim()
        ?: return InviteParse.Invalid("missing_host_id")
    if (!UUID_PATTERN.matches(hostId)) {
        return InviteParse.Invalid("bad_host_id")
    }

    val ip = (root["ip"] as? JsonPrimitive)?.content?.trim()
        ?: return InviteParse.Invalid("missing_ip")
    if (!isPrivateIpv4(ip)) {
        return InviteParse.Invalid("bad_ip")
    }

    val port = (root["port"] as? JsonPrimitive)?.intOrNull
        ?: return InviteParse.Invalid("missing_port")
    if (port !in 1..65535) {
        return InviteParse.Invalid("bad_port")
    }

    val certSha256 = normalizeHex((root["certSha256"] as? JsonPrimitive)?.content)
        ?: return InviteParse.Invalid("missing_fingerprint")
    if (certSha256.length != 64 || !HEX_PATTERN.matches(certSha256)) {
        return InviteParse.Invalid("bad_fingerprint")
    }

    val invite = normalizeHex((root["invite"] as? JsonPrimitive)?.content)
        ?: return InviteParse.Invalid("missing_code")
    if (invite.length != 32 || !HEX_PATTERN.matches(invite)) {
        return InviteParse.Invalid("bad_code")
    }

    return InviteParse.Valid(
        Invite(
            hostId = hostId.lowercase(),
            ip = ip,
            port = port,
            certSha256 = certSha256,
            invite = invite,
        )
    )
}

/**
 * Ручной ввод с экрана Windows: отпечаток и код приходят сгруппированными,
 * поэтому разделители снимаются перед проверкой.
 */
fun normalizeHex(raw: String?): String? {
    val cleaned = raw?.trim()?.lowercase()?.replace(" ", "")?.replace(":", "")?.replace("-", "")
        ?: return null
    return cleaned.ifEmpty { null }
}

fun isPrivateIpv4(value: String): Boolean {    val parts = value.split(".")
    if (parts.size != 4) {
        return false
    }

    val octets = parts.map { part ->
        if (part.isEmpty() || (part.length > 1 && part[0] == '0') || !part.all { it.isDigit() }) {
            return false
        }
        part.toIntOrNull() ?: return false
    }

    if (octets.any { it !in 0..255 }) {
        return false
    }

    return when {
        octets[0] == 10 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        else -> false
    }
}
