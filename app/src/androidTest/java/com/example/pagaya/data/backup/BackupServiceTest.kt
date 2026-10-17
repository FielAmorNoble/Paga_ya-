package com.example.pagaya.data.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.data.RegisterMovementResult
import com.example.pagaya.data.SaveClientResult
import com.example.pagaya.data.local.BackupDestination
import com.example.pagaya.data.local.BackupState
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.MutableClock
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.data.local.TestDatabaseFile
import com.example.pagaya.data.local.randomId
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.CorrectionInput
import com.example.pagaya.domain.MovementInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Generación, exportación y verificación del respaldo con Room real y destinos simulados. Ninguna prueba
 * usa la base operativa ni el directorio de temporales de la app: todo vive en archivos `test-*`.
 */
@RunWith(AndroidJUnit4::class)
class BackupServiceTest {

    private val file = TestDatabaseFile()
    private val clock = MutableClock(TestDatabaseFile.FIXED_CLOCK.millis())
    private val db: PagaYaDatabase = file.open(clock)
    private val clients = ClientRepository(db, clock)
    private val movements = MovementRepository(db, clock)
    private val root = File(file.context.cacheDir, "test-backup-${UUID.randomUUID()}")
    private val workDir = File(root, "work")
    private val destinations = File(root, "destinos").apply { mkdirs() }
    private val documents = FakeDocuments()
    private val service = newService()
    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct3 = LocalDate.of(2026, 10, 3)

    @After
    fun tearDown() {
        db.close()
        file.delete()
        root.deleteRecursively()
    }

    private fun newService(docs: DocumentAccess = documents) = BackupService(db, clock, workDir, "0.1.0-test", docs)

    /** Destinos en archivos de prueba; permite fallar al escribir, al releer o devolver otro contenido. */
    private inner class FakeDocuments : DocumentAccess {
        var failWrite = false
        var failRead = false
        var readOverride: ByteArray? = null
        var writeGate: CountDownLatch? = null
        var writeStarted: CompletableDeferred<Unit>? = null

        override fun openForWrite(location: String): OutputStream {
            writeStarted?.complete(Unit)
            writeGate?.await(10, TimeUnit.SECONDS)
            if (failWrite) throw IOException("escritura simulada fallida")
            return FileOutputStream(File(location))
        }

        override fun openForRead(location: String): InputStream {
            if (failRead) throw SecurityException("el proveedor no permite leer")
            readOverride?.let { return ByteArrayInputStream(it) }
            return FileInputStream(File(location))
        }
    }

    private fun destination(name: String = "copia-${UUID.randomUUID()}.pagaya.zip") = File(destinations, name).path

    private fun newClient(name: String): String = runBlocking {
        (clients.create(randomId(), ClientInput(name, "300 000 0000", "")) as SaveClientResult.Saved).clientId
    }

    private fun register(clientId: String, type: MovementType, amount: Long, date: LocalDate, description: String = ""): String = runBlocking {
        (movements.register(randomId(), clientId, type, MovementInput(amount.toString(), date, description)) as RegisterMovementResult.Saved).movementId
    }

    private fun revision() = db.ledgerMetadataDao().get()!!.dataRevision

    private fun prepared(s: BackupService = service): PrepareResult.Ready = runBlocking { s.prepare() as PrepareResult.Ready }

    private fun exportOk(ready: PrepareResult.Ready, to: String = destination(), s: BackupService = service): ExportResult.Verified =
        runBlocking { s.export(ready.backupId, to) } as ExportResult.Verified

    private fun record(backupId: String) = db.backupRecordDao().find(backupId, BackupDestination.ARCHIVO)!!

    private fun status() = runBlocking { withTimeout(5_000) { service.observeStatus().first() } }

    private fun validSummary(path: String): BackupSummary =
        (BackupValidator().validate(File(path)) as BackupValidation.Valid).summary

    private fun ownTemporaries() = workDir.listFiles().orEmpty().map { it.name }

    // ---- éxito ---------------------------------------------------------------------------

