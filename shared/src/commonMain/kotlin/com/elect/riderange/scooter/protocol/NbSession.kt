package com.elect.riderange.scooter.protocol

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

class HandshakeException(message: String) : Exception(message)
class WriteBlockedException(message: String) : Exception(message)

/** One traced packet or BLE chunk: [plain] is null for raw BLE chunks. */
class NbTrace(val timeMs: Long, val dir: String, val raw: ByteArray, val plain: ByteArray?)

/**
 * Read-only mode (the scooter report): only the handshake commands and READ may be sent. Any WRITE (0x02),
 * WRITE without reply (0x03) or other command is refused before it is encrypted.
 */
object ReadOnlyGuard {
    val ALLOWED = setOf(Nb.INIT, Nb.PING, Nb.PAIR, Nb.READ)
    fun allowed(pkt: NbPacket): Boolean = pkt.cmd in ALLOWED
}
class NbTimeout(message: String) : Exception(message)

/** Raw byte pipe to the scooter: BLE through the service, or the bridge TCP protocol to `nbbridge sim`. */
fun interface NbLink {
    /** Write already-encrypted bytes (the link splits them into 20-byte BLE writes). */
    suspend fun send(raw: ByteArray)
}

/** Register access, so the Tuner logic can be tested against a fake scooter. */
interface RegisterIo {
    /** Read [length] bytes starting at register [reg]. Throws [NbTimeout] if the scooter does not answer. */
    suspend fun read(dev: Int, reg: Int, length: Int = 2): ByteArray

    /** WRITE with reply. Throws [NbTimeout] if the scooter does not answer. */
    suspend fun write(dev: Int, reg: Int, value: ByteArray)
}

/**
 * One authenticated conversation with a scooter: reassembly, NbCrypto, request/response, and the pairing
 * handshake (INIT -> PING (+ power button) -> PAIR). A Kotlin port of pc/nbbridge/session.py.
 */
