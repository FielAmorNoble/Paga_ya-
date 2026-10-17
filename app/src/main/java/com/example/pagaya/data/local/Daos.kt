package com.example.pagaya.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// Acceso de bajo nivel de la capa de datos; ninguna pantalla los usa. Las reglas que
// cruzan filas (saldo, prefijos, revisiones, revision_datos) pertenecen al repositorio.
// @Insert y @Update usan ABORT por defecto: nunca REPLACE.

@Dao
interface ClientDao {
    @Insert
    fun insert(client: ClientEntity)

    @Update
    fun update(client: ClientEntity): Int

    @Query("SELECT * FROM cliente WHERE id = :id")
    fun findById(id: String): ClientEntity?

    /** Activos cuyo nombre normalizado contiene [likeFragment] (ya escapado), en orden alfabético estable. */
    @Query(
        CLIENT_WITH_ACCOUNT_SELECT +
            "WHERE c.activo = 1 AND c.nombre_busqueda LIKE '%' || :likeFragment || '%' ESCAPE '\\' " +
            "GROUP BY c.id ORDER BY c.nombre_busqueda, c.id",
    )
    fun observeActive(likeFragment: String): Flow<List<ClientWithAccount>>

    /** Archivados, con las mismas reglas de búsqueda y orden que [observeActive]. */
    @Query(
        CLIENT_WITH_ACCOUNT_SELECT +
            "WHERE c.activo = 0 AND c.nombre_busqueda LIKE '%' || :likeFragment || '%' ESCAPE '\\' " +
            "GROUP BY c.id ORDER BY c.nombre_busqueda, c.id",
    )
    fun observeArchived(likeFragment: String): Flow<List<ClientWithAccount>>

    @Query(CLIENT_WITH_ACCOUNT_SELECT + "WHERE c.id = :id GROUP BY c.id")
    fun observeWithAccount(id: String): Flow<ClientWithAccount?>

    @Query("SELECT COUNT(*) FROM cliente WHERE nombre_busqueda = :searchName AND id != :excludeId")
    fun countWithSearchName(searchName: String, excludeId: String): Int
}

@Dao
interface MovementDao {
    @Insert
    fun insert(movement: MovementEntity)

    @Update
    fun update(movement: MovementEntity): Int

    @Query("SELECT * FROM movimiento WHERE id = :id")
    fun findById(id: String): MovementEntity?

    /** Movimientos vigentes de una cuenta, en el orden de cálculo `(fecha, orden_registro)`. */
    @Query("SELECT * FROM movimiento WHERE cliente_id = :clientId AND activo = 1 ORDER BY fecha, orden_registro")
    fun findActiveByClient(clientId: String): List<MovementEntity>

    /** Saldo vigente de la cuenta con la misma regla que [CLIENT_WITH_ACCOUNT_SELECT]: FIADO suma, ABONO resta. */
    @Query(
        "SELECT COALESCE(SUM(CASE tipo WHEN 'FIADO' THEN valor_cop ELSE -valor_cop END), 0) " +
            "FROM movimiento WHERE cliente_id = :clientId AND activo = 1",
    )
    fun activeBalance(clientId: String): Long

    /** Todos los movimientos de la cuenta, vigentes y anulados: el historial muestra también los anulados. */
    @Query("SELECT * FROM movimiento WHERE cliente_id = :clientId ORDER BY fecha, orden_registro")
    fun observeByClient(clientId: String): Flow<List<MovementEntity>>

    @Query("SELECT * FROM movimiento WHERE id = :id")
    fun observeById(id: String): Flow<MovementEntity?>

    /**
     * El movimiento y sus revisiones en una sola lectura transaccional observable: nunca un movimiento
     * en una versión con las revisiones de otra. Se vuelve a emitir cuando cambia cualquiera de las dos tablas.
     */
    @Transaction
    @Query("SELECT * FROM movimiento WHERE id = :id")
    fun observeWithRevisions(id: String): Flow<MovementWithRevisions?>
}

@Dao
interface MovementRevisionDao {
    @Insert
    fun insert(revision: MovementRevisionEntity)

    @Query("SELECT * FROM movimiento_revision WHERE movimiento_id = :movementId ORDER BY version_anterior")
    fun findByMovement(movementId: String): List<MovementRevisionEntity>

    /** La revisión que guardó el estado de [previousVersion] (la que cambió esa versión por la siguiente). */
    @Query("SELECT * FROM movimiento_revision WHERE movimiento_id = :movementId AND version_anterior = :previousVersion")
    fun findByPreviousVersion(movementId: String, previousVersion: Long): MovementRevisionEntity?

    @Query("SELECT * FROM movimiento_revision WHERE id = :id")
    fun findById(id: String): MovementRevisionEntity?
}

@Dao
interface LedgerMetadataDao {
    @Query("SELECT * FROM libreta_metadata WHERE id = 1")
    fun get(): LedgerMetadataEntity?

    @Update
    fun update(metadata: LedgerMetadataEntity): Int

    /** Debe ejecutarse en la misma transacción que el cambio de negocio. */
    @Query("UPDATE libreta_metadata SET revision_datos = revision_datos + 1 WHERE id = 1")
    fun incrementDataRevision(): Int

    /** Consume el orden de registro leído con [get]; debe ir en la misma transacción que el alta. */
    @Query("UPDATE libreta_metadata SET siguiente_orden_registro = siguiente_orden_registro + 1 WHERE id = 1")
    fun advanceEntryOrder(): Int
}

@Dao
interface BackupRecordDao {
    @Insert
    fun insert(record: BackupRecordEntity)

    @Update
    fun update(record: BackupRecordEntity): Int

    @Query("SELECT * FROM backup_registro WHERE backup_id = :backupId AND destino = :destination")
    fun find(backupId: String, destination: BackupDestination): BackupRecordEntity?
}
