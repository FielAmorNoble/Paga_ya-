package com.example.pagaya.ui.clients

import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.ClientStatusResult
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.data.RegisterMovementResult
import com.example.pagaya.data.SaveClientResult
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.MutableClock
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.data.local.TestDatabaseFile
import com.example.pagaya.data.local.randomId
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.MovementInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Archivar y reactivar desde los ViewModels con Room real: confirmaciones, doble toque, listas
 * separadas y formularios financieros que se abrieron antes de archivar.
 */
@RunWith(AndroidJUnit4::class)
class ClientArchiveViewModelsTest {

    private val file = TestDatabaseFile()
    private val clock = MutableClock(TestDatabaseFile.FIXED_CLOCK.millis())
    private val db: PagaYaDatabase = file.open(clock)
    private val clients = ClientRepository(db, clock)
    private val movements = MovementRepository(db, clock)
    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct3 = LocalDate.of(2026, 10, 3)

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    private fun newClient(name: String = "Cliente Sintético"): String = runBlocking {
        (clients.create(randomId(), ClientInput(name, "", "")) as SaveClientResult.Saved).clientId
    }

    private fun register(clientId: String, type: MovementType, amount: String, date: LocalDate): String = runBlocking {
        (movements.register(randomId(), clientId, type, MovementInput(amount, date, "")) as RegisterMovementResult.Saved).movementId
    }

    private fun count(table: String): Long =
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { it.moveToFirst(); it.getLong(0) }

    private fun revision() = db.ledgerMetadataDao().get()!!.dataRevision

    private fun isActive(id: String) = db.clientDao().findById(id)!!.active

    private fun accountVm(clientId: String) =
        ClientDetailViewModel(SavedStateHandle(mapOf(ClientFormViewModel.CLIENT_ID_ARG to clientId)), clients, movements)

    private fun loadedAccount(clientId: String): ClientDetailViewModel {
        val vm = accountVm(clientId)
        awaitValue("cuenta cargada") { vm.state.value as? ClientDetailState.Loaded }
        return vm
    }

    private fun formVm(clientId: String, type: MovementType) = MovementFormViewModel(
        SavedStateHandle(mapOf(MovementFormViewModel.CLIENT_ID_ARG to clientId, MovementFormViewModel.TYPE_ARG to type.name)),
        clients,
        movements,
    )

    private fun editVm(movementId: String) =
        MovementEditViewModel(SavedStateHandle(mapOf(MovementEditViewModel.MOVEMENT_ID_ARG to movementId)), clients, movements)

    private fun movementDetailVm(movementId: String) =
        MovementDetailViewModel(SavedStateHandle(mapOf(MovementDetailViewModel.MOVEMENT_ID_ARG to movementId)), clients, movements)

    private fun listVm(archived: Boolean) = ClientListViewModel(SavedStateHandle(), clients, archived)

