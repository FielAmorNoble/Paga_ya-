package com.example.pagaya.domain

import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

class LedgerRulesTest {

    private var nextOrder = 1L

    private fun entry(
        day: Int,
        type: MovementType,
        amount: Long,
        order: Long = nextOrder++,
        id: String = "m$order",
        origin: MovementOrigin = MovementOrigin.MANUAL,
    ) = LedgerEntry(id, LocalDate.of(2026, 10, day), order, type, origin, amount, null)

    private fun fiado(day: Int, amount: Long, order: Long = nextOrder++) = entry(day, MovementType.FIADO, amount, order)

    private fun abono(day: Int, amount: Long, order: Long = nextOrder++) = entry(day, MovementType.ABONO, amount, order)

    @Test
    fun referenceExampleFromTheDataModel() {
        val history = listOf(fiado(1, 60_000), abono(3, 15_000), abono(5, 20_000))
        assertEquals(25_000L, LedgerRules.balance(history))

        val rows = LedgerRules.historyRecentFirst(history)
        assertEquals(listOf(25_000L, 45_000L, 60_000L), rows.map { it.balanceAfterCop })

        val withPayment = history + entry(5, MovementType.ABONO, 25_000, origin = MovementOrigin.PAGO_TOTAL)
        assertEquals(0L, LedgerRules.balance(withPayment))
        assertEquals(HistoryCheck.Valid(0), LedgerRules.check(withPayment))
    }

    @Test
    fun emptyHistoryHasZeroBalance() {
        assertEquals(0L, LedgerRules.balance(emptyList()))
        assertEquals(HistoryCheck.Valid(0), LedgerRules.check(emptyList()))
        assertTrue(LedgerRules.historyRecentFirst(emptyList()).isEmpty())
    }

    @Test
    fun historyIsShownMostRecentFirstWithBalanceAfterEachEntry() {
        // Se entrega desordenado: el orden lo dan (fecha, orden de registro), no la lista.
        val shuffled = listOf(abono(5, 20_000, order = 3), fiado(1, 60_000, order = 1), abono(3, 15_000, order = 2))
        val rows = LedgerRules.historyRecentFirst(shuffled)
        assertEquals(listOf("m3", "m2", "m1"), rows.map { it.entry.id })
        assertEquals(listOf(25_000L, 45_000L, 60_000L), rows.map { it.balanceAfterCop })
    }

    @Test
    fun sameDayEntriesAreOrderedByRegistrationOrderAndAreStable() {
        val debt = fiado(5, 10_000, order = 7)
        val payment = abono(5, 10_000, order = 8)
        // Primero la deuda y luego el abono, sea cual sea el orden de la lista.
        assertEquals(HistoryCheck.Valid(0), LedgerRules.check(listOf(payment, debt)))
        assertEquals(listOf("m8", "m7"), LedgerRules.historyRecentFirst(listOf(debt, payment)).map { it.entry.id })
        // Registrado al revés, el mismo día, el abono queda sin deuda que cubrir.
        val reversedPayment = abono(5, 10_000, order = 7)
        val reversedDebt = fiado(5, 10_000, order = 8)
        assertEquals(HistoryCheck.NegativeBalance("m7", -10_000), LedgerRules.check(listOf(reversedDebt, reversedPayment)))
    }

    @Test
    fun checkStopsAtTheFirstNegativePrefixEvenWhenTheFinalBalanceIsPositive() {
        // Fiado el 3, abono el 1: el total es 50.000 pero el 1 de octubre el saldo sería -10.000.
        val history = listOf(fiado(3, 60_000), abono(1, 10_000))
        assertEquals(50_000L, LedgerRules.balance(history))
        assertEquals(HistoryCheck.NegativeBalance("m2", -10_000), LedgerRules.check(history))
    }

    @Test
    fun abonoWithoutDebtIsRejected() {
        val rejection = LedgerRules.rejectionFor(emptyList(), abono(5, 1_000))
        assertEquals(HistoryRejection.NoDebt, rejection)
        val paid = listOf(fiado(1, 10_000), abono(2, 10_000))
        assertEquals(HistoryRejection.NoDebt, LedgerRules.rejectionFor(paid, abono(5, 1)))
    }

