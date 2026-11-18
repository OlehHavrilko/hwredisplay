package dev.hardwaredisplay.session

import dev.hardwaredisplay.protocol.CatalogFrame
import dev.hardwaredisplay.protocol.DeviceDto
import dev.hardwaredisplay.protocol.SampleDto
import dev.hardwaredisplay.protocol.SensorDto
import dev.hardwaredisplay.protocol.SnapshotFrame

sealed interface ConnectionState {
    data object NoHost : ConnectionState
    data object Stopped : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data object Live : ConnectionState
    data class Lost(val reason: String) : ConnectionState
    data class TrustFailed(val reason: String) : ConnectionState
    data object AccessDenied : ConnectionState
    data object Incompatible : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

data class CatalogModel(
    val revision: Long,
    val devices: Map<String, DeviceDto>,
    val sensors: Map<String, SensorDto>,
) {
    fun deviceOf(sensor: SensorDto): DeviceDto? = devices[sensor.deviceId]

    companion object {
        fun from(frame: CatalogFrame) = CatalogModel(
            revision = frame.revision,
            devices = frame.devices.associateBy { it.deviceId },
            sensors = frame.sensors.associateBy { it.sensorId },
        )
    }
}

data class Reading(
    val sensorId: String,
    val status: String,
    val value: Double?,
    val reason: String?,
)

data class TelemetryView(
    val hostId: String,
    val streamEpoch: String,
    val revision: Long,
    val seq: Long,
    val capturedAtUtcMs: Long,
    val receivedAtUptimeMs: Long,
    val readings: Map<String, Reading>,
) {
    fun isFresh(nowElapsedMs: Long): Boolean = nowElapsedMs - receivedAtUptimeMs <= FRESH_WINDOW_MS

    fun reading(sensorId: String): Reading? = readings[sensorId]

    companion object {
        const val FRESH_WINDOW_MS = 5_000L

        fun from(
            hostId: String,
            frame: SnapshotFrame,
            receivedAtUptimeMs: Long,
            capturedAtUtcMs: Long,
        ) = TelemetryView(
            hostId = hostId,
            streamEpoch = frame.streamEpoch,
            revision = frame.revision,
            seq = frame.seq,
            capturedAtUtcMs = capturedAtUtcMs,
            receivedAtUptimeMs = receivedAtUptimeMs,
            readings = frame.samples.associate { sample ->
                sample.sensorId to sample.toReading()
            },
        )

        private fun SampleDto.toReading() = Reading(
            sensorId = sensorId,
            status = status,
            value = value,
            reason = reason,
        )
    }
}
