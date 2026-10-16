package com.example.pagaya.data

import com.example.pagaya.data.local.MovementEntity
import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.domain.BusinessCalendar
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.HistoryRow
import com.example.pagaya.domain.LedgerEntry
import com.example.pagaya.domain.LedgerRules
import com.example.pagaya.domain.MovementInput
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.domain.MovementValidation
import com.example.pagaya.domain.ValidMovement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

sealed interface RegisterMovementResult {
    /** Guardado y confirmado (también cuando un reintento encuentra el mismo intento ya guardado). */
    data class Saved(val movementId: String) : RegisterMovementResult

    data class Invalid(val errors: MovementValidation.Invalid) : RegisterMovementResult

    /** El historial resultante no sería válido; no se escribió nada. */
    data class Rejected(val reason: HistoryRejection) : RegisterMovementResult

    /** Pago completo: el saldo ya no es el que la usuaria confirmó. No se escribió nada. */
    data class BalanceChanged(val currentBalanceCop: Long) : RegisterMovementResult

    /** Pago completo: el saldo supera el máximo por movimiento y debe dividirse en abonos. */
    data class ExceedsLimit(val balanceCop: Long) : RegisterMovementResult

    /** El ID de solicitud ya se usó para un movimiento con otros datos. */
    data object Conflict : RegisterMovementResult

    data object ClientNotFound : RegisterMovementResult

    data object ClientArchived : RegisterMovementResult
}

/**
 * Único punto de escritura de movimientos. Cada alta ocurre en una sola transacción: comprueba la
 * idempotencia, lee cliente y movimientos vigentes, valida el historial candidato, inserta el
 * movimiento y actualiza `siguiente_orden_registro` y `revision_datos`. Si algo falla o se rechaza,
 * no queda ningún cambio parcial (ni movimiento, ni orden consumido, ni revisión).
 *
 * El saldo nunca se guarda: se deriva de los movimientos vigentes (docs/03).
 */
