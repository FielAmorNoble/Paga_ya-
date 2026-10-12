package com.example.pagaya.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Clock

@Database(
    entities = [
        ClientEntity::class,
        MovementEntity::class,
        MovementRevisionEntity::class,
        LedgerMetadataEntity::class,
        BackupRecordEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(LocalDateConverter::class)
abstract class PagaYaDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun movementDao(): MovementDao
    abstract fun movementRevisionDao(): MovementRevisionDao
    abstract fun ledgerMetadataDao(): LedgerMetadataDao
    abstract fun backupRecordDao(): BackupRecordDao

    companion object {
        /** Base operativa en el directorio privado de la app (`databases/`). */
        const val FILE_NAME = "pagaya.db"

        /**
         * Construye la instancia; no toca el disco hasta la primera consulta, que Room
         * rechaza en el hilo principal. [newId] solo se usa al crear la base por primera vez.
         */
        fun build(context: Context, fileName: String, clock: Clock, newId: () -> String): PagaYaDatabase =
            Room.databaseBuilder(context.applicationContext, PagaYaDatabase::class.java, fileName)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(SetupCallback(clock, newId))
                .build()
    }
}

private class SetupCallback(
    private val clock: Clock,
    private val newId: () -> String,
) : RoomDatabase.Callback() {

    // Se ejecuta dentro de la transacción en la que Room crea las tablas: si algo falla,
    // no queda una base sin restricciones ni sin metadata.
    override fun onCreate(db: SupportSQLiteDatabase) {
        SchemaConstraints.createStatements.forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO libreta_metadata (id, libreta_id, revision_datos, siguiente_orden_registro, " +
                "creado_en, epoca_local) VALUES (?, ?, 0, 1, ?, ?)",
            arrayOf<Any>(LedgerMetadataEntity.SINGLE_ROW_ID, newId(), clock.millis(), newId()),
        )
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        // WAL + FULL: un commit confirmado sobrevive a un corte de energía (docs/03).
        db.execSQL("PRAGMA synchronous = FULL")
        verifySchema(db)
    }

    // Una base sin sus restricciones exactas o sin metadata no se usa ni se "repara" en silencio.
    private fun verifySchema(db: SupportSQLiteDatabase) {
        val triggers = mutableMapOf<String, String>()
        db.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger'").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (name.startsWith(SchemaConstraints.TRIGGER_PREFIX)) triggers[name] = cursor.getString(1)
            }
        }
        val expected = SchemaConstraints.definitions
        check(triggers == expected) {
            val changed = expected.keys.filter { it in triggers && triggers[it] != expected[it] }
            "Restricciones de la base distintas de la versión actual: faltan ${expected.keys - triggers.keys}, " +
                "sobran ${triggers.keys - expected.keys}, modificadas $changed"
        }
        val metadataRows = db.query("SELECT COUNT(*) FROM libreta_metadata").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
        check(metadataRows == 1L) { "libreta_metadata debe tener exactamente una fila; tiene $metadataRows" }
    }
}
