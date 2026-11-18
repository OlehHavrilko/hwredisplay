package dev.hardwaredisplay.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {

    private val hello =
        """{"v":1,"type":"hello","hostId":"1482a7c6-1d42-40af-869e-6e09ceab6de2","streamEpoch":"d5fa3350-cbbd-4ccf-b989-94163a402745","samplePeriodMs":1000}"""

    private val catalog =
        """{"v":1,"type":"catalog","revision":1,"devices":[{"deviceId":"cpu-01","kind":"cpu","label":"Процессор"}],"sensors":[{"sensorId":"cpu-01-time","deviceId":"cpu-01","metricKey":"cpu.time_busy.percent","label":"Занятость CPU по времени","unit":"percent","source":"win32.GetSystemTimes","samplePeriodMs":1000}]}"""

    private val snapshot =
        """{"v":1,"type":"snapshot","streamEpoch":"d5fa3350-cbbd-4ccf-b989-94163a402745","revision":1,"seq":48,"capturedAtUtc":"2026-11-12T11:04:42.123Z","monotonicMs":48000,"samples":[{"sensorId":"cpu-01-time","status":"ok","value":0,"ageMs":4},{"sensorId":"cpu-01-temp","status":"unavailable","reason":"permission_denied","value":null}]}"""

    @Test
    fun parsesHello() {
        val frame = (parseFrame(hello) as FrameParse.Ok).frame as Frame.Hello
        assertEquals("d5fa3350-cbbd-4ccf-b989-94163a402745", frame.body.streamEpoch)
        assertEquals(1000, frame.body.samplePeriodMs)
    }

    @Test
    fun parsesCatalog() {
        val frame = (parseFrame(catalog) as FrameParse.Ok).frame as Frame.Catalog
        assertEquals(1, frame.body.revision)
        assertEquals("cpu.time_busy.percent", frame.body.sensors.single().metricKey)
        assertEquals("Процессор", frame.body.devices.single().label)
    }

    @Test
    fun parsesSnapshotWithActualZeroAndMissingValue() {
        val frame = (parseFrame(snapshot) as FrameParse.Ok).frame as Frame.Snapshot
        assertEquals(48, frame.body.seq)
        val zero = frame.body.samples.first { it.sensorId == "cpu-01-time" }
        assertEquals(0.0, zero.value!!, 0.0)
        assertEquals(SensorStatus.OK, zero.status)
        assertEquals(4L, zero.ageMs)

        val missing = frame.body.samples.first { it.sensorId == "cpu-01-temp" }
        assertEquals(null, missing.value)
        assertEquals("permission_denied", missing.reason)
    }

    @Test
    fun rejectsUnknownOrBrokenFrames() {
        assertEquals("unknown_type", (parseFrame("""{"v":1,"type":"ping"}""") as FrameParse.Rejected).reason)
        assertEquals("missing_type", (parseFrame("""{"v":1}""") as FrameParse.Rejected).reason)
        assertEquals("bad_frame", (parseFrame("""{"v":1,"type":"hello"}""") as FrameParse.Rejected).reason)
        assertEquals("bad_frame", (parseFrame("not json at all") as FrameParse.Rejected).reason)
    }

    @Test
    fun rejectsOversizedMessage() {
        val oversized = "{\"type\":\"snapshot\",\"pad\":\"" + "x".repeat(MAX_MESSAGE_BYTES) + "\"}"
        assertTrue(parseFrame(oversized) is FrameParse.Rejected)
    }
}
