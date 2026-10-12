package com.example.pagaya.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * Cliente con el estado de su cuenta derivado por consulta de los movimientos vigentes.
 * El saldo no se guarda en ninguna tabla.
 */
data class ClientWithAccount(
    @Embedded val client: ClientEntity,
    @ColumnInfo(name = "movimientos_vigentes") val activeMovementCount: Int,
    @ColumnInfo(name = "saldo") val balanceCop: Long,
)

// FIADO suma y ABONO resta (docs/03); solo cuentan los movimientos vigentes.
internal const val CLIENT_WITH_ACCOUNT_SELECT =
    "SELECT c.*, COUNT(m.id) AS movimientos_vigentes, " +
        "COALESCE(SUM(CASE m.tipo WHEN 'FIADO' THEN m.valor_cop ELSE -m.valor_cop END), 0) AS saldo " +
        "FROM cliente c LEFT JOIN movimiento m ON m.cliente_id = c.id AND m.activo = 1 "
