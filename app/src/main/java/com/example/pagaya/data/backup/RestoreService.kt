package com.example.pagaya.data.backup

import android.content.Context
import android.util.AtomicFile
import com.example.pagaya.data.local.*
import com.example.pagaya.domain.BusinessCalendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

data class LedgerOverview(val ledgerId: String, val epoch: String, val revision: Long,
    val clients: Long, val movements: Long, val revisions: Long, val pendingCop: Long) {
    val hasData: Boolean get() = clients != 0L || movements != 0L || revisions != 0L
}

data class RestorePreview(val id: String, val source: BackupSummary, val current: LedgerOverview, val recovery: Boolean) {
    val emptyReplacesData: Boolean get() = source.manifest.clients == 0L && current.hasData
}

enum class RestoreFailure { READ, TOO_LARGE, INVALID, FUTURE_DATE, STAGING, RECOVERY, EXPIRED, APPLY, INTERRUPTED }
sealed interface RestorePreparation {
    data class Ready(val preview: RestorePreview) : RestorePreparation
    data class Failed(val failure: RestoreFailure, val problem: BackupProblem? = null) : RestorePreparation
    data object Busy : RestorePreparation
}
sealed interface RestoreApplyResult {
    data class Applied(val id: String) : RestoreApplyResult
    data class ReviewRequired(val preview: RestorePreview) : RestoreApplyResult
    data class Failed(val failure: RestoreFailure) : RestoreApplyResult
    data object Busy : RestoreApplyResult
}
data class RestoreNotice(val id: String, val committed: Boolean)

/** Puntos deterministas para pruebas; no cambian la política de producción. Un Error simula corte
 * abrupto (sin manejarlo como fallo normal); una Exception prueba rollback y tratamiento de errores.
 */
enum class RestoreCheckpoint {
    RECOVERY_WRITE, RECOVERY_VERIFY, BEFORE_REPLACE, AFTER_DELETE, AFTER_CLIENTS,
    AFTER_MOVEMENTS, AFTER_REVISIONS, BEFORE_METADATA, AFTER_METADATA, AFTER_COMMIT,
}

/** Copia privada -> validador único -> Room temporal comprobado -> preview -> exclusión -> copia
 * previa verificada -> UNA transacción operativa. Ningún archivo SQLite sustituye una base abierta.
 */
