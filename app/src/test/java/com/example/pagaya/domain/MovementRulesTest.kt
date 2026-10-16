package com.example.pagaya.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MovementRulesTest {

    private val today = LocalDate.of(2026, 10, 5)

    private fun amount(text: String): Long {
        val result = MovementRules.parseAmount(text)
        assertTrue("Se esperaba un importe válido para '$text': $result", result is AmountParse.Valid)
        return (result as AmountParse.Valid).amountCop
    }

    private fun amountError(text: String): AmountError {
        val result = MovementRules.parseAmount(text)
        assertTrue("Se esperaba rechazo para '$text': $result", result is AmountParse.Invalid)
        return (result as AmountParse.Invalid).error
    }

    @Test
    fun acceptsPlainDigitsAndColombianGrouping() {
        assertEquals(20_000L, amount("20000"))
        assertEquals(20_000L, amount("20.000"))
        assertEquals(20_000L, amount("$20.000"))
        assertEquals(20_000L, amount("  $ 20.000  "))
        assertEquals(1L, amount("1"))
        assertEquals(1_234_567L, amount("1.234.567"))
        assertEquals(1_234_567L, amount("1234567"))
        assertEquals(MovementRules.AMOUNT_MAX, amount("999.999.999"))
        assertEquals(MovementRules.AMOUNT_MAX, amount("999999999"))
    }

    @Test
    fun rejectsEmptyZeroAndNegativeAsNotPositive() {
        for (text in listOf("", "   ", "$", "0", "000", "-1", "-20.000", "−5", "-")) {
            assertEquals("'$text'", AmountError.NOT_POSITIVE, amountError(text))
        }
    }

    @Test
    fun rejectsFractionsInsteadOfTurningThemIntoOtherAmounts() {
        // «20,5» y «20.5» no son 205 ni 20.500; «20.000,50» no es 2.000.050 ni 20.000.
        for (text in listOf("20,5", "20.5", "20.50", "20.000,50", "1,000", "1,000.50", "20,000", "2.5000", "1.00", "0.000", "1..000", ".5", "5.")) {
            assertEquals("'$text'", AmountError.NOT_WHOLE, amountError(text))
        }
    }

    @Test
    fun rejectsTextExponentsSignsAndLeadingZeros() {
        for (text in listOf("abc", "1e5", "1E3", "20 000", "20k", "+5", "٣٠", "0x10", "007", "1_000", "NaN", "--5")) {
            val error = amountError(text)
            assertTrue("'$text' → $error", error == AmountError.INVALID || error == AmountError.NOT_POSITIVE)
        }
        assertEquals(AmountError.INVALID, amountError("1e5"))
        assertEquals(AmountError.INVALID, amountError("+5"))
        assertEquals(AmountError.INVALID, amountError("007"))
    }

    @Test
    fun rejectsAmountsAboveTheLimitWithoutOverflowing() {
        assertEquals(AmountError.TOO_LARGE, amountError("1.000.000.000"))
        assertEquals(AmountError.TOO_LARGE, amountError("1000000000"))
        assertEquals(AmountError.TOO_LARGE, amountError("9223372036854775807"))
        assertEquals(AmountError.TOO_LARGE, amountError("9223372036854775808"))
        assertEquals(AmountError.TOO_LARGE, amountError("99999999999999999999999999"))
    }

    @Test
    fun validatesNumericAmounts() {
        assertNull(MovementRules.validateAmount(1))
        assertNull(MovementRules.validateAmount(MovementRules.AMOUNT_MAX))
        assertEquals(AmountError.NOT_POSITIVE, MovementRules.validateAmount(0))
        assertEquals(AmountError.NOT_POSITIVE, MovementRules.validateAmount(-1))
        assertEquals(AmountError.TOO_LARGE, MovementRules.validateAmount(MovementRules.AMOUNT_MAX + 1))
    }

    @Test
    fun dateMustBeBetween1900AndToday() {
        assertNull(MovementRules.validateDate(today, today))
        assertNull(MovementRules.validateDate(today.minusDays(1), today))
        assertNull(MovementRules.validateDate(LocalDate.of(1900, 1, 1), today))
        assertEquals(DateError.TOO_OLD, MovementRules.validateDate(LocalDate.of(1899, 12, 31), today))
        assertEquals(DateError.FUTURE, MovementRules.validateDate(today.plusDays(1), today))
    }

    @Test
    fun todayIsTheCivilDayInBogotaNotInUtc() {
        // 2026-10-06 02:00 UTC es todavía el 5 de octubre a las 21:00 en Bogotá (UTC-5).
        val clock = Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 5), BusinessCalendar.today(clock))
        val noon = Clock.fixed(Instant.parse("2026-10-05T17:00:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 5), BusinessCalendar.today(noon))
    }

    @Test
    fun descriptionIsOptionalTrimmedAndLimitedInCodePoints() {
        fun validate(description: String) = MovementRules.validate(MovementInput("20000", today, description), today)

        assertNull(((validate("   ") as MovementValidation.Valid).movement.description))
        assertEquals("Pedido de revista", (validate("  Pedido de revista ") as MovementValidation.Valid).movement.description)
        assertTrue(validate("a".repeat(MovementRules.DESCRIPTION_MAX)) is MovementValidation.Valid)
        assertEquals(
            DescriptionError.TOO_LONG,
            (validate("a".repeat(MovementRules.DESCRIPTION_MAX + 1)) as MovementValidation.Invalid).description,
        )
        // 1000 emojis son 2000 unidades UTF-16 pero 1000 caracteres: caben, igual que en la base.
        assertTrue(validate("😀".repeat(MovementRules.DESCRIPTION_MAX)) is MovementValidation.Valid)
        assertTrue(validate("😀".repeat(MovementRules.DESCRIPTION_MAX + 1)) is MovementValidation.Invalid)
    }

    @Test
    fun validateReportsEveryProblemAtOnce() {
        val result = MovementRules.validate(MovementInput("20,5", today.plusDays(1), "x".repeat(1001)), today)
        result as MovementValidation.Invalid
        assertEquals(AmountError.NOT_WHOLE, result.amount)
        assertEquals(DateError.FUTURE, result.date)
        assertEquals(DescriptionError.TOO_LONG, result.description)
    }
}
