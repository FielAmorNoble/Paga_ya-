package com.example.pagaya.domain

import java.text.Normalizer
import java.util.Locale

/** Datos de cliente tal como los escribe la usuaria. */
data class ClientInput(val name: String, val phone: String, val note: String)

/** Datos listos para guardar: recortados, opcionales vacíos como null y clave de búsqueda derivada. */
data class ValidClient(val name: String, val searchName: String, val phone: String?, val note: String?)

enum class NameError { REQUIRED, TOO_LONG }

enum class PhoneError { TOO_LONG, INVALID }

enum class NoteError { TOO_LONG }

sealed interface ClientValidation {
    data class Valid(val client: ValidClient) : ClientValidation

    data class Invalid(val name: NameError?, val phone: PhoneError?, val note: NoteError?) : ClientValidation
}

/**
 * Reglas de cliente de docs/03 y HU-02. Las longitudes se cuentan en puntos de código Unicode,
 * igual que `length()` de SQLite en las restricciones de la base.
 */
object ClientRules {
    const val NAME_MAX = 120
    const val PHONE_MAX = 30
    const val NOTE_MAX = 1000

    // Dígitos, +, espacios y separadores habituales; al menos un dígito.
    private val phoneCharacters = Regex("[0-9+()\\-. ]+")
    private val combiningMarks = Regex("\\p{Mn}+")
    private val whitespaceRuns = Regex("\\s+")

    fun validate(input: ClientInput): ClientValidation {
        val name = input.name.trim()
        val phone = input.phone.trim().ifEmpty { null }
        val note = input.note.trim().ifEmpty { null }

        val nameError = when {
            name.isEmpty() -> NameError.REQUIRED
            name.codePointLength() > NAME_MAX -> NameError.TOO_LONG
            else -> null
        }
        val phoneError = when {
            phone == null -> null
            phone.codePointLength() > PHONE_MAX -> PhoneError.TOO_LONG
            !phoneCharacters.matches(phone) || phone.none(Char::isDigit) -> PhoneError.INVALID
            else -> null
        }
        val noteError = if (note != null && note.codePointLength() > NOTE_MAX) NoteError.TOO_LONG else null

        return if (nameError == null && phoneError == null && noteError == null) {
            ClientValidation.Valid(ValidClient(name, searchKey(name), phone, note))
        } else {
            ClientValidation.Invalid(nameError, phoneError, noteError)
        }
    }

    /**
     * Clave de búsqueda: minúsculas estables, sin marcas diacríticas y con espacios internos
     * colapsados. Se aplica igual al nombre guardado y a la consulta («María» y «maria» coinciden).
     */
    fun searchKey(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .replace(whitespaceRuns, " ")
            .trim()

    /** Fragmento para `LIKE '%' || ? || '%' ESCAPE '\'`: `%`, `_` y `\` se buscan como texto literal. */
    fun likeFragment(query: String): String =
        searchKey(query).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun String.codePointLength(): Int = codePointCount(0, length)
}
