package com.catatanku.app.data.backup

import java.io.BufferedWriter
import java.io.Closeable
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.StringReader

/**
 * Penulis JSON streaming O(1) memori tanpa ketergantungan Android agar dapat diuji di JVM murni.
 */
class JsonStreamWriter(out: OutputStream, private val pretty: Boolean = false) : Closeable {
    private val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 16 * 1024)
    private enum class Scope { OBJ_EMPTY, OBJ_FILLED, ARR_EMPTY, ARR_FILLED }
    private val stack = ArrayList<Scope>()
    private var pendingName = false

    fun beginObject(): JsonStreamWriter {
        beforeValue()
        w.write("{")
        stack.add(Scope.OBJ_EMPTY)
        return this
    }

    fun endObject(): JsonStreamWriter {
        val s = stack.removeAt(stack.lastIndex)
        if (pretty && s == Scope.OBJ_FILLED) newline()
        w.write("}")
        return this
    }

    fun beginArray(): JsonStreamWriter {
        beforeValue()
        w.write("[")
        stack.add(Scope.ARR_EMPTY)
        return this
    }

    fun endArray(): JsonStreamWriter {
        val s = stack.removeAt(stack.lastIndex)
        if (pretty && s == Scope.ARR_FILLED) newline()
        w.write("]")
        return this
    }

    fun name(key: String): JsonStreamWriter {
        val top = stack.last()
        when (top) {
            Scope.OBJ_EMPTY -> stack[stack.lastIndex] = Scope.OBJ_FILLED
            Scope.OBJ_FILLED -> w.write(",")
            else -> error("name() di luar objek JSON")
        }
        if (pretty) newline()
        writeEscaped(key)
        w.write(if (pretty) ": " else ":")
        pendingName = true
        return this
    }

    fun value(s: String): JsonStreamWriter { beforeValue(); writeEscaped(s); return this }
    fun value(n: Long): JsonStreamWriter { beforeValue(); w.write(n.toString()); return this }
    fun value(n: Int): JsonStreamWriter { beforeValue(); w.write(n.toString()); return this }
    fun value(b: Boolean): JsonStreamWriter { beforeValue(); w.write(if (b) "true" else "false"); return this }

    fun flush() { w.flush() }
    override fun close() { w.close() }

    private fun beforeValue() {
        if (pendingName) { pendingName = false; return }
        if (stack.isEmpty()) return
        when (stack.last()) {
            Scope.ARR_EMPTY -> stack[stack.lastIndex] = Scope.ARR_FILLED
            Scope.ARR_FILLED -> { w.write(","); if (pretty) newline() }
            else -> error("Nilai tanpa name() di dalam objek")
        }
    }

    private fun newline() {
        w.write("\n")
        repeat(stack.size * 2) { w.write(" ") }
    }

    private fun writeEscaped(s: String) {
        w.write("\"")
        for (ch in s) {
            when (ch) {
                '"' -> w.write("\\\"")
                '\\' -> w.write("\\\\")
                '\b' -> w.write("\\b")
                '\u000c' -> w.write("\\f")
                '\n' -> w.write("\\n")
                '\r' -> w.write("\\r")
                '\t' -> w.write("\\t")
                else -> if (ch.code < 0x20) {
                    w.write("\\u%04x".format(ch.code))
                } else {
                    w.write(ch.code)
                }
            }
        }
        w.write("\"")
    }
}

/**
 * Pengurai JSON ketat dan terikat batas ukuran/kedalaman untuk cadangan lokal dan data untrusted.
 */
object BoundedJson {
    private const val MAX_DEPTH = 16
    private const val MAX_STRING_CHARS = 1_000_000

    fun parseObject(text: String): Map<String, Any?> {
        val p = Parser(StringReader(text))
        val v = p.parseValue(0)
        p.skipWs()
        if (p.peek() != -1) error("Karakter berlebih setelah JSON.")
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: error("Akar JSON harus berupa objek.")
    }

    private class Parser(private val r: Reader) {
        private var buf: Int = r.read()

        fun peek(): Int = buf
        private fun next(): Int { val c = buf; buf = r.read(); return c }

