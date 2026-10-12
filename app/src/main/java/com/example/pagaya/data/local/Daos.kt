package com.example.pagaya.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

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
