package com.elect.riderange.core.json

/**
 * A small org.json-compatible JSON model for common (Android + iOS) code. RideRange 1.x used Android's built-in
 * org.json; this keeps the same API and semantics (lenient getters that coerce strings/numbers, optString returning ""
 * for missing keys, integers parsed as Int/Long and decimals as Double, whole doubles written without ".0"), so data
 * written by 1.x (garage, keys, cached routes) reads back exactly and the parsers didn't need rewriting.
 */
class JSONException(message: String) : RuntimeException(message)

private object JsonNull {
    override fun toString() = "null"
}

class JSONObject() {
    private val map = LinkedHashMap<String, Any>()

    constructor(json: String) : this() {
        val v = JsonParser(json).parseRoot()
        if (v !is JSONObject) throw JSONException("Value is not a JSON object")
        map.putAll(v.map)
    }

    constructor(m: Map<*, *>) : this() {
        for ((k, v) in m) if (k != null && v != null) map[k.toString()] = v
    }

    fun length(): Int = map.size
    fun has(name: String): Boolean = map.containsKey(name)
    fun isNull(name: String): Boolean { val v = map[name]; return v == null || v === NULL }
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun names(): JSONArray? = if (map.isEmpty()) null else JSONArray().also { a -> map.keys.forEach { a.put(it) } }
    fun remove(name: String): Any? = map.remove(name)

    /** Like org.json: a null value removes the key; non-finite numbers are refused. */
    fun put(name: String, value: Any?): JSONObject {
        if (value == null) { map.remove(name); return this }
        checkFinite(value)
        map[name] = value
        return this
    }

    fun opt(name: String): Any? = map[name]
    fun get(name: String): Any = map[name] ?: throw JSONException("No value for $name")

    fun getString(name: String): String = toStr(get(name)) ?: throw mismatch(name, "String")
    fun optString(name: String, fallback: String = ""): String = toStr(opt(name)) ?: fallback

    fun getDouble(name: String): Double = toDouble(get(name)) ?: throw mismatch(name, "double")
    fun optDouble(name: String, fallback: Double = Double.NaN): Double = toDouble(opt(name)) ?: fallback

    fun getInt(name: String): Int = toDouble(get(name))?.let { toIntLike(get(name), it) } ?: throw mismatch(name, "int")
    fun optInt(name: String, fallback: Int = 0): Int = opt(name)?.let { v -> toDouble(v)?.let { toIntLike(v, it) } } ?: fallback

    fun getLong(name: String): Long = toLongOrNull(get(name)) ?: throw mismatch(name, "long")
    fun optLong(name: String, fallback: Long = 0L): Long = opt(name)?.let { toLongOrNull(it) } ?: fallback

    fun getBoolean(name: String): Boolean = toBool(get(name)) ?: throw mismatch(name, "boolean")
    fun optBoolean(name: String, fallback: Boolean = false): Boolean = toBool(opt(name)) ?: fallback

    fun getJSONObject(name: String): JSONObject = get(name) as? JSONObject ?: throw mismatch(name, "JSONObject")
    fun optJSONObject(name: String): JSONObject? = opt(name) as? JSONObject
    fun getJSONArray(name: String): JSONArray = get(name) as? JSONArray ?: throw mismatch(name, "JSONArray")
    fun optJSONArray(name: String): JSONArray? = opt(name) as? JSONArray

    override fun toString(): String = StringBuilder().also { write(it) }.toString()

    internal fun write(sb: StringBuilder) {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            quote(k, sb)
            sb.append(':')
            writeValue(v, sb)
        }
        sb.append('}')
    }

    companion object {
        /** The JSON null value (stored explicitly, unlike a Kotlin null which removes the key). */
        val NULL: Any = JsonNull

        fun quote(s: String): String = StringBuilder().also { quote(s, it) }.toString()
    }
}

class JSONArray() {
    private val list = ArrayList<Any>()

    constructor(json: String) : this() {
        val v = JsonParser(json).parseRoot()
        if (v !is JSONArray) throw JSONException("Value is not a JSON array")
        list.addAll(v.list)
    }

    constructor(items: Collection<*>) : this() {
        for (v in items) list.add(v ?: JSONObject.NULL)
    }

    fun length(): Int = list.size

    fun put(value: Any?): JSONArray {
        val v = value ?: JSONObject.NULL
        checkFinite(v)
        list.add(v)
        return this
    }

    fun put(index: Int, value: Any?): JSONArray {
        val v = value ?: JSONObject.NULL
        checkFinite(v)
        while (list.size <= index) list.add(JSONObject.NULL)
        list[index] = v
        return this
    }

    fun opt(index: Int): Any? = list.getOrNull(index)
    fun get(index: Int): Any = list.getOrNull(index) ?: throw JSONException("Index $index out of range [0..${list.size})")
    fun isNull(index: Int): Boolean { val v = opt(index); return v == null || v === JSONObject.NULL }

