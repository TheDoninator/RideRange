package com.elect.riderange.scooter.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The classic Ninebot BLE encryption ("NinebotCrypto"), written from the algorithm used by the Python
 * `miauth` NbCrypto that the PC tool uses. It must match it byte for byte: NbCryptoTest replays
 * protocol/nbcrypto-vectors.json (generated from miauth) against this class.
 *
 * Key stages: the advertised name (+ fixed firmware data), then name + BLE key (from the INIT reply), then
 * app key + BLE key (after pairing). With counter [it] == 0 packets are XOR-ed with one AES block and carry
 * a plain checksum; with [it] > 0 they use an AES counter stream and a 4-byte AES-based MAC.
 */
class NbCrypto {
    companion object {
        val FW_DATA: ByteArray = byteArrayOf(
            0x97.toByte(), 0xCF.toByte(), 0xB8.toByte(), 0x02, 0x84.toByte(), 0x41, 0x43, 0xDE.toByte(),
            0x56, 0x00, 0x2B, 0x3B, 0x34, 0x78, 0x0A, 0x5D,
        )

        /** ~sum(bytes), low 16 bits, little-endian. */
        fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): ByteArray {
            var sum = 0
            for (i in from until to) sum += data[i].toInt() and 0xFF
            val n = sum.inv()
            return byteArrayOf(n.toByte(), (n shr 8).toByte())
        }

        /** SHA-1 of (b1 padded/truncated to 16 bytes) + b2, first 16 bytes. */
        fun sha1Key(b1: ByteArray, b2: ByteArray): ByteArray {
            val data = ByteArray(16 + b2.size)
            b1.copyInto(data, 0, 0, minOf(16, b1.size))
            b2.copyInto(data, 16)
            return MessageDigest.getInstance("SHA-1").digest(data).copyOf(16)
        }

        private fun aes(data: ByteArray, key: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            return c.doFinal(data)
        }

        private fun xor(a: ByteArray, b: ByteArray, n: Int): ByteArray =
            ByteArray(n) { (a[it].toInt() xor b[it].toInt()).toByte() }

        private fun cryptoNext(input: ByteArray, key: ByteArray, aesData: ByteArray?): ByteArray {
            val out = ByteArray(input.size)
            var idx = 0
            var left = input.size
            val fwBlock = if (aesData == null) aes(FW_DATA, key) else null
            while (left > 0) {
                val n = minOf(16, left)
                val x1 = ByteArray(16)
                input.copyInto(x1, 0, idx, idx + n)
                val stream = if (aesData == null) fwBlock!! else {
                    aesData[15] = (aesData[15] + 1).toByte()
                    aes(aesData, key)
                }
                xor(x1, stream, 16).copyInto(out, idx, 0, n)
                left -= n
                idx += n
            }
            return out
        }

        private fun crcNext(data: ByteArray, key: ByteArray, aesData: ByteArray): ByteArray {
            var x1 = ByteArray(16)
            data.copyInto(x1, 0, 0, 3)
            var x2 = aes(aesData, key)
            x2 = aes(xor(x1, x2, 16), key)
            var left = data.size - 3
            var idx = 3
            while (left > 0) {
                val n = minOf(16, left)
                x1 = ByteArray(16)
                data.copyInto(x1, 0, idx, idx + n)
                x2 = aes(xor(x1, x2, 16), key)
                left -= n
                idx += n
            }
            aesData[0] = 1
            aesData[15] = 0
            aes(aesData, key).copyInto(x1, 0, 0, 4)
            return xor(x1, x2, 4)
        }
    }

    var name: ByteArray = ByteArray(0)
        private set
    var sha1Key: ByteArray = ByteArray(16)
        private set
    var bleData: ByteArray? = null
        private set
    var appData: ByteArray? = null
        private set

    /** Message counter. Encrypt increments it (when > 0); decrypt takes it from the packet's last 2 bytes. */
    var it: Long = 0

    fun setName(name: ByteArray) {
        this.name = name.copyOf()
        sha1Key = sha1Key(this.name, FW_DATA)
    }

    fun setBleData(ble: ByteArray) {
        bleData = ble.copyOf()
        sha1Key = sha1Key(name, ble)
    }

    fun setAppData(app: ByteArray) {
        appData = app.copyOf()
        sha1Key = sha1Key(app, bleData ?: ByteArray(0))
    }

    private fun aesData(): ByteArray {
        val a = ByteArray(16)
        a[0] = 1
        a[1] = (it shr 24).toByte()
        a[2] = (it shr 16).toByte()
        a[3] = (it shr 8).toByte()
        a[4] = it.toByte()
        bleData!!.copyInto(a, 5, 0, 8)
        a[15] = 0
        return a
    }

    /** Encrypt a plain packet (5A A5 len ...). Output is 6 bytes longer. */
    fun encrypt(data: ByteArray): ByteArray {
        require(data.size >= 3) { "packet too short" }
        val plLen = data.size - 3
        val pl = data.copyOfRange(3, data.size)
        val out = ByteArray(plLen + 9)
        data.copyInto(out, 0, 0, 3)
        if (it == 0L || bleData == null) {
            val crc = crc16(pl)
            cryptoNext(pl, sha1Key, null).copyInto(out, 3)
            out[plLen + 5] = crc[0]
            out[plLen + 6] = crc[1]
        } else {
            it += 1
            val a = aesData()
            cryptoNext(pl, sha1Key, a).copyInto(out, 3)
            a[0] = 0x59
            a[15] = plLen.toByte()
            val crc = crcNext(data, sha1Key, a)
            crc.copyInto(out, plLen + 3)
            out[plLen + 7] = (it shr 8).toByte()
            out[plLen + 8] = it.toByte()
        }
        return out
    }

    /** Decrypt a whole encrypted packet (as reassembled). Does not verify the checksum, like miauth. */
    fun decrypt(data: ByteArray): ByteArray {
        require(data.size >= 9) { "packet too short" }
        val out = ByteArray(data.size - 6)
        data.copyInto(out, 0, 0, 3)
        val pl = data.copyOfRange(3, data.size - 6)
        it = (((data[data.size - 2].toInt() and 0xFF) shl 8) or (data[data.size - 1].toInt() and 0xFF)).toLong()
        val dec = if (it == 0L || bleData == null) cryptoNext(pl, sha1Key, null) else cryptoNext(pl, sha1Key, aesData())
        dec.copyInto(out, 3)
        return out
    }
}
