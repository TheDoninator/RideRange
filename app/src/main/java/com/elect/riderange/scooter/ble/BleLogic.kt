package com.elect.riderange.scooter.ble

import java.util.UUID

/** Pure BLE helpers (no Android types) so they can be unit-tested. */
object Uart {
    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    /** "\0ninebot": UUIDs look like 6e400001-xxxx-xxxx-006e-696e65626f74 (compared without dashes). */
    const val NINEBOT_SUFFIX = "006e696e65626f74"

    fun isNinebotVariant(u: UUID): Boolean = u.toString().replace("-", "").lowercase().endsWith(NINEBOT_SUFFIX)

    data class Choice(val service: UUID, val rx: UUID, val tx: UUID)

    /** Standard NUS first; otherwise a service ending in 006e696e65626f74 with its ...0002.../...0003... chars. */
    fun choose(services: Map<UUID, Set<UUID>>): Choice? {
        services[NUS_SERVICE]?.let { chars ->
            if (NUS_RX in chars && NUS_TX in chars) return Choice(NUS_SERVICE, NUS_RX, NUS_TX)
        }
        for ((svc, chars) in services) {
            if (!isNinebotVariant(svc)) continue
            val rx = chars.firstOrNull { variant(it, svc, "0002") } ?: continue
            val tx = chars.firstOrNull { variant(it, svc, "0003") } ?: continue
            return Choice(svc, rx, tx)
        }
        return null
    }

    /** Same UUID as [svc] but with the 4 hex digits after the first 4 replaced (6e40XXXX-...). */
    private fun variant(c: UUID, svc: UUID, code: String): Boolean {
        val s = svc.toString().lowercase()
        val expected = s.substring(0, 4) + code + s.substring(8)
        return c.toString().lowercase() == expected
    }
}

object ScanFilter {
    fun isNinebot(name: String?, serviceUuids: Collection<UUID>): Boolean =
        (name?.startsWith("NB") == true) || (name?.startsWith("01G") == true) ||
            serviceUuids.any { it == Uart.NUS_SERVICE || Uart.isNinebotVariant(it) }

    fun visible(name: String?, serviceUuids: Collection<UUID>, showAll: Boolean): Boolean =
        showAll || isNinebot(name, serviceUuids)
}

/**
 * Ordered GATT write queue: one write in flight at a time. Each WRITE frame becomes a [Job] of chunks;
 * when its last chunk is acknowledged, [onWritten] returns the job so the caller can report write_ok.
 */
class WriteQueue(private val maxChunk: Int = 20) {
    class Job(val total: Int, val chunks: ArrayDeque<ByteArray>)

    private val jobs = ArrayDeque<Job>()
    var inFlight: ByteArray? = null
        private set

    val isIdle: Boolean get() = inFlight == null && jobs.isEmpty()

    fun enqueue(data: ByteArray) {
        if (data.isEmpty()) {
            jobs.addLast(Job(0, ArrayDeque()))
            return
        }
        val chunks = ArrayDeque<ByteArray>()
        var i = 0
        while (i < data.size) {
            chunks.addLast(data.copyOfRange(i, minOf(i + maxChunk, data.size)))
            i += maxChunk
        }
        jobs.addLast(Job(data.size, chunks))
    }

    /** Next chunk to write, or null if one is already in flight or nothing is queued. */
    fun next(): ByteArray? {
        if (inFlight != null) return null
        val job = jobs.firstOrNull() ?: return null
        val c = job.chunks.removeFirstOrNull() ?: return null
        inFlight = c
        return c
    }

    /** Pops finished empty jobs (zero-length WRITE frames). */
    fun takeFinishedEmpty(): Job? {
        val job = jobs.firstOrNull() ?: return null
        if (inFlight == null && job.chunks.isEmpty()) {
            jobs.removeFirst()
            return job
        }
        return null
    }

    sealed interface Result {
        data object More : Result
        data class Done(val job: Job) : Result
        data class Failed(val job: Job?) : Result
    }

    fun onWritten(success: Boolean): Result {
        inFlight = null
        val job = jobs.firstOrNull() ?: return Result.More
        if (!success) {
            jobs.removeFirst()
            return Result.Failed(job)
        }
        if (job.chunks.isEmpty()) {
            jobs.removeFirst()
            return Result.Done(job)
        }
        return Result.More
    }

    fun clear(): Int {
        val n = jobs.size
        jobs.clear()
        inFlight = null
        return n
    }
}

/** Retry every [intervalMs] for at most [windowMs] after the link dropped. */
class ReconnectPolicy(private val intervalMs: Long = 3_000, private val windowMs: Long = 60_000) {
    private var droppedAt: Long? = null

    fun start(now: Long) {
        droppedAt = now
    }

    fun stop() {
        droppedAt = null
    }

    val active: Boolean get() = droppedAt != null

    /** Delay before the next attempt, or null when the window is over (give up). */
    fun nextDelay(now: Long): Long? {
        val start = droppedAt ?: return null
        if (now - start + intervalMs > windowMs) return null
        return intervalMs
    }
}
