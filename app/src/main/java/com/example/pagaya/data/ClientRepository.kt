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
import kotlinx.coroutines.withContext
import java.time.Clock

sealed interface SaveClientResult {
    data class Saved(val clientId: String) : SaveClientResult

    data class Invalid(val errors: ClientValidation.Invalid) : SaveClientResult

    /** El ID de solicitud ya se usó para un cliente con otros datos. */
    data object Conflict : SaveClientResult

    data object NotFound : SaveClientResult
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
    private val metadata = db.ledgerMetadataDao()

    fun observeActiveClients(query: String): Flow<List<ClientWithAccount>> =
        clients.observeActive(ClientRules.likeFragment(query))

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
    suspend fun create(requestId: String, input: ClientInput): SaveClientResult {
        val valid = when (val validation = ClientRules.validate(input)) {
            is ClientValidation.Invalid -> return SaveClientResult.Invalid(validation)
            is ClientValidation.Valid -> validation.client
        }
        return withContext(ioDispatcher) {
            db.runInTransaction<SaveClientResult> {
                val existing = clients.findById(requestId)
                if (existing != null) {
                    return@runInTransaction if (existing.hasSameData(valid)) {
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
    suspend fun update(id: String, input: ClientInput): SaveClientResult {
        val valid = when (val validation = ClientRules.validate(input)) {
            is ClientValidation.Invalid -> return SaveClientResult.Invalid(validation)
            is ClientValidation.Valid -> validation.client
        }
        return withContext(ioDispatcher) {
            db.runInTransaction<SaveClientResult> {
                val existing = clients.findById(id) ?: return@runInTransaction SaveClientResult.NotFound
                if (existing.hasSameData(valid)) return@runInTransaction SaveClientResult.Saved(id)
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

    private fun ClientEntity.hasSameData(valid: ValidClient) =
        name == valid.name && phone == valid.phone && note == valid.note
}
