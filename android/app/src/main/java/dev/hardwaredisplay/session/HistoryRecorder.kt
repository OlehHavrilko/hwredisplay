package dev.hardwaredisplay.session

import dev.hardwaredisplay.data.AppDatabase
import dev.hardwaredisplay.data.HistoryPointEntity
import dev.hardwaredisplay.protocol.SensorStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Пишет точки пачками, ограничивает объём и хранит только датчики, отмеченные на панели.
 * Дубликаты отсекает первичный ключ (hostId, sensorId, streamEpoch, seq).
 */
class HistoryRecorder(private val db: AppDatabase) {

    private val mutex = Mutex()
    private val pending = ArrayList<HistoryPointEntity>()
    private var lastFlushUtc = 0L
    private var lastMaintenanceUtc = 0L

    suspend fun record(hostId: String, view: TelemetryView, trackedSensors: Set<String>) {
        val points = buildList {
            for (reading in view.readings.values) {
                if (reading.status != SensorStatus.OK) continue
                val value = reading.value ?: continue
                if (!value.isFinite()) continue
                if (trackedSensors.isNotEmpty() && reading.sensorId !in trackedSensors) continue
                add(
                    HistoryPointEntity(
                        hostId = hostId,
                        sensorId = reading.sensorId,
                        streamEpoch = view.streamEpoch,
                        seq = view.seq,
                        capturedAtUtc = view.capturedAtUtcMs,
                        value = value,
                    )
                )
            }
        }

        if (points.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val ready = mutex.withLock {
            pending += points
            if (pending.size > MAX_PENDING) {
                pending.subList(0, pending.size - MAX_PENDING).clear()
            }
            if (now - lastFlushUtc >= FLUSH_INTERVAL_MS) {
                lastFlushUtc = now
                val batch = pending.toList()
                pending.clear()
                batch
            } else {
                null
            }
        }

        if (ready != null) {
            db.history().insertAll(ready)
            maintain(now)
        }
    }

    suspend fun flush() {
        val batch = mutex.withLock {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        if (batch.isNotEmpty()) {
            db.history().insertAll(batch)
        }
    }

    private suspend fun maintain(now: Long) {
        val due = mutex.withLock {
            if (now - lastMaintenanceUtc < MAINTENANCE_INTERVAL_MS) {
                false
            } else {
                lastMaintenanceUtc = now
                true
            }
        }
        if (!due) {
            return
        }

        db.history().deleteOlderThan(now - RETENTION_MS)
        val stored = db.history().count()
        if (stored > MAX_POINTS) {
            db.history().deleteOldest((stored - MAX_POINTS).toInt())
        }
    }

    companion object {
        const val RETENTION_MS = 60L * 60L * 1000L
        const val MAX_POINTS = 100_000L
        const val MAX_TRACKED_SENSORS = 24
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val MAINTENANCE_INTERVAL_MS = 60_000L
        private const val MAX_PENDING = 5_000
    }
}