    @Test
    fun abonoAboveTheBalanceIsRejectedWithTheCurrentBalance() {
        val history = listOf(fiado(1, 60_000), abono(3, 15_000))
        assertEquals(HistoryRejection.ExceedsBalance(45_000), LedgerRules.rejectionFor(history, abono(5, 45_001)))
        assertNull(LedgerRules.rejectionFor(history, abono(5, 45_000)))
    }

    @Test
    fun retroactiveAbonoBeforeTheDebtIsRejectedEvenIfTheTotalFits() {
        val history = listOf(fiado(3, 60_000))
        val rejection = LedgerRules.rejectionFor(history, abono(1, 10_000))
        assertEquals(HistoryRejection.NegativeAtDate(atNewEntry = true), rejection)
    }

    @Test
    fun retroactiveAbonoThatStarvesALaterAbonoIsRejected() {
        // Deuda 100k el 1, pagada el 5, y otra deuda de 50k el 8: el saldo final es 50k.
        val history = listOf(fiado(1, 100_000), abono(5, 100_000), fiado(8, 50_000))
        assertEquals(50_000L, LedgerRules.balance(history))
        // Un abono de 10k el día 3 cabe en el total, pero el abono del día 5 quedaría en -10k.
        val rejection = LedgerRules.rejectionFor(history, abono(3, 10_000))
        assertEquals(HistoryRejection.NegativeAtDate(atNewEntry = false), rejection)
        // Después del pago el abono sí cabe.
        assertNull(LedgerRules.rejectionFor(history, abono(9, 10_000)))
    }

    @Test
    fun fiadoIsAlwaysAcceptedAndCanBeRetroactive() {
        val history = listOf(fiado(3, 60_000), abono(5, 60_000))
        assertNull(LedgerRules.rejectionFor(history, fiado(1, 5_000)))
    }

    @Test
    fun overflowIsDetectedInsteadOfWrapping() {
        val huge = listOf(fiado(1, Long.MAX_VALUE))
        assertEquals(HistoryRejection.Overflow, LedgerRules.rejectionFor(huge, fiado(2, 1)))
        assertEquals(HistoryCheck.Overflow, LedgerRules.check(listOf(fiado(1, Long.MAX_VALUE), fiado(2, 1))))
        var thrown = false
        try {
            LedgerRules.balance(listOf(fiado(1, Long.MAX_VALUE), fiado(2, 1)))
        } catch (e: ArithmeticException) {
            thrown = true
        }
        assertTrue("balance debe lanzar ArithmeticException", thrown)
    }

    @Test
    fun generatedValidSequencesKeepEveryInvariant() {
        // Semilla fija: reproducible. Cada movimiento se intenta añadir con una fecha al azar;
        // solo entran los que las reglas aceptan, y el historial resultante siempre cumple el invariante.
        repeat(50) { round ->
            val random = Random(1000L + round)
            val accepted = mutableListOf<LedgerEntry>()
            var order = 1L
            repeat(60) {
                val type = if (random.nextInt(3) == 0) MovementType.ABONO else MovementType.FIADO
                val candidate = LedgerEntry(
                    "g$order", LocalDate.of(2026, 10, 1 + random.nextInt(28)), order++, type, MovementOrigin.MANUAL,
                    1L + random.nextInt(50_000), null,
                )
                if (LedgerRules.rejectionFor(accepted, candidate) == null) accepted += candidate
            }
            val check = LedgerRules.check(accepted)
            assertTrue("ronda $round: $check", check is HistoryCheck.Valid)
            assertEquals(LedgerRules.balance(accepted), (check as HistoryCheck.Valid).finalBalanceCop)
            // El resultado no depende del orden físico de lectura.
            assertEquals(
                LedgerRules.historyRecentFirst(accepted),
                LedgerRules.historyRecentFirst(accepted.shuffled(random)),
            )
            // El saldo mostrado en la fila más reciente es el saldo final.
            accepted.takeIf { it.isNotEmpty() }?.let {
                assertEquals(check.finalBalanceCop, LedgerRules.historyRecentFirst(it).first().balanceAfterCop)
            }
            assertTrue(LedgerRules.historyRecentFirst(accepted).all { (it.balanceAfterCop ?: 0L) >= 0 })
        }
    }
}