class MovementRepository(
    private val db: PagaYaDatabase,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val clients = db.clientDao()
    private val movements = db.movementDao()
    private val metadata = db.ledgerMetadataDao()

    /** Hoy en America/Bogota, el día inicial de un movimiento nuevo y el máximo admitido. */
    fun today(): LocalDate = BusinessCalendar.today(clock)

    /** Historial vigente del cliente: movimientos recientes primero, con el saldo tras cada uno. */
    fun observeHistory(clientId: String): Flow<List<HistoryRow>> =
        movements.observeActiveByClient(clientId)
            .map { rows -> LedgerRules.historyRecentFirst(rows.map { it.toLedgerEntry() }) }
            .flowOn(ioDispatcher)

    /**
     * Registra un FIADO o un ABONO manual con el ID de solicitud [requestId], generado una sola vez
     * por formulario. Un reintento idéntico devuelve el mismo movimiento sin duplicarlo; el mismo ID
     * con otros datos devuelve [RegisterMovementResult.Conflict]. Dos formularios distintos con los
     * mismos datos sí crean dos movimientos.
     */
    suspend fun register(
        requestId: String,
        clientId: String,
        type: MovementType,
        input: MovementInput,
    ): RegisterMovementResult {
        val valid = when (val validation = MovementRules.validate(input, today())) {
            is MovementValidation.Invalid -> return RegisterMovementResult.Invalid(validation)
            is MovementValidation.Valid -> validation.movement
        }
        return commit(requestId, clientId, type, MovementOrigin.MANUAL, valid, payFull = false)
    }

    /**
     * Registra un ABONO con origen PAGO_TOTAL por [confirmedBalanceCop], el saldo que la usuaria vio
     * y confirmó. El saldo se vuelve a leer dentro de la transacción: si cambió, no se guarda nada y
     * se devuelve el saldo actual para que lo revise; el importe confirmado nunca se reajusta.
     */
    suspend fun payFullBalance(
        requestId: String,
        clientId: String,
        confirmedBalanceCop: Long,
        date: LocalDate,
    ): RegisterMovementResult {
        MovementRules.validateDate(date, today())?.let {
            return RegisterMovementResult.Invalid(MovementValidation.Invalid(amount = null, date = it, description = null))
        }
        val valid = ValidMovement(confirmedBalanceCop, date, description = null)
        return commit(requestId, clientId, MovementType.ABONO, MovementOrigin.PAGO_TOTAL, valid, payFull = true)
    }

    private suspend fun commit(
        requestId: String,
        clientId: String,
        type: MovementType,
        origin: MovementOrigin,
        valid: ValidMovement,
        payFull: Boolean,
    ): RegisterMovementResult = withContext(ioDispatcher) {
        db.runInTransaction<RegisterMovementResult> {
            // 1. Idempotencia: el mismo intento ya guardado no se repite ni cuenta otra revisión.
            movements.findById(requestId)?.let { existing ->
                return@runInTransaction if (existing.isSameAttempt(clientId, type, origin, valid)) {
                    RegisterMovementResult.Saved(existing.id)
                } else {
                    RegisterMovementResult.Conflict
                }
            }

            // 2. Cliente activo y movimientos vigentes, leídos dentro de la transacción.
            val client = clients.findById(clientId) ?: return@runInTransaction RegisterMovementResult.ClientNotFound
            if (!client.active) return@runInTransaction RegisterMovementResult.ClientArchived
            val existing = movements.findActiveByClient(clientId).map { it.toLedgerEntry() }

            // 3. Orden de registro que recibiría el alta. Solo se consume (paso 5) si todo es válido.
            val entryOrder = checkNotNull(metadata.get()) { "Falta la fila de metadata" }.nextEntryOrder

            if (payFull) {
                val balance = try {
                    LedgerRules.balance(existing)
                } catch (e: ArithmeticException) {
                    return@runInTransaction RegisterMovementResult.Rejected(HistoryRejection.Overflow)
                }
                // Una cuenta ya sin deuda también es un saldo distinto del confirmado.
                if (balance <= 0) return@runInTransaction RegisterMovementResult.BalanceChanged(0)
                if (balance !=valid.amountCop) return@runInTransaction RegisterMovementResult.BalanceChanged(balance)
                if (balance > MovementRules.AMOUNT_MAX) return@runInTransaction RegisterMovementResult.ExceedsLimit(balance)
            }

            // 4. Historial candidato: ningún saldo intermedio negativo, sin desbordamientos.
            val candidate = LedgerEntry(requestId, valid.date, entryOrder, type, origin, valid.amountCop, valid.description)
            LedgerRules.rejectionFor(existing, candidate)?.let {
                return@runInTransaction RegisterMovementResult.Rejected(it)
            }

            // 5. Escritura: movimiento, orden consumido y revisión de datos, todo o nada.
            val now = clock.millis()
            movements.insert(
                MovementEntity(
                    id = requestId,
                    clientId = clientId,
                    date = valid.date,
                    entryOrder = entryOrder,
                    type = type,
                    amountCop = valid.amountCop,
                    description = valid.description,
                    origin = origin,
                    createdAtMillis = now,
                    modifiedAtMillis = now,
                    active = true,
                    version = 1,
                ),
            )
            check(metadata.advanceEntryOrder() == 1) { "Falta la fila de metadata" }
            check(metadata.incrementDataRevision() == 1) { "Falta la fila de metadata" }
            RegisterMovementResult.Saved(requestId)
        }
    }

    private fun MovementEntity.isSameAttempt(clientId: String, type: MovementType, origin: MovementOrigin, valid: ValidMovement) =
        this.clientId == clientId && this.type == type && this.origin == origin &&
            amountCop == valid.amountCop && date == valid.date && description == valid.description

    private fun MovementEntity.toLedgerEntry() = LedgerEntry(id, date, entryOrder, type, origin, amountCop, description)
}
