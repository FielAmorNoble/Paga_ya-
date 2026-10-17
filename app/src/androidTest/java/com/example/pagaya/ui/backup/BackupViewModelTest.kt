package com.example.pagaya.ui.backup

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pagaya.data.backup.BackupFailure
import com.example.pagaya.data.backup.BackupService
import com.example.pagaya.data.backup.DocumentAccess
import com.example.pagaya.data.local.BackupDestination
import com.example.pagaya.data.local.BackupState
import com.example.pagaya.data.local.MutableClock
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.data.local.TestDatabaseFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Estados de la pantalla «Respaldo» con Room real y un destino simulado. */
@RunWith(AndroidJUnit4::class)
class BackupViewModelTest {

    private val file = TestDatabaseFile()
    private val clock = MutableClock(TestDatabaseFile.FIXED_CLOCK.millis())
    private val db: PagaYaDatabase = file.open(clock)
    private val root = File(file.context.cacheDir, "test-backup-vm-${UUID.randomUUID()}")
    private val destinations = File(root, "destinos").apply { mkdirs() }
    private var failWrite = false
    private val documents = object : DocumentAccess {
        override fun openForWrite(location: String): OutputStream {
            if (failWrite) throw IOException("escritura simulada fallida")
            return FileOutputStream(File(location))
        }

        override fun openForRead(location: String): InputStream = FileInputStream(File(location))
    }
    private val service = BackupService(db, clock, File(root, "work"), "0.1.0-test", documents)

    @After
    fun tearDown() {
        db.close()
        file.delete()
        root.deleteRecursively()
    }

    private fun destination() = File(destinations, "copia-${UUID.randomUUID()}.pagaya.zip").path

    /** Los ViewModels se crean y usan en el hilo principal, como en la app. */
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private inline fun <T> await(describe: String, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(20)
        }
        fail("No se alcanzó: $describe")
        throw AssertionError()
    }

    private fun operation(vm: BackupViewModel) = onMain { vm.state.value.operation }

    private fun records() = db.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT backup_id, estado, error_codigo FROM backup_registro"))
        .use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) } }

    @Test
    fun doubleTapPreparesASingleCaptureAndOpensThePickerOnce() {
        val vm = onMain { BackupViewModel(SavedStateHandle(), service) }
        onMain {
            vm.onSave()
            vm.onSave()
        }
        val choosing = await("selector pedido") { operation(vm) as? BackupOperation.ChoosingDestination }
        assertEquals(choosing.fileName, onMain { vm.state.value.pickerRequest })
        onMain { vm.onPickerLaunched() }
        assertNull(onMain { vm.state.value.pickerRequest })
        assertEquals(1, records().size)
        onMain { vm.onSave() } // botón desactivado mientras se elige destino
        assertEquals(1, records().size)
    }

    @Test
    fun savingShowsSuccessOnlyAfterVerification() {
        val vm = onMain { BackupViewModel(SavedStateHandle(), service) }
        onMain { vm.onSave() }
        await("selector") { operation(vm) as? BackupOperation.ChoosingDestination }
        onMain { vm.onDestinationChosen(destination()) }
        val saved = await("guardada") { operation(vm) as? BackupOperation.Saved }
        val status = await("estado") { (onMain { vm.state.value.status } as? StatusState.Loaded)?.status?.takeIf { it.lastExport != null } }
        assertEquals(saved.exportedAtMillis, status.lastExport!!.verifiedAtMillis)
        assertEquals(BackupState.VERIFICADO.name, records().single().second)
        onMain { vm.onMessageShown() }
        assertEquals(BackupOperation.Idle, operation(vm))
    }

    @Test
    fun cancellingThePickerShowsNoErrorAndNoSuccess() {
        val vm = onMain { BackupViewModel(SavedStateHandle(), service) }
        onMain { vm.onSave() }
        await("selector") { operation(vm) as? BackupOperation.ChoosingDestination }
        onMain { vm.onDestinationChosen(null) }
        assertEquals(BackupOperation.Idle, operation(vm))
        await("intento cancelado") { records().singleOrNull()?.takeIf { it.third == "CANCELADO" } }
    }

    @Test
    fun aWriteFailureOffersAnotherLocationForTheSameCapture() {
        val vm = onMain { BackupViewModel(SavedStateHandle(), service) }
        onMain { vm.onSave() }
        await("selector") { operation(vm) as? BackupOperation.ChoosingDestination }
        failWrite = true
        onMain { vm.onDestinationChosen(destination()) }
        val failed = await("fallo") { operation(vm) as? BackupOperation.Failed }
        assertEquals(BackupOperation.Failed(BackupFailure.WRITE, canRetry = true), failed)
        failWrite = false
        onMain { vm.onRetry() }
        assertTrue(operation(vm) is BackupOperation.ChoosingDestination)
        onMain { vm.onDestinationChosen(destination()) }
        await("guardada") { operation(vm) as? BackupOperation.Saved }
        assertEquals(listOf(BackupState.VERIFICADO.name), records().map { it.second })
    }

    @Test
    fun aRecreatedProcessContinuesThePickerOrReportsTheInterruption() {
        // 1. El proceso murió con el selector abierto: al volver se espera el resultado y se exporta esa captura.
        val first = onMain { BackupViewModel(SavedStateHandle(), service) }
        onMain { first.onSave() }
        val choosing = await("selector") { operation(first) as? BackupOperation.ChoosingDestination }
        val backupId = records().single().first
        val restored = SavedStateHandle(
            mapOf("backupPendingId" to backupId, "backupFileName" to choosing.fileName, "backupPhase" to "choosing"),
        )
        val freshService = BackupService(db, clock, File(root, "work"), "0.1.0-test", documents)
        val second = onMain { BackupViewModel(restored, freshService) }
        assertEquals(choosing, operation(second))
        assertNull("no vuelve a abrir el selector", onMain { second.state.value.pickerRequest })
        onMain { second.onDestinationChosen(destination()) }
        await("guardada tras recrear") { operation(second) as? BackupOperation.Saved }

        // 2. El proceso murió mientras se escribía: no queda «Preparando» ni un éxito falso.
        val third = onMain { BackupViewModel(SavedStateHandle(), service) }
        onMain { third.onSave() }
        await("selector") { operation(third) as? BackupOperation.ChoosingDestination }
        val interruptedId = db.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT backup_id FROM backup_registro WHERE estado = 'PREPARANDO'"))
            .use { it.moveToFirst(); it.getString(0) }
        val dead = SavedStateHandle(mapOf("backupPendingId" to interruptedId, "backupFileName" to "x", "backupPhase" to "exporting"))
        val fourth = onMain { BackupViewModel(dead, BackupService(db, clock, File(root, "work"), "0.1.0-test", documents)) }
        assertEquals(BackupOperation.Failed(BackupFailure.INTERRUPTED, canRetry = false), operation(fourth))
        await("intento cerrado") {
            db.backupRecordDao().find(interruptedId, BackupDestination.ARCHIVO)?.takeIf { it.state == BackupState.FALLIDO && it.errorCode == "INTERRUMPIDO" }
        }
    }
}
