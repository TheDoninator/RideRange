package com.elect.riderange.scooter

import com.elect.riderange.Fixtures
import com.elect.riderange.hexToBytes
import com.elect.riderange.scooter.protocol.Nb
import com.elect.riderange.scooter.protocol.NbCrypto
import com.elect.riderange.scooter.protocol.NbPacket
import com.elect.riderange.scooter.protocol.le16
import com.elect.riderange.toHex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copied from ninebot-bridge. The Kotlin NbCrypto must match Python miauth byte for byte (protocol/nbcrypto-vectors.json). */
class NbCryptoTest {
    companion object {
        val vectors: JSONObject by lazy { JSONObject(Fixtures.read("nbcrypto-vectors.json")) }
    }

    @Test
    fun fwData() = assertEquals(vectors.getString("fw_data"), NbCrypto.FW_DATA.toHex())

    @Test
    fun crc16() {
        val cases = vectors.getJSONArray("crc16")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("in"), c.getString("crc"), NbCrypto.crc16(c.getString("in").hexToBytes()).toHex())
        }
    }

    @Test
    fun replayAllCases() {
        val cases = vectors.getJSONArray("cases")
        var steps = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val c = NbCrypto()
            val list = case.getJSONArray("steps")
            for (j in 0 until list.length()) {
                val s = list.getJSONObject(j)
                val where = "$name step $j (${s.getString("op")})"
                when (s.getString("op")) {
                    "set_name" -> { c.setName(s.getString("hex").hexToBytes()); assertEquals(where, s.getString("sha1_key"), c.sha1Key.toHex()) }
                    "set_ble" -> { c.setBleData(s.getString("hex").hexToBytes()); assertEquals(where, s.getString("sha1_key"), c.sha1Key.toHex()) }
                    "set_app" -> { c.setAppData(s.getString("hex").hexToBytes()); assertEquals(where, s.getString("sha1_key"), c.sha1Key.toHex()) }
                    "set_it" -> c.it = s.getLong("value")
                    "encrypt", "decrypt" -> {
                        assertEquals("$where it before", s.getLong("it_before"), c.it)
                        val input = s.getString("in").hexToBytes()
                        val out = if (s.getString("op") == "encrypt") c.encrypt(input) else c.decrypt(input)
                        assertEquals(where, s.getString("out"), out.toHex())
                        assertEquals("$where it after", s.getLong("it_after"), c.it)
                    }
                    else -> error("unknown op in $where")
                }
                steps++
            }
        }
        assertTrue("expected the full vector set", steps >= 100)
    }

    @Test
    fun roundTripBothDirections() {
        val a = NbCrypto().apply { setName("NBScooter1".toByteArray()) }
        val b = NbCrypto().apply { setName("NBScooter1".toByteArray()) }
        val p = NbPacket(Nb.PC, Nb.BLE, Nb.INIT, 0).pack()
        assertEquals(p.toHex(), b.decrypt(a.encrypt(p)).toHex())
        val ble = ByteArray(16) { it.toByte() }
        val app = ByteArray(16) { (0xF0 - it).toByte() }
        listOf(a, b).forEach { it.setBleData(ble); it.setAppData(app); it.it = 5 }
        val q = NbPacket(Nb.PC, Nb.CTRL, Nb.WRITE, 0xF1, le16(30)).pack()
        val enc = a.encrypt(q)
        assertEquals(6L, a.it)
        assertEquals(q.toHex(), b.decrypt(enc).toHex())
        assertEquals(6L, b.it)
    }
}
