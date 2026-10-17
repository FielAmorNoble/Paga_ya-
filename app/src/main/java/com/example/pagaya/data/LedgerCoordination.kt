package com.example.pagaya.data

import com.example.pagaya.data.local.PagaYaDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Orden: operation -> writes -> transacción Room. Negocio solo toma writes.
 * Nunca se llama a una operación pública de respaldo desde dentro de otra operación.
 */
class LedgerCoordination {
    internal val operation = Mutex()
    internal val writes = Mutex()
    companion object { const val EPOCH_ARG = "ledgerEpoch" }
}

class StaleLedgerException : IllegalStateException("La libreta fue reemplazada; abre de nuevo la pantalla")

/** El token se obtiene ANTES de esperar y se comprueba dentro de la transacción protegida. */
internal suspend fun <T> PagaYaDatabase.writeInEpoch(expectedEpoch: String?, block: () -> T): T {
    val epoch = expectedEpoch ?: checkNotNull(ledgerMetadataDao().get()).localEpoch
    return coordination.writes.withLock {
        runInTransaction<T> {
            if (ledgerMetadataDao().get()?.localEpoch != epoch) throw StaleLedgerException()
            block()
        }
    }
}