class NbSession(
    private val link: NbLink,
    val src: Int = Nb.PC,
    private val log: (String) -> Unit = {},
    /** Refuse everything except INIT/PING/PAIR/READ. */
    val readOnly: Boolean = false,
    /** Every packet sent/received (plain + encrypted) and every raw BLE chunk received. */
    private val trace: ((NbTrace) -> Unit)? = null,
) : RegisterIo {
    private val crypto = NbCrypto()
    private val reasm = Reassembler()
    private val lock = Any()
    private val rx = Channel<NbPacket>(Channel.UNLIMITED)
    private val requests = Mutex()
    private var started = false
    private val early = ArrayList<ByteArray>()

    var name: String? = null
        private set
    var serial: ByteArray = ByteArray(0)
        private set
    var bleKey: ByteArray? = null
        private set
    var appKey: ByteArray? = null
        private set
    var paired = false
        private set

    /** Set the advertised name (the first key stage). Notifications received before this are replayed. */
    fun start(name: String) {
        val replay: List<ByteArray>
        synchronized(lock) {
            this.name = name
            crypto.setName(name.toByteArray(Charsets.US_ASCII))
            started = true
            replay = early.toList()
            early.clear()
        }
        replay.forEach { onNotification(it) }
    }

    /** Feed one BLE notification (any thread). */
    fun onNotification(chunk: ByteArray) {
        val plains = ArrayList<ByteArray>()
        synchronized(lock) {
            if (!started) {
                early.add(chunk.copyOf())
                return
            }
            trace?.invoke(NbTrace(System.currentTimeMillis(), "<-", chunk.copyOf(), null))
            for (raw in reasm.feed(chunk)) {
                try {
                    val p = crypto.decrypt(raw)
                    trace?.invoke(NbTrace(System.currentTimeMillis(), "<-", raw, p))
                    plains.add(p)
                } catch (_: Exception) {
                }
            }
        }
        for (p in plains) {
            val pkt = try { NbPacket.unpack(p) } catch (_: IllegalArgumentException) { continue }
            log("<- ${pkt.describe()}")
            rx.trySend(pkt)
        }
    }

    suspend fun send(pkt: NbPacket) {
        if (readOnly && !ReadOnlyGuard.allowed(pkt)) {
            throw WriteBlockedException("Read-only session: refusing to send ${pkt.describe()}")
        }
        val plain = pkt.pack()
        val raw = synchronized(lock) {
            if (!started) throw HandshakeException("Session not started")
            crypto.encrypt(plain)
        }
        trace?.invoke(NbTrace(System.currentTimeMillis(), "->", raw, plain))
        log("-> ${pkt.describe()}")
        link.send(raw)
    }

    private suspend fun receive(timeoutMs: Long): NbPacket? = withTimeoutOrNull(timeoutMs) { rx.receive() }

    private fun replyCmd(cmd: Int) = when (cmd) {
        Nb.READ -> Nb.READ_ACK
        Nb.WRITE -> Nb.WRITE_ACK
        else -> cmd
    }

    private fun matches(req: NbPacket, rsp: NbPacket): Boolean {
        if (rsp.src != req.dst || rsp.dst != req.src || rsp.cmd != replyCmd(req.cmd)) return false
        return req.cmd > Nb.WRITE_ACK || rsp.index == req.index
    }

    private suspend fun requestUnlocked(req: NbPacket, timeoutMs: Long, retries: Int): NbPacket {
        repeat(retries) {
            send(req)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                val rsp = receive(left) ?: break
                if (matches(req, rsp)) return rsp
            }
        }
        throw NbTimeout("No reply to ${req.describe()}")
    }

    suspend fun request(req: NbPacket, timeoutMs: Long = 1500, retries: Int = 3): NbPacket =
        requests.withLock { requestUnlocked(req, timeoutMs, retries) }

    // ---- handshake -------------------------------------------------------------------------------

    class Result(val serial: String, val appKey: ByteArray, val newKey: Boolean)

    /**
     * Authenticate. [knownKey] is the saved app key for this scooter (or null to pair a new one, which
     * needs the power button: [onPressButton] is called once when the user must press it).
     */
    suspend fun handshake(
        knownKey: ByteArray?,
        keyForSerial: (String) -> ByteArray? = { null },
        noPing: Boolean = false,
        pairTimeoutMs: Long = 60_000,
        onPressButton: () -> Unit = {},
        /** Fail instead of pairing a new key when no key is known for this scooter's serial. */
        requireKnownKey: Boolean = false,
    ): Result = requests.withLock {
        val init = try {
            requestUnlocked(NbPacket(src, Nb.BLE, Nb.INIT, 0), 2000, 3)
        } catch (_: NbTimeout) {
            throw HandshakeException(
                "The scooter did not answer the first handshake step. Close the Segway app (only one " +
                    "Bluetooth connection at a time) and try again. A newer 'Encryption2' scooter is not supported.")
        }
        if (init.data.size < 16) throw HandshakeException("INIT reply too short")
        val ble = init.data.copyOfRange(0, 16)
        bleKey = ble
        serial = init.data.copyOfRange(16, init.data.size)
        synchronized(lock) { crypto.setBleData(ble) }
        val serialTxt = serialText
        var key = knownKey ?: keyForSerial(serialTxt)
        if (key == null && requireKnownKey) throw HandshakeException("No saved pairing key for $serialTxt")
        val newKey = key == null
        if (key == null) key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        appKey = key

        if (noPing) {
            synchronized(lock) {
                crypto.setAppData(key)
                crypto.it = maxOf(crypto.it, 1)
            }
        } else {
            val rsp = requestUnlocked(NbPacket(src, Nb.BLE, Nb.PING, 0, key), 2000, 3)
            if (rsp.index == 1) {
                synchronized(lock) { crypto.setAppData(key) }
            } else {
                onPressButton()
                waitForButton(key, pairTimeoutMs)
            }
        }
        try {
            requestUnlocked(NbPacket(src, Nb.BLE, Nb.PAIR, 0, serial), 2000, 3)
        } catch (_: NbTimeout) {
            throw HandshakeException("The scooter did not answer the final pairing step.")
        }
        paired = true
        Result(serialTxt, key, newKey)
    }

    private suspend fun waitForButton(key: ByteArray, timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            send(NbPacket(src, Nb.BLE, Nb.PAIR, 0, serial))
            val end = System.nanoTime() + 1_000_000_000L
            while (true) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                val rsp = receive(left) ?: break
                if (rsp.src != Nb.BLE) continue
                if (rsp.cmd == Nb.PING && rsp.index == 1) {
                    synchronized(lock) { crypto.setAppData(key) }
                    return
                }
                if (rsp.cmd == Nb.PAIR && rsp.index == 1) return
            }
        }
        throw HandshakeException(
            "Pairing timed out: the power button was not pressed, or the scooter is bound to the Segway app " +
                "(import its app key in Settings).")
    }

    val serialText: String
        get() {
            val trimmed = serial.dropLastWhile { it == 0.toByte() }.toByteArray()
            val s = String(trimmed, Charsets.US_ASCII)
            return if (s.isNotEmpty() && s.all { it.code in 0x20..0x7E }) s
            else serial.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        }

    // ---- registers -------------------------------------------------------------------------------

    override suspend fun read(dev: Int, reg: Int, length: Int): ByteArray = read(dev, reg, length, 1000, 2)

    suspend fun read(dev: Int, reg: Int, length: Int, timeoutMs: Long, retries: Int): ByteArray =
        request(NbPacket(src, dev, Nb.READ, reg, byteArrayOf(length.toByte())), timeoutMs, retries).data

    override suspend fun write(dev: Int, reg: Int, value: ByteArray) {
        request(NbPacket(src, dev, Nb.WRITE, reg, value), 1500, 2)
    }
}
