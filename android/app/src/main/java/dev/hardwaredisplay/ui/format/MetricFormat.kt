package dev.hardwaredisplay.ui.format

import dev.hardwaredisplay.protocol.SensorUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RU = Locale.forLanguageTag("ru-RU")

object MetricFormat {

    fun number(value: Double, decimals: Int = 1): String =
        String.format(RU, "%.${decimals}f", value)

    /** Число без единицы: единица показывается отдельно, чтобы карточка читалась за один взгляд. */
    fun valueText(unit: String, value: Double): String = when (unit) {
        SensorUnit.PERCENT -> number(value, 1)
        SensorUnit.BYTES -> bytesSplit(value).first
        SensorUnit.BYTES_PER_SECOND -> bytesSplit(value).first
        SensorUnit.CELSIUS -> number(value, 1)
        SensorUnit.HERTZ -> number(value / 1_000_000_000.0, 2)
        SensorUnit.WATT -> number(value, 1)
        SensorUnit.VOLT -> number(value, 2)
        SensorUnit.RPM -> String.format(RU, "%.0f", value)
        SensorUnit.COUNT -> String.format(RU, "%.0f", value)
        else -> number(value, 1)
    }

    fun unitText(unit: String, value: Double): String = when (unit) {
        SensorUnit.PERCENT -> "%"
        SensorUnit.BYTES -> bytesSplit(value).second
        SensorUnit.BYTES_PER_SECOND -> bytesSplit(value).second + "/с"
        SensorUnit.CELSIUS -> "°C"
        SensorUnit.HERTZ -> "ГГц"
        SensorUnit.WATT -> "Вт"
        SensorUnit.VOLT -> "В"
        SensorUnit.RPM -> "об/мин"
        SensorUnit.COUNT -> "шт"
        else -> unit
    }

    fun withUnit(unit: String, value: Double): String {
        val unitText = unitText(unit, value)
        return if (unitText.isEmpty() || unitText == unit) valueText(unit, value) else "${valueText(unit, value)} $unitText"
    }

    /** Название единицы для списков, где нет конкретного значения. */
    fun unitName(unit: String): String = when (unit) {
        SensorUnit.PERCENT -> "%"
        SensorUnit.BYTES -> "байты"
        SensorUnit.BYTES_PER_SECOND -> "байты/с"
        SensorUnit.CELSIUS -> "°C"
        SensorUnit.HERTZ -> "Гц"
        SensorUnit.WATT -> "Вт"
        SensorUnit.VOLT -> "В"
        SensorUnit.RPM -> "об/мин"
        SensorUnit.COUNT -> "шт"
        else -> unit
    }

    fun reasonLabel(reason: String?): String = when (reason) {
        "warmup" -> "ждём первый замер"
        "unsupported" -> "датчик не поддерживается"
        "not_applicable" -> "неприменимо к этому устройству"
        "permission_denied" -> "нужны дополнительные права на ПК"
        "provider_offline" -> "источник датчика недоступен"
        "read_failed" -> "ошибка чтения"
        "timed_out" -> "источник не ответил вовремя"
        "expired" -> "значение устарело"
        null -> "нет показаний"
        else -> reason
    }

    fun clockTime(utcMillis: Long): String =
        SimpleDateFormat("HH:mm:ss", RU).format(Date(utcMillis))

    fun windowLabel(millis: Long): String = when {
        millis <= 5 * 60_000L -> "5 минут"
        millis <= 15 * 60_000L -> "15 минут"
        else -> "час"
    }

    private fun bytesSplit(value: Double): Pair<String, String> {
        val gib = 1024.0 * 1024.0 * 1024.0
        val mib = 1024.0 * 1024.0
        return when {
            value >= gib -> number(value / gib, 1) to "ГиБ"
            value >= mib -> number(value / mib, 0) to "МиБ"
            else -> number(value / 1024.0, 0) to "КиБ"
        }
    }
}
