package com.example.pagaya.data.backup

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pagaya.data.*
import com.example.pagaya.data.local.*
import com.example.pagaya.domain.*
import com.example.pagaya.ui.clients.ClientFormViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Solo test-*.db; jamás abre ni exporta la pagaya.db de Dev. */
@RunWith(AndroidJUnit4::class)
class RestoreServiceTest {
    private val sourceFile = TestDatabaseFile()
    private val targetFile = TestDatabaseFile()
    private val clock = MutableClock(TestDatabaseFile.FIXED_CLOCK.millis())
    private val source = sourceFile.open(clock)
    private val target = targetFile.open(clock)
    private val root = File(targetFile.context.cacheDir, "test-restore-${UUID.randomUUID()}").apply { mkdirs() }
    private val work = File(root, "restore")
    private val recovery = File(root, "recovery")
    private var readCount = 0
    private var readFailure = false
    private var writeGate: CountDownLatch? = null
    private var writeStarted: CompletableDeferred<Unit>? = null
    private val documents = object : DocumentAccess {
        override fun openForRead(location: String): java.io.InputStream {
            readCount++
            if (readFailure) throw java.io.IOException("fallo de prueba")
            return File(location).inputStream()
        }
        override fun openForWrite(location: String): java.io.OutputStream {
            writeStarted?.complete(Unit)
            check(writeGate?.await(10, TimeUnit.SECONDS) != false)
            return File(location).outputStream()
        }
    }
    private val backup = BackupService(target, clock, File(root, "backup"), "test", documents)
    private var fault: (RestoreCheckpoint) -> Unit = {}
    private fun service(limits: BackupLimits = BackupLimits()) = RestoreService(targetFile.context, target, backup, documents,
        clock, work, recovery, limits, checkpoint = { fault(it) })
    private val restore = service()
    private val clients = ClientRepository(target, clock)
    private val movements = MovementRepository(target, clock)
    private val oct1 = LocalDate.of(2026, 10, 1)

    @After fun close() {
        source.close(); target.close(); sourceFile.delete(); targetFile.delete(); root.deleteRecursively()
    }
    private fun meta(db: PagaYaDatabase = target) = db.ledgerMetadataDao().get()!!
    private fun business(db: PagaYaDatabase = target): List<Any> = listOf(
        db.clientDao().all().sortedBy { it.id }, db.movementDao().all().sortedBy { it.id },
        db.movementRevisionDao().all().sortedBy { it.id },
        meta(db).let { listOf(it.ledgerId, it.createdAtMillis, it.dataRevision, it.nextEntryOrder) },
    )
    private fun snapshot(): List<Any> = business() + meta()
    private fun records() = target.query("SELECT COUNT(*) FROM backup_registro", emptyArray()).use { it.moveToFirst(); it.getLong(0) }
    private fun client(db: PagaYaDatabase, name: String): String = runBlocking {
        (ClientRepository(db, clock).create(randomId(), ClientInput(name, "+57 300 000 0000", "Nota sintética 😀\n\"\\")) as SaveClientResult.Saved).clientId
    }
    private fun movement(db: PagaYaDatabase, client: String, amount: Long, type: MovementType = MovementType.FIADO): String = runBlocking {
        (MovementRepository(db, clock).register(randomId(), client, type, MovementInput("$amount", oct1, "Descripción 😀")) as RegisterMovementResult.Saved).movementId
    }
    private fun richSource(): File = runBlocking {
        val a = client(source, "María Ñúñez \"Sintética\" 😀")
        val b = client(source, "Archivado Sintético")
        client(source, "Sin movimientos")
        movement(source, a, 60_000)
        val abono = movement(source, a, 15_000, MovementType.ABONO)
        val repo = MovementRepository(source, clock)
        assertTrue(repo.correct(randomId(), abono, 1, CorrectionInput("20000", oct1, "Descripción 😀", "Corrección 1")) is ChangeMovementResult.Saved)
        assertTrue(repo.correct(randomId(), abono, 2, CorrectionInput("20000", oct1, "Descripción final 😀", "Corrección 2")) is ChangeMovementResult.Saved)
        val annulled = movement(source, a, 777)
        assertTrue(repo.annul(randomId(), annulled, 1, "Anulación sintética") is ChangeMovementResult.Saved)
        movement(source, b, 10_000)
        assertTrue(repo.payFullBalance(randomId(), b, 10_000, oct1) is RegisterMovementResult.Saved)
        assertEquals(ClientStatusResult.Changed, ClientRepository(source, clock).archive(b))
        export(source)
    }
    private fun export(db: PagaYaDatabase): File = runBlocking {
        val s = if (db === target) backup else BackupService(db, clock, File(root, "source-backup"), "test", documents)
        val prepared = s.prepare() as PrepareResult.Ready
        File(root, "export-${UUID.randomUUID()}.arbitrary").also {
            assertTrue(s.export(prepared.backupId, it.path) is ExportResult.Verified)
        }
    }
    private fun preview(archive: File, s: RestoreService = restore): RestorePreview = runBlocking {
        val result = s.prepare(archive.path)
        assertTrue("$result", result is RestorePreparation.Ready)
        (result as RestorePreparation.Ready).preview
    }
    private fun apply(preview: RestorePreview, s: RestoreService = restore) = runBlocking {
        assertEquals(RestoreApplyResult.Applied(preview.id), s.confirm(preview.id))
    }
    private fun seedTarget() { movement(target, client(target, "Estado anterior sintético"), 7_000) }

