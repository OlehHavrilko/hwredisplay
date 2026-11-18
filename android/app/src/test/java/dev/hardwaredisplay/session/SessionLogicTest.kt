package dev.hardwaredisplay.session

import dev.hardwaredisplay.protocol.CatalogFrame
import dev.hardwaredisplay.protocol.DeviceDto
import dev.hardwaredisplay.protocol.SensorDto
import dev.hardwaredisplay.protocol.SnapshotFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SessionLogicTest {

    private fun sensor(
        id: String,
        deviceId: String,
        metricKey: String,
        unit: String,
        label: String = id,
    ) = SensorDto(id, deviceId, metricKey, label, unit, "test", 1000)

    private val cpuCatalog = CatalogFrame(
        v = 1,
        type = "catalog",
        revision = 1,
        devices = listOf(
            DeviceDto("cpu-01", "cpu", "Процессор"),
            DeviceDto("memory-01", "memory", "Память"),
        ),
        sensors = listOf(
            sensor("cpu-01-time", "cpu-01", "cpu.time_busy.percent", "percent"),
            sensor("memory-01-used-percent", "memory-01", "memory.used.percent", "percent"),
            sensor("memory-01-used", "memory-01", "memory.used.bytes", "bytes"),
            sensor("memory-01-total", "memory-01", "memory.total.bytes", "bytes"),
        ),
    )

    @Test
    fun buildsOverviewAndMemoryPresetsFromCpuOnlyCatalog() {
        val presets = buildDefaultPresets(CatalogModel.from(cpuCatalog))

        assertEquals(listOf("Основное", "Память"), presets.map { it.name })
        val overview = presets.first()
        assertEquals("cpu-01-time", overview.primarySensorId)
        assertTrue(overview.secondarySensorIds.isNotEmpty())
        assertTrue(overview.secondarySensorIds.size <= 4)
        assertTrue(overview.compareSensorIds.size <= 2)

        val memory = presets[1]
        assertEquals("memory-01-used-percent", memory.primarySensorId)
    }

    @Test
    fun addsGpuAndTemperaturePresetsOnlyWhenSensorsExist() {
        val catalog = cpuCatalog.copy(
            devices = cpuCatalog.devices + DeviceDto("gpu-01", "gpu", "Видеокарта"),
            sensors = cpuCatalog.sensors + listOf(
                sensor("gpu-01-busy", "gpu-01", "gpu.busy.percent", "percent"),
                sensor("gpu-01-temp", "gpu-01", "gpu.temperature.core", "celsius"),
                sensor("gpu-01-hotspot", "gpu-01", "gpu.temperature.hotspot", "celsius"),
            ),
        )

        val presets = buildDefaultPresets(CatalogModel.from(catalog))

        assertEquals(listOf("Основное", "Память", "Температуры", "Видеокарта"), presets.map { it.name })
        val temperatures = presets.first { it.name == "Температуры" }
        assertEquals("celsius", catalog.sensors.first { it.sensorId == temperatures.primarySensorId }.unit)
    }

    @Test
    fun mapsSnapshotAndReportsFreshness() {
        val snapshot = SnapshotFrame(
            v = 1,
            type = "snapshot",
            streamEpoch = "epoch-1",
            revision = 1,
            seq = 7,
            capturedAtUtc = "2026-11-12T11:04:42.123Z",
            monotonicMs = 7000,
            samples = listOf(
                dev.hardwaredisplay.protocol.SampleDto("cpu-01-time", "ok", 12.5, null, 4),
                dev.hardwaredisplay.protocol.SampleDto("cpu-01-temp", "unavailable", null, "unsupported"),
            ),
        )

        val view = TelemetryView.from("host", snapshot, receivedAtUptimeMs = 10_000, capturedAtUtcMs = 1_000)

        assertEquals(12.5, view.reading("cpu-01-time")!!.value)
        assertEquals("unsupported", view.reading("cpu-01-temp")!!.reason)
        assertEquals(7, view.seq)
        assertTrue(view.isFresh(12_000))
        assertFalse(view.isFresh(20_000))
    }

    @Test
    fun backoffGrowsAndStaysBounded() {
        val random = Random(7)
        val first = SessionRepository.backoffMillis(1, random)
        val second = SessionRepository.backoffMillis(2, random)
        val last = SessionRepository.backoffMillis(20, random)

        assertTrue(first in 800..1_200)
        assertTrue(second in 1_600..2_400)
        assertTrue(last in 24_000..36_000)
        assertTrue(SessionRepository.backoffMillis(0, random) >= 500)
    }
}
