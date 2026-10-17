package com.example.pagaya.data.backup

import com.example.pagaya.data.local.BackupDestination
import com.example.pagaya.data.local.BackupRecordEntity
import com.example.pagaya.data.local.BackupState
import com.example.pagaya.data.local.PagaYaDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.util.UUID

/**
 * Acceso a un documento elegido por la usuaria (un URI del selector del sistema). Es el límite que las
 * pruebas sustituyen para simular fallos de escritura o de relectura del proveedor.
 */
interface DocumentAccess {
    /** Abre el documento vacío para escribir. Cerrar el flujo debe dejar los datos escritos en el proveedor. */
    fun openForWrite(location: String): OutputStream

    fun openForRead(location: String): InputStream
}

/** Por qué no se completó una copia, en términos que la pantalla traduce a mensajes cotidianos. */
enum class BackupFailure(val code: String) {
    PREPARATION("PREPARACION"),

    /** La libreta supera los límites del formato. */
    TOO_LARGE("LIMITE"),

    /** La copia preparada ya no está disponible (por ejemplo, se cerró la app mientras se elegía destino). */
    EXPIRED("EXPIRADO"),
    WRITE("ESCRITURA"),

    /** Se escribió, pero el proveedor no permitió volver a leer el archivo: la copia no se confirmó. */
    UNCONFIRMED("RELECTURA"),

    /** El archivo releído no es válido o no corresponde a la captura. */
    VERIFICATION("VERIFICACION"),

    /** La app se cerró durante una copia anterior. */
    INTERRUPTED("INTERRUMPIDO"),
}

sealed interface PrepareResult {
    data class Ready(val backupId: String, val fileName: String) : PrepareResult

    /** Ya hay una operación de respaldo en curso: no se crea otra. */
    data object Busy : PrepareResult

    data class Failed(val failure: BackupFailure) : PrepareResult
}

sealed interface ExportResult {
    data class Verified(val exportedAtMillis: Long, val capturedAtMillis: Long) : ExportResult

    data object Busy : ExportResult

    /** [canRetry]: la misma captura sigue preparada y se puede guardar en otra ubicación. */
    data class Failed(val failure: BackupFailure, val canRetry: Boolean) : ExportResult
}

enum class ExportPhase { WRITING, VERIFYING }

/**
 * Estado del respaldo en archivo para esta libreta y esta instalación. [coveredRevision] es la mayor
 * revisión con una copia verificada en la época local actual; la libreta está respaldada solo si coincide
 * con [currentRevision]. Una copia antigua exportada después no cubre cambios posteriores a su captura.
 */
data class BackupStatus(
    val lastExport: BackupRecordEntity?,
    val currentRevision: Long,
    val coveredRevision: Long?,
) {
    val hasUnsavedChanges: Boolean get() = coveredRevision != currentRevision
}

/**
 * Generación, validación y exportación manual del respaldo en archivo (docs/05 y docs/19).
 *
 * 1. [prepare] lee metadatos, clientes, movimientos y revisiones en **una** transacción Room y registra
 *    la captura como PREPARANDO; fuera de la transacción escribe `data.json` y el ZIP en [workDir]
 *    (almacenamiento privado) y valida esa copia con [BackupValidator]. Después de capturar se puede seguir
 *    registrando: la copia representa solo la revisión capturada.
 * 2. [export] copia el ZIP al documento elegido, lo cierra, lo vuelve a leer a un temporal privado, lo
 *    valida y exige que sea exactamente la misma captura. Solo entonces marca VERIFICADO.
 *
 * Una preparación o exportación a la vez: un doble toque o dos pantallas no crean dos copias (Busy), y
 * la limpieza de temporales solo espera a que terminen.
 * Registrar una copia no incrementa `revision_datos`. Solo se borran temporales propios de [workDir];
 * nunca un archivo elegido por la usuaria.
 */