    @Test fun exportToAnotherEmptyDatabasePreservesEveryRowAndResetsOperationalState() = runBlocking {
        val archive = richSource()
        val oldEpoch = meta().localEpoch
        val p = preview(archive)
        assertFalse(p.current.hasData)
        assertEquals(40_000L, p.source.totalPendingCop)
        assertEquals(1, p.source.archivedClients)
        assertEquals(1, p.source.annulledMovements)
        apply(p)
        assertEquals(business(source), business())
        assertNotEquals(oldEpoch, meta().localEpoch)
        assertEquals(p.id, meta().lastRestoreId)
        assertEquals(clock.millis(), meta().lastRestoreAtMillis)
        assertNull(meta().recoveryCopyLocator)
        assertEquals(0L, records()) // No importa la verificación del exportador ni necesita copia de una vacía.
        val status = backup.observeStatus().first()
        assertNull(status.lastExport); assertTrue(status.hasUnsavedChanges)
        assertEquals(RestoreNotice(p.id, true), restore.recoverInterrupted())
        restore.acknowledge(p.id)
        assertNull(restore.recoverInterrupted())
    }

    @Test fun searchNameIsRegeneratedRatherThanImported() {
        val id = client(source, "María   ÑÚÑEZ 😀")
        source.openHelper.writableDatabase.execSQL("UPDATE cliente SET nombre_busqueda = 'incorrecto' WHERE id = ?", arrayOf(id))
        apply(preview(export(source)))
        assertEquals(ClientRules.searchKey("María   ÑÚÑEZ 😀"), target.clientDao().findById(id)!!.searchName)
    }

    @Test fun replaceAndRecoverPreviousUseTheSameProtocolAndProtectBothCopies() = runBlocking {
        seedTarget()
        val before = business()
        val p = preview(richSource())
        apply(p)
        val previous = File(meta().recoveryCopyLocator!!)
        val bytes = previous.readBytes()
        assertTrue(restore.hasRecovery())
        assertTrue(BackupValidator().validate(previous) is BackupValidation.Valid)
        val recoveryPreview = (restore.prepareRecovery() as RestorePreparation.Ready).preview
        assertTrue(recoveryPreview.recovery)
        assertEquals(7_000L, recoveryPreview.source.totalPendingCop)
        apply(recoveryPreview)
        assertEquals(before, business())
        assertArrayEquals(bytes, previous.readBytes()) // Selección nunca se sobreescribe.
        assertNotEquals(previous.path, meta().recoveryCopyLocator)
        val toggle = (restore.prepareRecovery() as RestorePreparation.Ready).preview
        apply(toggle)
        assertEquals(business(source), business())
    }

    @Test fun aValidEmptyCopyRequiresExplicitWarningAndCanBeUndone() = runBlocking {
        seedTarget(); val before = business()
        val p = preview(export(source))
        assertTrue(p.emptyReplacesData)
        apply(p)
        assertTrue(target.clientDao().all().isEmpty())
        apply((restore.prepareRecovery() as RestorePreparation.Ready).preview)
        assertEquals(before, business())
    }

    @Test fun restoringTwiceReplacesWithoutDuplicatingAndCreatesAnotherEpoch() {
        val archive = richSource()
        apply(preview(archive)); val firstEpoch = meta().localEpoch
        apply(preview(archive))
        assertEquals(business(source), business())
        assertNotEquals(firstEpoch, meta().localEpoch)
        assertNotNull(meta().recoveryCopyLocator)
    }

