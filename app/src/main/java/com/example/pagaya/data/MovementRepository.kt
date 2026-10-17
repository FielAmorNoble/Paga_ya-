package com.example.pagaya.data

import com.example.pagaya.data.local.MovementEntity
import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementRevisionEntity
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.data.local.RevisionAction
import com.example.pagaya.domain.AmountError
import com.example.pagaya.domain.BusinessCalendar
import com.example.pagaya.domain.CorrectionInput
import com.example.pagaya.domain.DateError
import com.example.pagaya.domain.DescriptionError
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.HistoryRow
import com.example.pagaya.domain.LedgerEntry
import com.example.pagaya.domain.LedgerRules
import com.example.pagaya.domain.MovementChange
import com.example.pagaya.domain.MovementChangeLog
import com.example.pagaya.domain.MovementInput
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.domain.MovementSnapshot
import com.example.pagaya.domain.MovementValidation
import com.example.pagaya.domain.ReasonError
import com.example.pagaya.domain.RevisionRecord
import com.example.pagaya.domain.SnapshotJson
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

/** Un movimiento con el número de revisiones que ya tiene. */
data class MovementDetail(val movement: MovementEntity, val revisionCount: Int)

/** Errores de campos de una corrección o anulación. */
data class CorrectionInvalid(
    val amount: AmountError? = null,
    val date: DateError? = null,
    val description: DescriptionError? = null,
    val reason: ReasonError? = null,
)

sealed interface ChangeMovementResult {
    /** Corrección o anulación confirmada (también cuando un reintento encuentra el mismo intento ya aplicado). */
    data class Saved(val movementId: String, val newVersion: Long) : ChangeMovementResult

    /** Corregir sin modificar nada: no se crea revisión ni se cuentan cambios. */
    data object Unchanged : ChangeMovementResult

    data class Invalid(val errors: CorrectionInvalid) : ChangeMovementResult

    /** El historial resultante no sería válido; no se escribió nada. */
    data class Rejected(val reason: HistoryRejection) : ChangeMovementResult

    /** Otra pantalla cambió el movimiento desde que se abrió: hay que recargarlo y revisarlo. */
    data object VersionConflict : ChangeMovementResult

    /** El ID de solicitud ya se usó para otro cambio distinto. */
    data object Conflict : ChangeMovementResult

    data object AlreadyAnnulled : ChangeMovementResult

    data object NotFound : ChangeMovementResult

    data object ClientArchived : ChangeMovementResult
}

