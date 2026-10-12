package com.example.pagaya.data.local

import androidx.room.TypeConverter
import java.time.LocalDate

/** Fecha económica como TEXT `YYYY-MM-DD` (día civil en America/Bogota). */
class LocalDateConverter {
    @TypeConverter
    fun toText(date: LocalDate): String = date.toString()

    @TypeConverter
    fun fromText(text: String): LocalDate = LocalDate.parse(text)
}