class RestoreService(
    private val context: Context,
    private val db: PagaYaDatabase,
    private val backup: BackupService,
    private val documents: DocumentAccess,
    private val clock: Clock,
    private val workDir: File,
    private val recoveryDir: File,
    private val limits: BackupLimits = BackupLimits(),
    private val checkpoint: (RestoreCheckpoint) -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val coordination = db.coordination
    private val validator = BackupValidator(limits)
    private val journal get() = AtomicFile(File(workDir, "application.intent"))
    private val stagePrefix = "restore-stage-${digest(workDir.absolutePath.byteInputStream()).take(12)}-"
    private val stagePattern = Regex(Regex.escape(stagePrefix) + UUID_PATTERN + "\\.db")
    private val sourcePattern = Regex("source-$UUID_PATTERN\\.zip\\.tmp")
    private data class Pending(val preview: RestorePreview, val archive: File, val archiveSha: String,
        val databaseName: String, val databaseSha: String)
    private var pending: Pending? = null

    suspend fun prepare(location: String): RestorePreparation = prepareSource(false) { documents.openForRead(location) }

    suspend fun prepareRecovery(): RestorePreparation = prepareSource(true) {
        checkNotNull(recoverySource()) { "No hay copia previa comprobada" }.inputStream()
    }

    private suspend fun prepareSource(recovery: Boolean, input: () -> InputStream): RestorePreparation = withContext(Dispatchers.IO) {
        if (!coordination.operation.tryLock()) return@withContext RestorePreparation.Busy
        var archive: File? = null
        var stageName: String? = null
        var phase = RestoreFailure.READ
        try {
            if (backup.hasPreparingBackupLocked()) return@withContext RestorePreparation.Busy
            discardPending()
            check(workDir.mkdirs() || workDir.isDirectory)
            val id = newId().also { check(BackupFormat.isUuid(it)) }
            archive = File(workDir, "source-$id.zip.tmp")
            stageName = "$stagePrefix$id.db"
            input().use { source -> archive.outputStream().use { LimitedInputStream(source, limits.maxArchiveBytes, "archivo seleccionado").copyTo(it) } }
            phase = RestoreFailure.STAGING
            val summary = importValidated(archive, stageName)
            // Una base cerrada es solo staging: se fija su huella antes de volver a abrirla para copiar
            // filas. No se intercambia ni renombra sobre pagaya.db.
            check(!File(context.getDatabasePath(stageName).path + "-wal").let { it.exists() && it.length() != 0L })
            val databaseSha = digest(context.getDatabasePath(stageName).inputStream())
            val preview = RestorePreview(id, summary, overview(), recovery)
            pending = Pending(preview, archive, digest(archive.inputStream()), stageName, databaseSha)
            RestorePreparation.Ready(preview)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupInvalidException) {
            RestorePreparation.Failed(when (e.problem) {
                BackupProblem.FUTURE_DATE -> RestoreFailure.FUTURE_DATE
                BackupProblem.LIMIT_EXCEEDED -> RestoreFailure.TOO_LARGE
                else -> RestoreFailure.INVALID
            }, e.problem)
        } catch (e: Exception) {
            RestorePreparation.Failed(phase)
        } finally {
            if (pending?.archive != archive) {
                archive?.delete()
                stageName?.let { context.deleteDatabase(it) }
            }
            coordination.operation.unlock()
        }
    }

    /** El ParsedData deja de estar referenciado al salir; no se guarda en el preview. */
    private fun importValidated(archive: File, stageName: String): BackupSummary {
        val imported = importRows(archive, stageName)
        openStage(stageName).useStage { stage ->
            check(BackupDatabaseTransfer.inspect(stage, imported.manifest, today(), validator) == imported)
        }
        return imported
    }

    private fun importRows(archive: File, stageName: String): BackupSummary {
        val validated = validator.readValidated(archive, today())
        openStage(stageName).useStage { BackupDatabaseTransfer.import(validated.data, it) }
        return validated.summary
    }

    /** No cancelable después de confirmar. Orden de bloqueos: operation -> writes -> Room. */
    suspend fun confirm(id: String): RestoreApplyResult = withContext(Dispatchers.IO + NonCancellable) {
        if (!coordination.operation.tryLock()) return@withContext RestoreApplyResult.Busy
        try {
            if (backup.hasPreparingBackupLocked()) return@withContext RestoreApplyResult.Busy
            coordination.writes.withLock { applyLocked(id) }
        } finally { coordination.operation.unlock() }
    }

    private fun applyLocked(id: String): RestoreApplyResult {
        val selected = pending?.takeIf { it.preview.id == id }
            ?: return RestoreApplyResult.Failed(RestoreFailure.EXPIRED)
        var failure = RestoreFailure.APPLY
        try {
            val current = overview()
            if (current != selected.preview.current) {
                val updated = selected.preview.copy(current = current)
                pending = selected.copy(preview = updated)
                return RestoreApplyResult.ReviewRequired(updated)
            }
            check(digest(selected.archive.inputStream()) == selected.archiveSha)
            check(digest(context.getDatabasePath(selected.databaseName).inputStream()) == selected.databaseSha)
            check(!File(context.getDatabasePath(selected.databaseName).path + "-wal").let { it.exists() && it.length() != 0L })
            openStage(selected.databaseName).useStage { stage ->
                check(BackupDatabaseTransfer.inspect(stage, selected.preview.source.manifest, today(), validator) == selected.preview.source)
                failure = RestoreFailure.RECOVERY
                val recovery = if (current.hasData) backup.createRecoveryLocked(recoveryDir, checkpoint) else null
                failure = RestoreFailure.APPLY
                writeIntent(id) // fsync + reemplazo atómico antes del commit; no guarda cuentas ni URI.
                checkpoint(RestoreCheckpoint.BEFORE_REPLACE)
                db.runInTransaction {
                    val old = db.ledgerMetadataDao().get()!!
                    check(old.ledgerId == current.ledgerId && old.localEpoch == current.epoch && old.dataRevision == current.revision)
                    db.openHelper.writableDatabase.execSQL("DELETE FROM movimiento_revision")
                    db.openHelper.writableDatabase.execSQL("DELETE FROM movimiento")
                    db.openHelper.writableDatabase.execSQL("DELETE FROM cliente")
                    checkpoint(RestoreCheckpoint.AFTER_DELETE)
                    BackupDatabaseTransfer.copyRows(stage, db, checkpoint)
                    val source = stage.ledgerMetadataDao().get()!!
                    checkpoint(RestoreCheckpoint.BEFORE_METADATA)
                    check(db.ledgerMetadataDao().update(old.copy(
                        ledgerId = source.ledgerId, createdAtMillis = source.createdAtMillis,
                        dataRevision = source.dataRevision, nextEntryOrder = source.nextEntryOrder,
                        localEpoch = newId().also { check(BackupFormat.isUuid(it) && it != old.localEpoch) }, lastRestoreId = id, lastRestoreAtMillis = clock.millis(),
                        recoveryCopyLocator = recovery?.path ?: old.recoveryCopyLocator,
                    )) == 1)
                    checkpoint(RestoreCheckpoint.AFTER_METADATA)
                }
                checkpoint(RestoreCheckpoint.AFTER_COMMIT)
            }
            backup.invalidatePreparedLocked()
            discardPending()
            return RestoreApplyResult.Applied(id)
        } catch (e: Exception) {
            // Si falló la notificación/limpieza después del commit, el marcador es la autoridad.
            if (db.ledgerMetadataDao().get()?.lastRestoreId == id) {
                backup.invalidatePreparedLocked()
                discardPending()
                return RestoreApplyResult.Applied(id)
            }
            if (intentId() == id) journal.delete()
            return RestoreApplyResult.Failed(if (e is BackupInvalidException && e.problem == BackupProblem.FUTURE_DATE) RestoreFailure.FUTURE_DATE else failure)
        }
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        coordination.operation.withLock { if (pending?.preview?.id == id) discardPending() }
    }

    suspend fun hasRecovery(): Boolean = withContext(Dispatchers.IO) {
        recoverySource() != null // Solo lectura: las copias verificadas nunca se sobrescriben ni limpian.
    }

    /** El marcador transaccional distingue corte antes/después del commit. La intención persiste
     * hasta que la interfaz reconoce el aviso. La limpieza no entra en el directorio de recuperación.
     */
    suspend fun recoverInterrupted(): RestoreNotice? = withContext(Dispatchers.IO) {
        coordination.operation.withLock {
            val id = intentId()
            val notice = id?.let { RestoreNotice(it, db.ledgerMetadataDao().get()?.lastRestoreId == it) }
            workDir.listFiles().orEmpty().filter { sourcePattern.matches(it.name) && it != pending?.archive }.forEach { it.delete() }
            context.getDatabasePath("unused").parentFile?.listFiles().orEmpty()
                .filter { stagePattern.matches(it.name) && it.name != pending?.databaseName }
                .forEach { context.deleteDatabase(it.name) }
            // Solo el JSON temporal de candidatos previos; nunca archivos .pagaya.zip verificados.
            recoveryDir.listFiles().orEmpty().filter { Regex("recovery-$UUID_PATTERN\\.data\\.tmp").matches(it.name) }.forEach { it.delete() }
            notice
        }
    }

    suspend fun acknowledge(id: String) = withContext(Dispatchers.IO) {
        coordination.operation.withLock { if (intentId() == id) journal.delete() }
    }

    private fun overview(): LedgerOverview = db.runInTransaction<LedgerOverview> {
        val meta = db.ledgerMetadataDao().get()!!
        val size = db.backupRecordDao().measureLedger()
        val total = db.query("SELECT COALESCE(SUM(CASE tipo WHEN 'FIADO' THEN valor_cop ELSE -valor_cop END), 0) FROM movimiento WHERE activo = 1", emptyArray())
            .use { check(it.moveToFirst()); it.getLong(0) }
        LedgerOverview(meta.ledgerId, meta.localEpoch, meta.dataRevision, size.clients, size.movements, size.revisions, total)
    }

    private fun recoverySource(): File? {
        val locator = db.ledgerMetadataDao().get()?.recoveryCopyLocator ?: return null
        val candidate = File(locator).canonicalFile
        if (candidate.parentFile != recoveryDir.canonicalFile || !Regex("recovery-$UUID_PATTERN\\.pagaya\\.zip").matches(candidate.name) || !candidate.isFile) return null
        val checked = validator.validate(candidate) as? BackupValidation.Valid ?: return null
        val record = db.backupRecordDao().find(checked.summary.manifest.backupId, BackupDestination.RECUPERACION) ?: return null
        return candidate.takeIf {
            record.state == BackupState.VERIFICADO && record.locator?.let { path -> File(path).canonicalFile == candidate } == true &&
                record.ledgerId == checked.summary.manifest.ledgerId && record.dataRevision == checked.summary.manifest.dataRevision &&
                record.capturedAtMillis == checked.summary.manifest.capturedAtMillis
        }
    }

    private fun discardPending() {
        val old = pending ?: return
        pending = null
        old.archive.delete()
        context.deleteDatabase(old.databaseName)
    }
    private fun openStage(name: String) = PagaYaDatabase.build(context, name, clock, newId)
    private inline fun <T> PagaYaDatabase.useStage(block: (PagaYaDatabase) -> T): T = try { block(this) } finally { close() }
    private fun today() = BusinessCalendar.today(clock)
    private fun writeIntent(id: String) {
        check(workDir.mkdirs() || workDir.isDirectory)
        val out = journal.startWrite()
        try { out.write(id.toByteArray(Charsets.US_ASCII)); out.fd.sync(); journal.finishWrite(out); check(intentId() == id) }
        catch (e: Exception) { journal.failWrite(out); throw e }
    }
    private fun intentId(): String? = try {
        journal.openRead().use { input ->
            val bytes = ByteArray(37)
            val count = input.read(bytes)
            if (count == 36 && input.read() == -1) String(bytes, 0, count, Charsets.US_ASCII).takeIf(BackupFormat::isUuid) else null
        }
    } catch (e: java.io.FileNotFoundException) { null }

    companion object {
        const val WORK_DIR = "restauracion_tmp"
        const val RECOVERY_DIR = "recuperacion"
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
        private fun digest(input: InputStream): String = input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }
}
