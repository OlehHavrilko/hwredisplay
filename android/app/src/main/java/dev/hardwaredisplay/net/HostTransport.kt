package dev.hardwaredisplay.net

import dev.hardwaredisplay.protocol.ErrorResponse
import dev.hardwaredisplay.protocol.Invite
import dev.hardwaredisplay.protocol.PROTOCOL_SUB_PROTOCOL
import dev.hardwaredisplay.protocol.PairRequest
import dev.hardwaredisplay.protocol.PairResponse
import dev.hardwaredisplay.protocol.ProtocolJson
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager

/**
 * Числовой IPv4 из приглашения подставляется вместо системного DNS: HTTP Host, SNI и проверка
 * SAN продолжают использовать логическое имя ПК, поэтому адрес можно менять без нового сопряжения.
 */
class PinnedDns(private val ip: String) : Dns {
    private val address: InetAddress = InetAddress.getByName(ip)

    override fun lookup(hostname: String): List<InetAddress> =
        listOf(InetAddress.getByAddress(hostname, address.address))
}

sealed interface SessionAnswer {
    data class Ok(val hostId: String, val clientId: String) : SessionAnswer
    data class Rejected(val status: Int, val error: String?) : SessionAnswer
    data class TrustFailure(val reason: String) : SessionAnswer
    data class Unreachable(val message: String) : SessionAnswer
}

class HostTransport(private val invite: Invite) : Closeable {

    private val client: OkHttpClient = buildClient()

    fun pair(token: String, clientName: String): SessionAnswer {
        val body = ProtocolJson.encodeToString(
            PairRequest.serializer(),
            PairRequest(invite = invite.invite, clientName = clientName),
        )
        val request = authorized("/v1/pair", token)
            .post(body.toRequestBody(JSON))
            .build()
        return execute(request)
    }

    fun checkSession(token: String): SessionAnswer =
        execute(authorized("/v1/session", token).get().build())

    fun openTelemetry(token: String, listener: WebSocketListener): WebSocket {
        val request = authorized("/v1/telemetry", token)
            .header("Sec-WebSocket-Protocol", PROTOCOL_SUB_PROTOCOL)
            .build()
        return client.newWebSocket(request, listener)
    }

    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun authorized(path: String, token: String): Request.Builder =
        Request.Builder()
            .url(invite.baseUrl + path.removePrefix("/"))
            .header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-store")

    private fun execute(request: Request): SessionAnswer {
        return try {
            client.newCall(request).execute().use { response -> readResponse(response) }
        } catch (error: SSLHandshakeException) {
            SessionAnswer.TrustFailure(pinReason(error))
        } catch (error: SSLPeerUnverifiedException) {
            SessionAnswer.TrustFailure("tls")
        } catch (error: IOException) {
            SessionAnswer.Unreachable(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun readResponse(response: Response): SessionAnswer {
        val body = response.body?.string().orEmpty()
        if (response.isSuccessful) {
            return runCatching {
                val parsed = ProtocolJson.decodeFromString(PairResponse.serializer(), body)
                SessionAnswer.Ok(parsed.hostId, parsed.clientId)
            }.getOrElse { SessionAnswer.Rejected(response.code, "bad_json") }
        }

        val error = runCatching {
            ProtocolJson.decodeFromString(ErrorResponse.serializer(), body).error
        }.getOrNull()
        return SessionAnswer.Rejected(response.code, error)
    }

    /**
     * OkHttp заворачивает отказ TrustManager в SSLHandshakeException. Причина `pin_*`
     * сохраняется в цепочке, чтобы UI объяснил несовпадение отпечатка, а не «ошибку сети».
     */
    private fun pinReason(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            val cause = current
            if (cause is CertificateException) {
                val message = cause.message.orEmpty()
                if (message.startsWith("pin_")) {
                    return message.removePrefix("pin_")
                }
            }
            current = cause.cause
        }
        return "tls"
    }

    private fun buildClient(): OkHttpClient {
        val trustManager = PinnedTrustManager(invite.certSha256, invite.canonicalName)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .dns(PinnedDns(invite.ip))
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
