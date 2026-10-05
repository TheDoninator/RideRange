package com.elect.riderange.core

import kotlin.math.abs

/**
 * printf-style formatting for common code (Kotlin/Native has no String.format). Supports what RideRange uses:
 * `%d %s %f %x %X %%` with flags `- + 0 space ,`, width and precision. Decimal output always uses "." (like
 * Locale.US). Rounding is half-up on the shortest decimal representation, as java.util.Formatter does.
 * Import it explicitly (`import com.elect.riderange.core.format`) so it wins over the JVM's String.format.
 */
fun String.format(vararg args: Any?): String {
    val out = StringBuilder()
    var argIndex = 0
    var i = 0
    val s = this
    while (i < s.length) {
        val c = s[i]
        if (c != '%') { out.append(c); i++; continue }
        i++
        if (i >= s.length) throw IllegalArgumentException("Dangling % in format")
        if (s[i] == '%') { out.append('%'); i++; continue }
        if (s[i] == 'n') { out.append('\n'); i++; continue }
        var left = false; var plus = false; var zero = false; var space = false; var group = false
        loop@ while (i < s.length) {
            when (s[i]) {
                '-' -> left = true
                '+' -> plus = true
                '0' -> zero = true
                ' ' -> space = true
                ',' -> group = true
                else -> break@loop
            }
            i++
        }
        var width = 0
        while (i < s.length && s[i].isDigit()) { width = width * 10 + (s[i] - '0'); i++ }
        var precision = -1
        if (i < s.length && s[i] == '.') {
            i++
            precision = 0
            while (i < s.length && s[i].isDigit()) { precision = precision * 10 + (s[i] - '0'); i++ }
        }
        if (i >= s.length) throw IllegalArgumentException("Bad format: $s")
        val conv = s[i++]
        val arg = args.getOrNull(argIndex++)
        val body: String = when (conv) {
            'd' -> {
                val n = when (arg) {
                    is Int -> arg.toLong(); is Long -> arg; is Short -> arg.toLong(); is Byte -> arg.toLong()
                    else -> throw IllegalArgumentException("%d needs an integer, got $arg")
                }
                signed(n < 0, group(abs(n).toString().let { if (n == Long.MIN_VALUE) "9223372036854775808" else it }, group), plus, space)
            }
            'f' -> {
                val d = when (arg) { is Number -> arg.toDouble(); else -> throw IllegalArgumentException("%f needs a number, got $arg") }
                when {
                    d.isNaN() -> "NaN"
                    d.isInfinite() -> if (d > 0) (if (plus) "+Infinity" else "Infinity") else "-Infinity"
                    else -> {
                        val neg = d < 0 || (d == 0.0 && 1.0 / d < 0)
                        val txt = fixed(abs(d), if (precision < 0) 6 else precision)
                        val (ip, fp) = txt.split('.').let { it[0] to it.getOrNull(1) }
                        signed(neg, group(ip, group) + (fp?.let { ".$it" } ?: ""), plus, space)
                    }
                }
            }
            's', 'S' -> {
                val t = arg.toString()
                val cut = if (precision >= 0 && t.length > precision) t.substring(0, precision) else t
                if (conv == 'S') cut.uppercase() else cut
            }
            'x', 'X' -> {
                val h = when (arg) {
                    is Byte -> (arg.toInt() and 0xFF).toString(16)
                    is Short -> (arg.toInt() and 0xFFFF).toString(16)
                    is Int -> arg.toUInt().toString(16)
                    is Long -> arg.toULong().toString(16)
                    else -> throw IllegalArgumentException("%x needs an integer, got $arg")
                }
                if (conv == 'X') h.uppercase() else h
            }
            'c' -> arg.toString()
            'b' -> (arg != null && arg != false).toString()
            else -> throw IllegalArgumentException("Unsupported conversion %$conv")
        }
        out.append(pad(body, width, left, zero && conv in "dfxX"))
    }
    return out.toString()
}

private fun signed(neg: Boolean, digits: String, plus: Boolean, space: Boolean) = when {
    neg -> "-$digits"
    plus -> "+$digits"
    space -> " $digits"
    else -> digits
}

private fun group(intDigits: String, on: Boolean): String {
    if (!on || intDigits.length <= 3) return intDigits
    val sb = StringBuilder()
    intDigits.forEachIndexed { idx, ch ->
        if (idx > 0 && (intDigits.length - idx) % 3 == 0) sb.append(',')
        sb.append(ch)
    }
    return sb.toString()
}

private fun pad(body: String, width: Int, left: Boolean, zero: Boolean): String {
    if (body.length >= width) return body
    val fill = width - body.length
    return when {
        left -> body + " ".repeat(fill)
        zero -> {
            val signLen = if (body.startsWith("-") || body.startsWith("+") || body.startsWith(" ")) 1 else 0
            body.substring(0, signLen) + "0".repeat(fill) + body.substring(signLen)
        }
        else -> " ".repeat(fill) + body
    }
}

/** [v] >= 0 with [prec] decimals, rounded half-up on the shortest decimal representation. */
internal fun fixed(v: Double, prec: Int): String {
    val s = v.toString()
    var mant = s
    var exp = 0
    val e = s.indexOfFirst { it == 'E' || it == 'e' }
    if (e >= 0) { mant = s.substring(0, e); exp = s.substring(e + 1).toInt() }
    val dot = mant.indexOf('.')
    val ip = if (dot >= 0) mant.substring(0, dot) else mant
    val fp = if (dot >= 0) mant.substring(dot + 1) else ""
    var digits = ip + fp
    var point = ip.length + exp
    while (digits.length > 1 && digits[0] == '0') { digits = digits.substring(1); point-- }
    if (point <= 0) { digits = "0".repeat(1 - point) + digits; point = 1 }
    if (point > digits.length) digits += "0".repeat(point - digits.length)
    // Round to prec decimals.
    val keep = point + prec
    val arr: CharArray
    if (digits.length > keep) {
        val roundUp = digits[keep] >= '5'
        arr = digits.substring(0, keep).toCharArray()
        if (roundUp) {
            var k = arr.size - 1
            var carry = true
            while (carry && k >= 0) {
                if (arr[k] == '9') { arr[k] = '0'; k-- } else { arr[k] = arr[k] + 1; carry = false }
            }
            if (carry) {
                val grown = CharArray(arr.size + 1); grown[0] = '1'; arr.copyInto(grown, 1)
                return assemble(grown, point + 1, prec)
            }
        }
    } else {
        arr = (digits + "0".repeat(keep - digits.length)).toCharArray()
    }
    return assemble(arr, point, prec)
}

private fun assemble(arr: CharArray, point: Int, prec: Int): String {
    val str = arr.concatToString()
    val intPart = str.substring(0, point).trimStart('0').ifEmpty { "0" }
    return if (prec == 0) intPart else intPart + "." + str.substring(point, point + prec)
}
