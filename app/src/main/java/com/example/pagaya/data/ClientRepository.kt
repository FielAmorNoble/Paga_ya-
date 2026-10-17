package com.example.pagaya.data

import com.example.pagaya.data.local.ClientEntity
import com.example.pagaya.data.local.ClientWithAccount
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.ClientRules
import com.example.pagaya.domain.ClientValidation
import com.example.pagaya.domain.ValidClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock

sealed interface SaveClientResult {
    data class Saved(val clientId: String) : SaveClientResult

    data class Invalid(val errors: ClientValidation.Invalid) : SaveClientResult

    /** El ID de solicitud ya se usó para un cliente con otros datos. */
    data object Conflict : SaveClientResult

    data object NotFound : SaveClientResult
}

sealed interface ClientStatusResult {
    /** El cliente quedó archivado o activo, según la operación pedida. */
    data object Changed : ClientStatusResult

    /** Ya estaba en ese estado: no se escribió nada ni se contó una revisión. */
    data object Unchanged : ClientStatusResult

    /** Archivar exige saldo vigente cero; no se escribió nada. */
    data class BalanceNotZero(val balanceCop: Long) : ClientStatusResult

    data object NotFound : ClientStatusResult
}

/**
 * Único punto de escritura de clientes. Valida de nuevo aunque la pantalla ya lo haya hecho y
 * actualiza `revision_datos` en la misma transacción que el cambio. Las lecturas son observables
 * y Room las ejecuta fuera del hilo principal; las escrituras usan [ioDispatcher].
 */
class ClientRepository(
    private val db: PagaYaDatabase,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val clients = db.clientDao()
    private val movements = db.movementDao()
    private val metadata = db.ledgerMetadataDao()

    suspend fun currentEpoch(): String = withContext(ioDispatcher) { checkNotNull(metadata.get()).localEpoch }
    fun observeEpoch(): Flow<String> = metadata.observe().filterNotNull().map { it.localEpoch }

    fun observeActiveClients(query: String): Flow<List<ClientWithAccount>> =
        clients.observeActive(ClientRules.likeFragment(query))

    fun observeArchivedClients(query: String): Flow<List<ClientWithAccount>> =
        clients.observeArchived(ClientRules.likeFragment(query))

    fun observeClient(id: String): Flow<ClientWithAccount?> = clients.observeWithAccount(id)

    /** Otros clientes (activos o archivados) con el mismo nombre normalizado. */
    suspend fun countHomonyms(name: String, excludeId: String?): Int = withContext(ioDispatcher) {
        val key = ClientRules.searchKey(name)
        if (key.isEmpty()) 0 else clients.countWithSearchName(key, excludeId.orEmpty())
    }

    /**
     * Crea un cliente con id [requestId], generado una sola vez por formulario. Un reintento con
     * los mismos datos devuelve el cliente existente sin duplicarlo ni contar otra revisión.
     */
    suspend fun create(requestId: String, input: ClientInput, expectedEpoch: String? = null): SaveClientResult {
        val valid = when (val validation = ClientRules.validate(input)) {
            is ClientValidation.Invalid -> return SaveClientResult.Invalid(validation)
            is ClientValidation.Valid -> validation.client
        }
        return withContext(ioDispatcher) {
            db.writeInEpoch<SaveClientResult>(expectedEpoch) {
                val existing = clients.findById(requestId)
                if (existing != null) {
                    return@writeInEpoch if (existing.hasSameData(valid)) {
                        SaveClientResult.Saved(existing.id)
                    } else {
                        SaveClientResult.Conflict
                    }
                }
                val now = clock.millis()
                check(metadata.incrementDataRevision() == 1) { "Falta la fila de metadata" }
                clients.insert(
                    ClientEntity(
                        id = requestId,
                        name = valid.name,
                        searchName = valid.searchName,
                        phone = valid.phone,
                        note = valid.note,
                        createdAtMillis = now,
                        modifiedAtMillis = now,
                        active = true,
                    ),
                )
                SaveClientResult.Saved(requestId)
            }
        }
    }

    /**
     * Edita nombre, teléfono y nota. Conserva id, creación y estado activo. Guardar sin cambios
     * (por ejemplo, un reintento ya aplicado) no modifica la fila ni la revisión.
     */
    suspend fun update(id: String, input: ClientInput, expectedEpoch: String? = null): SaveClientResult {
        val valid = when (val validation = ClientRules.validate(input)) {
            is ClientValidation.Invalid -> return SaveClientResult.Invalid(validation)
            is ClientValidation.Valid -> validation.client
        }
        return withContext(ioDispatcher) {
            db.writeInEpoch<SaveClientResult>(expectedEpoch) {
                val existing = clients.findById(id) ?: return@writeInEpoch SaveClientResult.NotFound
                if (existing.hasSameData(valid)) return@writeInEpoch SaveClientResult.Saved(id)
                check(metadata.incrementDataRevision() == 1) { "Falta la fila de metadata" }
                clients.update(
                    existing.copy(
                        name = valid.name,
                        searchName = valid.searchName,
                        phone = valid.phone,
                        note = valid.note,
                        modifiedAtMillis = clock.millis(),
                    ),
                )
                SaveClientResult.Saved(id)
            }
        }
    }

    /**
     * Archiva el cliente [id]: deja de aparecer en la lista principal sin perder datos, movimientos ni
     * revisiones. Solo con saldo vigente exactamente cero (sin movimientos, saldada o con todo anulado).
     * El saldo se lee en la misma transacción que cambia `activo`: un alta o corrección concurrente
     * nunca deja un cliente archivado con deuda, porque las altas también exigen cliente activo dentro
     * de su transacción.
     */
    suspend fun archive(id: String, expectedEpoch: String? = null): ClientStatusResult = setActive(id, active = false, expectedEpoch)

    /** Reactiva el cliente [id] conservando toda su información. */
    suspend fun reactivate(id: String, expectedEpoch: String? = null): ClientStatusResult = setActive(id, active = true, expectedEpoch)

    private suspend fun setActive(id: String, active: Boolean, expectedEpoch: String?): ClientStatusResult = withContext(ioDispatcher) {
        db.writeInEpoch<ClientStatusResult>(expectedEpoch) {
            val existing = clients.findById(id) ?: return@writeInEpoch ClientStatusResult.NotFound
            if (existing.active == active) return@writeInEpoch ClientStatusResult.Unchanged
            if (!active) {
                val balance = movements.activeBalance(id)
                if (balance != 0L) return@writeInEpoch ClientStatusResult.BalanceNotZero(balance)
            }
            check(metadata.incrementDataRevision() == 1) { "Falta la fila de metadata" }
            check(clients.update(existing.copy(active = active, modifiedAtMillis = clock.millis())) == 1) { "No se actualizó el cliente" }
            ClientStatusResult.Changed
        }
    }

    private fun ClientEntity.hasSameData(valid: ValidClient) =
        name == valid.name && phone == valid.phone && note == valid.note
}
