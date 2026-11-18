package dev.hardwaredisplay.ui

import dev.hardwaredisplay.protocol.SensorDto
import dev.hardwaredisplay.protocol.SensorStatus
import dev.hardwaredisplay.session.ConnectionState
import dev.hardwaredisplay.session.Reading
import dev.hardwaredisplay.ui.format.MetricFormat

enum class MetricTone { FRESH, WAITING, MISSING, STALE, OFFLINE }

data class MetricUi(
    val title: String,
    val subtitle: String?,
    val value: String?,
    val unit: String?,
    val note: String?,
    val tone: MetricTone,
) {
    val hasValue: Boolean get() = value != null
}

/**
 * Один и тот же набор правил для панели, редактора и истории: ноль показывается как ноль,
 * ожидание первого замера, отсутствие датчика и потеря связи выглядят по-разному.
 */
fun buildMetricUi(
    sensor: SensorDto?,
    reading: Reading?,
    deviceLabel: String?,
    connection: ConnectionState,
    nowElapsedMs: Long,
    receivedAtElapsedMs: Long?,
    lastSampleUtcMs: Long?,
): MetricUi {
    val title = sensor?.label ?: "Датчик недоступен"
    val subtitle = deviceLabel

    if (sensor == null) {
        return MetricUi(
            title = title,
            subtitle = subtitle,
            value = null,
            unit = null,
            note = "Выберите другой датчик в редакторе",
            tone = MetricTone.MISSING,
        )
    }

    val linkDown = connection is ConnectionState.Lost ||
        connection is ConnectionState.Failed ||
        connection is ConnectionState.Connecting ||
        connection is ConnectionState.TrustFailed ||
        connection is ConnectionState.AccessDenied

    if (linkDown) {
        val lastValue = reading?.takeIf { it.status == SensorStatus.OK }?.value
        return if (lastValue != null) {
            MetricUi(
                title = title,
                subtitle = subtitle,
                value = MetricFormat.valueText(sensor.unit, lastValue),
                unit = MetricFormat.unitText(sensor.unit, lastValue),
                note = "Последний замер " + (lastSampleUtcMs?.let { MetricFormat.clockTime(it) } ?: "—"),
                tone = MetricTone.OFFLINE,
            )
        } else {
            MetricUi(title, subtitle, null, null, "Связь потеряна", MetricTone.OFFLINE)
        }
    }

    if (reading == null) {
        return MetricUi(title, subtitle, null, null, "Ждём первый замер", MetricTone.WAITING)
    }

    return when (reading.status) {
        SensorStatus.OK -> {
            val value = reading.value
            if (value == null) {
                MetricUi(title, subtitle, null, null, "Значение не получено", MetricTone.MISSING)
            } else {
                val staleByAge = receivedAtElapsedMs != null &&
                    nowElapsedMs - receivedAtElapsedMs > STALE_AFTER_MS
                MetricUi(
                    title = title,
                    subtitle = subtitle,
                    value = MetricFormat.valueText(sensor.unit, value),
                    unit = MetricFormat.unitText(sensor.unit, value),
                    note = if (staleByAge) "Замер задерживается" else null,
                    tone = if (staleByAge) MetricTone.STALE else MetricTone.FRESH,
                )
            }
        }

        SensorStatus.WAITING -> MetricUi(
            title,
            subtitle,
            null,
            null,
            MetricFormat.reasonLabel(reading.reason ?: "warmup"),
            MetricTone.WAITING,
        )

        else -> MetricUi(
            title,
            subtitle,
            null,
            null,
            MetricFormat.reasonLabel(reading.reason),
            MetricTone.MISSING,
        )
    }
}

const val STALE_AFTER_MS = 5_000L

fun connectionLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.NoHost -> "ПК не выбран"
    is ConnectionState.Stopped -> "Остановлено"
    is ConnectionState.Connecting -> "Подключение…"
    is ConnectionState.Live -> "В эфире"
    is ConnectionState.Lost -> when (state.reason) {
        "no_samples" -> "Нет свежих замеров"
        "hello_timeout" -> "ПК не ответил"
        else -> "Связь потеряна"
    }

    is ConnectionState.TrustFailed -> "Не удалось подтвердить ПК"
    is ConnectionState.AccessDenied -> "Доступ отозван"
    is ConnectionState.Incompatible -> "Версия несовместима"
    is ConnectionState.Failed -> "Ошибка подключения"
}
