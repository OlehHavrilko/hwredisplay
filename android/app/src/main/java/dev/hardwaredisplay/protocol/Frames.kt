package dev.hardwaredisplay.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val PROTOCOL_VERSION = 1
const val PROTOCOL_SUB_PROTOCOL = "hardwaredisplay.v1"
const val MAX_MESSAGE_BYTES = 256 * 1024

val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class DeviceDto(
    val deviceId: String,
    val kind: String,
    val label: String,
)

@Serializable
data class SensorDto(
    val sensorId: String,
    val deviceId: String,
    val metricKey: String,
    val label: String,
    val unit: String,
    val source: String,
    val samplePeriodMs: Int,
)

@Serializable
data class SampleDto(
    val sensorId: String,
    val status: String,
    val value: Double? = null,
    val reason: String? = null,
    val ageMs: Long? = null,
)

@Serializable
data class HelloFrame(
    val v: Int,
    val type: String,
    val hostId: String,
    val streamEpoch: String,
    val samplePeriodMs: Int,
)

@Serializable
data class CatalogFrame(
    val v: Int,
    val type: String,
    val revision: Long,
    val devices: List<DeviceDto>,
    val sensors: List<SensorDto>,
)

@Serializable
data class SnapshotFrame(
    val v: Int,
    val type: String,
    val streamEpoch: String,
    val revision: Long,
    val seq: Long,
    val capturedAtUtc: String,
    val monotonicMs: Long,
    val samples: List<SampleDto>,
)

@Serializable
data class PairRequest(
    val v: Int = PROTOCOL_VERSION,
    val invite: String,
    val clientName: String,
)

@Serializable
data class PairResponse(
    val v: Int,
    val hostId: String,
    val clientId: String,
)

@Serializable
data class ErrorResponse(
    val v: Int,
    @SerialName("error") val error: String,
    val reason: String? = null,
)

sealed interface Frame {
    data class Hello(val body: HelloFrame) : Frame
    data class Catalog(val body: CatalogFrame) : Frame
    data class Snapshot(val body: SnapshotFrame) : Frame
}

sealed interface FrameParse {
    data class Ok(val frame: Frame) : FrameParse
    data class Rejected(val reason: String) : FrameParse
}

/**
 * Кадры различаются по полю `type`. Неизвестный тип — ошибка потока, а не молчаливый пропуск:
 * клиент не должен продолжать работу на непонятном контракте.
 */
fun parseFrame(text: String): FrameParse {
    if (text.length > MAX_MESSAGE_BYTES) {
        return FrameParse.Rejected("frame_too_large")
    }

    return try {
        val body = Json.parseToJsonElement(text)
        val type = (body as? JsonObject)?.get("type")
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return FrameParse.Rejected("missing_type")

        when (type) {
            "hello" -> FrameParse.Ok(Frame.Hello(ProtocolJson.decodeFromString<HelloFrame>(text)))
            "catalog" -> FrameParse.Ok(Frame.Catalog(ProtocolJson.decodeFromString<CatalogFrame>(text)))
            "snapshot" -> FrameParse.Ok(Frame.Snapshot(ProtocolJson.decodeFromString<SnapshotFrame>(text)))
            else -> FrameParse.Rejected("unknown_type")
        }
    } catch (error: Exception) {
        FrameParse.Rejected("bad_frame")
    }
}

object SensorStatus {
    const val OK = "ok"
    const val WAITING = "waiting"
    const val UNAVAILABLE = "unavailable"
    const val ERROR = "error"
    const val STALE = "stale"
}

object SensorUnit {
    const val PERCENT = "percent"
    const val BYTES = "bytes"
    const val BYTES_PER_SECOND = "bytes_per_second"
    const val CELSIUS = "celsius"
    const val HERTZ = "hertz"
    const val WATT = "watt"
    const val VOLT = "volt"
    const val RPM = "rpm"
    const val COUNT = "count"
}

object DeviceKind {
    const val CPU = "cpu"
    const val GPU = "gpu"
    const val MEMORY = "memory"
    const val STORAGE = "storage"
    const val MOTHERBOARD = "motherboard"
}
