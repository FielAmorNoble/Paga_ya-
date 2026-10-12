package com.example.pagaya.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Tabla `cliente` (docs/03_Modelo_Datos.md). */
@Entity(
    tableName = "cliente",
    indices = [Index(value = ["activo", "nombre_busqueda", "id"])],
)
data class ClientEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "nombre") val name: String,
    @ColumnInfo(name = "nombre_busqueda") val searchName: String,
    @ColumnInfo(name = "telefono") val phone: String?,
    @ColumnInfo(name = "nota") val note: String?,
    @ColumnInfo(name = "creado_en") val createdAtMillis: Long,
    @ColumnInfo(name = "modificado_en") val modifiedAtMillis: Long,
    @ColumnInfo(name = "activo") val active: Boolean,
)
