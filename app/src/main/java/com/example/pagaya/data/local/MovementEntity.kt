package com.example.pagaya.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Códigos persistidos; el nombre de cada constante es el valor guardado. */
enum class MovementType { FIADO, ABONO }

enum class MovementOrigin { MANUAL, PAGO_TOTAL }

/** Tabla `movimiento`. No guarda saldo: se deriva de los movimientos vigentes. */
@Entity(
    tableName = "movimiento",
    foreignKeys = [
        ForeignKey(
            entity = ClientEntity::class,
            parentColumns = ["id"],
            childColumns = ["cliente_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["cliente_id", "fecha", "orden_registro"]),
        Index(value = ["orden_registro"], unique = true),
    ],
)
data class MovementEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "cliente_id") val clientId: String,
    @ColumnInfo(name = "fecha") val date: LocalDate,
    @ColumnInfo(name = "orden_registro") val entryOrder: Long,
    @ColumnInfo(name = "tipo") val type: MovementType,
    @ColumnInfo(name = "valor_cop") val amountCop: Long,
    @ColumnInfo(name = "descripcion") val description: String?,
    @ColumnInfo(name = "origen") val origin: MovementOrigin,
    @ColumnInfo(name = "creado_en") val createdAtMillis: Long,
    @ColumnInfo(name = "modificado_en") val modifiedAtMillis: Long,
    @ColumnInfo(name = "activo") val active: Boolean,
    @ColumnInfo(name = "version") val version: Long,
)
