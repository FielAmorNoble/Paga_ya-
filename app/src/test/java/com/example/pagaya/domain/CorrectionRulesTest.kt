package com.example.pagaya.domain

import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Reglas de corrección y anulación: motivo, origen y validación de la secuencia cronológica. */
class CorrectionRulesTest {

    private fun entry(
        id: String,
        day: Int,
        type: MovementType,
        amount: Long,
        order: Long,
        active: Boolean = true,
        origin: MovementOrigin = MovementOrigin.MANUAL,
    ) = LedgerEntry(id, LocalDate.of(2026, 10, day), order, type, origin, amount, null, active)

    private fun fiado(id: String, day: Int, amount: Long, order: Long) = entry(id, day, MovementType.FIADO, amount, order)

    private fun abono(id: String, day: Int, amount: Long, order: Long) = entry(id, day, MovementType.ABONO, amount, order)

    // Ejemplo del modelo: fiado de 60.000 y abono de 40.000.
    private val history = listOf(fiado("f", 1, 60_000, 1), abono("a", 3, 40_000, 2))

    // ---- motivo y origen -----------------------------------------------------------------

    @Test
    fun reasonIsRequiredTrimmedAndLimitedToThreeHundredCharacters() {
        assertEquals(ReasonError.REQUIRED, MovementRules.validateReason(""))
        assertEquals(ReasonError.REQUIRED, MovementRules.validateReason("  \t\n "))
        assertNull(MovementRules.validateReason("  Me equivoqué  "))
        assertNull(MovementRules.validateReason("a".repeat(300)))
        assertEquals(ReasonError.TOO_LONG, MovementRules.validateReason("a".repeat(301)))
        // 300 emojis son 600 unidades UTF-16 pero 300 caracteres, igual que length() de SQLite.
        assertNull(MovementRules.validateReason("😀".repeat(300)))
        assertEquals(ReasonError.TOO_LONG, MovementRules.validateReason("😀".repeat(301)))
        // El espacio sobrante no cuenta: 300 caracteres rodeados de espacios son válidos.
        assertNull(MovementRules.validateReason(" " + "a".repeat(300) + " "))
    }

    @Test
    fun correctingTheAmountOfAFullPaymentTurnsItIntoAManualAbono() {
        assertEquals(MovementOrigin.MANUAL, MovementRules.originAfterCorrection(MovementOrigin.PAGO_TOTAL, 25_000, 24_000))
        // Cambiar solo la fecha o la nota (mismo importe) no cambia el origen.
        assertEquals(MovementOrigin.PAGO_TOTAL, MovementRules.originAfterCorrection(MovementOrigin.PAGO_TOTAL, 25_000, 25_000))
        assertEquals(MovementOrigin.MANUAL, MovementRules.originAfterCorrection(MovementOrigin.MANUAL, 25_000, 24_000))
    }

    // ---- corregir ------------------------------------------------------------------------

    @Test
    fun reducingTheFiadoBelowItsAbonosIsRejectedNamingTheUncoveredAbono() {
        val rejection = LedgerRules.rejectionForChange(history, history[0].copy(amountCop = 30_000))
        assertEquals(HistoryRejection.UncoveredAbono(history[1]), rejection)
        // Reducirlo justo hasta cubrir el abono sí se puede.
        assertNull(LedgerRules.rejectionForChange(history, history[0].copy(amountCop = 40_000)))
    }

    @Test
    fun movingTheFiadoAfterItsAbonoIsRejected() {
        val rejection = LedgerRules.rejectionForChange(history, history[0].copy(date = LocalDate.of(2026, 10, 5)))
        assertEquals(HistoryRejection.UncoveredAbono(history[1]), rejection)
    }

    @Test
    fun increasingTheFiadoOrMovingItEarlierIsAlwaysAllowed() {
        assertNull(LedgerRules.rejectionForChange(history, history[0].copy(amountCop = 90_000)))
        assertNull(LedgerRules.rejectionForChange(history, history[0].copy(date = LocalDate.of(2026, 9, 1))))
    }

    @Test
    fun anAbonoCannotBeCorrectedAboveWhatTheAccountOwesWithoutIt() {
        assertEquals(HistoryRejection.ExceedsBalance(60_000), LedgerRules.rejectionForChange(history, history[1].copy(amountCop = 60_001)))
        assertNull(LedgerRules.rejectionForChange(history, history[1].copy(amountCop = 60_000)))
        assertNull(LedgerRules.rejectionForChange(history, history[1].copy(amountCop = 1)))
    }