    @Test fun invalidFileChangesNoOperationalDataAndKeepsTheLastExport() = runBlocking {
        seedTarget(); export(target)
        val before = snapshot(); val count = records(); val last = backup.observeStatus().first().lastExport
        val result = restore.prepare(File(root, "invalid").apply { writeText("no zip") }.path)
        assertTrue(result is RestorePreparation.Failed)
        assertEquals(before, snapshot()); assertEquals(count, records()); assertEquals(last, backup.observeStatus().first().lastExport)
    }

    @Test fun actualReadLimitRejectsBeforeImportAndReadFailureIsNotSuccess() = runBlocking {
        seedTarget(); val before = snapshot(); val archive = richSource()
        assertEquals(RestorePreparation.Failed(RestoreFailure.TOO_LARGE, BackupProblem.LIMIT_EXCEEDED), service(BackupLimits(maxArchiveBytes = 64)).prepare(archive.path))
        readFailure = true
        assertEquals(RestorePreparation.Failed(RestoreFailure.READ), restore.prepare(archive.path))
        assertEquals(before, snapshot()); assertEquals(0L, records())
    }

    @Test fun futureDatesRejectWithoutChangingDatesOrAccounts() = runBlocking {
        val archive = richSource(); seedTarget(); val before = snapshot()
        clock.millis -= 6 * 24 * 60 * 60 * 1000L
        assertEquals(RestorePreparation.Failed(RestoreFailure.FUTURE_DATE, BackupProblem.FUTURE_DATE), restore.prepare(archive.path))
        assertEquals(before, snapshot())
    }

    @Test fun confirmationUsesThePrivateCopyAndNeverRereadsTheProvider() {
        val archive = richSource(); val p = preview(archive); val reads = readCount
        archive.writeText("proveedor cambió el contenido")
        apply(p)
        assertEquals(reads, readCount); assertEquals(business(source), business())
    }

    @Test fun privateArchiveTamperingInvalidatesConfirmationBeforeRecovery() = runBlocking {
        seedTarget(); val before = snapshot(); val p = preview(richSource())
        File(work, "source-${p.id}.zip.tmp").appendText("alterado")
        assertEquals(RestoreApplyResult.Failed(RestoreFailure.APPLY), restore.confirm(p.id))
        assertEquals(before, snapshot()); assertEquals(0L, records())
    }

    @Test fun privateStagingTamperingInvalidatesConfirmation() = runBlocking {
        seedTarget(); val before = snapshot(); val p = preview(richSource())
        val stageName = targetFile.context.databaseList().single { it.startsWith("restore-stage-") && it.endsWith("${p.id}.db") }
        val stage = PagaYaDatabase.build(targetFile.context, stageName, clock, ::randomId)
        try { stage.openHelper.writableDatabase.execSQL("UPDATE cliente SET nota = 'alterado'") } finally { stage.close() }
        assertEquals(RestoreApplyResult.Failed(RestoreFailure.APPLY), restore.confirm(p.id))
        assertEquals(before, snapshot())
    }

    @Test fun changedLedgerRequiresAnotherReviewAndThenCanBeConfirmed() = runBlocking {
        seedTarget(); val p = preview(richSource()); client(target, "Cambio posterior")
        val changed = snapshot()
        val review = restore.confirm(p.id) as RestoreApplyResult.ReviewRequired
        assertTrue(review.preview.current.revision > p.current.revision)
        assertEquals(changed, snapshot()); assertEquals(0L, records())
        apply(review.preview)
        assertEquals(business(source), business())
    }

    @Test fun epochChangeWithSameRevisionAlsoRequiresReview() = runBlocking {
        val p = preview(richSource())
        target.ledgerMetadataDao().update(meta().copy(localEpoch = randomId()))
        assertTrue(restore.confirm(p.id) is RestoreApplyResult.ReviewRequired)
        assertTrue(target.clientDao().all().isEmpty())
    }

    @Test fun pendingBackupAndAnExportInProgressBothBlockRestore() = runBlocking {
        seedTarget(); val p = preview(richSource()); val before = snapshot()
        val ready = backup.prepare() as PrepareResult.Ready
        assertEquals(RestoreApplyResult.Busy, restore.confirm(p.id))
        writeGate = CountDownLatch(1); writeStarted = CompletableDeferred()
        val exporting = async(Dispatchers.IO) { backup.export(ready.backupId, File(root, "blocked-destination").path) }
        writeStarted!!.await()
        try { assertEquals(RestoreApplyResult.Busy, restore.confirm(p.id)); assertEquals(before, snapshot()) }
        finally { writeGate!!.countDown() }
        assertTrue(exporting.await() is ExportResult.Verified)
        apply(p)
    }

