package com.elect.riderange.core

import com.elect.riderange.core.json.JSONArray
import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.hexToBytes
import com.elect.riderange.testing.assertEquals
import com.elect.riderange.testing.assertFalse
import com.elect.riderange.testing.assertTrue
import com.elect.riderange.toHex
import kotlinx.datetime.TimeZone
import kotlin.test.Test

/** The common replacements for JVM APIs (2.0): crypto, printf formatting, JSON, dates, URL encoding. */
class CoreTest {
    @Test fun sha1Vectors() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Crypto.sha1("abc".encodeToByteArray()).toHex())
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Crypto.sha1(ByteArray(0)).toHex())
        assertEquals("84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            Crypto.sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHex())
    }

    @Test fun aesFips197() {
        val key = "000102030405060708090a0b0c0d0e0f".hexToBytes()
        val pt = "00112233445566778899aabbccddeeff".hexToBytes()
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", Crypto.aes128EcbEncrypt(pt, key).toHex())
        // FIPS-197 appendix B.
        assertEquals("3925841d02dc09fbdc118597196a0b32",
            Crypto.aes128EcbEncrypt("3243f6a8885a308d313198a2e0370734".hexToBytes(), "2b7e151628aed2a6abf7158809cf4f3c".hexToBytes()).toHex())
    }

    @Test fun printfLikeJava() {
        assertEquals("3.1", "%.1f".format(3.14159))
        assertEquals("2.68", "%.2f".format(2.675))
        assertEquals("0.13", "%.2f".format(0.125))
        assertEquals("100%", "%.0f%%".format(99.6))
        assertEquals("+5%", "%+.0f%%".format(4.8))
        assertEquals("-0.0", "%.1f".format(-0.04))
        assertEquals("07", "%02d".format(7))
        assertEquals("0A", "%02X".format(10))
        assertEquals("ff", "%02x".format(255))
        assertEquals("a b", "%s %s".format("a", "b"))
        assertEquals("0.0000100", "%.7f".format(1.0E-5))
        assertEquals("12345678.9", "%.1f".format(12345678.94))
        assertEquals("1,234", "%,d".format(1234))
        assertEquals("  42", "%4d".format(42))
    }

    @Test fun jsonRoundTripsLikeOrgJson() {
        val o = JSONObject("""{"a":16,"b":0.95,"c":"x\/y","d":null,"e":[1,2.5,"z"],"f":true,"big":12345678901}""")
        assertEquals(16, o.getInt("a")); assertEquals(16.0, o.getDouble("a"), 0.0)
        assertEquals("0.95", o.getString("b")); assertEquals("x/y", o.getString("c"))
        assertTrue(o.isNull("d")); assertEquals("null", o.optString("d")); assertEquals("", o.optString("missing"))
        assertEquals(3, o.getJSONArray("e").length()); assertEquals("z", o.getJSONArray("e").getString(2))
        assertTrue(o.getBoolean("f")); assertEquals(12345678901L, o.getLong("big"))
        assertFalse(o.has("missing"))
        assertEquals("""{"x":16,"y":0.5,"s":"a\/b\"c","n":null}""",
            JSONObject().put("x", 16.0).put("y", 0.5).put("s", "a/b\"c").put("n", JSONObject.NULL).put("gone", null).toString())
        assertEquals("[1,\"a\"]", JSONArray().put(1).put("a").toString())
        assertEquals(0L, JSONObject().optLong("x"))
        assertTrue(o.optDouble("missing").isNaN())
    }

    @Test fun datesAndUrls() {
        assertEquals("2026-10-04T18:30:05Z", DateFmt.utc(1791138605000, "yyyy-MM-dd'T'HH:mm:ss'Z'"))
        assertEquals("Sun 4 Oct 2026, 6:30 PM", DateFmt.format(1791138605000, "EEE d MMM yyyy, h:mm a", TimeZone.UTC))
        assertEquals("a+b%26c%3D%C3%A9*_.-", Text.urlEncode("a b&c=é*_.-"))
    }
}
