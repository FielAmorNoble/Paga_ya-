package com.example.pagaya.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tabla `libreta_metadata`: exactamente una fila, con id [SINGLE_ROW_ID].
 * [PagaYaDatabase] la crea en la misma transacción que el esquema.
 */
@Entity(tableName = "libreta_metadata")
data class LedgerMetadataEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long = SINGLE_ROW_ID,
    @ColumnInfo(name = "libreta_id") val ledgerId: String,
    @ColumnInfo(name = "revision_datos") val dataRevision: Long,
    @ColumnInfo(name = "siguiente_orden_registro") val nextEntryOrder: Long,
    @ColumnInfo(name = "creado_en") val createdAtMillis: Long,
    // Campos operativos de esta instalación: no se exportan.
    @ColumnInfo(name = "epoca_local") val localEpoch: String,
    @ColumnInfo(name = "ultima_restauracion_id") val lastRestoreId: String?,
    @ColumnInfo(name = "ultima_restauracion_en") val lastRestoreAtMillis: Long?,
    @ColumnInfo(name = "copia_recuperacion_localizador") val recoveryCopyLocator: String?,
) {
    companion object {
        const val SINGLE_ROW_ID = 1L
    }
}
