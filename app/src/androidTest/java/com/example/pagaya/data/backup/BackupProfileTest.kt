package com.example.pagaya.data.backup

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pagaya.data.local.*
import com.example.pagaya.domain.MovementSnapshot
import com.example.pagaya.domain.SnapshotJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Medición opt-in: -e backupProfile reference|medium|nearBytes|movementNear|movementMax|clientMax|revisionMedium|revisionNear.
 * Usa solo una base test-*.db. No es un benchmark estadístico ni una búsqueda del punto de OOM.
 */
@RunWith(AndroidJUnit4::class)
class BackupProfileTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = TestDatabaseFile()
    private val db = file.open()
    private val root = File(file.context.cacheDir, "test-profile-${UUID.randomUUID()}").apply { mkdirs() }
    private val runtime = Runtime.getRuntime()

    @After fun close() { db.close(); file.delete(); root.deleteRecursively() }

    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "PROFILE $message\n")
    })

    private data class Shape(val clients: Int, val movements: Int, val revisions: Int, val textLength: Int = 0)

    @Test fun measureSyntheticBackup() {
        val scenario = InstrumentationRegistry.getArguments().getString("backupProfile")
        assumeTrue("Perfil opt-in", scenario != null)
        val shape = when (scenario) {
            "reference" -> Shape(1_000, 20_000, 5_000)
            "medium" -> Shape(2_000, 40_000, 10_000)
            "nearBytes" -> Shape(2_000, 40_000, 10_000, 450)
            "movementNear" -> Shape(1_000, 80_000, 0)
            "movementMax" -> Shape(1_000, 100_000, 5_000)
            "clientMax" -> Shape(100_000, 1_000, 1_000)
            "revisionNear" -> Shape(1_000, 1_000, 70_000)
            "revisionMedium" -> Shape(1_000, 1_000, 45_000)
            else -> error("Perfil desconocido")
        }
        // Cotas conservadoras de trabajo para decidir ANTES de cargar listas, no una estimación
        // del pico real. No se aumenta el heap ni se captura OutOfMemoryError.
        val projected = 16L * 1024 * 1024 + shape.clients * 700L + shape.movements * 1600L + shape.revisions * 3000L +
            shape.textLength * 2L * (shape.movements + shape.revisions)
        val memory = ActivityManager.MemoryInfo().also {
            (file.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        report("scenario=$scenario rows=$shape maxHeap=${runtime.maxMemory()} projected=$projected availableSystem=${memory.availMem}")
        assumeTrue("Margen de seguridad insuficiente; no se intentó la carga", projected < runtime.maxMemory() * 0.60 && memory.availMem > 512L * 1024 * 1024)
        seed(shape)
        System.gc() // Solo antes de la serie, nunca dentro de una fase medida. Es una solicitud al GC.
        val archive = File(root, "synthetic.pagaya.zip")
        val manifest = captureAndWrite(archive)
        report("dataBytes=${manifest.dataBytes} archiveBytes=${archive.length()}")
        assertTrue(manifest.dataBytes <= BackupLimits().maxDataBytes)
        val validated = phase("validation") { BackupValidator().validate(archive) }
        assertTrue("$validated", validated is BackupValidation.Valid)
        assertEquals(manifest, (validated as BackupValidation.Valid).summary.manifest)
        assertEquals(shape.clients.toLong(), manifest.clients)
        assertEquals(shape.movements.toLong(), manifest.movements)
        assertEquals(shape.revisions.toLong(), manifest.revisions)
        // Además del desglose reproducible, ejecutar el servicio real con sus transacciones,
        // registro de intento y relectura. El destino es privado y sintético.
        val service = BackupService(db, TestDatabaseFile.FIXED_CLOCK, File(root, "work"), "profile", object : DocumentAccess {
            override fun openForWrite(location: String) = File(location).outputStream()
            override fun openForRead(location: String) = File(location).inputStream()
        })
        val before = db.ledgerMetadataDao().get()
        val ready = phase("service.prepare.total") { runBlocking { service.prepare() } }
        assertTrue("$ready", ready is PrepareResult.Ready)
        val exported = phase("service.export.write+reread+validate") {
            runBlocking { service.export((ready as PrepareResult.Ready).backupId, File(root, "export.pagaya.zip").path) }
        }
        assertTrue("$exported", exported is ExportResult.Verified)
        assertEquals(before, db.ledgerMetadataDao().get())
    }

    private fun captureAndWrite(archive: File): BackupManifest {
        val capture = phase("capture.measure+transaction+lists") {
            db.runInTransaction<LedgerCapture> {
                val meta = db.ledgerMetadataDao().get()!!
                val size = db.backupRecordDao().measureLedger()
                assertTrue(size.clients <= BackupLimits().maxClients)
                LedgerCapture(randomId(), TestDatabaseFile.FIXED_CLOCK.millis(), meta.ledgerId,
                    meta.createdAtMillis, meta.dataRevision, meta.nextEntryOrder,
                    db.clientDao().all(), db.movementDao().all(), db.movementRevisionDao().all())
            }
        }
        return phase("write.data+hash+zip") {
            BackupWriter.write(capture, "profile", File(root, "data.json"), archive)
        }
    }

    /** Instantes y picos OBSERVADOS por muestreo cada 10 ms. Java usado incluye basura aún no
     * recolectada; native es Debug.getNativeHeapAllocatedSize. No mide PSS, GPU ni un máximo absoluto.
     */
    private fun <T> phase(name: String, block: () -> T): T {
        fun javaUsed() = runtime.totalMemory() - runtime.freeMemory()
        val initialJava = javaUsed()
        val initialNative = Debug.getNativeHeapAllocatedSize()
        var peakJava = initialJava
        var peakNative = initialNative
        var samples = 0
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                peakJava = maxOf(peakJava, javaUsed())
                peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                samples++
                Thread.sleep(10)
            }
        }.apply { start() }
        val start = SystemClock.elapsedRealtimeNanos()
        try { return block() } finally {
            val elapsed = SystemClock.elapsedRealtimeNanos() - start
            running.set(false)
            sampler.join()
            peakJava = maxOf(peakJava, javaUsed())
            peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
            report("phase=$name ms=${elapsed / 1_000_000} initialJava=$initialJava peakObservedJava=$peakJava initialNative=$initialNative peakObservedNative=$peakNative samples=$samples intervalMs=10")
        }
    }

    /** Inserciones incrementales sintéticas con las restricciones reales activas. Correcciones
     * continuas; anulaciones solo de ABONO para no crear prefijos negativos; archivados sin deuda.
     */
    private fun seed(shape: Shape) {
        fun id(kind: Int, n: Int) = "%08x-0000-4000-8000-%012x".format(kind, n)
        val payload = "x".repeat(shape.textLength)
        db.runInTransaction {
            repeat(shape.clients) { i ->
                db.clientDao().insert(client(id(1, i), "Cliente Sintético $i").copy(
                    active = i < shape.movements || i % 2 == 0,
                ))
            }
            repeat(shape.movements) { i ->
                val count = shape.revisions / shape.movements + if (i < shape.revisions % shape.movements) 1 else 0
                val original = movement(id(1, i % shape.clients), i + 1L, id(2, i),
                    type = if ((i / shape.clients) % 2 == 0) MovementType.FIADO else MovementType.ABONO,
                    amountCop = 1_000).copy(description = "Sintético 😀$payload")
                fun state(version: Int) = original.copy(
                    description = if (version == 1) original.description else "Sintético versión $version 😀$payload",
                    version = version.toLong(), modifiedAtMillis = T0 + version - 1,
                )
                val annul = count > 0 && original.type == MovementType.ABONO && i % 17 == 0
                val final = if (annul) state(count).copy(active = false, version = count + 1L, modifiedAtMillis = T0 + count)
                    else state(count + 1)
                db.movementDao().insert(final)
                repeat(count) { v ->
                    val before = state(v + 1)
                    db.movementRevisionDao().insert(MovementRevisionEntity(
                        id(3 + v, i), original.id, v + 1L, v + 2L,
                        if (annul && v == count - 1) RevisionAction.ANULAR else RevisionAction.EDITAR,
                        SnapshotJson.encode(MovementSnapshot(before.id, before.clientId, before.date, before.entryOrder,
                            before.type, before.amountCop, before.description, before.origin, before.createdAtMillis,
                            before.modifiedAtMillis, before.active, before.version)),
                        T0 + v + 1, "Cambio sintético 😀",
                    ))
                }
            }
            val meta = db.ledgerMetadataDao().get()!!
            db.ledgerMetadataDao().update(meta.copy(dataRevision = (shape.clients + shape.movements + shape.revisions).toLong(), nextEntryOrder = shape.movements + 1L))
        }
    }
}