        fun skipWs() {
            while (buf == ' '.code || buf == '\t'.code || buf == '\n'.code || buf == '\r'.code) next()
        }

        fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) error("Kedalaman JSON melebihi batas.")
            skipWs()
            return when (val c = peek()) {
                '{'.code -> parseObj(depth + 1)
                '['.code -> parseArr(depth + 1)
                '"'.code -> parseStr()
                't'.code -> { expect("true"); true }
                'f'.code -> { expect("false"); false }
                'n'.code -> { expect("null"); null }
                '-'.code, in '0'.code..'9'.code -> parseNum()
                else -> error("Token JSON tidak valid: ${if (c < 0) "EOF" else c.toChar()}")
            }
        }

        private fun parseObj(depth: Int): Map<String, Any?> {
            next() // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}'.code) { next(); return map }
            while (true) {
                skipWs()
                if (peek() != '"'.code) error("Kunci objek JSON harus string.")
                val k = parseStr()
                skipWs()
                if (next() != ':'.code) error("Diharapkan ':' setelah kunci JSON.")
                map[k] = parseValue(depth)
                skipWs()
                when (next()) {
                    ','.code -> continue
                    '}'.code -> return map
                    else -> error("Diharapkan ',' atau '}' pada objek JSON.")
                }
            }
        }

        private fun parseArr(depth: Int): List<Any?> {
            next() // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']'.code) { next(); return list }
            while (true) {
                list += parseValue(depth)
                skipWs()
                when (next()) {
                    ','.code -> continue
                    ']'.code -> return list
                    else -> error("Diharapkan ',' atau ']' pada array JSON.")
                }
            }
        }

        private fun parseStr(): String {
            next() // '"'
            val sb = StringBuilder()
            while (true) {
                val c = next()
                if (c < 0) error("String JSON tidak tertutup.")
                if (c == '"'.code) return sb.toString()
                if (sb.length >= MAX_STRING_CHARS) error("String JSON melebihi batas.")
                if (c == '\\'.code) {
                    val esc = next()
                    when (esc) {
                        '"'.code -> sb.append('"')
                        '\\'.code -> sb.append('\\')
                        '/'.code -> sb.append('/')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000c')
                        'n'.code -> sb.append('\n')
                        'r'.code -> sb.append('\r')
                        't'.code -> sb.append('\t')
                        'u'.code -> {
                            var code = 0
                            repeat(4) {
                                val h = next()
                                val v = when (h) {
                                    in '0'.code..'9'.code -> h - '0'.code
                                    in 'a'.code..'f'.code -> 10 + h - 'a'.code
                                    in 'A'.code..'F'.code -> 10 + h - 'A'.code
                                    else -> error("Escape unicode JSON tidak valid.")
                                }
                                code = (code shl 4) or v
                            }
                            sb.append(code.toChar())
                        }
                        else -> error("Escape JSON tidak dikenal.")
                    }
                } else {
                    if (c < 0x20) error("Karakter kontrol mentah di string JSON.")
                    sb.append(c.toChar())
                }
            }
        }

        private fun parseNum(): Number {
            val sb = StringBuilder()
            var isFloat = false
            if (peek() == '-'.code) sb.append(next().toChar())
            while (peek() in '0'.code..'9'.code) sb.append(next().toChar())
            if (peek() == '.'.code) {
                isFloat = true; sb.append(next().toChar())
                while (peek() in '0'.code..'9'.code) sb.append(next().toChar())
            }
            if (peek() == 'e'.code || peek() == 'E'.code) {
                isFloat = true; sb.append(next().toChar())
                if (peek() == '+'.code || peek() == '-'.code) sb.append(next().toChar())
                while (peek() in '0'.code..'9'.code) sb.append(next().toChar())
            }
            val s = sb.toString()
            if (isFloat) {
                val d = s.toDouble()
                if (!d.isFinite()) error("Angka JSON tidak valid.")
                return d
            }
            return s.toLongOrNull() ?: s.toDouble().also { if (!it.isFinite()) error("Angka JSON di luar batas.") }
        }

        private fun expect(lit: String) {
            for (ch in lit) if (next() != ch.code) error("Literal JSON tidak valid.")
        }
    }
}
