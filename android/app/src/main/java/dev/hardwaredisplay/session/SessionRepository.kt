package dev.hardwaredisplay.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import dev.hardwaredisplay.data.AppDatabase
import dev.hardwaredisplay.data.CardEntity
import dev.hardwaredisplay.data.CardRole
import dev.hardwaredisplay.data.CardView
import dev.hardwaredisplay.data.HostEntity
import dev.hardwaredisplay.data.PresetEntity
import dev.hardwaredisplay.data.SessionRecord
import dev.hardwaredisplay.data.SettingsStore
import dev.hardwaredisplay.data.TokenStore
import dev.hardwaredisplay.net.Bearer
import dev.hardwaredisplay.net.HostTransport
import dev.hardwaredisplay.net.SessionAnswer
import dev.hardwaredisplay.protocol.Frame
import dev.hardwaredisplay.protocol.FrameParse
import dev.hardwaredisplay.protocol.Invite
import dev.hardwaredisplay.protocol.PROTOCOL_SUB_PROTOCOL
import dev.hardwaredisplay.protocol.PROTOCOL_VERSION
import dev.hardwaredisplay.protocol.parseFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

sealed interface PairOutcome {
    data class Paired(val hostId: String) : PairOutcome
    data object InvalidInvite : PairOutcome
    data object CodeConflict : PairOutcome
    data object RateLimited : PairOutcome
    data class TrustMismatch(val reason: String) : PairOutcome
    data class Unreachable(val message: String) : PairOutcome
    data class Pending(val hostId: String) : PairOutcome
}

private sealed interface WsEvent {
    data class Open(val protocolAccepted: Boolean) : WsEvent
    data class Text(val body: String) : WsEvent
    data class Closed(val code: Int, val reason: String) : WsEvent
    data class Failure(val error: Throwable) : WsEvent
}

private sealed interface PumpOutcome {
    data class Retry(val reason: String, val reachedLive: Boolean) : PumpOutcome
    data class Fatal(val state: ConnectionState) : PumpOutcome
    data object Stopped : PumpOutcome
}