    @Test
    fun anAbonoCannotBeMovedBeforeItsDebt() {
        val rejection = LedgerRules.rejectionForChange(history, history[1].copy(date = LocalDate.of(2026, 9, 30)))
        assertEquals(HistoryRejection.NegativeAtDate(atNewEntry = true), rejection)
        // El mismo día de la deuda sí: el orden de registro la deja primero.
        assertNull(LedgerRules.rejectionForChange(history, history[1].copy(date = LocalDate.of(2026, 10, 1))))
    }

    @Test
    fun correctingAnAbonoOfAnAccountWhoseOnlyDebtIsItselfReportsNoDebt() {
        val onlyAbono = listOf(abono("a", 3, 10_000, 1))
        assertEquals(HistoryRejection.NoDebt, LedgerRules.rejectionForChange(onlyAbono, onlyAbono[0].copy(amountCop = 5_000)))
    }

    @Test
    fun correctionRejectsAnIntermediateNegativeBalanceEvenWhenTheFinalBalanceIsPositive() {
        // Fiado 100k (1 oct), abono 100k (4 oct), fiado 50k (5 oct): saldo final 50k.
        val h = listOf(fiado("f1", 1, 100_000, 1), abono("a", 4, 100_000, 2), fiado("f2", 5, 50_000, 3))
        assertEquals(50_000L, LedgerRules.balance(h))
        // Reducir el primer fiado a 90k deja el saldo final en 40k, pero el 4 de oct. quedaría en -10k.
        val rejection = LedgerRules.rejectionForChange(h, h[0].copy(amountCop = 90_000))
        assertEquals(HistoryRejection.UncoveredAbono(h[1]), rejection)
    }

    @Test
    fun overflowOnCorrectionIsDetected() {
        val h = listOf(fiado("f1", 1, Long.MAX_VALUE - 5, 1), fiado("f2", 2, 3, 2))
        assertEquals(HistoryRejection.Overflow, LedgerRules.rejectionForChange(h, h[1].copy(amountCop = 10)))
    }

    // ---- anular --------------------------------------------------------------------------

    @Test
    fun annullingTheFiadoIsRejectedWhileAnAbonoDependsOnIt() {
        assertEquals(HistoryRejection.UncoveredAbono(history[1]), LedgerRules.rejectionForRemoval(history, "f"))
    }

    @Test
    fun annullingAnAbonoIsAlwaysAllowedAndIncreasesTheDebt() {
        assertNull(LedgerRules.rejectionForRemoval(history, "a"))
        assertEquals(60_000L, LedgerRules.balance(history.filter { it.id != "a" }))
    }

    @Test
    fun annullingAFiadoWithoutAbonosIsAllowedAndReducesTheDebt() {
        val h = listOf(fiado("f1", 1, 60_000, 1), fiado("f2", 2, 20_000, 2), abono("a", 3, 50_000, 3))
        // Anular f2 deja 60k − 50k = 10k: válido. Anular f1 deja 20k − 50k < 0: inválido.
        assertNull(LedgerRules.rejectionForRemoval(h, "f2"))
        assertEquals(HistoryRejection.UncoveredAbono(h[2]), LedgerRules.rejectionForRemoval(h, "f1"))
        assertEquals(10_000L, LedgerRules.balance(h.filter { it.id != "f2" }))
    }

    // ---- historial con anulados ----------------------------------------------------------

    @Test
    fun annulledEntriesStayInTheHistoryWithoutBalanceAndDoNotCountForOthers() {
        val h = listOf(
            fiado("f", 1, 60_000, 1),
            abono("a", 3, 40_000, 2).copy(active = false),
            abono("b", 5, 15_000, 3),
        )
        val rows = LedgerRules.historyRecentFirst(h)
        assertEquals(listOf("b", "a", "f"), rows.map { it.entry.id })
        // El abono anulado no figura en el saldo: 60.000 → 45.000.
        assertEquals(listOf(45_000L, null, 60_000L), rows.map { it.balanceAfterCop })
        assertTrue(rows[1].entry.active.not())
    }

    @Test
    fun annulledEntriesDoNotParticipateInPrefixChecks() {
        val h = listOf(fiado("f", 1, 10_000, 1), abono("a", 2, 10_000, 2, ).copy(active = false))
        // Con el abono anulado la cuenta debe 10.000 y un abono de 10.000 es válido.
        val active = h.filter { it.active }
        assertNull(LedgerRules.rejectionFor(active, abono("n", 3, 10_000, 3)))
    }
}
