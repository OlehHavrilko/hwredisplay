package dev.hardwaredisplay.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteTest {

    private val hostId = "1482a7c6-1d42-40af-869e-6e09ceab6de2"
    private val fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val code = "00112233445566778899aabbccddeeff"

    private fun json(
        version: String = "1",
        id: String = hostId,
        ip: String = "192.168.1.20",
        port: String = "18443",
        cert: String = fingerprint,
        invite: String = code,
    ) = """{"v":$version,"hostId":"$id","ip":"$ip","port":$port,"certSha256":"$cert","invite":"$invite"}"""

    @Test
    fun acceptsWellFormedInvite() {
        val parsed = parseInvite(json()) as InviteParse.Valid
        assertEquals(hostId, parsed.invite.hostId)
        assertEquals("192.168.1.20", parsed.invite.ip)
        assertEquals(18443, parsed.invite.port)
        assertEquals("pc-$hostId.hardwaredisplay.test", parsed.invite.canonicalName)
        assertEquals("https://pc-$hostId.hardwaredisplay.test:18443/", parsed.invite.baseUrl)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertEquals("unsupported_version", (parseInvite(json(version = "2")) as InviteParse.Invalid).reason)
    }

    @Test
    fun rejectsNonRfc1918Address() {
        assertEquals("bad_ip", (parseInvite(json(ip = "8.8.8.8")) as InviteParse.Invalid).reason)
        assertEquals("bad_ip", (parseInvite(json(ip = "example.local")) as InviteParse.Invalid).reason)
    }

    @Test
    fun rejectsPortOutsideRange() {
        assertEquals("bad_port", (parseInvite(json(port = "0")) as InviteParse.Invalid).reason)
        assertEquals("bad_port", (parseInvite(json(port = "65536")) as InviteParse.Invalid).reason)
    }

    @Test
    fun rejectsMalformedIdentityFields() {
        assertEquals("bad_host_id", (parseInvite(json(id = "not-a-uuid")) as InviteParse.Invalid).reason)
        assertEquals(
            "bad_fingerprint",
            (parseInvite(json(cert = "abc")) as InviteParse.Invalid).reason,
        )
        assertEquals("bad_code", (parseInvite(json(invite = "ff")) as InviteParse.Invalid).reason)
    }

    @Test
    fun rejectsLinksAndNonJson() {
        assertEquals("bad_json", (parseInvite("https://192.168.1.20:18443") as InviteParse.Invalid).reason)
        assertEquals("bad_json", (parseInvite("1482a7c6") as InviteParse.Invalid).reason)
    }

    @Test
    fun rejectsOversizedInput() {
        val big = "{\"v\":1,\"hostId\":\"" + "x".repeat(2000) + "\"}"
        assertEquals("too_large", (parseInvite(big) as InviteParse.Invalid).reason)
    }

    @Test
    fun normalizesGroupedManualInput() {
        assertEquals("aabbcc", normalizeHex("AA BB:CC"))
        assertEquals(null, normalizeHex("   "))
    }

    @Test
    fun privateRangesFollowRfc1918() {
        assertTrue(isPrivateIpv4("10.1.2.3"))
        assertTrue(isPrivateIpv4("172.16.0.1"))
        assertTrue(isPrivateIpv4("172.31.255.255"))
        assertTrue(isPrivateIpv4("192.168.0.1"))
        assertFalse(isPrivateIpv4("172.15.0.1"))
        assertFalse(isPrivateIpv4("172.32.0.1"))
        assertFalse(isPrivateIpv4("192.169.0.1"))
        assertFalse(isPrivateIpv4("127.0.0.1"))
        assertFalse(isPrivateIpv4("192.168.01.1"))
        assertFalse(isPrivateIpv4("192.168.1"))
    }
}