/**
 * Único punto de escritura de movimientos. Cada alta, corrección o anulación ocurre en una sola
 * transacción: comprueba la idempotencia, lee cliente y movimientos vigentes, valida el historial
 * candidato, escribe y actualiza `revision_datos`. Si algo falla o se rechaza, no queda ningún
 * cambio parcial (ni movimiento, ni revisión, ni orden consumido, ni contador).
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
    private val revisions = db.movementRevisionDao()
    private val metadata = db.ledgerMetadataDao()

    /** Hoy en America/Bogota, el día inicial de un movimiento nuevo y el máximo admitido. */
    fun today(): LocalDate = BusinessCalendar.today(clock)

    /**
     * Historial del cliente: movimientos recientes primero. Los vigentes llevan el saldo tras cada uno;
     * los anulados aparecen en su lugar, sin saldo.
     */
    fun observeHistory(clientId: String): Flow<List<HistoryRow>> =
        movements.observeByClient(clientId)
            .map { rows -> LedgerRules.historyRecentFirst(rows.map { it.toLedgerEntry() }) }
            .flowOn(ioDispatcher)

    fun observeMovement(movementId: String): Flow<MovementEntity?> = movements.observeById(movementId)

    /** El movimiento y cuántas revisiones tiene, leídos juntos: la versión y el número de revisiones siempre cuadran. */
    fun observeMovementDetail(movementId: String): Flow<MovementDetail?> =
        movements.observeWithRevisions(movementId)
            .map { row -> row?.let { MovementDetail(it.movement, it.revisions.size) } }
            .flowOn(ioDispatcher)

    /**
     * «Ver cambios»: valores anteriores y posteriores, motivo y fecha de cada revisión, del más
     * reciente al más antiguo. El movimiento y sus revisiones vienen de una misma lectura transaccional,
     * así que cada emisión es un conjunto consistente. Si aun así las versiones no son continuas o un
     * snapshot está dañado, falla con excepción (es una inconsistencia real, no se oculta).
     */
    fun observeChanges(movementId: String): Flow<List<MovementChange>> =
        movements.observeWithRevisions(movementId)
            .map { row ->
                if (row == null) {
                    emptyList()
                } else {
                    MovementChangeLog.build(
                        row.revisions.map { RevisionRecord(it.action, it.changedAtMillis, it.reason, it.previousVersion, it.previousStateJson) },
                        row.movement.toSnapshot(),
                    )
                }
            }
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

    /**
     * Corrige valor, fecha y descripción del movimiento [movementId], visto en la versión
     * [expectedVersion]. [requestId] identifica el intento (se guarda como ID de su revisión) y se
     * genera una vez por formulario: un reintento idéntico no crea otra revisión. El cliente, el tipo,
     * el ID, el orden de registro y la creación no cambian. Corregir el importe de un pago completo
     * lo convierte en un abono manual.
     */
    suspend fun correct(
        requestId: String,
        movementId: String,
        expectedVersion: Long,
        input: CorrectionInput,
    ): ChangeMovementResult {
        val fields = MovementInput(input.amount, input.date, input.description)
        val valid = when (val validation = MovementRules.validate(fields, today())) {
            is MovementValidation.Invalid -> return ChangeMovementResult.Invalid(
                CorrectionInvalid(validation.amount, validation.date, validation.description, MovementRules.validateReason(input.reason)),
            )
            is MovementValidation.Valid -> validation.movement
        }
        return change(requestId, movementId, expectedVersion, RevisionAction.EDITAR, input.reason, valid)
    }

    /**
     * Anula el movimiento [movementId]: no se borra, queda marcado como anulado y deja de contar en
     * el saldo. Exige un [reason]. No consume un orden de registro.
     */
    suspend fun annul(requestId: String, movementId: String, expectedVersion: Long, reason: String): ChangeMovementResult =
        change(requestId, movementId, expectedVersion, RevisionAction.ANULAR, reason, corrected = null)

    private suspend fun change(
        requestId: String,
        movementId: String,
        expectedVersion: Long,
        action: RevisionAction,
        rawReason: String,
        corrected: ValidMovement?,
    ): ChangeMovementResult = withContext(ioDispatcher) {
        db.runInTransaction<ChangeMovementResult> {
            val reason = rawReason.trim()

            // 1. Idempotencia: la revisión lleva el ID del intento. Si ya existe, es el mismo intento.
            revisions.findById(requestId)?.let { previous ->
                val now = movements.findById(movementId)
                return@runInTransaction if (now != null && previous.isSameAttempt(movementId, expectedVersion, action, reason, now, corrected)) {
                    ChangeMovementResult.Saved(movementId, previous.newVersion)
                } else {
                    ChangeMovementResult.Conflict
                }
            }

            // 2. Lectura del movimiento, de su estado y de la versión esperada.
            val current = movements.findById(movementId) ?: return@runInTransaction ChangeMovementResult.NotFound
            if (!current.active) return@runInTransaction ChangeMovementResult.AlreadyAnnulled
            if (current.version != expectedVersion) return@runInTransaction ChangeMovementResult.VersionConflict
            val client = clients.findById(current.clientId) ?: return@runInTransaction ChangeMovementResult.NotFound
            if (!client.active) return@runInTransaction ChangeMovementResult.ClientArchived

            // 3. Estado candidato. Corregir sin modificar nada no escribe nada.
            val candidate = if (corrected == null) {
                current.copy(active = false)
            } else {
                current.copy(
                    date = corrected.date,
                    amountCop = corrected.amountCop,
                    description = corrected.description,
                    origin = MovementRules.originAfterCorrection(current.origin, current.amountCop, corrected.amountCop),
                )
            }
            if (corrected != null && candidate.date == current.date && candidate.amountCop == current.amountCop &&
                candidate.description == current.description
            ) {
                return@runInTransaction ChangeMovementResult.Unchanged
            }
            MovementRules.validateReason(reason)?.let {
                return@runInTransaction ChangeMovementResult.Invalid(CorrectionInvalid(reason = it))
            }

            // 4. Toda la secuencia vigente, con el estado candidato, debe seguir siendo válida.
            val existing = movements.findActiveByClient(current.clientId).map { it.toLedgerEntry() }
            val rejection = if (corrected == null) {
                LedgerRules.rejectionForRemoval(existing, movementId)
            } else {
                LedgerRules.rejectionForChange(existing, candidate.toLedgerEntry())
            }
            rejection?.let { return@runInTransaction ChangeMovementResult.Rejected(it) }

            // 5. Escritura: revisión con el snapshot completo anterior, movimiento y revisión de datos.
            //    No se consume un orden de registro: el orden original no cambia.
            val now = clock.millis()
            val newVersion = current.version + 1
            revisions.insert(
                MovementRevisionEntity(
                    id = requestId,
                    movementId = movementId,
                    previousVersion = current.version,
                    newVersion = newVersion,
                    action = action,
                    previousStateJson = SnapshotJson.encode(current.toSnapshot()),
                    changedAtMillis = now,
                    reason = reason,
                ),
            )
            check(movements.update(candidate.copy(modifiedAtMillis = now, version = newVersion)) == 1) { "No se actualizó el movimiento" }
            check(metadata.incrementDataRevision() == 1) { "Falta la fila de metadata" }
            ChangeMovementResult.Saved(movementId, newVersion)
        }
    }

    /**
     * ¿Esta revisión es el mismo intento? Deben coincidir movimiento, acción, versión anterior y motivo, y el
     * contenido del intento debe ser el que esa revisión produjo: el estado del movimiento en su versión nueva.
     * Ese estado es el actual si nadie lo cambió después; si hubo cambios posteriores, es el snapshot que guardó
     * la revisión siguiente. Nunca se compara con un estado posterior que ya incluya otras correcciones.
     *
     * @throws IllegalStateException si falta la revisión siguiente o su snapshot no corresponde: es una
     * inconsistencia real del historial y no se resuelve a favor ni en contra del reintento.
     */
    private fun MovementRevisionEntity.isSameAttempt(
        movementId: String,
        expectedVersion: Long,
        action: RevisionAction,
        reason: String,
        now: MovementEntity,
        corrected: ValidMovement?,
    ): Boolean {
        if (this.movementId != movementId || previousVersion != expectedVersion || this.action != action || this.reason != reason) return false
        val produced = when {
            now.version == newVersion -> now.toSnapshot()
            now.version > newVersion -> {
                val following = checkNotNull(revisions.findByPreviousVersion(movementId, newVersion)) {
                    "Falta la revisión que sigue a la versión $newVersion del movimiento $movementId"
                }
                SnapshotJson.decode(following.previousStateJson).also {
                    check(it.version == newVersion) { "El snapshot de la revisión siguiente no corresponde a la versión $newVersion" }
                }
            }
            else -> error("El movimiento $movementId está en la versión ${now.version}, anterior a la que produjo la revisión $id")
        }
        return if (corrected == null) {
            !produced.active
        } else {
            produced.active && produced.date == corrected.date && produced.amountCop == corrected.amountCop &&
                produced.description == corrected.description
        }
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
                if (balance != valid.amountCop) return@runInTransaction RegisterMovementResult.BalanceChanged(balance)
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

    private fun MovementEntity.toLedgerEntry() =
        LedgerEntry(id, date, entryOrder, type, origin, amountCop, description, active, version, createdAtMillis, modifiedAtMillis)

    private fun MovementEntity.toSnapshot() = MovementSnapshot(
        id, clientId, date, entryOrder, type, amountCop, description, origin, createdAtMillis, modifiedAtMillis, active, version,
    )
}
