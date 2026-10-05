package com.elect.riderange.scooter.protocol

/** Device addresses and commands of the Ninebot serial protocol (same as pc/nbbridge/packet.py). */
object Nb {
    const val CTRL = 0x20
    const val BLE = 0x21
    const val BMS = 0x22
    const val PC = 0x3D
    const val APP = 0x3E

    const val READ = 0x01
    const val WRITE = 0x02
    const val WRITE_NO_REPLY = 0x03
    const val READ_ACK = 0x04
    const val WRITE_ACK = 0x05
    const val INIT = 0x5B
    const val PING = 0x5C
    const val PAIR = 0x5D

    const val PLAIN_OVERHEAD = 7   // 5A A5 len src dst cmd index
    const val CRYPTO_OVERHEAD = 6  // 4 checksum bytes + 2 counter bytes

    fun deviceName(d: Int): String = when (d) {
        CTRL -> "ctrl"; BLE -> "ble"; BMS -> "bms"; PC -> "pc"; APP -> "app"
        else -> "0x%02X".format(d)
    }

    fun cmdName(c: Int): String = when (c) {
        READ -> "READ"; WRITE -> "WRITE"; WRITE_NO_REPLY -> "WRITE_NR"; READ_ACK -> "READ_ACK"
        WRITE_ACK -> "WRITE_ACK"; INIT -> "INIT"; PING -> "PING"; PAIR -> "PAIR"
        else -> "0x%02X".format(c)
    }
}

/** A plain Ninebot packet: 5A A5 | len | src | dst | cmd | index | data[len]. */
class NbPacket(val src: Int, val dst: Int, val cmd: Int, val index: Int, val data: ByteArray = ByteArray(0)) {
    fun pack(): ByteArray {
        require(data.size <= 0xFF) { "packet data too long" }
        val out = ByteArray(Nb.PLAIN_OVERHEAD + data.size)
        out[0] = 0x5A
        out[1] = 0xA5.toByte()
        out[2] = data.size.toByte()
        out[3] = src.toByte()
        out[4] = dst.toByte()
        out[5] = cmd.toByte()
        out[6] = index.toByte()
        data.copyInto(out, 7)
        return out
    }

    fun describe(): String {
        val d = if (data.isEmpty()) "" else " data=" + data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return "${Nb.deviceName(src)}->${Nb.deviceName(dst)} ${Nb.cmdName(cmd)} idx=0x%02X$d".format(index)
    }

    override fun toString(): String = describe()

    override fun equals(other: Any?): Boolean = other is NbPacket && other.src == src && other.dst == dst &&
        other.cmd == cmd && other.index == index && other.data.contentEquals(data)

    override fun hashCode(): Int = ((src * 31 + dst) * 31 + cmd) * 31 + index

    companion object {
        fun unpack(raw: ByteArray): NbPacket {
            require(raw.size >= Nb.PLAIN_OVERHEAD && raw[0] == 0x5A.toByte() && raw[1] == 0xA5.toByte()) {
                "not a Ninebot packet"
            }
            val n = raw[2].toInt() and 0xFF
            require(raw.size >= Nb.PLAIN_OVERHEAD + n) { "truncated packet" }
            return NbPacket(raw[3].u(), raw[4].u(), raw[5].u(), raw[6].u(), raw.copyOfRange(7, 7 + n))
        }

        private fun Byte.u() = toInt() and 0xFF
    }
}

/**
 * Joins BLE notifications into whole encrypted packets (same rule as pc/nbbridge/packet.py Reassembler):
 * a notification starting with 5A A5 starts a new buffer, others append; a packet is complete at
 * buffer[2] + 7 + 6 bytes.
 */
class Reassembler {
    private var buf = ByteArray(0)
    var dropped = 0
        private set

    fun feed(chunk: ByteArray): List<ByteArray> {
        val starts = chunk.size >= 2 && chunk[0] == 0x5A.toByte() && chunk[1] == 0xA5.toByte()
        if (starts) {
            if (buf.isNotEmpty()) dropped++
            buf = chunk.copyOf()
        } else if (buf.isNotEmpty()) {
            buf += chunk
        } else {
            dropped++
            return emptyList()
        }
        val out = ArrayList<ByteArray>()
        while (buf.size >= 3) {
            val need = (buf[2].toInt() and 0xFF) + Nb.PLAIN_OVERHEAD + Nb.CRYPTO_OVERHEAD
            if (buf.size < need) break
            out.add(buf.copyOfRange(0, need))
            val rest = buf.copyOfRange(need, buf.size)
            buf = if (rest.size >= 2 && rest[0] == 0x5A.toByte() && rest[1] == 0xA5.toByte()) rest else {
                if (rest.isNotEmpty()) dropped++
                ByteArray(0)
            }
        }
        return out
    }
}

/** Little-endian 16-bit helpers for register values. */
fun ByteArray.u16(at: Int = 0): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
fun ByteArray.s16(at: Int = 0): Int = u16(at).toShort().toInt()
fun ByteArray.u32(at: Int = 0): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte())
