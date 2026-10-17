package com.example.pagaya.data.local

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Un movimiento con todas sus revisiones, leídos en una misma consulta transaccional: Room resuelve
 * la relación dentro de una transacción de lectura, así que ambos lados pertenecen al mismo instante.
 * No es una tabla: no cambia el esquema.
 */
data class MovementWithRevisions(
    @Embedded val movement: MovementEntity,
    @Relation(parentColumn = "id", entityColumn = "movimiento_id")
    val revisions: List<MovementRevisionEntity>,
)
