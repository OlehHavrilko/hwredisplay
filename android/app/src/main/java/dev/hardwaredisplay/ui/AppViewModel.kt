package dev.hardwaredisplay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hardwaredisplay.HardwareDisplayApp
import dev.hardwaredisplay.data.CardEntity
import dev.hardwaredisplay.data.CardRole
import dev.hardwaredisplay.data.CardView
import dev.hardwaredisplay.data.HistoryPointEntity
import dev.hardwaredisplay.data.HostEntity
import dev.hardwaredisplay.data.PresetEntity
import dev.hardwaredisplay.protocol.Invite
import dev.hardwaredisplay.protocol.InviteParse
import dev.hardwaredisplay.protocol.SensorDto
import dev.hardwaredisplay.session.CatalogModel
import dev.hardwaredisplay.session.ConnectionState
import dev.hardwaredisplay.session.PairOutcome
import dev.hardwaredisplay.session.SessionRepository
import dev.hardwaredisplay.session.TelemetryView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface Screen {
    data object Connect : Screen
    data object Scanner : Screen
    data object Manual : Screen
    data object Panel : Screen
    data object Compare : Screen
    data object Editor : Screen
}

sealed interface PairUiState {
    data object Idle : PairUiState
    data object Working : PairUiState
    data class Message(val text: String) : PairUiState
}

data class CardDraft(
    val cardId: String,
    val role: CardRole,
    val view: CardView,
    val sensorId: String,
    val deviceKind: String,
    val metricKey: String,
    val hostId: String,
)

data class PresetDraft(
    val presetId: String?,
    val name: String,
    val cards: List<CardDraft>,
) {
    val primary: CardDraft? get() = cards.firstOrNull { it.role == CardRole.PRIMARY }
    val secondary: List<CardDraft> get() = cards.filter { it.role == CardRole.SECONDARY }
    val compare: List<CardDraft> get() = cards.filter { it.role == CardRole.COMPARE }
}