    @Test
    fun anEmptyLedgerIsExportedAndVerified() {
        val before = revision()
        val ready = prepared()
        assertTrue(ready.fileName.matches(Regex("backup_\\d{4}-\\d{2}-\\d{2}_\\d{6}_${ready.backupId}\\.pagaya\\.zip")))
        assertEquals(BackupState.PREPARANDO, record(ready.backupId).state)
        val to = destination()
        val result = exportOk(ready, to)

        val r = record(ready.backupId)
        assertEquals(BackupState.VERIFICADO, r.state)
        assertEquals(result.exportedAtMillis, r.verifiedAtMillis)
        assertEquals(to, r.locator)
        assertNull(r.errorCode)
        assertEquals("registrar la copia no es un cambio de negocio", before, revision())
        val summary = validSummary(to)
        assertEquals(ready.backupId, summary.manifest.backupId)
        assertEquals(0L, summary.manifest.clients)
        assertTrue("no quedan temporales", ownTemporaries().isEmpty())
        val s = status()
        assertEquals(ready.backupId, s.lastExport?.backupId)
        assertFalse(s.hasUnsavedChanges)
    }

    @Test
    fun theExportedFileContainsEveryRowExactly() {
        val maria = newClient("María Ñúñez «Sintética»")
        val pedro = newClient("Pedro Sintético")
        register(maria, MovementType.FIADO, 999_999_999, oct1, "Máximo \"comillas\" \\ 😀")
        val abono = register(maria, MovementType.ABONO, 15_000, oct3, "Abono")
        runBlocking {
            movements.correct(randomId(), abono, 1, CorrectionInput("20000", oct3, "Abono", "Primera corrección"))
            movements.correct(randomId(), abono, 2, CorrectionInput("20000", LocalDate.of(2026, 10, 4), "Abono corregido", "Segunda corrección"))
        }
        val wrong = register(maria, MovementType.FIADO, 7_777, oct3)
        runBlocking { movements.annul(randomId(), wrong, 1, "Anulación sintética") }
        register(pedro, MovementType.FIADO, 10_000, oct1)
        runBlocking {
            movements.payFullBalance(randomId(), pedro, 10_000, oct3)
            clients.archive(pedro)
        }

        val ready = prepared()
        val to = destination()
        exportOk(ready, to)
        val summary = validSummary(to)
        assertEquals(1, summary.archivedClients)
        assertEquals(1, summary.annulledMovements)
        assertEquals(999_999_999L - 20_000, summary.totalPendingCop)
        assertEquals(revision(), summary.manifest.dataRevision)

        val data = ZipInputStream(FileInputStream(to)).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == BackupFormat.DATA_ENTRY }
            zip.readBytes().toString(Charsets.UTF_8)
        }
        val root = StrictJsonReader(data.byteInputStream(), 20).nextAny() as Map<*, *>
        val exportedMovements = (root["movimientos"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
        for (m in db.movementDao().all()) {
            val row = exportedMovements.getValue(m.id)
            assertEquals(JsonNumber(m.amountCop.toString()), row["valor_cop"])
            assertEquals(m.description, row["descripcion"])
            assertEquals(m.date.toString(), row["fecha"])
            assertEquals(m.active, row["activo"])
            assertEquals(JsonNumber(m.version.toString()), row["version"])
        }
        val exportedRevisions = (root["revisiones"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
        val stored = db.movementRevisionDao().all()
        assertEquals(3, stored.size)
        for (r in stored) assertEquals(r.previousStateJson, exportedRevisions.getValue(r.id)["estado_anterior_json"])
        val exportedClients = (root["clientes"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
        for (c in db.clientDao().all()) {
            assertEquals(c.name, exportedClients.getValue(c.id)["nombre"])
            assertEquals(c.active, exportedClients.getValue(c.id)["activo"])
        }
    }

    @Test
    fun theRealContentResolverWritesAndRereadsALocalFile() {
        newClient("Cliente Archivo Local")
        val real = newService(ContentResolverDocuments(file.context.contentResolver))
        val ready = prepared(real)
        val target = File(destinations, ready.fileName).apply { createNewFile() }
        val result = runBlocking { real.export(ready.backupId, Uri.fromFile(target).toString()) }
        assertTrue("$result", result is ExportResult.Verified)
        assertEquals(ready.backupId, validSummary(target.path).manifest.backupId)
    }

    // ---- captura consistente -------------------------------------------------------------

    @Test
    fun eachCaptureIsOneCoherentRevisionDuringConcurrentWrites() = runBlocking {
        val clientId = newClient("Cliente Concurrente")
        register(clientId, MovementType.FIADO, 1_000_000, oct1)
        val baseRevision = revision()
        val baseMovements = db.movementDao().all().size
        val writer = launch(Dispatchers.IO) {
            while (isActive) register(clientId, MovementType.FIADO, 1_000, oct3)
        }
        try {
            repeat(6) {
                val ready = service.prepare() as PrepareResult.Ready
                val to = destination()
                val result = service.export(ready.backupId, to)
                assertTrue("$result", result is ExportResult.Verified)
                val summary = validSummary(to)
                // Cada fiado suma un movimiento y una revisión: una captura mezclada lo rompería.
                assertEquals(summary.manifest.dataRevision - baseRevision, summary.manifest.movements - baseMovements)
                assertEquals(summary.manifest.dataRevision, record(ready.backupId).dataRevision)
            }
        } finally {
            writer.cancel()
            writer.join()
        }
        assertTrue("hubo escrituras durante las capturas", revision() > baseRevision + 6)
    }

    @Test
    fun changesAfterTheCaptureAreNotMarkedAsBackedUp() {
        val clientId = newClient("Cliente Posterior")
        register(clientId, MovementType.FIADO, 5_000, oct1)
        val ready = prepared()
        val capturedRevision = revision()
        register(clientId, MovementType.FIADO, 2_000, oct3) // después de capturar, antes de exportar
        exportOk(ready)
        assertEquals(capturedRevision, record(ready.backupId).dataRevision)
        val s = status()
        assertEquals(ready.backupId, s.lastExport?.backupId)
        assertEquals(capturedRevision, s.coveredRevision)
        assertEquals(capturedRevision + 1, s.currentRevision)
        assertTrue(s.hasUnsavedChanges)
        assertEquals(capturedRevision, validSummary(record(ready.backupId).locator!!).manifest.dataRevision)
    }

    // ---- fallos --------------------------------------------------------------------------

    @Test
    fun aWriteFailureKeepsTheLastVerifiedBackupAndAllowsRetryingTheSameCapture() {
        newClient("Cliente Reintento")
        val first = prepared()
        exportOk(first)
        newClient("Cliente Nuevo")

        val second = prepared()
        documents.failWrite = true
        val failed = runBlocking { service.export(second.backupId, destination()) }
        assertEquals(ExportResult.Failed(BackupFailure.WRITE, canRetry = true), failed)
        assertEquals(BackupState.FALLIDO, record(second.backupId).state)
        assertEquals("ESCRITURA", record(second.backupId).errorCode)
        val s = status()
        assertEquals("se conserva el último éxito", first.backupId, s.lastExport?.backupId)
        assertTrue(s.hasUnsavedChanges)

        documents.failWrite = false
        clock.millis += 60_000 // el reintento se verifica después del primer éxito
        val to = destination()
        exportOk(second, to)
        assertEquals("el reintento usa la misma captura", second.backupId, validSummary(to).manifest.backupId)
        assertEquals(second.backupId, status().lastExport?.backupId)
        assertFalse(status().hasUnsavedChanges)
    }

    @Test
    fun aDestinationThatCannotBeRereadIsNotConfirmed() {
        newClient("Cliente Sin Relectura")
        val ready = prepared()
        documents.failRead = true
        val result = runBlocking { service.export(ready.backupId, destination()) }
        assertEquals(ExportResult.Failed(BackupFailure.UNCONFIRMED, canRetry = true), result)
        assertEquals(BackupState.FALLIDO, record(ready.backupId).state)
        assertEquals("RELECTURA", record(ready.backupId).errorCode)
        assertNull(record(ready.backupId).verifiedAtMillis)
        assertNull(status().lastExport)
    }

    @Test
    fun aRereadFileThatDiffersFromTheCaptureIsRejected() {
        newClient("Cliente Verificación")
        // Otra copia válida de la misma libreta no basta: debe ser la misma captura.
        val other = prepared()
        val otherPath = destination()
        exportOk(other, otherPath)
        val ready = prepared()
        documents.readOverride = File(otherPath).readBytes()
        assertEquals(ExportResult.Failed(BackupFailure.VERIFICATION, canRetry = true), runBlocking { service.export(ready.backupId, destination()) })

        // Contenido truncado al releer.
        val bytes = File(otherPath).readBytes()
        documents.readOverride = bytes.copyOf(bytes.size / 2)
        assertEquals(ExportResult.Failed(BackupFailure.VERIFICATION, canRetry = true), runBlocking { service.export(ready.backupId, destination()) })
        assertEquals("VERIFICACION", record(ready.backupId).errorCode)
        assertEquals(other.backupId, status().lastExport?.backupId)
    }

    @Test
    fun cancellingThePickerIsNeitherSuccessNorErrorAndCleansTheCapture() {
        newClient("Cliente Cancelado")
        val ready = prepared()
        assertTrue(ownTemporaries().isNotEmpty())
        runBlocking { service.cancel(ready.backupId) }
        assertEquals(BackupState.FALLIDO, record(ready.backupId).state)
        assertEquals("CANCELADO", record(ready.backupId).errorCode)
        assertTrue(ownTemporaries().isEmpty())
        assertNull(status().lastExport)
        // Exportar una captura cancelada ya no es posible.
        val late = runBlocking { service.export(ready.backupId, destination()) }
        assertEquals(ExportResult.Failed(BackupFailure.EXPIRED, canRetry = false), late)
    }

    @Test
    fun backupOperationsAreSerialized() = runBlocking {
        newClient("Cliente Doble Toque")
        val ready = service.prepare() as PrepareResult.Ready
        val gate = CountDownLatch(1)
        documents.writeGate = gate
        documents.writeStarted = CompletableDeferred()
        val export = async(Dispatchers.IO) { service.export(ready.backupId, destination()) }
        documents.writeStarted!!.await()
        // Mientras una copia se escribe, un segundo toque no prepara otra ni exporta dos veces.
        assertEquals(PrepareResult.Busy, service.prepare())
        assertEquals(ExportResult.Busy, service.export(ready.backupId, destination()))
        gate.countDown()
        assertTrue(export.await() is ExportResult.Verified)
        assertEquals(1, db.backupRecordDao().findPreparing(BackupDestination.ARCHIVO).size + countVerified())
    }

    private fun countVerified(): Int = db.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT COUNT(*) FROM backup_registro WHERE estado = 'VERIFICADO'"))
        .use { it.moveToFirst(); it.getInt(0) }

    // ---- recuperación y limpieza ---------------------------------------------------------

    @Test
    fun aCaptureSurvivesARecreatedProcessWhileThePickerIsOpen() {
        newClient("Cliente Proceso")
        val ready = prepared()
        // Otro proceso: nueva instancia del servicio, sin estado en memoria.
        val restarted = newService()
        runBlocking { restarted.recoverInterrupted(keepBackupId = ready.backupId) }
        assertEquals(BackupState.PREPARANDO, record(ready.backupId).state)
        val result = runBlocking { restarted.export(ready.backupId, destination()) }
        assertTrue("$result", result is ExportResult.Verified)
    }

    @Test
    fun recoveryClosesInterruptedAttemptsAndDeletesOnlyOwnTemporaries() {
        newClient("Cliente Limpieza")
        val interrupted = prepared()
        val restarted = newService()
        val foreign = listOf(
            "notas.txt",
            "captura-${interrupted.backupId}.pagaya.zip", // sin .tmp: no es un nombre propio
            "captura-no-es-uuid.pagaya.zip.tmp",
            "backup_2026-10-05_213045_${interrupted.backupId}.pagaya.zip",
        ).map { File(workDir, it).apply { writeText("ajeno") } }
        val orphan = File(workDir, "verificacion-${UUID.randomUUID()}.pagaya.zip.tmp").apply { writeText("x") }
        val exported = destination()
        File(exported).writeText("archivo elegido por la usuaria")

        runBlocking { restarted.recoverInterrupted(keepBackupId = null) }

        val r = record(interrupted.backupId)
        assertEquals(BackupState.FALLIDO, r.state)
        assertEquals("INTERRUMPIDO", r.errorCode)
        for (f in foreign) assertTrue("${f.name} se conserva", f.exists())
        assertFalse(orphan.exists())
        assertFalse(File(workDir, "captura-${interrupted.backupId}.pagaya.zip.tmp").exists())
        assertTrue("nunca toca destinos elegidos", File(exported).exists())
        assertNull(status().lastExport)
    }

    @Test
    fun aNewFailureNeverDowngradesAVerifiedRecord() {
        newClient("Cliente Verificado")
        val ok = prepared()
        exportOk(ok)
        val verified = record(ok.backupId)
        runBlocking {
            service.cancel(ok.backupId)
            service.recoverInterrupted(null)
        }
        assertEquals(verified, record(ok.backupId))
        assertNotNull(status().lastExport)
    }
}