    @Test fun concurrentOldWriteWaitsAndIsRejectedAfterRestoreAndBackupCannotStart() = runBlocking {
        seedTarget(); val p = preview(richSource()); val epoch = meta().localEpoch
        val started = CompletableDeferred<Unit>(); val gate = CountDownLatch(1)
        fault = { if (it == RestoreCheckpoint.AFTER_DELETE) { started.complete(Unit); check(gate.await(10, TimeUnit.SECONDS)) } }
        val applying = async(Dispatchers.IO) { restore.confirm(p.id) }
        started.await()
        val writing = async(Dispatchers.IO) {
            try { clients.create(randomId(), ClientInput("Solicitud antigua", "", ""), epoch); false }
            catch (e: StaleLedgerException) { true }
        }
        try { delay(100); assertFalse(writing.isCompleted); assertEquals(PrepareResult.Busy, backup.prepare()) }
        finally { gate.countDown() }
        assertTrue(applying.await() is RestoreApplyResult.Applied)
        assertTrue(writing.await()); assertEquals(business(source), business())
    }

    @Test fun recoveryWriteOrVerificationFailureKeepsAccountsAndPreviousVerifiedCopy() = runBlocking {
        seedTarget(); val archive = richSource(); apply(preview(archive))
        val previous = File(meta().recoveryCopyLocator!!); val bytes = previous.readBytes(); val before = snapshot()
        for (point in listOf(RestoreCheckpoint.RECOVERY_WRITE, RestoreCheckpoint.RECOVERY_VERIFY)) {
            val p = preview(archive)
            fault = { if (it == point) throw java.io.IOException("fallo determinista") }
            assertEquals(RestoreApplyResult.Failed(RestoreFailure.RECOVERY), restore.confirm(p.id))
            assertEquals(before, snapshot()); assertArrayEquals(bytes, previous.readBytes()); assertTrue(restore.hasRecovery())
            fault = {}
        }
    }

    @Test fun everyReplacementCheckpointRollsBackRowsAndMetadataCompletely() = runBlocking {
        seedTarget(); export(target); val before = snapshot(); val archive = richSource()
        val last = backup.observeStatus().first().lastExport
        for (point in listOf(RestoreCheckpoint.AFTER_DELETE, RestoreCheckpoint.AFTER_CLIENTS, RestoreCheckpoint.AFTER_MOVEMENTS,
            RestoreCheckpoint.AFTER_REVISIONS, RestoreCheckpoint.BEFORE_METADATA, RestoreCheckpoint.AFTER_METADATA)) {
            val p = preview(archive); fault = { if (it == point) throw java.io.IOException("fallo determinista") }
            assertEquals("$point", RestoreApplyResult.Failed(RestoreFailure.APPLY), restore.confirm(p.id))
            assertEquals("$point", before, snapshot()); assertEquals(last, backup.observeStatus().first().lastExport)
            fault = {}
        }
    }

    @Test fun sqliteConstraintFailuresDuringInsertAndMetadataUpdateRollback() = runBlocking {
        seedTarget(); val archive = richSource(); val before = snapshot()
        for (point in listOf(RestoreCheckpoint.AFTER_CLIENTS, RestoreCheckpoint.BEFORE_METADATA)) {
            val p = preview(archive)
            fault = { if (it == point) {
                if (point == RestoreCheckpoint.AFTER_CLIENTS) target.openHelper.writableDatabase.execSQL("UPDATE cliente SET nombre = ''")
                else target.openHelper.writableDatabase.execSQL("UPDATE libreta_metadata SET revision_datos = -1")
            } }
            assertEquals(RestoreApplyResult.Failed(RestoreFailure.APPLY), restore.confirm(p.id))
            assertEquals(before, snapshot()); fault = {}
        }
    }

