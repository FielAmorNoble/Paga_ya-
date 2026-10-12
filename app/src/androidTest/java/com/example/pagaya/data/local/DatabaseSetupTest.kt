package com.example.pagaya.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class DatabaseSetupTest {

    private val file = TestDatabaseFile()
    private var db: PagaYaDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        file.delete()
    }

    @Test
    fun creatingTheDatabaseInitializesOneMetadataRowAndAllConstraints() {
        val ledgerId = "0f8b3c1e-6a2d-4f4b-9c11-2b7f5e8d9a01"
        val epoch = "7c2e9a44-1b3f-4d6e-8a90-5f1c2d3e4b5a"
        val ids = ArrayDeque(listOf(ledgerId, epoch))
        val database = file.open(newId = { ids.removeFirst() }).also { db = it }

        val metadata = database.ledgerMetadataDao().get()
        assertEquals(
            LedgerMetadataEntity(
                ledgerId = ledgerId,
                dataRevision = 0,
                nextEntryOrder = 1,
                createdAtMillis = T0,
                localEpoch = epoch,
                lastRestoreId = null,
                lastRestoreAtMillis = null,
                recoveryCopyLocator = null,
            ),
            metadata,
        )
        assertTrue("Solo se generan libreta_id y epoca_local", ids.isEmpty())

        val sql = database.openHelper.writableDatabase
        assertEquals(1L, sql.longQuery("SELECT COUNT(*) FROM libreta_metadata"))
        assertEquals(SchemaConstraints.definitions, sql.triggerDefinitions())
        assertTrue("La base debe existir como archivo", file.context.getDatabasePath(file.fileName).isFile)
    }

    @Test
    fun reopeningTheFileKeepsDataAndDoesNotRegenerateIdentifiers() {
        val first = file.open()
        val client = client()
        val movement = movement(client.id, entryOrder = 1)
        first.clientDao().insert(client)
        first.movementDao().insert(movement)
        val metadataBefore = first.ledgerMetadataDao().get()
        first.close()

        val laterClock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)
        val reopened = file.open(clock = laterClock, newId = { fail("No debe generar IDs al reabrir"); "" })
            .also { db = it }

        assertNotNull(metadataBefore)
        assertEquals(metadataBefore, reopened.ledgerMetadataDao().get())
        assertEquals(client, reopened.clientDao().findById(client.id))
        assertEquals(movement, reopened.movementDao().findById(movement.id))
    }

    @Test
    fun foreignKeysWalAndFullSynchronousAreEffectiveOnTheWriterConnection() {
        val database = file.open().also { db = it }
        val sql = database.openHelper.writableDatabase

        // Dentro de una transacción la consulta usa la conexión que realiza las escrituras.
        database.runInTransaction {
            assertEquals(1L, sql.longQuery("PRAGMA foreign_keys"))
            assertEquals("synchronous=FULL", 2L, sql.longQuery("PRAGMA synchronous"))
        }
        assertEquals("wal", sql.query("PRAGMA journal_mode").use { it.moveToFirst(); it.getString(0) })
    }

    @Test
    fun metadataKeepsExactlyOneRow() {
        val database = file.open().also { db = it }
        val sql = database.openHelper.writableDatabase
        val current = database.ledgerMetadataDao().get()!!

        assertConstraint(rule = "libreta_metadata.fila_unica") {
            sql.execSQL(
                "INSERT INTO libreta_metadata (id, libreta_id, revision_datos, siguiente_orden_registro, creado_en, " +
                    "epoca_local) VALUES (2, ?, 0, 1, 0, ?)",
                arrayOf(randomId(), randomId()),
            )
        }
        assertConstraint(messageFragment = "libreta_metadata.id") {
            sql.execSQL(
                "INSERT INTO libreta_metadata (id, libreta_id, revision_datos, siguiente_orden_registro, creado_en, " +
                    "epoca_local) VALUES (1, ?, 0, 1, 0, ?)",
                arrayOf(randomId(), randomId()),
            )
        }
        assertConstraint(rule = "libreta_metadata.sin_borrado") { sql.execSQL("DELETE FROM libreta_metadata") }

        assertEquals(1L, sql.longQuery("SELECT COUNT(*) FROM libreta_metadata"))
        assertEquals(current, database.ledgerMetadataDao().get())
    }

    @Test
    fun aDatabaseMissingAConstraintIsNotOpened() {
        val first = file.open()
        first.openHelper.writableDatabase.execSQL("DROP TRIGGER pagaya_movimiento_valor_cop_insert")
        first.close()

        assertOpeningFails(expectedInMessage = "pagaya_movimiento_valor_cop_insert")
    }

    @Test
    fun aDatabaseWithAWeakenedConstraintIsNotOpened() {
        val first = file.open()
        first.openHelper.writableDatabase.apply {
            execSQL("DROP TRIGGER pagaya_movimiento_valor_cop_insert")
            execSQL(
                "CREATE TRIGGER pagaya_movimiento_valor_cop_insert BEFORE INSERT ON movimiento " +
                    "FOR EACH ROW WHEN 0 BEGIN SELECT RAISE(ABORT, 'debilitado'); END",
            )
        }
        first.close()

        assertOpeningFails(expectedInMessage = "modificadas [pagaya_movimiento_valor_cop_insert]")
    }

    private fun assertOpeningFails(expectedInMessage: String) {
        val reopened = file.open().also { db = it }
        val failure = runCatching { reopened.ledgerMetadataDao().get() }.exceptionOrNull()
        var cause = failure
        while (cause != null && cause !is IllegalStateException) cause = cause.cause
        assertNotNull("Debe rechazar la apertura, obtuvo: $failure", cause)
        assertTrue(cause!!.message.orEmpty(), cause.message.orEmpty().contains(expectedInMessage))
    }
}

internal fun SupportSQLiteDatabase.longQuery(sql: String): Long = query(sql).use { cursor ->
    cursor.moveToFirst()
    cursor.getLong(0)
}

internal fun SupportSQLiteDatabase.triggerDefinitions(): Map<String, String> =
    query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger'").use { cursor ->
        buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
    }
