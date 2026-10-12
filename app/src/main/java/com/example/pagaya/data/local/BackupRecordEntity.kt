package com.example.pagaya.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

enum class BackupDestination { ARCHIVO, DRIVE, RECUPERACION }

enum class BackupState { PREPARANDO, VERIFICADO, FALLIDO }

/**
 * Tabla `backup_registro`: metadata operativa local, no exportable.
 * La clave (backup_id, destino) permite registrar una misma captura en varios destinos.
 */
@Entity(tableName = "backup_registro", primaryKeys = ["backup_id", "destino"])
data class BackupRecordEntity(
    @ColumnInfo(name = "backup_id") val backupId: String,
    @ColumnInfo(name = "destino") val destination: BackupDestination,
    @ColumnInfo(name = "libreta_id") val ledgerId: String,
    @ColumnInfo(name = "epoca_local") val localEpoch: String,
    @ColumnInfo(name = "revision_datos") val dataRevision: Long,
    @ColumnInfo(name = "capturado_en") val capturedAtMillis: Long,
    @ColumnInfo(name = "estado") val state: BackupState,
    @ColumnInfo(name = "verificado_en") val verifiedAtMillis: Long?,
    // URI del documento o ID del archivo en Drive; nunca contenido financiero.
    @ColumnInfo(name = "localizador") val locator: String?,
    @ColumnInfo(name = "cuenta_drive_huella") val driveAccountFingerprint: String?,
    @ColumnInfo(name = "error_codigo") val errorCode: String?,
)
