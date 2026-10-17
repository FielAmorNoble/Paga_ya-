package com.example.pagaya.domain

import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.RevisionAction
import java.time.LocalDate

/**
 * Todos los campos de un movimiento en un instante. Es el contenido de `estado_anterior_json` de
 * cada revisión (docs/03: «snapshot completo de campos de Movimiento») y la base de «Ver cambios».
 */
data class MovementSnapshot(
    val id: String,
    val clientId: String,
    val date: LocalDate,
    val order: Long,
    val type: MovementType,
    val amountCop: Long,
    val description: String?,
    val origin: MovementOrigin,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
    val active: Boolean,
    val version: Long,
)

/**
 * JSON del snapshot con las claves lógicas en español de docs/03. Escritor y lector mínimos para un
 * objeto plano, sin dependencias: texto, enteros, booleanos y `null`. El lector es estricto: exige
 * todas las claves con su tipo y rechaza duplicados, de modo que un snapshot dañado no se interpreta.
 */
object SnapshotJson {
    private const val ID = "id"
    private const val CLIENT_ID = "cliente_id"
    private const val DATE = "fecha"
    private const val ORDER = "orden_registro"
    private const val TYPE = "tipo"
    private const val AMOUNT = "valor_cop"
    private const val DESCRIPTION = "descripcion"
    private const val ORIGIN = "origen"
    private const val CREATED = "creado_en"
    private const val MODIFIED = "modificado_en"
    private const val ACTIVE = "activo"
    private const val VERSION = "version"

    fun encode(s: MovementSnapshot): String = buildString {
        append('{')
        field(ID, s.id).append(',')
        field(CLIENT_ID, s.clientId).append(',')
        field(DATE, s.date.toString()).append(',')
        append('"').append(ORDER).append("\":").append(s.order).append(',')
        field(TYPE, s.type.name).append(',')
        append('"').append(AMOUNT).append("\":").append(s.amountCop).append(',')
        append('"').append(DESCRIPTION).append("\":")
        if (s.description == null) append("null") else quote(s.description)
        append(',')
        field(ORIGIN, s.origin.name).append(',')
        append('"').append(CREATED).append("\":").append(s.createdAtMillis).append(',')
        append('"').append(MODIFIED).append("\":").append(s.modifiedAtMillis).append(',')
        append('"').append(ACTIVE).append("\":").append(s.active).append(',')
        append('"').append(VERSION).append("\":").append(s.version)
        append('}')
    }

    /** @throws IllegalArgumentException si el texto no es un snapshot válido. */
    fun decode(json: String): MovementSnapshot {
        val values = Reader(json).readObject()
        fun text(key: String) = values[key] as? String ?: bad("«$key» debe ser texto")
        fun long(key: String) = values[key] as? Long ?: bad("«$key» debe ser un entero")
        return try {
            MovementSnapshot(
                id = text(ID),
                clientId = text(CLIENT_ID),
                date = LocalDate.parse(text(DATE)),
                order = long(ORDER),
                type = MovementType.valueOf(text(TYPE)),
                amountCop = long(AMOUNT),
                description = when (val d = values[DESCRIPTION]) {
                    null -> if (DESCRIPTION in values) null else bad("falta «$DESCRIPTION»")
                    is String -> d
                    else -> bad("«$DESCRIPTION» debe ser texto o null")
                },
                origin = MovementOrigin.valueOf(text(ORIGIN)),
                createdAtMillis = long(CREATED),
                modifiedAtMillis = long(MODIFIED),
                active = values[ACTIVE] as? Boolean ?: bad("«$ACTIVE» debe ser booleano"),
                version = long(VERSION),
            )
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("Fecha inválida en el snapshot", e)
        }
    }

    private fun bad(message: String): Nothing = throw IllegalArgumentException("Snapshot inválido: $message")

    private fun StringBuilder.field(key: String, value: String): StringBuilder {
        append('"').append(key).append("\":")
        quote(value)
        return this
    }

