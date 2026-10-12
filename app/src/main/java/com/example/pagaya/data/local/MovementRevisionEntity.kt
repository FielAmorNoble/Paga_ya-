package com.example.pagaya.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RevisionAction { EDITAR, ANULAR }

/** Tabla `movimiento_revision`: estado anterior de un movimiento corregido o anulado. */
@Entity(
    tableName = "movimiento_revision",
    foreignKeys = [
        ForeignKey(
            entity = MovementEntity::class,
            parentColumns = ["id"],
            childColumns = ["movimiento_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["movimiento_id", "version_anterior"], unique = true)],
)
data class MovementRevisionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "movimiento_id") val movementId: String,
    @ColumnInfo(name = "version_anterior") val previousVersion: Long,
    @ColumnInfo(name = "version_nueva") val newVersion: Long,
    @ColumnInfo(name = "accion") val action: RevisionAction,
    @ColumnInfo(name = "estado_anterior_json") val previousStateJson: String,
    @ColumnInfo(name = "cambiado_en") val changedAtMillis: Long,
    @ColumnInfo(name = "motivo") val reason: String,
)