class BackupService(
    private val db: PagaYaDatabase,
    private val clock: Clock,
    private val workDir: File,
    private val appVersion: String,
    private val documents: DocumentAccess,
    private val limits: BackupLimits = BackupLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val records = db.backupRecordDao()
    private val validator = BackupValidator(limits)

    /** Una sola preparación o exportación a la vez: un segundo intento simultáneo recibe Busy. */
    private val userOperation = Mutex()

    /** Serializa todo acceso a registros, captura pendiente y temporales; la limpieza solo hace esperar. */
    private val stateLock = Mutex()

    /** Captura preparada y validada, a la espera de destino o de un reintento. */
    private class Prepared(val backupId: String, val archive: File, val manifest: BackupManifest)

    private var pending: Prepared? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStatus(): Flow<BackupStatus> =
        db.ledgerMetadataDao().observe().filterNotNull().flatMapLatest { meta ->
            combine(
                records.observeLatestVerified(BackupDestination.ARCHIVO, meta.ledgerId, meta.localEpoch),
                records.observeMaxVerifiedRevision(BackupDestination.ARCHIVO, meta.ledgerId, meta.localEpoch),
            ) { last, covered -> BackupStatus(last, meta.dataRevision, covered) }
        }

    /** Captura la libreta y prepara una copia privada validada. Una captura anterior no exportada se descarta. */
    suspend fun prepare(): PrepareResult = withContext(ioDispatcher) {
        if (!userOperation.tryLock()) return@withContext PrepareResult.Busy
        try {
            stateLock.withLock { prepareLocked() }
        } finally {
            userOperation.unlock()
        }
    }

    private suspend fun prepareLocked(): PrepareResult {
        pending?.let { abandon(it.backupId, "ABANDONADO") }
        val backupId = newId()
        val archive = archiveFile(backupId)
        return try {
            // Al volver de captureAndWrite las listas dejan de estar referenciadas; el GC decide
            // cuándo libera su memoria. El validador construye sus propias filas.
            val manifest = captureAndWrite(backupId, archive)
            when (val result = validator.validate(archive)) {
                is BackupValidation.Invalid -> {
                    val failure = if (result.problem == BackupProblem.LIMIT_EXCEEDED) BackupFailure.TOO_LARGE else BackupFailure.PREPARATION
                    abandon(backupId, failure.code)
                    PrepareResult.Failed(failure)
                }
                is BackupValidation.Valid -> {
                    if (result.summary.manifest != manifest) {
                        abandon(backupId, BackupFailure.PREPARATION.code)
                        return PrepareResult.Failed(BackupFailure.PREPARATION)
                    }
                    pending = Prepared(backupId, archive, manifest)
                    PrepareResult.Ready(backupId, BackupFormat.fileName(manifest.capturedAtMillis, backupId))
                }
            }
        } catch (e: CaptureTooLargeException) {
            abandon(backupId, BackupFailure.TOO_LARGE.code)
            PrepareResult.Failed(BackupFailure.TOO_LARGE)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { abandon(backupId, BackupFailure.INTERRUPTED.code) }
            throw e
        } catch (e: Exception) {
            abandon(backupId, BackupFailure.PREPARATION.code)
            PrepareResult.Failed(BackupFailure.PREPARATION)
        }
    }

    /**
     * Guarda la captura [backupId] en [destination], la relee y la valida. No se cancela a mitad: termina
     * siempre en VERIFICADO o en FALLIDO. Un fallo conserva la captura para elegir otra ubicación.
     */
    suspend fun export(backupId: String, destination: String, onPhase: (ExportPhase) -> Unit = {}): ExportResult =
        withContext(ioDispatcher + NonCancellable) {
            if (!userOperation.tryLock()) return@withContext ExportResult.Busy
            try {
                stateLock.withLock { exportLocked(backupId, destination, onPhase) }
            } finally {
                userOperation.unlock()
            }
        }

    private fun exportLocked(backupId: String, destination: String, onPhase: (ExportPhase) -> Unit): ExportResult {
        val prepared = preparedCapture(backupId)
            ?: return ExportResult.Failed(BackupFailure.EXPIRED, canRetry = false).also {
                markFailed(backupId, BackupFailure.EXPIRED.code)
            }
        setState(backupId, BackupState.PREPARANDO, errorCode = null)

        onPhase(ExportPhase.WRITING)
        try {
            documents.openForWrite(destination).use { out ->
                prepared.archive.inputStream().use { it.copyTo(out) }
            }
        } catch (e: Exception) {
            return fail(backupId, BackupFailure.WRITE)
        }

        onPhase(ExportPhase.VERIFYING)
        val copy = verificationFile(backupId)
        try {
            try {
                documents.openForRead(destination).use { input ->
                    copy.outputStream().use { LimitedInputStream(input, limits.maxArchiveBytes, "archivo releído").copyTo(it) }
                }
            } catch (e: BackupInvalidException) {
                return fail(backupId, BackupFailure.VERIFICATION)
            } catch (e: Exception) {
                return fail(backupId, BackupFailure.UNCONFIRMED)
            }
            val result = try {
                validator.validate(copy)
            } catch (e: Exception) {
                return fail(backupId, BackupFailure.UNCONFIRMED)
            }
            // Debe ser exactamente la captura preparada: mismo backup_id, revisión, conteos y SHA-256.
            if (result !is BackupValidation.Valid || result.summary.manifest != prepared.manifest) {
                return fail(backupId, BackupFailure.VERIFICATION)
            }
        } finally {
            copy.delete()
        }

        val now = clock.millis()
        setState(
            backupId,
            BackupState.VERIFICADO,
            errorCode = null,
            verifiedAtMillis = now,
            locator = destination.takeIf { it.length in 1..LOCATOR_MAX },
        )
        pending = null
        prepared.archive.delete()
        return ExportResult.Verified(now, prepared.manifest.capturedAtMillis)
    }

    /** La usuaria cerró el selector sin elegir destino: no es éxito ni error; se descarta la captura. */
    suspend fun cancel(backupId: String) = withContext(ioDispatcher + NonCancellable) {
        stateLock.withLock { abandon(backupId, "CANCELADO") }
    }

    /**
     * Cierra los intentos que quedaron PREPARANDO por un cierre de la app y borra temporales propios de
     * [workDir]. Conserva [keepBackupId] (la captura cuyo selector sigue abierto tras recrear la actividad)
     * y la captura pendiente de este proceso.
     */
    suspend fun recoverInterrupted(keepBackupId: String?) = withContext(ioDispatcher + NonCancellable) {
        stateLock.withLock {
            val keep = setOfNotNull(keepBackupId, pending?.backupId)
            records.findPreparing(BackupDestination.ARCHIVO)
                .filter { it.backupId !in keep }
                .forEach { markFailed(it.backupId, BackupFailure.INTERRUPTED.code) }
            ownTemporaryFiles().filter { ownerOf(it) !in keep }.forEach { it.delete() }
        }
    }

    // ---- interno ---------------------------------------------------------------------------

    /**
     * Captura y escribe `data.json` y el ZIP privados. Devuelve solo el manifiesto: las listas de filas
     * dejan de estar referenciadas al volver, antes de que el validador construya las suyas.
     */
    private fun captureAndWrite(backupId: String, archive: File): BackupManifest {
        val capture = capture(backupId)
        workDir.mkdirs()
        val dataFile = dataFile(backupId)
        try {
            return BackupWriter.write(capture, appVersion, dataFile, archive)
        } finally {
            dataFile.delete()
        }
    }

    /**
     * Lee una revisión coherente de la libreta y registra el intento, todo en una transacción. Antes de
     * cargar filas comprueba los límites del formato con conteos y bytes de texto calculados en SQLite:
     * una libreta que no cabría en un respaldo válido se rechaza sin traerla a memoria.
     */
    private fun capture(backupId: String): LedgerCapture = db.runInTransaction<LedgerCapture> {
        val meta = checkNotNull(db.ledgerMetadataDao().get()) { "Falta la fila de metadata" }
        val size = records.measureLedger()
        if (size.clients > limits.maxClients || size.movements > limits.maxMovements || size.revisions > limits.maxRevisions) {
            throw CaptureTooLargeException("filas ${size.clients}/${size.movements}/${size.revisions}")
        }
        val minimumDataBytes = size.textBytes + MIN_CLIENT_ROW_BYTES * size.clients +
            MIN_MOVEMENT_ROW_BYTES * size.movements + MIN_REVISION_ROW_BYTES * size.revisions
        if (minimumDataBytes > limits.maxDataBytes) throw CaptureTooLargeException("data.json de al menos $minimumDataBytes bytes")
        val capturedAt = clock.millis()
        val capture = LedgerCapture(
            backupId = backupId,
            capturedAtMillis = capturedAt,
            ledgerId = meta.ledgerId,
            ledgerCreatedAtMillis = meta.createdAtMillis,
            dataRevision = meta.dataRevision,
            nextEntryOrder = meta.nextEntryOrder,
            clients = db.clientDao().all(),
            movements = db.movementDao().all(),
            revisions = db.movementRevisionDao().all(),
        )
        // Registrar el intento no es un cambio de negocio: no incrementa revision_datos.
        records.insert(
            BackupRecordEntity(
                backupId = backupId,
                destination = BackupDestination.ARCHIVO,
                ledgerId = meta.ledgerId,
                localEpoch = meta.localEpoch,
                dataRevision = meta.dataRevision,
                capturedAtMillis = capturedAt,
                state = BackupState.PREPARANDO,
                verifiedAtMillis = null,
                locator = null,
                driveAccountFingerprint = null,
                errorCode = null,
            ),
        )
        capture
    }

    /** La captura en memoria o, tras recrear el proceso, la que quedó en disco y sigue siendo válida. */
    private fun preparedCapture(backupId: String): Prepared? {
        pending?.takeIf { it.backupId == backupId && it.archive.isFile }?.let { return it }
        val record = records.find(backupId, BackupDestination.ARCHIVO) ?: return null
        if (record.state == BackupState.VERIFICADO) return null
        val archive = archiveFile(backupId)
        if (!archive.isFile) return null
        val result = try {
            validator.validate(archive)
        } catch (e: Exception) {
            return null
        }
        val manifest = (result as? BackupValidation.Valid)?.summary?.manifest ?: return null
        if (manifest.backupId != backupId || manifest.dataRevision != record.dataRevision || manifest.capturedAtMillis != record.capturedAtMillis) {
            return null
        }
        return Prepared(backupId, archive, manifest).also { pending = it }
    }

    private fun fail(backupId: String, failure: BackupFailure): ExportResult {
        markFailed(backupId, failure.code)
        return ExportResult.Failed(failure, canRetry = pending?.backupId == backupId)
    }

    private fun abandon(backupId: String, code: String) {
        markFailed(backupId, code)
        if (pending?.backupId == backupId) pending = null
        ownTemporaryFiles().filter { ownerOf(it) == backupId }.forEach { it.delete() }
    }

    private fun markFailed(backupId: String, code: String) = setState(backupId, BackupState.FALLIDO, errorCode = code)

    /** Una copia verificada nunca se degrada: un intento posterior no borra el último éxito. */
    private fun setState(
        backupId: String,
        state: BackupState,
        errorCode: String?,
        verifiedAtMillis: Long? = null,
        locator: String? = null,
    ) {
        val record = records.find(backupId, BackupDestination.ARCHIVO) ?: return
        if (record.state == BackupState.VERIFICADO) return
        records.update(record.copy(state = state, errorCode = errorCode, verifiedAtMillis = verifiedAtMillis, locator = locator))
    }

    private fun dataFile(backupId: String) = File(workDir, "captura-$backupId.data.json.tmp")

    private fun archiveFile(backupId: String) = File(workDir, "captura-$backupId.pagaya.zip.tmp")

    private fun verificationFile(backupId: String) = File(workDir, "verificacion-$backupId.pagaya.zip.tmp")

    /** Solo archivos con los nombres que genera este servicio; cualquier otro se deja intacto. */
    private fun ownTemporaryFiles(): List<File> =
        workDir.listFiles()?.filter { it.isFile && OWN_TEMPORARY.matches(it.name) }.orEmpty()

    private fun ownerOf(file: File): String = OWN_TEMPORARY.matchEntire(file.name)!!.groupValues[1]

    companion object {
        /** Subcarpeta privada de temporales, dentro de `noBackupFilesDir`. */
        const val WORK_DIR = "respaldo_tmp"

        private const val LOCATOR_MAX = 2048

        // Bytes mínimos de cada fila en data.json sin contar sus textos variables (claves, UUID, enteros
        // de un dígito, booleanos). Son cotas inferiores: la estimación nunca rechaza una libreta que cabría.
        private const val MIN_CLIENT_ROW_BYTES = 100L
        private const val MIN_MOVEMENT_ROW_BYTES = 200L
        private const val MIN_REVISION_ROW_BYTES = 150L

        private val OWN_TEMPORARY = Regex(
            "(?:captura|verificacion)-([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})" +
                "\\.(?:data\\.json|pagaya\\.zip)\\.tmp",
        )
    }
}

/** La libreta supera los límites del formato; se detecta antes de cargar sus filas. */
private class CaptureTooLargeException(detail: String) : RuntimeException(detail)
