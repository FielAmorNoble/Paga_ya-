package com.example.pagaya.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StrictJsonReaderTest {

    private fun read(json: ByteArray, depth: Int = 20): Any? {
        val reader = StrictJsonReader(json.inputStream(), depth)
        return reader.nextAny().also { reader.endDocument() }
    }

    private fun read(json: String, depth: Int = 20) = read(json.toByteArray(Charsets.UTF_8), depth)

    private fun assertRejected(json: String, expected: BackupProblem) = assertRejected(json.toByteArray(Charsets.UTF_8), expected)

    private fun assertRejected(json: ByteArray, expected: BackupProblem, depth: Int = 20) {
        try {
            read(json, depth)
            fail("Se esperaba $expected: ${String(json, Charsets.UTF_8)}")
        } catch (e: BackupInvalidException) {
            assertEquals(e.detail, expected, e.problem)
        }
    }

    private fun integer(json: String): Long {
        val reader = StrictJsonReader(json.byteInputStream(), 20)
        return reader.nextLong().also { reader.endDocument() }
    }

    /**
     * `prefix` + [repeat] veces [unit] + `suffix`, generado al leer: nunca existe completo en memoria.
     * [read] cuenta los bytes que el lector llegó a pedir.
     */
    private class HugeStream(prefix: String, private val unit: ByteArray, private val repeat: Long, suffix: String) : java.io.InputStream() {
        private val head = prefix.toByteArray()
        private val tail = suffix.toByteArray()
        private val total = head.size + unit.size * repeat + tail.size
        var read = 0L
            private set

        override fun read(): Int {
            if (read >= total) return -1
            val i = read++
            return when {
                i < head.size -> head[i.toInt()]
                i < head.size + unit.size * repeat -> unit[((i - head.size) % unit.size).toInt()]
                else -> tail[(i - head.size - unit.size * repeat).toInt()]
            }.toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= total) return -1
            var n = 0
            while (n < len && read < total) b[off + n++] = read().toByte()
            return n
        }
    }

    /** Ninguna lectura de una cadena rechazada puede pasar de unos pocos búferes. */
    private val earlyBytes = 64 * 1024L

    private fun assertEarly(stream: HugeStream, expected: BackupProblem, read: StrictJsonReader.() -> Unit) {
        val reader = StrictJsonReader(stream, 20)
        try {
            reader.read()
            fail("Se esperaba $expected")
        } catch (e: BackupInvalidException) {
            assertEquals(e.detail, expected, e.problem)
        }
        assertTrue("leyó ${stream.read} bytes de una cadena de 50 MB", stream.read < earlyBytes)
    }

    @Test
    fun aHugeValueIsRejectedAsSoonAsItPassesItsLimit() {
        // 50 MB de "a" dentro de un nombre de 120 caracteres como máximo.
        assertEarly(HugeStream("{\"nombre\":\"", "a".toByteArray(), 50_000_000, "\"}"), BackupProblem.INTEGRITY) {
            beginObject()
            nextName()
            nextString(120, BackupProblem.INTEGRITY, "nombre")
        }
        // El mismo texto como escapes a: seis bytes por carácter, mismo límite de caracteres.
        assertEarly(HugeStream("\"", "\\u0061".toByteArray(), 10_000_000, "\""), BackupProblem.REVISION_CHAIN) {
            nextString(8192, BackupProblem.REVISION_CHAIN, "estado_anterior_json")
        }
        assertEarly(HugeStream("\"", "a".toByteArray(), 50_000_000, "\""), BackupProblem.INTEGRITY) {
            nextStringOrNull(1000, BackupProblem.INTEGRITY, "nota")
        }
    }

    @Test
    fun aHugeKeyOrManifestTextIsRejectedEarly() {
        assertEarly(HugeStream("{\"", "k".toByteArray(), 50_000_000, "\":1}"), BackupProblem.TYPE) { nextAny() }
        assertEarly(HugeStream("{\"version_app\":\"", "1".toByteArray(), 50_000_000, "\"}"), BackupProblem.LIMIT_EXCEEDED) { nextAny() }
    }

    @Test
    fun genericObjectsCanBoundKnownManifestFieldsDuringReading() {
        val stream = HugeStream("{\"version_app\":\"", "a".toByteArray(), 50_000_000, "\"}")
        try {
            StrictJsonReader(stream, 20, fieldStringLimits = mapOf("version_app" to 100)).nextAny()
            fail("Se esperaba rechazo al leer")
        } catch (e: BackupInvalidException) {
            assertEquals(BackupProblem.TYPE, e.problem)
        }
        assertTrue(stream.read < earlyBytes)
        val reader = StrictJsonReader("{\"version_app\":null,\"x\":1}".byteInputStream(), 20,
            fieldStringLimits = mapOf("version_app" to 100))
        assertEquals(mapOf("version_app" to null, "x" to JsonNumber("1")), reader.nextAny())
        reader.endDocument()
    }

    @Test
    fun lengthLimitsCountUnicodeCodePoints() {
        fun text(json: String, max: Int) = StrictJsonReader(json.byteInputStream(), 20).nextString(max, BackupProblem.INTEGRITY, "texto")
        // 120 emoji: 240 unidades UTF-16 pero 120 puntos de código, como length() de SQLite.
        val emoji = "😀".repeat(120)
        assertEquals(emoji, text("\"$emoji\"", 120))
        assertEquals("ñ".repeat(3), text("\"\\u00f1ñ\\u00F1\"", 3))
        for (json in listOf("\"${"😀".repeat(121)}\"", "\"${"\\ud83d\\ude00".repeat(121)}\"", "\"${"a".repeat(121)}\"")) {
            try {
                text(json, 120)
                fail("Se esperaba rechazo por longitud")
            } catch (e: BackupInvalidException) {
                assertEquals(BackupProblem.INTEGRITY, e.problem)
            }
        }
    }

    @Test
    fun readsTextIntegersBooleansNullsAndNesting() {
        val value = read(""" { "a" : [1, -2, true, false, null, "ñ😀\n\"\\\u00e9"], "b": {} } """)
        assertEquals(
            mapOf("a" to listOf(JsonNumber("1"), JsonNumber("-2"), true, false, null, "ñ😀\n\"\\é"), "b" to emptyMap<String, Any?>()),
            value,
        )
        assertEquals(Long.MAX_VALUE, integer(Long.MAX_VALUE.toString()))
        assertEquals(Long.MIN_VALUE, integer(Long.MIN_VALUE.toString()))
    }

    @Test
    fun onlyPlainIntegersAreIntegers() {
        for (text in listOf("1.0", "1e3", "1E3", "-0.5", "01", "+1", "9223372036854775808", "NaN", "Infinity", "\"1\"")) {
            try {
                integer(text)
                fail("Se esperaba rechazo de $text")
            } catch (e: BackupInvalidException) {
                // esperado: tipo o sintaxis
            }
        }
    }

    @Test
    fun duplicateKeysAreRejectedAtAnyLevel() {
        assertRejected("""{"a":1,"a":1}""", BackupProblem.DUPLICATE_KEY)
        assertRejected("""{"x":{"a":1,"b":2,"a":3}}""", BackupProblem.DUPLICATE_KEY)
        // La misma clave en objetos distintos es válida.
        read("""[{"a":1},{"a":2}]""")
    }

    @Test
    fun malformedDocumentsAreRejected() {
        for (json in listOf("", "{", "{\"a\":1,}", "[1,]", "[1 2]", "{\"a\" 1}", "{a:1}", "'x'", "{} {}", "[] x", "nul", "truex", "\"a\nb\"", "\"\\x\"", "\"\\u12G4\"")) {
            try {
                read(json)
                fail("Se esperaba rechazo: $json")
            } catch (e: BackupInvalidException) {
                // esperado
            }
        }
    }

    @Test
    fun invalidUnicodeIsRejected() {
        assertRejected(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{}".toByteArray(), BackupProblem.ENCODING)
        assertRejected(byteArrayOf('"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte()), BackupProblem.ENCODING)
        assertRejected(byteArrayOf('"'.code.toByte(), 0xFF.toByte(), '"'.code.toByte()), BackupProblem.ENCODING)
        // Secuencia sobrelarga de '/' (C0 AF): UTF-8 inválido aunque "parezca" un carácter.
        assertRejected(byteArrayOf('"'.code.toByte(), 0xC0.toByte(), 0xAF.toByte(), '"'.code.toByte()), BackupProblem.ENCODING)
        assertRejected("\"\\ud800\"", BackupProblem.ENCODING)
        assertRejected("\"\\udc00x\"", BackupProblem.ENCODING)
        assertEquals("😀", read("\"\\ud83d\\ude00\""))
    }

    @Test
    fun unicodeDigitsAreNotJsonHexEscapes() {
        assertRejected("\"\\u００４１\"", BackupProblem.JSON_SYNTAX)
        assertRejected("\"\\u٠٠٤١\"", BackupProblem.JSON_SYNTAX)
    }

    @Test
    fun depthIsLimited() {
        read("[".repeat(20) + "]".repeat(20))
        assertRejected(("[".repeat(21) + "]".repeat(21)).toByteArray(), BackupProblem.LIMIT_EXCEEDED)
        assertRejected(("{\"a\":".repeat(5) + "1" + "}".repeat(5)).toByteArray(), BackupProblem.LIMIT_EXCEEDED, depth = 4)
    }
}