data class CompareRow(
    val sensorId: String,
    val title: String,
    val unit: String,
    val colorIndex: Int,
    val points: List<HistoryPointEntity>,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HardwareDisplayApp
    private val repository: SessionRepository = app.repository
    private val database = app.database
    private val settings = app.settings

    val connection: StateFlow<ConnectionState> = repository.connection
    val catalog: StateFlow<CatalogModel?> = repository.catalog
    val telemetry: StateFlow<TelemetryView?> = repository.telemetry

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Connect))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    private val _pairState = MutableStateFlow<PairUiState>(PairUiState.Idle)
    val pairState: StateFlow<PairUiState> = _pairState.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _activePresetId = MutableStateFlow<String?>(null)
    val activePresetId: StateFlow<String?> = _activePresetId.asStateFlow()

    private val _draft = MutableStateFlow<PresetDraft?>(null)
    val draft: StateFlow<PresetDraft?> = _draft.asStateFlow()

    private val _compareWindowMs = MutableStateFlow(15 * 60_000L)
    val compareWindowMs: StateFlow<Long> = _compareWindowMs.asStateFlow()

    val hosts: StateFlow<List<HostEntity>> = repository.hosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeHostId: StateFlow<String?> = settings.activeHostId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val keepScreenOn: StateFlow<Boolean> = settings.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleKeepScreenOn() {
        viewModelScope.launch { settings.setKeepScreenOn(!keepScreenOn.value) }
    }

    val activeHost: StateFlow<HostEntity?> = activeHostId
        .filterNotNull()
        .flatMapLatest { id -> database.hosts().observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val presets: StateFlow<List<PresetEntity>> = activeHostId
        .filterNotNull()
        .flatMapLatest { id -> database.presets().observePresets(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cards: StateFlow<List<CardEntity>> = _activePresetId
        .filterNotNull()
        .flatMapLatest { presetId -> database.presets().observeCards(presetId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val hostId = settings.activeHostId.first()
            if (hostId == null) {
                setStack(listOf(Screen.Connect))
            } else {
                setStack(listOf(Screen.Panel))
                repository.start()
            }
        }

        viewModelScope.launch {
            presets.collect { list ->
                val hostId = activeHostId.value ?: return@collect
                if (list.isEmpty()) {
                    _activePresetId.value = null
                    return@collect
                }
                val stored = settings.activePresetId(hostId)
                val chosen = list.firstOrNull { it.presetId == stored } ?: list.first()
                if (_activePresetId.value != chosen.presetId) {
                    _activePresetId.value = chosen.presetId
                }
                if (stored != chosen.presetId) {
                    settings.setActivePreset(hostId, chosen.presetId)
                }
            }
        }
    }

    override fun onCleared() {
        repository.pause()
        super.onCleared()
    }

    fun onResume() = repository.resume()

    fun onPause() = repository.pause()

    fun navigate(screen: Screen) {
        if (_stack.value.lastOrNull() == screen) return
        setStack(_stack.value + screen)
    }

    fun back(): Boolean {
        val current = _stack.value
        if (current.size <= 1) {
            if (current.lastOrNull() == Screen.Panel && activeHostId.value != null) {
                return false
            }
            return false
        }
        setStack(current.dropLast(1))
        return true
    }

    private fun setStack(value: List<Screen>) {
        _stack.value = if (value.isEmpty()) listOf(Screen.Connect) else value
    }

    fun selectPreset(presetId: String) {
        val hostId = activeHostId.value ?: return
        _activePresetId.value = presetId
        viewModelScope.launch { settings.setActivePreset(hostId, presetId) }
    }

    fun reconnect() = repository.reconnectNow()

    fun clearNotice() {
        _notice.value = null
    }

    fun setCompareWindow(millis: Long) {
        _compareWindowMs.value = millis
    }

    fun inviteCommand(invite: Invite) {
        viewModelScope.launch {
            _pairState.value = PairUiState.Working
            _pairState.value = when (val outcome = repository.pair(invite, clientName())) {
                is PairOutcome.Paired -> {
                    setStack(listOf(Screen.Panel))
                    PairUiState.Idle
                }

                is PairOutcome.Pending -> {
                    setStack(listOf(Screen.Connect))
                    PairUiState.Message("ПК не подтвердил сопряжение. Проверьте приглашение и повторите.")
                }

                is PairOutcome.InvalidInvite -> PairUiState.Message("Приглашение недействительно или истекло. Создайте новое на ПК.")
                is PairOutcome.CodeConflict -> PairUiState.Message("Код уже использован. Создайте новое приглашение на ПК.")
                is PairOutcome.RateLimited -> PairUiState.Message("ПК отклонил подключение: превышен лимит телефонов.")
                is PairOutcome.TrustMismatch -> PairUiState.Message("Не удалось подтвердить ПК: сертификат не совпал с отпечатком.")
                is PairOutcome.Unreachable -> PairUiState.Message("ПК недоступен по указанному адресу. Проверьте сеть и адрес.")
            }
        }
    }

    fun manualEntry(fields: dev.hardwaredisplay.protocol.ManualEntryFields) {
        when (val parsed = dev.hardwaredisplay.protocol.parseManualEntry(fields)) {
            is InviteParse.Valid -> inviteCommand(parsed.invite)
            is InviteParse.Invalid -> _pairState.value = PairUiState.Message(manualReason(parsed.reason))
        }
    }

    fun manualJson(raw: String) {
        when (val parsed = dev.hardwaredisplay.protocol.parseInvite(raw)) {
            is InviteParse.Valid -> inviteCommand(parsed.invite)
            is InviteParse.Invalid -> _pairState.value = PairUiState.Message(manualReason(parsed.reason))
        }
    }

    fun resetPairState() {
        _pairState.value = PairUiState.Idle
    }

    fun selectHost(hostId: String) {
        viewModelScope.launch {
            repository.selectHost(hostId)
            setStack(listOf(Screen.Panel))
        }
    }

    fun confirmPending(hostId: String) {
        viewModelScope.launch {
            _pairState.value = PairUiState.Working
            when (val outcome = repository.confirmPending(hostId)) {
                is PairOutcome.Paired -> {
                    setStack(listOf(Screen.Panel))
                    _pairState.value = PairUiState.Idle
                }

                is PairOutcome.Unreachable -> _pairState.value = PairUiState.Message("ПК пока недоступен. Повторите позже.")
                else -> _pairState.value = PairUiState.Message("Сопряжение не подтверждено. Получите новое приглашение на ПК.")
            }
        }
    }

    fun renameHost(hostId: String, label: String) {
        viewModelScope.launch { repository.renameHost(hostId, label) }
    }

    fun removeHost(hostId: String) {
        viewModelScope.launch {
            repository.removeHost(hostId)
            if (settings.activeHostId.first() == null) {
                setStack(listOf(Screen.Connect))
            }
        }
    }

    fun startEdit(preset: PresetEntity?) {
        val hostId = activeHostId.value ?: return
        viewModelScope.launch {
            val cards = if (preset == null) emptyList() else database.presets().cards(preset.presetId)
            _draft.value = PresetDraft(
                presetId = preset?.presetId,
                name = preset?.name ?: "Новый пресет",
                cards = cards.map { it.toDraft() },
            )
        }
    }

    fun draftRename(name: String) = updateDraft { it.copy(name = name) }

    fun draftMove(cardId: String, delta: Int) = updateDraft { current ->
        val index = current.cards.indexOfFirst { it.cardId == cardId }
        if (index < 0) return@updateDraft current
        val target = index + delta
        if (target !in current.cards.indices) return@updateDraft current
        val list = current.cards.toMutableList()
        val item = list.removeAt(index)
        list.add(target, item)
        current.copy(cards = list)
    }

    fun draftCycleView(cardId: String) = updateDraft { current ->
        current.copy(cards = current.cards.map { card ->
            if (card.cardId != cardId) card
            else card.copy(view = if (card.view == CardView.NUMBER) CardView.LINE else CardView.NUMBER)
        })
    }

    fun draftReplaceSensor(cardId: String, sensor: SensorDto) = updateDraft { current ->
        val kind = catalog.value?.devices?.get(sensor.deviceId)?.kind.orEmpty()
        current.copy(cards = current.cards.map { card ->
            if (card.cardId != cardId) card
            else card.copy(sensorId = sensor.sensorId, deviceKind = kind, metricKey = sensor.metricKey)
        })
    }

    fun draftSetPrimary(cardId: String) = updateDraft { current ->
        val target = current.cards.firstOrNull { it.cardId == cardId } ?: return@updateDraft current
        val previousPrimary = current.cards.firstOrNull { it.role == CardRole.PRIMARY }
        val reassigned = current.cards.map { card ->
            when {
                card.cardId == cardId -> card.copy(role = CardRole.PRIMARY)
                previousPrimary != null && card.cardId == previousPrimary.cardId ->
                    card.copy(role = CardRole.SECONDARY)
                else -> card
            }
        }
        val ordered = listOfNotNull(
            reassigned.firstOrNull { it.cardId == target.cardId },
        ) + reassigned.filter { it.cardId != target.cardId }
        current.copy(cards = ordered)
    }

    fun draftAddSensor(sensor: SensorDto) = updateDraft { current ->
        if (current.cards.any { it.sensorId == sensor.sensorId && it.role != CardRole.COMPARE }) {
            return@updateDraft current
        }
        val kind = catalog.value?.devices?.get(sensor.deviceId)?.kind.orEmpty()
        val hostId = activeHostId.value.orEmpty()
        val card = CardDraft(
            cardId = UUID.randomUUID().toString(),
            role = if (current.primary == null) CardRole.PRIMARY else CardRole.SECONDARY,
            view = CardView.NUMBER,
            sensorId = sensor.sensorId,
            deviceKind = kind,
            metricKey = sensor.metricKey,
            hostId = hostId,
        )
        current.copy(cards = current.cards + card)
    }

    fun draftRemove(cardId: String) = updateDraft { current ->
        val filtered = current.cards.filter { it.cardId != cardId }
        val promoted = if (filtered.none { it.role == CardRole.PRIMARY }) {
            val first = filtered.firstOrNull { it.role == CardRole.SECONDARY } ?: filtered.firstOrNull()
            if (first == null) filtered
            else filtered.map { if (it.cardId == first.cardId) it.copy(role = CardRole.PRIMARY) else it }
        } else {
            filtered
        }
        current.copy(cards = promoted)
    }

    fun draftToggleCompare(cardId: String) = updateDraft { current ->
        val card = current.cards.firstOrNull { it.cardId == cardId } ?: return@updateDraft current
        when (card.role) {
            CardRole.COMPARE -> current.copy(
                cards = current.cards.map {
                    if (it.cardId == cardId) it.copy(role = CardRole.SECONDARY) else it
                }
            )

            else -> {
                val compareCount = current.cards.count { it.role == CardRole.COMPARE }
                if (compareCount >= MAX_COMPARE) current
                else current.copy(
                    cards = current.cards.map {
                        if (it.cardId == cardId) it.copy(role = CardRole.COMPARE) else it
                    }
                )
            }
        }
    }

    fun discardDraft() {
        _draft.value = null
    }

    fun saveDraft(copy: Boolean = false) {
        val current = _draft.value ?: return
        val hostId = activeHostId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val presetId = current.presetId
            when {
                presetId == null || copy -> {
                    val newId = UUID.randomUUID().toString()
                    val position = database.presets().presets(hostId).size
                    database.presets().insertPreset(
                        PresetEntity(
                            presetId = newId,
                            hostId = hostId,
                            name = if (copy) current.name + " (копия)" else current.name,
                            position = position,
                        )
                    )
                    database.presets().insertCards(current.cards.mapIndexed { index, card -> card.toEntity(newId, index) })
                    _activePresetId.value = newId
                    settings.setActivePreset(hostId, newId)
                }

                else -> {
                    database.presets().rename(presetId, current.name)
                    database.presets().replaceCards(presetId, current.cards.mapIndexed { index, card -> card.toEntity(presetId, index) })
                    _activePresetId.value = presetId
                }
            }
            repository.refreshTrackedSensors()
            _draft.value = null
            _stack.value = listOf(Screen.Panel)
        }
    }

    fun deletePreset(preset: PresetEntity) {
        val hostId = activeHostId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            database.presets().deletePreset(preset.presetId)
            repository.refreshTrackedSensors()
            val remaining = database.presets().presets(hostId)
            val next = remaining.firstOrNull()
            _activePresetId.value = next?.presetId
            if (next != null) {
                settings.setActivePreset(hostId, next.presetId)
            }
        }
    }

    suspend fun historyFor(sensorId: String, windowMs: Long): List<HistoryPointEntity> {
        val hostId = activeHostId.value ?: return emptyList()
        return database.history().range(hostId, sensorId, System.currentTimeMillis() - windowMs)
    }

    fun sensorsForSelection(): List<SensorDto> {
        val model = catalog.value ?: return emptyList()
        return model.sensors.values.sortedWith(compareBy({ it.deviceId }, { it.metricKey }))
    }

    private fun updateDraft(transform: (PresetDraft) -> PresetDraft) {
        val current = _draft.value ?: return
        _draft.value = transform(current)
    }

    private fun CardEntity.toDraft() = CardDraft(
        cardId = cardId,
        role = runCatching { CardRole.valueOf(role) }.getOrDefault(CardRole.SECONDARY),
        view = runCatching { CardView.valueOf(view) }.getOrDefault(CardView.NUMBER),
        sensorId = sensorId,
        deviceKind = deviceKind,
        metricKey = metricKey,
        hostId = hostId,
    )

    private fun CardDraft.toEntity(presetId: String, position: Int) = CardEntity(
        cardId = if (cardId.isEmpty()) UUID.randomUUID().toString() else cardId,
        presetId = presetId,
        position = position,
        role = role.name,
        view = view.name,
        hostId = hostId.ifEmpty { activeHostId.value.orEmpty() },
        sensorId = sensorId,
        deviceKind = deviceKind,
        metricKey = metricKey,
    )

    private fun manualReason(reason: String): String = when (reason) {
        "too_large" -> "Слишком длинные данные приглашения."
        "bad_json" -> "Не удалось разобрать данные приглашения."
        "unsupported_version" -> "Версия приглашения не поддерживается приложением."
        "bad_host_id" -> "Неверное имя ПК или идентификатор."
        "bad_ip" -> "Адрес должен быть локальным IPv4, например 192.168.1.20."
        "bad_port" -> "Порт должен быть числом от 1 до 65535."
        "bad_fingerprint" -> "Отпечаток сертификата должен содержать 64 шестнадцатеричных символа."
        "bad_code" -> "Код приглашения должен содержать 32 шестнадцатеричных символа."
        else -> "Проверьте данные приглашения."
    }

    private fun clientName(): String = "Android " + android.os.Build.MODEL

    companion object {
        const val MAX_COMPARE = 2
    }
}
