package com.example.pagaya.data.backup

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Lector JSON por streaming y estricto para el contrato del respaldo (docs/19):
 * - UTF-8 válido sin BOM (una secuencia mal formada es un error, no un carácter de reemplazo);
 * - números solo enteros `-?(0|[1-9][0-9]*)` dentro de `Long`: sin fracciones, exponentes, NaN ni infinitos;
 * - claves repetidas en un mismo objeto rechazadas;
 * - profundidad de anidamiento limitada a [maxDepth];
 * - cadenas sin caracteres de control sin escapar ni sustitutos UTF-16 sueltos;
 * - cada cadena con un máximo de puntos de código que fija quien la pide: se rechaza en cuanto lo supera,
 *   sin terminar de construirla (claves: [MAX_KEY_LENGTH]; valores genéricos: [maxAnyString]);
 * - nada después del valor raíz salvo espacios JSON.
 *
 * Lee del flujo a medida que se piden valores, sin cargar el documento como texto; lo que el llamador
 * conserve de esos valores es responsabilidad suya. Cualquier error lanza [BackupInvalidException]; los
 * errores de lectura del flujo se propagan tal cual.
 */
internal class StrictJsonReader(
    input: InputStream,
    private val maxDepth: Int,
    private val maxAnyString: Int = DEFAULT_MAX_ANY_STRING,
    private val fieldStringLimits: Map<String, Int> = emptyMap(),
) {

    private val reader: Reader = InputStreamReader(
        input,
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT),
    )
    private val buffer = CharArray(8192)
    private var pos = 0
    private var limit = 0
    private var started = false
    private var rootRead = false

    private class Frame(val isObject: Boolean) {
        var count = 0
        var nameRead = false
        val keys = HashSet<String>()
    }

    private val frames = ArrayList<Frame>()

    // ---- contenedores ----------------------------------------------------------------------

    fun beginObject() {
        beforeValue()
        expect('{', "un objeto")
        push(Frame(isObject = true))
    }

    fun endObject() {
        val frame = top()
        if (!frame.isObject || frame.nameRead) syntax("cierre de objeto inesperado")
        skipSpace()
        expect('}', "'}'")
        frames.removeAt(frames.size - 1)
    }

    fun beginArray() {
        beforeValue()
        expect('[', "una lista")
        push(Frame(isObject = false))
    }

    fun endArray() {
        val frame = top()
        if (frame.isObject) syntax("cierre de lista inesperado")
        skipSpace()
        expect(']', "']'")
        frames.removeAt(frames.size - 1)
    }

    /** ¿Queda otro elemento (o clave) en el contenedor actual? */
    fun hasNext(): Boolean {
        val frame = top()
        if (frame.nameRead) syntax("falta el valor de una clave")
        skipSpace()
        val c = peek()
        return c != (if (frame.isObject) '}' else ']')
    }

    /** Siguiente clave del objeto actual; una clave repetida es un error. */
    fun nextName(): String {
        val frame = top()
        if (!frame.isObject || frame.nameRead) syntax("se esperaba un valor, no una clave")
        separator(frame)
        if (peek() != '"') syntax("se esperaba una clave")
        val name = readString(MAX_KEY_LENGTH, BackupProblem.TYPE, "clave")
        skipSpace()
        expect(':', "':'")
        if (!frame.keys.add(name)) throw BackupInvalidException(BackupProblem.DUPLICATE_KEY, "clave repetida «$name»")
        frame.nameRead = true
        frame.count++
        return name
    }

    // ---- valores ---------------------------------------------------------------------------

    /** Texto de hasta [maxLength] puntos de código; uno más largo se rechaza con [overflow] al alcanzar el límite. */
    fun nextString(maxLength: Int, overflow: BackupProblem, what: String): String {
        beforeValue()
        if (peek() != '"') type("texto")
        return readString(maxLength, overflow, what)
    }

    /** Texto o `null`; ningún otro tipo. */
    fun nextStringOrNull(maxLength: Int, overflow: BackupProblem, what: String): String? {
        beforeValue()
        return when (peek()) {
            '"' -> readString(maxLength, overflow, what)
            'n' -> {
                literal("null")
                null
            }
            else -> type("texto o null")
        }
    }

    fun nextLong(): Long {
        beforeValue()
        val c = peek()
        if (c != '-' && c !in '0'..'9') type("un entero")
        return readInteger()
    }

    fun nextBoolean(): Boolean {
        beforeValue()
        return when (peek()) {
            't' -> {
                literal("true")
                true
            }
            'f' -> {
                literal("false")
                false
            }
            else -> type("true o false")
        }
    }

    /**
     * Cualquier valor JSON, para leer el manifiesto antes de saber si su versión es compatible:
     * objetos como mapas, listas, texto, `Boolean`, `null` y números como [JsonNumber] sin interpretar.
     */
    fun nextAny(stringLimit: Int = maxAnyString, stringOverflow: BackupProblem = BackupProblem.LIMIT_EXCEEDED): Any? {
        beforeValue()
        return when (peek()) {
            '{' -> {
                expect('{', "un objeto")
                push(Frame(isObject = true))
                val map = LinkedHashMap<String, Any?>()
                while (hasNext()) {
                    val key = nextName()
                    val fieldLimit = fieldStringLimits[key]
                    map[key] = if (fieldLimit != null) nextAny(fieldLimit, BackupProblem.TYPE) else nextAny()
                }
                endObject()
                map
            }
            '[' -> {
                expect('[', "una lista")
                push(Frame(isObject = false))
                val list = ArrayList<Any?>()
                while (hasNext()) list.add(nextAny())
                endArray()
                list
            }
            '"' -> readString(stringLimit, stringOverflow, "texto")
            't' -> literal("true").let { true }
            'f' -> literal("false").let { false }
            'n' -> literal("null").let { null }
            else -> JsonNumber(readNumberText())
        }
    }

    /** Exige que no quede nada después del valor raíz salvo espacios. */
    fun endDocument() {
        if (frames.isNotEmpty() || !rootRead) syntax("documento incompleto")
        skipSpace()
        if (fill() && pos < limit) syntax("contenido sobrante después del documento")
    }

    // ---- interno ---------------------------------------------------------------------------

    private fun push(frame: Frame) {
        if (frames.size + 1 > maxDepth) {
            throw BackupInvalidException(BackupProblem.LIMIT_EXCEEDED, "profundidad JSON mayor que $maxDepth")
        }
        frames.add(frame)
    }

    private fun top(): Frame = frames.lastOrNull() ?: syntax("fuera de un contenedor")

    /** Consume la coma entre elementos; una coma final es un error. */
    private fun separator(frame: Frame) {
        skipSpace()
        if (frame.count > 0) {
            expect(',', "','")
            skipSpace()
        }
        val closer = if (frame.isObject) '}' else ']'
        if (peek() == closer) syntax("se esperaba otro elemento")
    }

    private fun beforeValue() {
        if (!started) {
            started = true
            checkBom()
        }
        val frame = frames.lastOrNull()
        when {
            frame == null -> {
                if (rootRead) syntax("más de un valor raíz")
                rootRead = true
            }
            frame.isObject -> {
                if (!frame.nameRead) syntax("se esperaba una clave")
                frame.nameRead = false
            }
            else -> {
                separator(frame)
                frame.count++
            }
        }
        skipSpace()
    }

    private fun checkBom() {
        if (fill() && buffer[pos] == BOM) {
            throw BackupInvalidException(BackupProblem.ENCODING, "el JSON empieza con BOM")
        }
    }

    /**
     * Lee una cadena validando Unicode a medida que avanza. Cuenta puntos de código (un par sustituto es
     * uno, como `length()` de SQLite) y se detiene en cuanto supera [maxLength]: una cadena enorme se
     * rechaza tras decodificar el primer punto que excede el límite, sin construirla completa. La entrada
     * puede ocupar hasta 12 caracteres por punto (`\uXXXX\uXXXX`), más la lectura anticipada acotada
     * de los búferes UTF-8 y de caracteres.
     */
    private fun readString(maxLength: Int, overflow: BackupProblem, what: String): String {
        expect('"', "texto")
        val out = StringBuilder()
        var codePoints = 0
        var pendingHigh = false

        fun add(ch: Char) {
            if (pendingHigh) {
                if (!Character.isLowSurrogate(ch)) unpaired()
                pendingHigh = false
            } else {
                if (Character.isLowSurrogate(ch)) unpaired()
                pendingHigh = Character.isHighSurrogate(ch)
                if (++codePoints > maxLength) {
                    throw BackupInvalidException(overflow, "$what de más de $maxLength caracteres")
                }
            }
            out.append(ch)
        }

        while (true) {
            val c = next()
            when {
                c == '"' -> break
                c == '\\' -> when (val e = next()) {
                    '"' -> add('"')
                    '\\' -> add('\\')
                    '/' -> add('/')
                    'b' -> add('\b')
                    'f' -> add('\u000C')
                    'n' -> add('\n')
                    'r' -> add('\r')
                    't' -> add('\t')
                    'u' -> {
                        var code = 0
                        repeat(4) {
                            // JSON solo admite dígitos hexadecimales ASCII; Character.digit también
                            // aceptaría cifras Unicode como las de ancho completo.
                            val digit = when (val hex = next()) {
                                in '0'..'9' -> hex - '0'
                                in 'a'..'f' -> hex - 'a' + 10
                                in 'A'..'F' -> hex - 'A' + 10
                                else -> -1
                            }
                            if (digit < 0) syntax("escape \\u inválido")
                            code = code * 16 + digit
                        }
                        add(code.toChar())
                    }
                    else -> syntax("escape desconocido \\$e")
                }
                c < ' ' -> syntax("carácter de control sin escapar")
                else -> add(c)
            }
        }
        if (pendingHigh) unpaired()
        return out.toString()
    }

    // Un sustituto UTF-16 suelto (p. ej. "\ud800") no es texto Unicode válido.
    private fun unpaired(): Nothing = throw BackupInvalidException(BackupProblem.ENCODING, "texto con un sustituto UTF-16 suelto")

    private fun readInteger(): Long {
        val text = readNumberText()
        if (!INTEGER.matches(text)) type("un entero sin decimales ni exponente")
        return text.toLongOrNull() ?: throw BackupInvalidException(BackupProblem.TYPE, "entero fuera de rango")
    }

    /** Texto de un número con la gramática JSON completa; [readInteger] decide si es entero. */
    private fun readNumberText(): String {
        val out = StringBuilder()
        while (fill() && pos < limit) {
            val c = buffer[pos]
            if (c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                out.append(c)
                pos++
                if (out.length > 400) syntax("número demasiado largo")
            } else {
                break
            }
        }
        if (!NUMBER.matches(out)) syntax("número JSON inválido")
        return out.toString()
    }

    private fun literal(word: String) {
        for (expected in word) if (next() != expected) syntax("valor desconocido")
        // "nullx" o "true1" no son literales válidos.
        if (fill() && pos < limit && buffer[pos].isLetterOrDigit()) syntax("valor desconocido")
    }

    private fun skipSpace() {
        while (fill() && pos < limit) {
            when (buffer[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }

    private fun expect(c: Char, what: String) {
        if (next() != c) syntax("se esperaba $what")
    }

    private fun peek(): Char {
        if (!fill() || pos >= limit) syntax("el JSON termina antes de tiempo")
        return buffer[pos]
    }

    private fun next(): Char = peek().also { pos++ }

    /** Garantiza datos en el búfer; `false` al final del flujo. */
    private fun fill(): Boolean {
        if (pos < limit) return true
        val n = try {
            reader.read(buffer, 0, buffer.size)
        } catch (e: CharacterCodingException) {
            throw BackupInvalidException(BackupProblem.ENCODING, "UTF-8 inválido")
        }
        if (n <= 0) return false
        pos = 0
        limit = n
        return true
    }

    private fun syntax(message: String): Nothing = throw BackupInvalidException(BackupProblem.JSON_SYNTAX, message)

    private fun type(expected: String): Nothing = throw BackupInvalidException(BackupProblem.TYPE, "se esperaba $expected")

    companion object {
        /** Ninguna clave del contrato pasa de 24 caracteres. */
        const val MAX_KEY_LENGTH = 64

        /** Textos de la lectura genérica (manifiesto, que además está limitado a 64 KiB). */
        const val DEFAULT_MAX_ANY_STRING = 1024

        private val BOM = 0xFEFF.toChar()
        private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}

/** Número JSON leído sin interpretar (solo en la lectura genérica del manifiesto). */
internal data class JsonNumber(val text: String)
