package com.elect.riderange.testing

import kotlin.math.abs

/**
 * JUnit 4's Assert API on top of kotlin.test, so the 1.x unit tests run unchanged in commonTest (Android host
 * tests and the iOS simulator). Note JUnit's order: the optional message comes first.
 */
private fun failWith(message: String?, detail: String): Nothing =
    throw AssertionError((if (message.isNullOrEmpty()) "" else "$message ") + detail)

fun fail(message: String? = null): Nothing = throw AssertionError(message)

fun assertTrue(condition: Boolean) { if (!condition) failWith(null, "expected true") }
fun assertTrue(message: String?, condition: Boolean) { if (!condition) failWith(message, "expected true") }
fun assertFalse(condition: Boolean) { if (condition) failWith(null, "expected false") }
fun assertFalse(message: String?, condition: Boolean) { if (condition) failWith(message, "expected false") }

fun assertNull(value: Any?) { if (value != null) failWith(null, "expected null but was <$value>") }
fun assertNull(message: String?, value: Any?) { if (value != null) failWith(message, "expected null but was <$value>") }
fun assertNotNull(value: Any?) { if (value == null) failWith(null, "expected not null") }
fun assertNotNull(message: String?, value: Any?) { if (value == null) failWith(message, "expected not null") }

fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)
fun assertEquals(message: String?, expected: Any?, actual: Any?) {
    if (expected != actual) failWith(message, "expected:<$expected> but was:<$actual>")
}
fun assertEquals(expected: Long, actual: Long) = assertEquals(null, expected, actual)
fun assertEquals(message: String?, expected: Long, actual: Long) {
    if (expected != actual) failWith(message, "expected:<$expected> but was:<$actual>")
}
fun assertEquals(expected: Double, actual: Double, delta: Double) = assertEquals(null, expected, actual, delta)
fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
    if (expected.compareTo(actual) == 0) return
    if (!(abs(expected - actual) <= delta)) failWith(message, "expected:<$expected> but was:<$actual> (delta $delta)")
}
fun assertEquals(expected: Float, actual: Float, delta: Float) = assertEquals(null, expected.toDouble(), actual.toDouble(), delta.toDouble())

fun assertArrayEquals(expected: ByteArray?, actual: ByteArray?) = assertArrayEquals(null, expected, actual)
fun assertArrayEquals(message: String?, expected: ByteArray?, actual: ByteArray?) {
    if (expected == null && actual == null) return
    if (expected == null || actual == null || !expected.contentEquals(actual))
        failWith(message, "arrays differ: expected:<${expected?.toList()}> but was:<${actual?.toList()}>")
}
fun assertArrayEquals(expected: DoubleArray, actual: DoubleArray, delta: Double) {
    if (expected.size != actual.size) failWith(null, "array lengths differ")
    for (i in expected.indices) assertEquals("index $i", expected[i], actual[i], delta)
}

/** The bits of java.nio.ByteBuffer the tests use to build frames (big-endian). */
class TestByteBuffer private constructor(private val buf: ByteArray) {
    private var pos = 0
    fun position(): Int = pos
    fun array(): ByteArray = buf
    fun put(b: Byte): TestByteBuffer { buf[pos++] = b; return this }
    fun put(b: Int): TestByteBuffer = put(b.toByte())
    fun put(bytes: ByteArray): TestByteBuffer { bytes.copyInto(buf, pos); pos += bytes.size; return this }
    fun putShort(v: Short): TestByteBuffer = put((v.toInt() shr 8).toByte()).put(v.toByte())
    fun putInt(v: Int): TestByteBuffer = put((v shr 24).toByte()).put((v shr 16).toByte()).put((v shr 8).toByte()).put(v.toByte())
    fun putFloat(v: Float): TestByteBuffer = putInt(v.toRawBits())
    fun putLong(v: Long): TestByteBuffer = putInt((v shr 32).toInt()).putInt(v.toInt())

    companion object {
        fun allocate(n: Int) = TestByteBuffer(ByteArray(n))
    }
}