    private class ProcessCut : Error("corte simulado, no muerte real")
    @Test fun simulatedCutsBeforeAndAfterCommitAreRecognizedByThePersistentMarker() = runBlocking {
        seedTarget(); val archive = richSource(); val before = snapshot()
        for (point in listOf(RestoreCheckpoint.BEFORE_REPLACE, RestoreCheckpoint.AFTER_METADATA, RestoreCheckpoint.AFTER_COMMIT)) {
            val p = preview(archive); fault = { if (it == point) throw ProcessCut() }
            try { restore.confirm(p.id); fail("Faltó el corte $point") } catch (e: ProcessCut) { /* muerte simulada */ }
            fault = {}
            val restarted = service()
            assertEquals(RestoreNotice(p.id, point == RestoreCheckpoint.AFTER_COMMIT), restarted.recoverInterrupted())
            if (point == RestoreCheckpoint.AFTER_COMMIT) assertEquals(business(source), business()) else assertEquals(before, snapshot())
            restarted.acknowledge(p.id); assertNull(restarted.recoverInterrupted())
        }
    }

    @Test fun anExceptionAfterCommitCannotReportRollbackOrLoseTheOutcome() = runBlocking {
        val p = preview(richSource())
        fault = { if (it == RestoreCheckpoint.AFTER_COMMIT) throw java.io.IOException("falló notificación") }
        assertEquals(RestoreApplyResult.Applied(p.id), restore.confirm(p.id))
        assertEquals(business(source), business()); assertEquals(RestoreNotice(p.id, true), service().recoverInterrupted())
    }

    @Test fun staleFormAndEveryOldBusinessCommandCannotWriteIntoTheNewEpoch() = runBlocking {
        val archive = richSource(); apply(preview(archive)); val epoch = meta().localEpoch
        val form = ClientFormViewModel(SavedStateHandle(mapOf("ledgerEpoch" to epoch, "name" to "Formulario antiguo")), clients)
        apply(preview(archive)); val before = snapshot()
        form.save()
        withTimeout(5_000) { while (form.state.value.failure == null) delay(10) }
        assertNull(form.state.value.savedClientId); assertEquals(before, snapshot())
        val c = target.clientDao().all().first { it.active }
        val m = target.movementDao().all().first { it.active && it.type == MovementType.FIADO }
        suspend fun stale(block: suspend () -> Unit) { try { block(); fail("Escritura obsoleta aceptada") } catch (e: StaleLedgerException) {} }
        stale { clients.update(c.id, ClientInput("Otro nombre", "", ""), epoch) }
        stale { clients.archive(c.id, epoch) }; stale { clients.reactivate(c.id, epoch) }
        stale { movements.register(randomId(), c.id, MovementType.FIADO, MovementInput("100", oct1, ""), epoch) }
        stale { movements.payFullBalance(randomId(), c.id, 40_000, oct1, epoch) }
        stale { movements.correct(randomId(), m.id, m.version, CorrectionInput("61000", oct1, "Cambio", "Motivo"), epoch) }
        stale { movements.annul(randomId(), m.id, m.version, "Motivo", epoch) }
        assertEquals(before, snapshot())
    }

    @Test fun cancelAndStartupCleanupTouchOnlyOwnedStagingAndNeverRecoveryOrUserFiles() = runBlocking {
        seedTarget(); val archive = richSource(); apply(preview(archive)); val previous = File(meta().recoveryCopyLocator!!)
        val bytes = previous.readBytes(); val before = snapshot()
        val p = preview(archive)
        val foreign = File(work, "archivo-usuario.pagaya.zip").apply { writeText("ajeno") }
        val orphan = File(work, "source-${UUID.randomUUID()}.zip.tmp").apply { writeText("incompleto") }
        restore.cancel(p.id)
        assertEquals(before, snapshot()); assertTrue(foreign.exists()); assertTrue(archive.exists())
        service().recoverInterrupted()
        assertFalse(orphan.exists()); assertArrayEquals(bytes, previous.readBytes())
        assertTrue(restore.hasRecovery())
    }

    @Test fun onlyArchivedOrAnnulledDataStillRequiresRecoveryAndCorruptRecoveryIsNotOffered() = runBlocking {
        val id = client(target, "Solo archivado")
        assertEquals(ClientStatusResult.Changed, clients.archive(id))
        val p = preview(export(source)); assertTrue(p.current.hasData); apply(p)
        val previous = File(meta().recoveryCopyLocator!!)
        assertTrue(previous.isFile); assertTrue(restore.hasRecovery())
        previous.writeText("dañado")
        assertFalse(restore.hasRecovery())
        val before = snapshot()
        assertTrue(restore.prepareRecovery() is RestorePreparation.Failed)
        assertEquals(before, snapshot())
    }
}
