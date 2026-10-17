package com.example.pagaya.ui.backup

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.backup.*
import com.example.pagaya.data.local.TestDatabaseFile
import com.example.pagaya.domain.ClientInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Estados UI con servicios reales y bases independientes, nunca pagaya.db. */
@RunWith(AndroidJUnit4::class)
class RestoreViewModelTest {
    private val sourceFile = TestDatabaseFile()
    private val targetFile = TestDatabaseFile()
    private val source = sourceFile.open()
    private val target = targetFile.open()
    private val root = File(targetFile.context.cacheDir, "test-restore-vm-${UUID.randomUUID()}").apply { mkdirs() }
    private val clock = TestDatabaseFile.FIXED_CLOCK
    private val documents = object : DocumentAccess {
        override fun openForRead(location: String) = File(location).inputStream()
        override fun openForWrite(location: String) = File(location).outputStream()
    }
    private val backup = BackupService(target, clock, File(root, "backup"), "test", documents)
    private val restore = RestoreService(targetFile.context, target, backup, documents, clock,
        File(root, "restore"), File(root, "recovery"))

    @After fun close() {
        source.close(); target.close(); sourceFile.delete(); targetFile.delete(); root.deleteRecursively()
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun state(vm: RestoreViewModel) = main { vm.state.value }
    private fun await(vm: RestoreViewModel, condition: (RestoreUiState) -> Boolean): RestoreUiState {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            state(vm).let { if (condition(it)) return it }
            Thread.sleep(20)
        }
        throw AssertionError("Estado no alcanzado: ${state(vm)}")
    }
    private fun emptyArchive(): File = runBlocking {
        val s = BackupService(source, clock, File(root, "source"), "test", documents)
        val ready = s.prepare() as PrepareResult.Ready
        File(root, "empty.bin").also { assertTrue(s.export(ready.backupId, it.path) is ExportResult.Verified) }
    }

    @Test fun cancellingTheDocumentPickerIsNeitherFailureNorSuccess() {
        val before = target.ledgerMetadataDao().get()
        val vm = main { RestoreViewModel(SavedStateHandle(), restore) }
        main { assertTrue(vm.choose()); assertFalse(vm.choose()); vm.chosen(null) }
        val after = state(vm)
        assertFalse(after.busy); assertNull(after.failure); assertNull(after.preview)
        assertEquals(before, target.ledgerMetadataDao().get())
        assertNull(runBlocking { restore.recoverInterrupted() })
    }

    @Test fun invalidSelectionLeavesAccountsAndNoPreview() {
        val before = target.ledgerMetadataDao().get()
        val vm = main { RestoreViewModel(SavedStateHandle(), restore) }
        val invalid = File(root, "invalid").apply { writeText("no ZIP") }
        main { vm.choose(); vm.chosen(invalid.path) }
        val failed = await(vm) { it.failure != null && !it.busy }
        assertEquals(RestoreFailure.INVALID, failed.failure); assertNull(failed.preview)
        assertEquals(before, target.ledgerMetadataDao().get())
    }

    @Test fun emptyWarningAndChangedPreviewRequireAnotherExplicitConfirmation() = runBlocking {
        val clients = ClientRepository(target, clock)
        clients.create(UUID.randomUUID().toString(), ClientInput("Sintético previo", "", ""))
        val epoch = target.ledgerMetadataDao().get()!!.localEpoch
        val archive = emptyArchive()
        val vm = main { RestoreViewModel(SavedStateHandle(), restore) }
        main { vm.chosen(archive.path) }
        assertTrue(await(vm) { it.preview != null }.preview!!.emptyReplacesData)
        clients.create(UUID.randomUUID().toString(), ClientInput("Cambio posterior", "", ""))
        main { vm.confirm() }
        val changed = await(vm) { it.changed && !it.applying }
        assertEquals(2L, changed.preview!!.current.clients)
        assertEquals(2, target.clientDao().all().size)
        assertEquals(epoch, target.ledgerMetadataDao().get()!!.localEpoch)
        main { vm.confirm() }
        await(vm) { !it.applying && it.preview == null }
        assertTrue(target.clientDao().all().isEmpty())
        assertNotEquals(epoch, target.ledgerMetadataDao().get()!!.localEpoch)
        assertTrue(restore.hasRecovery())
    }

    @Test fun processRecreationExpiresPreviewAndDoesNotApplyItSilently() {
        val before = target.ledgerMetadataDao().get()
        val vm = main { RestoreViewModel(SavedStateHandle(mapOf("restorePhase" to "preview")), restore) }
        val expired = await(vm) { it.failure == RestoreFailure.EXPIRED }
        assertNull(expired.preview); assertFalse(expired.busy)
        main { vm.confirm() }
        assertEquals(before, target.ledgerMetadataDao().get())
    }
}