    private inline fun <T> awaitValue(describe: String, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(25)
        }
        fail("Tiempo agotado esperando: $describe")
        throw IllegalStateException()
    }

    private fun ClientListViewModel.names(): List<String>? =
        (state.value as? ClientListState.Loaded)?.clients?.map { it.client.name }

    // ---- cuenta: archivar y reactivar ----------------------------------------------------

    @Test
    fun withDebtTheAccountExplainsItMustBeSettledFirstAndArchivesNothing() {
        val id = newClient()
        register(id, MovementType.FIADO, "60000", oct1)
        register(id, MovementType.ABONO, "15000", oct3)
        val revisionBefore = revision()
        val vm = loadedAccount(id)

        vm.startArchive()
        assertEquals(ArchiveState.HasDebt(45_000), vm.archive.value)
        vm.confirmArchive() // sin confirmación abierta no hace nada

        assertTrue(isActive(id))
        assertEquals(revisionBefore, revision())
        assertNull(vm.archived.value)
        vm.dismissArchive()
        assertEquals(ArchiveState.Hidden, vm.archive.value)
    }

    @Test
    fun doubleTapOnArchiveAndOnReactivateWritesOnceEach() {
        val id = newClient("Cliente Doble Toque")
        register(id, MovementType.FIADO, "20000", oct1)
        register(id, MovementType.ABONO, "20000", oct3)
        val revisionBefore = revision()
        val vm = loadedAccount(id)

        vm.startArchive()
        assertEquals(ArchiveState.Confirm(), vm.archive.value)
        vm.confirmArchive()
        vm.confirmArchive() // segundo toque inmediato
        vm.confirmArchive()

        assertEquals("Cliente Doble Toque", awaitValue("archivado") { vm.archived.value })
        assertEquals(ArchiveState.Hidden, vm.archive.value)
        assertFalse(isActive(id))
        assertEquals(revisionBefore + 1, revision())
        vm.consumeArchived()

        // La cuenta archivada refleja su estado y no ofrece archivar de nuevo.
        awaitValue("cuenta archivada") { (vm.state.value as? ClientDetailState.Loaded)?.takeIf { !it.account.client.active } }
        vm.startArchive()
        assertEquals(ArchiveState.Hidden, vm.archive.value)

        vm.reactivate()
        vm.reactivate()
        vm.reactivate()
        awaitValue("reactivado") { vm.reactivated.value.takeIf { it } }
        awaitValue("cuenta activa") { (vm.state.value as? ClientDetailState.Loaded)?.takeIf { it.account.client.active } }
        assertTrue(isActive(id))
        assertEquals(revisionBefore + 2, revision())
        assertFalse(vm.reactivateFailed.value)
        assertEquals(2L, count("movimiento"))
    }

    @Test
    fun aDebtRegisteredWhileConfirmingPreventsArchiving() {
        val id = newClient()
        val vm = loadedAccount(id)
        vm.startArchive()
        assertEquals(ArchiveState.Confirm(), vm.archive.value)

        // Con la confirmación abierta, otro formulario registra un fiado.
        register(id, MovementType.FIADO, "5000", oct1)
        val revisionBefore = revision()
        vm.confirmArchive()

        assertEquals(ArchiveState.HasDebt(5_000), awaitValue("rechazo por deuda") { vm.archive.value as? ArchiveState.HasDebt })
        assertTrue(isActive(id))
        assertEquals(revisionBefore, revision())
        assertNull(vm.archived.value)
    }

    // ---- formularios abiertos antes de archivar ------------------------------------------

    @Test
    fun aMovementFormOpenedBeforeArchivingCannotSaveAndKeepsWhatWasTyped() {
        val id = newClient()
        val form = formVm(id, MovementType.FIADO)
        awaitValue("formulario cargado") { form.state.value.account }
        form.onAmountChange("10.000")
        form.onDescriptionChange("Pedido")
        assertEquals(ClientStatusResult.Changed, runBlocking { clients.archive(id) })
        val revisionBefore = revision()

        form.save()

        assertEquals(MovementFormFailure.CLIENT_ARCHIVED, awaitValue("rechazo") { form.state.value.failure })
        assertFalse(form.state.value.saving)
        assertNull(form.state.value.savedMovementId)
        assertEquals("10.000", form.state.value.amountText)
        assertEquals("Pedido", form.state.value.description)
        assertEquals(0L, count("movimiento"))
        assertEquals(revisionBefore, revision())
        assertFalse(isActive(id))
    }

    @Test
    fun aCorrectionReviewedBeforeArchivingIsRejectedOnConfirm() {
        val id = newClient()
        val fiado = register(id, MovementType.FIADO, "60000", oct1)
        register(id, MovementType.ABONO, "60000", oct3)
        val vm = editVm(fiado)
        awaitValue("formulario cargado") { vm.state.value.takeUnless { it.loading } }
        vm.onAmountChange("70000")
        vm.onReasonChange("Valor mal escrito")
        vm.review()
        awaitValue("vista previa") { vm.state.value.preview }
        assertEquals(ClientStatusResult.Changed, runBlocking { clients.archive(id) })
        val revisionBefore = revision()

        vm.confirm()

        assertEquals(EditFailure.CLIENT_ARCHIVED, awaitValue("rechazo") { vm.state.value.failure })
        assertNull(vm.state.value.savedVersion)
        assertEquals(0L, count("movimiento_revision"))
        assertEquals(60_000L, db.movementDao().findById(fiado)!!.amountCop)
        assertEquals(revisionBefore, revision())
    }

    @Test
    fun aCorrectionOpenedOnAnArchivedClientIsBlockedFromTheStart() {
        val id = newClient()
        val fiado = register(id, MovementType.FIADO, "60000", oct1)
        register(id, MovementType.ABONO, "60000", oct3)
        runBlocking { clients.archive(id) }

        val vm = editVm(fiado)
        awaitValue("formulario cargado") { vm.state.value.takeUnless { it.loading } }
        assertEquals(EditFailure.CLIENT_ARCHIVED, vm.state.value.failure)
        vm.onAmountChange("70000")
        vm.onReasonChange("Valor mal escrito")
        vm.review()

        assertNull(vm.state.value.preview)
        assertEquals(0L, count("movimiento_revision"))
    }

    @Test
    fun anAnnulmentOpenedBeforeArchivingIsRejectedOnConfirm() {
        val id = newClient()
        register(id, MovementType.FIADO, "60000", oct1)
        val abono = register(id, MovementType.ABONO, "60000", oct3)
        val vm = movementDetailVm(abono)
        awaitValue("detalle cargado") { vm.state.value as? MovementDetailState.Loaded }
        vm.openAnnul()
        vm.onAnnulReasonChange("Abono duplicado")
        assertEquals(ClientStatusResult.Changed, runBlocking { clients.archive(id) })
        val revisionBefore = revision()

        vm.confirmAnnul()

        assertEquals(
            AnnulFailure.CLIENT_ARCHIVED,
            awaitValue("rechazo") { (vm.annul.value as? AnnulState.Open)?.failure },
        )
        assertFalse(vm.annulled.value)
        assertTrue(db.movementDao().findById(abono)!!.active)
        assertEquals(0L, count("movimiento_revision"))
        assertEquals(revisionBefore, revision())

        // Cerrado el aviso, con el cliente archivado ya no se puede abrir otra anulación.
        vm.dismissAnnul()
        awaitValue("detalle archivado") { (vm.state.value as? MovementDetailState.Loaded)?.takeIf { !it.account.client.active } }
        vm.openAnnul()
        assertEquals(AnnulState.Hidden, vm.annul.value)
    }

    // ---- listas --------------------------------------------------------------------------

    @Test
    fun activeAndArchivedListsAreSeparateKeepTheirOwnSearchAndUpdateTogether() {
        val ana = newClient("Ana Torres")
        newClient("María Pérez")
        val active = listVm(archived = false)
        val archived = listVm(archived = true)
        assertEquals(listOf("Ana Torres", "María Pérez"), awaitValue("activos") { active.names()?.takeIf { it.size == 2 } })
        assertEquals(emptyList<String>(), awaitValue("archivados vacíos") { archived.names() })

        runBlocking { clients.archive(ana) }
        assertEquals(listOf("María Pérez"), awaitValue("activos sin Ana") { active.names()?.takeIf { it.size == 1 } })
        assertEquals(listOf("Ana Torres"), awaitValue("Ana archivada") { archived.names()?.takeIf { it.isNotEmpty() } })

        archived.onQueryChange("ANA")
        assertEquals("", active.query.value)
        assertEquals(listOf("Ana Torres"), awaitValue("búsqueda en archivados") { archived.names() })
        archived.onQueryChange("maria")
        assertEquals(emptyList<String>(), awaitValue("sin resultados") { archived.names()?.takeIf { it.isEmpty() } })

        archived.onQueryChange("")
        runBlocking { clients.reactivate(ana) }
        assertEquals(emptyList<String>(), awaitValue("archivados tras reactivar") { archived.names()?.takeIf { it.isEmpty() } })
        assertEquals(listOf("Ana Torres", "María Pérez"), awaitValue("activos tras reactivar") { active.names()?.takeIf { it.size == 2 } })
    }
}