    private fun StringBuilder.quote(value: String) {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    private class Reader(private val text: String) {
        private var pos = 0

        fun readObject(): Map<String, Any?> {
            skipSpace()
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                pos++
            } else {
                while (true) {
                    skipSpace()
                    val key = readString()
                    skipSpace()
                    expect(':')
                    skipSpace()
                    if (key in result) bad("clave repetida «$key»")
                    result[key] = readValue()
                    skipSpace()
                    when (next()) {
                        ',' -> continue
                        '}' -> break
                        else -> bad("se esperaba ',' o '}'")
                    }
                }
            }
            skipSpace()
            if (pos != text.length) bad("texto sobrante tras el objeto")
            return result
        }

        private fun readValue(): Any? = when (peek()) {
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> readInteger()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) bad("valor desconocido")
            pos += word.length
            return value
        }

        private fun readInteger(): Long {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos] in '0'..'9') pos++
            return text.substring(start, pos).toLongOrNull() ?: bad("número inválido")
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) bad("cadena sin cerrar")
                when (val c = text[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) bad("escape incompleto")
                        when (val e = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (pos + 4 > text.length) bad("escape \\u incompleto")
                                out.append(text.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: bad("escape \\u inválido"))
                                pos += 4
                            }
                            else -> bad("escape desconocido \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun skipSpace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else bad("fin inesperado")

        private fun next(): Char = peek().also { pos++ }

        private fun expect(c: Char) {
            if (next() != c) bad("se esperaba '$c'")
        }
    }
}

/** Un campo que cambió entre dos estados de un movimiento, con valores tipados para presentarlos. */
sealed interface FieldChange {
    data class Amount(val before: Long, val after: Long) : FieldChange

    data class Date(val before: LocalDate, val after: LocalDate) : FieldChange

    data class Description(val before: String?, val after: String?) : FieldChange

    /** PAGO_TOTAL pasa a MANUAL cuando se corrige el importe de un pago completo. */
    data class Origin(val before: MovementOrigin, val after: MovementOrigin) : FieldChange

    /** Vigente → anulado (`true` = vigente). */
    data class Status(val before: Boolean, val after: Boolean) : FieldChange
}

/** Diferencias de valor, fecha, descripción, origen y estado. Cliente, tipo, ID y orden no cambian. */
fun MovementSnapshot.changesTo(next: MovementSnapshot): List<FieldChange> = buildList {
    if (amountCop != next.amountCop) add(FieldChange.Amount(amountCop, next.amountCop))
    if (date != next.date) add(FieldChange.Date(date, next.date))
    if (description != next.description) add(FieldChange.Description(description, next.description))
    if (origin != next.origin) add(FieldChange.Origin(origin, next.origin))
    if (active != next.active) add(FieldChange.Status(active, next.active))
}

/** Una revisión tal como se guarda: el estado anterior en JSON. */
data class RevisionRecord(
    val action: RevisionAction,
    val changedAtMillis: Long,
    val reason: String,
    val previousVersion: Long,
    val previousStateJson: String,
)

/** Un cambio explicado: qué había antes, qué hay después, cuándo y por qué. */
data class MovementChange(
    val action: RevisionAction,
    val changedAtMillis: Long,
    val reason: String,
    val before: MovementSnapshot,
    val after: MovementSnapshot,
) {
    val changes: List<FieldChange> get() = before.changesTo(after)
}

object MovementChangeLog {
    /**
     * Cambios del más reciente al más antiguo. El estado posterior de cada revisión es el snapshot
     * de la siguiente o, para la última, el movimiento actual. Comprueba la continuidad de versiones
     * (docs/13: `version` = revisiones + 1); si falta o sobra una revisión, lanza
     * [IllegalStateException] en lugar de mostrar un historial engañoso.
     *
     * @throws IllegalArgumentException si algún snapshot está dañado.
     */
    fun build(revisions: List<RevisionRecord>, current: MovementSnapshot): List<MovementChange> {
        val ordered = revisions.sortedBy { it.previousVersion }
        ordered.forEachIndexed { index, revision ->
            check(revision.previousVersion == index + 1L) { "Las revisiones no son continuas" }
        }
        check(current.version == ordered.size + 1L) { "La versión del movimiento no coincide con sus revisiones" }
        val states = ordered.map { SnapshotJson.decode(it.previousStateJson) } + current
        return ordered.mapIndexed { index, revision ->
            check(states[index].version == revision.previousVersion) { "El snapshot no corresponde a su versión" }
            MovementChange(revision.action, revision.changedAtMillis, revision.reason, states[index], states[index + 1])
        }.asReversed()
    }
}
