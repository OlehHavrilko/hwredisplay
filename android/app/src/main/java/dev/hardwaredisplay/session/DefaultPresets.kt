package dev.hardwaredisplay.session

import dev.hardwaredisplay.protocol.DeviceKind
import dev.hardwaredisplay.protocol.SensorDto
import dev.hardwaredisplay.protocol.SensorUnit

data class PresetSpec(
    val name: String,
    val primarySensorId: String,
    val secondarySensorIds: List<String>,
    val compareSensorIds: List<String>,
)

private const val MAX_SECONDARY = 4
private const val MAX_COMPARE = 2

/**
 * Стартовые пресеты строятся из фактического каталога: карточки создаются только для
 * существующих датчиков, поэтому отсутствие GPU или температур не даёт ложных нулей.
 */
fun buildDefaultPresets(catalog: CatalogModel): List<PresetSpec> {
    val sensors = catalog.sensors.values.sortedWith(compareBy({ it.deviceId }, { it.sensorId }))
    if (sensors.isEmpty()) {
        return emptyList()
    }

    val presets = mutableListOf<PresetSpec>()

    val cpuBusy = sensors.firstOrNull { it.metricKey == "cpu.time_busy.percent" }
    val overviewPrimary = cpuBusy ?: sensors.first()
    presets += build(
        name = "Основное",
        primary = overviewPrimary,
        candidates = sensors.filter { it.sensorId != overviewPrimary.sensorId },
    )

    val memory = sensors.filter { kindOf(catalog, it) == DeviceKind.MEMORY }
    val memoryPrimary = memory.firstOrNull { it.unit == SensorUnit.PERCENT } ?: memory.firstOrNull()
    if (memoryPrimary != null) {
        presets += build(
            name = "Память",
            primary = memoryPrimary,
            candidates = memory.filter { it.sensorId != memoryPrimary.sensorId },
        )
    }

    val temperatures = sensors.filter { it.unit == SensorUnit.CELSIUS }
    if (temperatures.isNotEmpty()) {
        presets += build(
            name = "Температуры",
            primary = temperatures.first(),
            candidates = temperatures.drop(1),
        )
    }

    val gpu = sensors.filter { kindOf(catalog, it) == DeviceKind.GPU }
    if (gpu.isNotEmpty()) {
        val gpuPrimary = gpu.firstOrNull { it.unit == SensorUnit.PERCENT } ?: gpu.first()
        presets += build(
            name = "Видеокарта",
            primary = gpuPrimary,
            candidates = gpu.filter { it.sensorId != gpuPrimary.sensorId },
        )
    }

    return presets
}

private fun build(name: String, primary: SensorDto, candidates: List<SensorDto>): PresetSpec {
    val secondary = candidates.take(MAX_SECONDARY).map { it.sensorId }
    return PresetSpec(
        name = name,
        primarySensorId = primary.sensorId,
        secondarySensorIds = secondary,
        compareSensorIds = secondary.take(MAX_COMPARE),
    )
}

private fun kindOf(catalog: CatalogModel, sensor: SensorDto): String? =
    catalog.devices[sensor.deviceId]?.kind
