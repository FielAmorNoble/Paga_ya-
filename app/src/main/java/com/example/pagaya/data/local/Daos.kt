package com.example.pagaya.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
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
}

@Dao
interface MovementRevisionDao {
    @Insert
    fun insert(revision: MovementRevisionEntity)

    @Query("SELECT * FROM movimiento_revision WHERE movimiento_id = :movementId ORDER BY version_anterior")
    fun findByMovement(movementId: String): List<MovementRevisionEntity>
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
