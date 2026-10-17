package com.example.pagaya.domain

import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import java.time.LocalDate

/** Un movimiento vigente tal como lo necesita el cálculo del historial. */
data class LedgerEntry(
    val id: String,
    val date: LocalDate,
    val order: Long,
    val type: MovementType,
    val origin: MovementOrigin,
    val amountCop: Long,
    val description: String?,
    /** Un movimiento anulado se muestra pero no participa en el saldo ni en los prefijos. */
    val active: Boolean = true,
    val version: Long = 1,
    val createdAtMillis: Long = 0,
    val modifiedAtMillis: Long = 0,
)

/**
 * Un movimiento con el saldo de la cuenta inmediatamente después de aplicarlo. [balanceAfterCop] es
 * `null` en un movimiento anulado: no participa en el cálculo, así que no tiene saldo resultante.
 */
data class HistoryRow(val entry: LedgerEntry, val balanceAfterCop: Long?)

sealed interface HistoryCheck {
    data class Valid(val finalBalanceCop: Long) : HistoryCheck

    /** El saldo acumulado quedó por debajo de cero justo después de [entryId]. */
    data class NegativeBalance(val entryId: String, val balanceCop: Long) : HistoryCheck

    data object Overflow : HistoryCheck
}

/** Por qué un movimiento nuevo dejaría la cuenta en un estado inválido. */
sealed interface HistoryRejection {
    /** Un abono en una cuenta sin deuda. */
    data object NoDebt : HistoryRejection

    /** Un abono mayor que el saldo actual. */
    data class ExceedsBalance(val balanceCop: Long) : HistoryRejection

    /**
     * El total alcanza, pero por la fecha elegida algún saldo intermedio quedaría negativo.
     * [atNewEntry] distingue si falla el propio movimiento o un abono posterior ya registrado.
     */
    data class NegativeAtDate(val atNewEntry: Boolean) : HistoryRejection

    /**
     * Corregir o anular dejaría este [abono] sin deuda suficiente que cubrir. Nunca es un fiado: el
     * primer saldo negativo de un historial solo puede aparecer al aplicar un abono.
     */
    data class UncoveredAbono(val abono: LedgerEntry) : HistoryRejection

    data object Overflow : HistoryRejection
}

/**
 * Cálculo del saldo y del historial (docs/03 «Cálculo del saldo»). FIADO suma y ABONO resta;
 * el orden es `(fecha, orden_registro)`; ningún saldo intermedio puede ser negativo (RN-04) y la
 * aritmética detecta desbordamientos en lugar de envolver el `Long`. Kotlin puro.
 */
object LedgerRules {
    private val calculationOrder = compareBy<LedgerEntry>({ it.date }, { it.order })

    private fun contribution(entry: LedgerEntry): Long =
        if (entry.type == MovementType.FIADO) entry.amountCop else Math.negateExact(entry.amountCop)

    fun inCalculationOrder(entries: List<LedgerEntry>): List<LedgerEntry> = entries.sortedWith(calculationOrder)

    /** Saldo final. Lanza [ArithmeticException] si la suma desborda un `Long`. */
    fun balance(entries: List<LedgerEntry>): Long =
        entries.fold(0L) { total, entry -> Math.addExact(total, contribution(entry)) }

    /** Recorre el historial en orden de cálculo y se detiene en el primer saldo negativo. */
    fun check(entries: List<LedgerEntry>): HistoryCheck {
        var balance = 0L
        for (entry in inCalculationOrder(entries)) {
            balance = try {
                Math.addExact(balance, contribution(entry))
            } catch (e: ArithmeticException) {
                return HistoryCheck.Overflow
            }
            if (balance < 0) return HistoryCheck.NegativeBalance(entry.id, balance)
        }
        return HistoryCheck.Valid(balance)
    }

    /**
     * Comprueba si [candidate] puede añadirse a [existing] (el historial vigente de la misma
     * cuenta). Devuelve `null` si el historial resultante es válido. El candidato debe traer el
     * orden de registro que recibiría; así los movimientos del mismo día se ordenan por registro.
     */
    fun rejectionFor(existing: List<LedgerEntry>, candidate: LedgerEntry): HistoryRejection? {
        val current = try {
            balance(existing)
        } catch (e: ArithmeticException) {
            return HistoryRejection.Overflow
        }
        if (candidate.type == MovementType.ABONO) {
            if (current <= 0) return HistoryRejection.NoDebt
            if (candidate.amountCop > current) return HistoryRejection.ExceedsBalance(current)
        }
        return when (val result = check(existing + candidate)) {
            is HistoryCheck.Valid -> null
            is HistoryCheck.NegativeBalance -> HistoryRejection.NegativeAtDate(atNewEntry = result.entryId == candidate.id)
            HistoryCheck.Overflow -> HistoryRejection.Overflow
        }
    }

    /**
     * Historial para mostrar: del más reciente al más antiguo. Los movimientos vigentes llevan el
     * saldo resultante, calculado solo con los vigentes; los anulados aparecen en su lugar, sin saldo.
     * Lanza [ArithmeticException] si desborda.
     */
    fun historyRecentFirst(entries: List<LedgerEntry>): List<HistoryRow> {
        var balance = 0L
        return inCalculationOrder(entries)
            .map { entry ->
                if (entry.active) {
                    balance = Math.addExact(balance, contribution(entry))
                    HistoryRow(entry, balance)
                } else {
                    HistoryRow(entry, null)
                }
            }
            .asReversed()
    }

    /**
     * Comprueba si sustituir un movimiento por [changed] (mismo `id`, importe o fecha corregidos)
     * deja un historial válido. [existing] es el historial vigente con el estado anterior incluido.
     * Devuelve `null` si es válido.
     */
    fun rejectionForChange(existing: List<LedgerEntry>, changed: LedgerEntry): HistoryRejection? {
        val others = existing.filter { it.id != changed.id }
        if (changed.type == MovementType.ABONO) {
            val available = try {
                balance(others)
            } catch (e: ArithmeticException) {
                return HistoryRejection.Overflow
            }
            if (available <= 0) return HistoryRejection.NoDebt
            if (changed.amountCop > available) return HistoryRejection.ExceedsBalance(available)
        }
        return rejectionFrom(others + changed, changed.id)
    }

    /** Comprueba si anular el movimiento [removedId] deja un historial válido. */
    fun rejectionForRemoval(existing: List<LedgerEntry>, removedId: String): HistoryRejection? =
        rejectionFrom(existing.filter { it.id != removedId }, editedId = null)

    private fun rejectionFrom(entries: List<LedgerEntry>, editedId: String?): HistoryRejection? =
        when (val result = check(entries)) {
            is HistoryCheck.Valid -> null
            HistoryCheck.Overflow -> HistoryRejection.Overflow
            is HistoryCheck.NegativeBalance ->
                if (result.entryId == editedId) {
                    HistoryRejection.NegativeAtDate(atNewEntry = true)
                } else {
                    HistoryRejection.UncoveredAbono(entries.first { it.id == result.entryId })
                }
        }
}
