package com.example.pagaya.domain

import com.example.pagaya.data.local.MovementOrigin
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Día civil del negocio: siempre America/Bogota, sin importar la zona del teléfono (docs/03). */
object BusinessCalendar {
    val ZONE: ZoneId = ZoneId.of("America/Bogota")

    fun today(clock: Clock): LocalDate = LocalDate.now(clock.withZone(ZONE))
}

/** Datos de un movimiento tal como los escribe la usuaria: el importe aún es texto. */
data class MovementInput(val amount: String, val date: LocalDate, val description: String)

/** Datos listos para guardar: importe entero válido, fecha admitida y descripción recortada (o null). */
data class ValidMovement(val amountCop: Long, val date: LocalDate, val description: String?)

enum class AmountError {
    /** Vacío, cero o negativo. */
    NOT_POSITIVE,

    /** Decimales o agrupaciones ambiguas: nunca se convierten en otro importe. */
    NOT_WHOLE,

    /** Letras, exponentes u otros caracteres. */
    INVALID,

    TOO_LARGE,
}

enum class DateError { TOO_OLD, FUTURE }

enum class DescriptionError { TOO_LONG }

enum class ReasonError { REQUIRED, TOO_LONG }

sealed interface AmountParse {
    data class Valid(val amountCop: Long) : AmountParse

    data class Invalid(val error: AmountError) : AmountParse
}

sealed interface MovementValidation {
    data class Valid(val movement: ValidMovement) : MovementValidation

    data class Invalid(
        val amount: AmountError?,
        val date: DateError?,
        val description: DescriptionError?,
    ) : MovementValidation
}

/** Corrección tal como la escribe la usuaria: importe, fecha y descripción nuevos y un motivo. */
data class CorrectionInput(val amount: String, val date: LocalDate, val description: String, val reason: String)

/** Valores corregidos válidos. [reason] ya está recortado, entre 1 y 300 caracteres. */
data class ValidCorrection(val movement: ValidMovement, val reason: String)

/**
 * Reglas de importe, fecha y descripción de un movimiento (RN-02, RN-03, RN-11; docs/03 y 06).
 * Kotlin puro y aritmética entera: no usa `Float` ni `Double`.
 */
object MovementRules {
    const val AMOUNT_MIN = 1L
    const val AMOUNT_MAX = 999_999_999L
    const val DESCRIPTION_MAX = 1000
    const val REASON_MAX = 300

    /** Fecha económica más antigua admitida. */
    val MIN_DATE: LocalDate = LocalDate.of(1900, 1, 1)

    private val ZEROS = Regex("0+")
    private val PLAIN_DIGITS = Regex("[1-9][0-9]*")

    // Agrupación colombiana inequívoca: grupos de tres dígitos separados por punto.
    private val GROUPED = Regex("[1-9][0-9]{0,2}(\\.[0-9]{3})+")
    private val DIGITS_AND_SEPARATORS = Regex("[0-9.,]+")
    private val MAX_DIGITS = AMOUNT_MAX.toString().length

    /**
     * Interpreta un importe en pesos completos. Acepta solo dígitos (`20000`) o agrupación con
     * puntos inequívoca (`20.000`, `$20.000`). Rechaza `20,5`, `20.5`, `20.000,50`, `1,000`,
     * exponentes, signos y ceros a la izquierda: nunca reinterpreta una fracción como miles.
     */
    fun parseAmount(text: String): AmountParse {
        var s = text.trim()
        if (s.startsWith("$")) s = s.drop(1).trim()
        if (s.isEmpty()) return invalid(AmountError.NOT_POSITIVE)
        if (s.startsWith("-") || s.startsWith("−")) return invalid(AmountError.NOT_POSITIVE)

        if (!DIGITS_AND_SEPARATORS.matches(s)) return invalid(AmountError.INVALID)
        val digits: String
        when {
            PLAIN_DIGITS.matches(s) -> digits = s
            GROUPED.matches(s) -> digits = s.replace(".", "")
            ZEROS.matches(s) -> return invalid(AmountError.NOT_POSITIVE)
            // Con coma, o con puntos mal agrupados, no se puede saber si es fracción o miles.
            s.any { it == ',' || it == '.' } -> return invalid(AmountError.NOT_WHOLE)
            else -> return invalid(AmountError.INVALID) // ceros a la izquierda
        }
        // Se compara la longitud antes de convertir: un texto enorme no debe desbordar un Long.
        if (digits.length > MAX_DIGITS) return invalid(AmountError.TOO_LARGE)
        val value = digits.toLong()
        return if (value > AMOUNT_MAX) invalid(AmountError.TOO_LARGE) else AmountParse.Valid(value)
    }

    /** Valida un importe que ya es un número (por ejemplo, el saldo del pago completo). */
    fun validateAmount(amountCop: Long): AmountError? = when {
        amountCop < AMOUNT_MIN -> AmountError.NOT_POSITIVE
        amountCop > AMOUNT_MAX -> AmountError.TOO_LARGE
        else -> null
    }

    fun validateDate(date: LocalDate, today: LocalDate): DateError? = when {
        date < MIN_DATE -> DateError.TOO_OLD
        date > today -> DateError.FUTURE
        else -> null
    }

    fun validate(input: MovementInput, today: LocalDate): MovementValidation {
        val amount = parseAmount(input.amount)
        val description = input.description.trim().ifEmpty { null }

        val amountError = (amount as? AmountParse.Invalid)?.error
        val dateError = validateDate(input.date, today)
        val descriptionError = description
            ?.takeIf { it.codePointCount(0, it.length) > DESCRIPTION_MAX }
            ?.let { DescriptionError.TOO_LONG }

        return if (amount is AmountParse.Valid && dateError == null && descriptionError == null) {
            MovementValidation.Valid(ValidMovement(amount.amountCop, input.date, description))
        } else {
            MovementValidation.Invalid(amountError, dateError, descriptionError)
        }
    }

    /** Motivo de una corrección o anulación: obligatorio, recortado, 1–300 caracteres Unicode (docs/03). */
    fun validateReason(reason: String): ReasonError? {
        val trimmed = reason.trim()
        return when {
            trimmed.isEmpty() -> ReasonError.REQUIRED
            trimmed.codePointCount(0, trimmed.length) > REASON_MAX -> ReasonError.TOO_LONG
            else -> null
        }
    }

    /**
     * Origen tras corregir el importe: editar el importe de un pago completo lo convierte en un abono
     * manual (docs/03). Cambiar solo la fecha o la nota no altera el origen.
     */
    fun originAfterCorrection(origin: MovementOrigin, oldAmountCop: Long, newAmountCop: Long): MovementOrigin =
        if (origin == MovementOrigin.PAGO_TOTAL && oldAmountCop != newAmountCop) MovementOrigin.MANUAL else origin

    private fun invalid(error: AmountError) = AmountParse.Invalid(error)
}