class SessionRepository(
    context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val tokens: TokenStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val history = HistoryRecorder(db)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.NoHost)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _catalog = MutableStateFlow<CatalogModel?>(null)
    val catalog: StateFlow<CatalogModel?> = _catalog.asStateFlow()

    private val _telemetry = MutableStateFlow<TelemetryView?>(null)
    val telemetry: StateFlow<TelemetryView?> = _telemetry.asStateFlow()

    val hosts: Flow<List<HostEntity>> = db.hosts().observeAll()

    private var activeHost: HostEntity? = null
    private var socket: WebSocket? = null
    private var loopJob: Job? = null
    private var stopped = true
    private var trackedSensors: Set<String> = emptySet()

    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!stopped) {
                reconnectNow()
            }
        }
    }

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
    }

    fun currentHostId(): String? = activeHost?.hostId

    suspend fun start() {
        val hostId = settings.activeHostIdOnce() ?: run {
            _connection.value = ConnectionState.NoHost
            return
        }
        val entity = db.hosts().get(hostId)
        if (entity == null) {
            settings.setActiveHost(null)
            _connection.value = ConnectionState.NoHost
            return
        }
        openSession(entity)
    }

    suspend fun selectHost(hostId: String) {
        settings.setActiveHost(hostId)
        start()
    }

    fun pause() {
        stopped = true
        loopJob?.cancel()
        loopJob = null
        socket?.close(1000, "app_background")
        socket = null
        _connection.value = ConnectionState.Stopped
        scope.launch { history.flush() }
    }

    fun resume() {
        if (stopped) {
            scope.launch { start() }
        }
    }

    fun reconnectNow() {
        val host = activeHost ?: return
        scope.launch { openSession(host) }
    }

    private suspend fun openSession(host: HostEntity) {
        activeHost = host
        _catalog.value = settings.cachedCatalog(host.hostId)?.let { cached ->
            runCatching {
                val frame = kotlinx.serialization.json.Json.decodeFromString(
                    dev.hardwaredisplay.protocol.CatalogFrame.serializer(),
                    cached,
                )
                CatalogModel.from(frame)
            }.getOrNull()
        }
        _telemetry.value = null
        refreshTrackedSensors()
        launchLoop(host)
    }

    private fun launchLoop(host: HostEntity) {
        loopJob?.cancel()
        stopped = false
        loopJob = scope.launch {
            var attempt = 0
            while (isActive && !stopped) {
                val record = tokens.read()[host.hostId]
                if (record == null) {
                    _connection.value = ConnectionState.AccessDenied
                    return@launch
                }

                _connection.value = ConnectionState.Connecting(attempt)
                val invite = Invite(
                    hostId = host.hostId,
                    ip = host.ip,
                    port = host.port,
                    certSha256 = host.certSha256,
                    invite = record.pendingInvite.orEmpty(),
                )

                val transport = HostTransport(invite)
                val events = Channel<WsEvent>(Channel.UNLIMITED)
                socket = transport.openTelemetry(record.token, listener(events))

                val outcome = pump(events)
                socket = null
                transport.close()
                events.close()
                history.flush()

                when (outcome) {
                    is PumpOutcome.Fatal -> {
                        _connection.value = outcome.state
                        if (outcome.state is ConnectionState.AccessDenied) {
                            tokens.remove(host.hostId)
                        }
                        return@launch
                    }

                    is PumpOutcome.Stopped -> return@launch

                    is PumpOutcome.Retry -> {
                        attempt = if (outcome.reachedLive) 1 else attempt + 1
                        _connection.value = ConnectionState.Lost(outcome.reason)
                        delay(backoffMillis(attempt, Random.Default))
                    }
                }
            }
        }
    }

    private suspend fun pump(events: Channel<WsEvent>): PumpOutcome {
        val host = activeHost ?: return PumpOutcome.Fatal(ConnectionState.NoHost)
        var epoch: String? = null
        var samplePeriodMs = 1000
        var catalogRevision = -1L
        var reachedLive = false
        var lastFrameElapsed = SystemClock.elapsedRealtime()

        while (true) {
            val event = withTimeoutOrNull(WATCHDOG_TICK_MS) { events.receiveCatching().getOrNull() }

            if (event == null) {
                if (stopped) return PumpOutcome.Stopped
                val silentMs = SystemClock.elapsedRealtime() - lastFrameElapsed
                if (epoch == null && silentMs > HELLO_TIMEOUT_MS) {
                    return PumpOutcome.Retry("hello_timeout", reachedLive)
                }
                if (epoch != null && silentMs > STALE_AFTER_MS && _connection.value is ConnectionState.Live) {
                    _connection.value = ConnectionState.Lost("no_samples")
                }
                continue
            }

            when (event) {
                is WsEvent.Open -> {
                    if (!event.protocolAccepted) {
                        return PumpOutcome.Fatal(ConnectionState.Incompatible)
                    }
                    lastFrameElapsed = SystemClock.elapsedRealtime()
                }

                is WsEvent.Text -> {
                    val parsed = parseFrame(event.body)
                    if (parsed is FrameParse.Rejected) {
                        return PumpOutcome.Retry("frame_" + parsed.reason, reachedLive)
                    }

                    when (val frame = (parsed as FrameParse.Ok).frame) {
                        is Frame.Hello -> {
                            if (frame.body.v != PROTOCOL_VERSION) {
                                return PumpOutcome.Fatal(ConnectionState.Incompatible)
                            }
                            epoch = frame.body.streamEpoch
                            samplePeriodMs = frame.body.samplePeriodMs.coerceAtLeast(1)
                            reachedLive = true
                            _connection.value = ConnectionState.Live
                            lastFrameElapsed = SystemClock.elapsedRealtime()
                        }

                        is Frame.Catalog -> {
                            if (epoch == null) {
                                return PumpOutcome.Retry("catalog_before_hello", reachedLive)
                            }
                            catalogRevision = frame.body.revision
                            _catalog.value = CatalogModel.from(frame.body)
                            settings.saveCatalog(
                                host.hostId,
                                kotlinx.serialization.json.Json.encodeToString(
                                    dev.hardwaredisplay.protocol.CatalogFrame.serializer(),
                                    frame.body,
                                ),
                            )
                            ensurePresets(host, _catalog.value!!)
                            lastFrameElapsed = SystemClock.elapsedRealtime()
                        }

                        is Frame.Snapshot -> {
                            val snapshotEpoch = epoch
                            if (snapshotEpoch == null || frame.body.streamEpoch != snapshotEpoch) {
                                return PumpOutcome.Retry("epoch_mismatch", reachedLive)
                            }
                            if (frame.body.revision != catalogRevision) {
                                return PumpOutcome.Retry("snapshot_without_catalog", reachedLive)
                            }

                            val view = TelemetryView.from(
                                hostId = host.hostId,
                                frame = frame.body,
                                receivedAtUptimeMs = SystemClock.elapsedRealtime(),
                                capturedAtUtcMs = parseUtcMillis(frame.body.capturedAtUtc),
                            )
                            _telemetry.value = view
                            _connection.value = ConnectionState.Live
                            lastFrameElapsed = SystemClock.elapsedRealtime()
                            history.record(host.hostId, view, trackedSensors)
                        }
                    }
                }

                is WsEvent.Closed -> {
                    return when {
                        event.code == 1008 && event.reason == "revoked" ->
                            PumpOutcome.Fatal(ConnectionState.AccessDenied)

                        else -> PumpOutcome.Retry("closed_" + event.code, reachedLive)
                    }
                }

                is WsEvent.Failure -> {
                    val answer = classifyFailure(event.error)
                    return when (answer) {
                        is SessionAnswer.TrustFailure ->
                            PumpOutcome.Fatal(ConnectionState.TrustFailed(answer.reason))

                        else -> PumpOutcome.Retry("failed", reachedLive)
                    }
                }
            }
        }
    }

    suspend fun pair(invite: Invite, clientName: String): PairOutcome = withContext(Dispatchers.IO) {
        val token = Bearer.create()
        tokens.put(SessionRecord(hostId = invite.hostId, token = token, pendingInvite = invite.invite))
        val transport = HostTransport(invite)

        try {
            when (val answer = transport.pair(token, clientName)) {
                is SessionAnswer.Ok -> {
                    completePairing(invite, token, answer.clientId)
                    PairOutcome.Paired(invite.hostId)
                }

                is SessionAnswer.Rejected -> when (answer.status) {
                    403 -> {
                        tokens.remove(invite.hostId)
                        PairOutcome.InvalidInvite
                    }

                    409 -> {
                        tokens.remove(invite.hostId)
                        PairOutcome.CodeConflict
                    }

                    429 -> {
                        tokens.remove(invite.hostId)
                        PairOutcome.RateLimited
                    }

                    else -> PairOutcome.Unreachable("http_" + answer.status)
                }

                is SessionAnswer.TrustFailure -> {
                    tokens.remove(invite.hostId)
                    PairOutcome.TrustMismatch(answer.reason)
                }

                is SessionAnswer.Unreachable -> confirmWithinWindow(invite, token, transport)
            }
        } finally {
            transport.close()
        }
    }

    /**
     * Ответ `201` мог потеряться: решение принимает только `GET /v1/session` с ожидаемым hostId.
     * Pending сохраняется, если окно истекло, и проверяется при ручном действии.
     */
    private suspend fun confirmWithinWindow(
        invite: Invite,
        token: String,
        transport: HostTransport,
    ): PairOutcome {
        val deadline = SystemClock.elapsedRealtime() + CONFIRM_WINDOW_MS
        var lastTrustReason: String? = null

        while (SystemClock.elapsedRealtime() < deadline) {
            when (val answer = transport.checkSession(token)) {
                is SessionAnswer.Ok -> {
                    if (answer.hostId != invite.hostId) {
                        tokens.remove(invite.hostId)
                        return PairOutcome.Unreachable("host_mismatch")
                    }
                    completePairing(invite, token, answer.clientId)
                    return PairOutcome.Paired(invite.hostId)
                }

                is SessionAnswer.Rejected -> if (answer.status != 401) {
                    break
                }

                is SessionAnswer.TrustFailure -> {
                    lastTrustReason = answer.reason
                    break
                }

                is SessionAnswer.Unreachable -> Unit
            }
            delay(750)
        }

        if (lastTrustReason != null) {
            tokens.remove(invite.hostId)
            return PairOutcome.TrustMismatch(lastTrustReason)
        }

        saveHost(invite, clientId = "")
        return PairOutcome.Pending(invite.hostId)
    }

    suspend fun confirmPending(hostId: String): PairOutcome = withContext(Dispatchers.IO) {
        val record = tokens.read()[hostId] ?: return@withContext PairOutcome.InvalidInvite
        val host = db.hosts().get(hostId) ?: return@withContext PairOutcome.InvalidInvite
        val invite = Invite(
            hostId = host.hostId,
            ip = host.ip,
            port = host.port,
            certSha256 = host.certSha256,
            invite = record.pendingInvite.orEmpty(),
        )
        val transport = HostTransport(invite)
        try {
            when (val answer = transport.checkSession(record.token)) {
                is SessionAnswer.Ok -> {
                    completePairing(invite, record.token, answer.clientId)
                    PairOutcome.Paired(hostId)
                }

                is SessionAnswer.Rejected -> if (answer.status == 401) {
                    tokens.remove(hostId)
                    PairOutcome.InvalidInvite
                } else {
                    PairOutcome.Unreachable("http_" + answer.status)
                }

                is SessionAnswer.TrustFailure -> PairOutcome.TrustMismatch(answer.reason)
                is SessionAnswer.Unreachable -> PairOutcome.Unreachable(answer.message)
            }
        } finally {
            transport.close()
        }
    }

    private suspend fun completePairing(invite: Invite, token: String, clientId: String) {
        tokens.put(
            SessionRecord(
                hostId = invite.hostId,
                token = token,
                clientId = clientId,
                pendingInvite = null,
            )
        )
        saveHost(invite, clientId, label = null)
        settings.setActiveHost(invite.hostId)
        db.hosts().get(invite.hostId)?.let { start() }
    }

    private suspend fun saveHost(invite: Invite, clientId: String, label: String? = null) {
        val existing = db.hosts().get(invite.hostId)
        db.hosts().upsert(
            HostEntity(
                hostId = invite.hostId,
                label = label ?: existing?.label ?: defaultLabel(invite),
                ip = invite.ip,
                port = invite.port,
                certSha256 = invite.certSha256,
                clientId = clientId,
                lastSeenUtc = System.currentTimeMillis(),
            )
        )
    }

    private fun defaultLabel(invite: Invite): String = "ПК " + invite.ip

    suspend fun renameHost(hostId: String, label: String) {
        val host = db.hosts().get(hostId) ?: return
        db.hosts().upsert(host.copy(label = label))
        if (activeHost?.hostId == hostId) {
            activeHost = host.copy(label = label)
        }
    }

    suspend fun removeHost(hostId: String) {
        if (activeHost?.hostId == hostId) {
            pause()
            activeHost = null
        }
        tokens.remove(hostId)
        db.history().deleteForHost(hostId)
        db.hosts().delete(hostId)
        settings.clearCatalog(hostId)
        if (settings.activeHostIdOnce() == hostId) {
            settings.setActiveHost(null)
            _connection.value = ConnectionState.NoHost
        }
    }

    suspend fun refreshTrackedSensors() {
        val hostId = activeHost?.hostId ?: return
        trackedSensors = db.presets().allCardsForHost(hostId)
            .map { it.sensorId }
            .distinct()
            .take(HistoryRecorder.MAX_TRACKED_SENSORS)
            .toSet()
    }

    private suspend fun ensurePresets(host: HostEntity, catalog: CatalogModel) {
        if (db.presets().presets(host.hostId).isNotEmpty()) {
            return
        }

        val specs = buildDefaultPresets(catalog)
        if (specs.isEmpty()) {
            return
        }

        specs.forEachIndexed { index, spec ->
            val presetId = UUID.randomUUID().toString()
            db.presets().insertPreset(
                PresetEntity(
                    presetId = presetId,
                    hostId = host.hostId,
                    name = spec.name,
                    position = index,
                )
            )
            db.presets().insertCards(
                buildCards(presetId, host.hostId, spec, catalog)
            )
        }

        refreshTrackedSensors()
    }

    private fun buildCards(
        presetId: String,
        hostId: String,
        spec: PresetSpec,
        catalog: CatalogModel,
    ): List<CardEntity> {
        val cards = mutableListOf<CardEntity>()

        fun card(sensorId: String, position: Int, role: CardRole): CardEntity? {
            val sensor = catalog.sensors[sensorId] ?: return null
            return CardEntity(
                cardId = UUID.randomUUID().toString(),
                presetId = presetId,
                position = position,
                role = role.name,
                view = CardView.NUMBER.name,
                hostId = hostId,
                sensorId = sensor.sensorId,
                deviceKind = catalog.devices[sensor.deviceId]?.kind.orEmpty(),
                metricKey = sensor.metricKey,
            )
        }

        card(spec.primarySensorId, 0, CardRole.PRIMARY)?.let { cards += it }
        spec.secondarySensorIds.forEachIndexed { index, sensorId ->
            card(sensorId, index + 1, CardRole.SECONDARY)?.let { cards += it }
        }
        spec.compareSensorIds.forEachIndexed { index, sensorId ->
            card(sensorId, 100 + index, CardRole.COMPARE)?.let { cards += it }
        }

        return cards
    }

    private fun listener(events: Channel<WsEvent>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val protocol = response.header("Sec-WebSocket-Protocol")
            events.trySend(WsEvent.Open(protocol == PROTOCOL_SUB_PROTOCOL))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            events.trySend(WsEvent.Text(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            events.trySend(WsEvent.Closed(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.trySend(WsEvent.Closed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            events.trySend(WsEvent.Failure(t))
        }
    }

    private fun classifyFailure(error: Throwable): SessionAnswer {
        var current: Throwable? = error
        while (current != null) {
            val cause = current
            if (cause is javax.net.ssl.SSLHandshakeException || cause is javax.net.ssl.SSLPeerUnverifiedException) {
                return SessionAnswer.TrustFailure(cause.message.orEmpty())
            }
            current = cause.cause
        }
        return SessionAnswer.Unreachable(error.message.orEmpty())
    }

    private fun parseUtcMillis(text: String): Long {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        val parsed = format.parse(text, position)
        return parsed?.time ?: System.currentTimeMillis()
    }

    companion object {
        private const val WATCHDOG_TICK_MS = 1_000L
        private const val HELLO_TIMEOUT_MS = 5_000L
        private const val STALE_AFTER_MS = 5_000L
        private const val CONFIRM_WINDOW_MS = 15_000L

        fun backoffMillis(attempt: Int, random: Random): Long {
            val exponent = (attempt - 1).coerceIn(0, 5)
            val base = minOf(30_000L, 1_000L shl exponent)
            val jitter = (base * 0.2 * (random.nextDouble() * 2 - 1)).toLong()
            return (base + jitter).coerceAtLeast(500L)
        }
    }
}