    fun getString(index: Int): String = toStr(get(index)) ?: throw JSONException("Value at $index is not a String")
    fun optString(index: Int, fallback: String = ""): String = toStr(opt(index)) ?: fallback
    fun getDouble(index: Int): Double = toDouble(get(index)) ?: throw JSONException("Value at $index is not a double")
    fun optDouble(index: Int, fallback: Double = Double.NaN): Double = toDouble(opt(index)) ?: fallback
    fun getInt(index: Int): Int = toDouble(get(index))?.let { toIntLike(get(index), it) } ?: throw JSONException("Value at $index is not an int")
    fun optInt(index: Int, fallback: Int = 0): Int = opt(index)?.let { v -> toDouble(v)?.let { toIntLike(v, it) } } ?: fallback
    fun getLong(index: Int): Long = toLongOrNull(get(index)) ?: throw JSONException("Value at $index is not a long")
    fun optLong(index: Int, fallback: Long = 0L): Long = opt(index)?.let { toLongOrNull(it) } ?: fallback
    fun getBoolean(index: Int): Boolean = toBool(get(index)) ?: throw JSONException("Value at $index is not a boolean")
    fun getJSONObject(index: Int): JSONObject = get(index) as? JSONObject ?: throw JSONException("Value at $index is not a JSONObject")
    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject
    fun getJSONArray(index: Int): JSONArray = get(index) as? JSONArray ?: throw JSONException("Value at $index is not a JSONArray")
    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray

    override fun toString(): String = StringBuilder().also { write(it) }.toString()

    internal fun write(sb: StringBuilder) {
        sb.append('[')
        list.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            writeValue(v, sb)
        }
        sb.append(']')
    }
}

// ---- coercion (same rules as Android's org.json JSON helper) ----

private fun mismatch(name: String, type: String) = JSONException("Value at $name is not a $type")

private fun checkFinite(v: Any) {
    if (v is Double && (v.isNaN() || v.isInfinite())) throw JSONException("Forbidden numeric value: $v")
    if (v is Float && (v.isNaN() || v.isInfinite())) throw JSONException("Forbidden numeric value: $v")
}

private fun toStr(v: Any?): String? = when (v) {
    null -> null
    is String -> v
    is Double, is Float -> numberToString(v as Number)
    else -> v.toString()
}

private fun toDouble(v: Any?): Double? = when (v) {
    is Double -> v
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

/** org.json truncates doubles to int ((int) d), saturating like the JVM cast. */
private fun toIntLike(raw: Any, d: Double): Int = when (raw) {
    is Int -> raw
    is Long -> raw.toInt()
    else -> d.toInt()
}

private fun toLongOrNull(v: Any?): Long? = when (v) {
    is Long -> v
    is Int -> v.toLong()
    is Number -> v.toDouble().toLong()
    is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong()
    else -> null
}

private fun toBool(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
    else -> null
}

/** Whole doubles are written as integers ("16", not "16.0"), like org.json. */
internal fun numberToString(n: Number): String {
    val d = n.toDouble()
    if (n is Int || n is Long || n is Short || n is Byte) return n.toString()
    if (d == 0.0 && 1.0 / d < 0) return "-0"
    val l = d.toLong()
    if (d == l.toDouble() && d >= -9.2E18 && d <= 9.2E18) return l.toString()
    return d.toString()
}

private fun writeValue(v: Any?, sb: StringBuilder) {
    when (v) {
        null -> sb.append("null")
        is JSONObject -> v.write(sb)
        is JSONArray -> v.write(sb)
        is String -> quote(v, sb)
        is Boolean -> sb.append(v.toString())
        is Number -> sb.append(numberToString(v))
        else -> if (v === JSONObject.NULL) sb.append("null") else quote(v.toString(), sb)
    }
}

private fun quote(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"', '\\', '/' -> sb.append('\\').append(c)
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code <= 0x1F || c == ' ' || c == ' ') {
                sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            } else sb.append(c)
        }
    }
    sb.append('"')
}

// ---- parser ----

private class JsonParser(private val s: String) {
    private var i = 0

    fun parseRoot(): Any {
        val v = value()
        skipWs()
        if (i < s.length) throw JSONException("Unexpected trailing text at $i")
        return v
    }

    private fun skipWs() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
        }
    }

    private fun value(): Any {
        skipWs()
        if (i >= s.length) throw JSONException("End of input")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", JSONObject.NULL)
            else -> if (c == '-' || c in '0'..'9') num() else throw JSONException("Unexpected '$c' at $i")
        }
    }

    private fun lit(word: String, v: Any): Any {
        if (!s.startsWith(word, i)) throw JSONException("Expected $word at $i")
        i += word.length
        return v
    }

    private fun obj(): JSONObject {
        val o = JSONObject()
        i++ // {
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return o }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') throw JSONException("Expected a key at $i")
            val k = str()
            skipWs()
            if (i >= s.length || s[i] != ':') throw JSONException("Expected ':' at $i")
            i++
            val v = value()
            o.put(k, v)
            skipWs()
            if (i >= s.length) throw JSONException("Unterminated object")
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return o }
                else -> throw JSONException("Expected ',' or '}' at $i")
            }
        }
    }

    private fun arr(): JSONArray {
        val a = JSONArray()
        i++ // [
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return a }
        while (true) {
            a.put(value())
            skipWs()
            if (i >= s.length) throw JSONException("Unterminated array")
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return a }
                else -> throw JSONException("Expected ',' or ']' at $i")
            }
        }
    }

    private fun str(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw JSONException("Unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) throw JSONException("Unterminated escape")
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw JSONException("Bad unicode escape")
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Any {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val t = s.substring(start, i)
        if (t.none { it == '.' || it == 'e' || it == 'E' }) {
            t.toLongOrNull()?.let { l -> return if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l }
        }
        return t.toDoubleOrNull() ?: throw JSONException("Bad number '$t'")
    }
}
