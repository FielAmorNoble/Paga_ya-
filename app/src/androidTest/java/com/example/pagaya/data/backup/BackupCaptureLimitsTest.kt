package com.example.pagaya.data.backup

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.data.SaveClientResult
import com.example.pagaya.data.RegisterMovementResult
import com.example.pagaya.data.local.*
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.CorrectionInput
import com.example.pagaya.domain.MovementInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor

/** Observa las consultas Room reales: rechazar no debe ejecutar ninguno de los tres all(). */
@RunWith(AndroidJUnit4::class)
class BackupCaptureLimitsTest {
    private val file = TestDatabaseFile()
    private var db = file.open()
    private val root = File(file.context.cacheDir, "test-capture-limits-${UUID.randomUUID()}").apply { mkdirs() }
    private val queries = Collections.synchronizedList(mutableListOf<String>())
    private val rowQueries = setOf("SELECT * FROM cliente", "SELECT * FROM movimiento", "SELECT * FROM movimiento_revision")
    private val documents = object : DocumentAccess {
        override fun openForWrite(location: String) = File(location).outputStream()
        override fun openForRead(location: String) = File(location).inputStream()
    }

    @After fun close() {
        db.close()
        file.delete()
        root.deleteRecursively()
    }

    private fun service(limits: BackupLimits = BackupLimits()) = BackupService(
        db, TestDatabaseFile.FIXED_CLOCK, File(root, "work"), "test", documents, limits,
    )

    private fun seedAndObserve() = runBlocking {
        val clients = ClientRepository(db, TestDatabaseFile.FIXED_CLOCK)
        val movements = MovementRepository(db, TestDatabaseFile.FIXED_CLOCK)
        val client = (clients.create(randomId(), ClientInput("😀".repeat(120), "", "ñ\"\\\n😀".repeat(150))) as SaveClientResult.Saved).clientId
        val id = (movements.register(randomId(), client, MovementType.FIADO,
            MovementInput("60000", LocalDate.of(2026, 10, 1), "ñ\"\\\n😀".repeat(150))) as RegisterMovementResult.Saved).movementId
        movements.correct(randomId(), id, 1, CorrectionInput("65000", LocalDate.of(2026, 10, 1), "Corregido", "Motivo 😀"))
        // La base se creó con las restricciones de producción. Reabrir el MISMO archivo de prueba
        // permite añadir QueryCallback sin introducir un gancho de pruebas en la app.
        db.close()
        db = Room.databaseBuilder(file.context, PagaYaDatabase::class.java, file.fileName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) { queries.add(sqlQuery.trim()) }
            }, Executor { it.run() })
            .build()
        db.openHelper.writableDatabase.execSQL("PRAGMA synchronous = FULL")
    }

    private fun financialState(): List<Any> = listOf(
        db.ledgerMetadataDao().get()!!, db.clientDao().all(), db.movementDao().all(), db.movementRevisionDao().all(),
    )

    private fun reject(limits: BackupLimits) = runBlocking {
        seedAndObserve()
        val s = service()
        val first = s.prepare() as PrepareResult.Ready
        val target = File(root, "verified.pagaya.zip")
        assertTrue(s.export(first.backupId, target.path) is ExportResult.Verified)
        val verified = db.backupRecordDao().find(first.backupId, BackupDestination.ARCHIVO)
        val before = financialState()
        val bytes = target.readBytes()
        queries.clear()
        val limited = service(limits)
        assertEquals(PrepareResult.Failed(BackupFailure.TOO_LARGE), limited.prepare())
        val executed = queries.toList()
        assertTrue("se ejecutó la medición SQL", executed.any { it.contains("AS bytes_texto") })
        assertTrue("no se cargó ninguna lista: $executed", executed.none { it in rowQueries })
        assertEquals(before, financialState())
        assertEquals(verified, db.backupRecordDao().find(first.backupId, BackupDestination.ARCHIVO))
        assertEquals(first.backupId, limited.observeStatus().first().lastExport?.backupId)
        assertArrayEquals(bytes, target.readBytes())
        assertTrue(File(root, "work").listFiles().orEmpty().isEmpty())
        db.query("SELECT COUNT(*) FROM backup_registro", emptyArray()).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
    }

    @Test fun clientLimitRejectsBeforeAnyListIsLoaded() = reject(BackupLimits(maxClients = 0))
    @Test fun movementLimitRejectsBeforeAnyListIsLoaded() = reject(BackupLimits(maxMovements = 0))
    @Test fun revisionLimitRejectsBeforeAnyListIsLoaded() = reject(BackupLimits(maxRevisions = 0))
    @Test fun byteLimitRejectsBeforeAnyListIsLoaded() = reject(BackupLimits(maxDataBytes = 1000))

    @Test fun anAdmissibleCaptureAtExactByteAndRowLimitsIsNotRejectedByTheEstimate() = runBlocking {
        seedAndObserve()
        val s = service()
        val first = s.prepare() as PrepareResult.Ready
        val target = File(root, "baseline.pagaya.zip")
        assertTrue(s.export(first.backupId, target.path) is ExportResult.Verified)
        val manifest = (BackupValidator().validate(target) as BackupValidation.Valid).summary.manifest
        val before = financialState()
        val exact = service(BackupLimits(maxClients = 1, maxMovements = 1, maxRevisions = 1, maxDataBytes = manifest.dataBytes))
        queries.clear()
        val ready = exact.prepare()
        assertTrue("$ready", ready is PrepareResult.Ready)
        assertTrue(queries.containsAll(rowQueries))
        assertTrue(exact.export((ready as PrepareResult.Ready).backupId, File(root, "exact.pagaya.zip").path) is ExportResult.Verified)
        assertEquals(before, financialState())
    }
}
