package com.example.pagaya.ui.clients

import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pagaya.data.ChangeMovementResult
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.data.RegisterMovementResult
import com.example.pagaya.data.SaveClientResult
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.MutableClock
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.data.local.TestDatabaseFile
import com.example.pagaya.data.local.randomId
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.CorrectionInput
import com.example.pagaya.domain.FieldChange
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.MovementInput
import com.example.pagaya.domain.ReasonError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** ViewModels de detalle, corrección, anulación y «Ver cambios» con Room real. */
@RunWith(AndroidJUnit4::class)
class MovementCorrectionViewModelsTest {

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

    private fun newClient(): String = runBlocking {
        (clients.create(randomId(), ClientInput("Cliente Sintético", "", "")) as SaveClientResult.Saved).clientId
    }

    private fun register(clientId: String, type: MovementType, amount: String, date: LocalDate, description: String = ""): String = runBlocking {
        (movements.register(randomId(), clientId, type, MovementInput(amount, date, description)) as RegisterMovementResult.Saved).movementId
    }

    private fun count(table: String): Long =
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { it.moveToFirst(); it.getLong(0) }

    private fun revision() = db.ledgerMetadataDao().get()!!.dataRevision

    private fun movement(id: String) = db.movementDao().findById(id)!!

    private fun editVm(movementId: String, vararg extra: Pair<String, Any?>) =
        MovementEditViewModel(SavedStateHandle(mapOf(MovementEditViewModel.MOVEMENT_ID_ARG to movementId) + extra), clients, movements)

    private fun detailVm(movementId: String) =
        MovementDetailViewModel(SavedStateHandle(mapOf(MovementDetailViewModel.MOVEMENT_ID_ARG to movementId)), clients, movements)

    private inline fun <T> awaitValue(describe: String, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(25)
        }
        fail("Tiempo agotado esperando: $describe")
        throw IllegalStateException()
    }

    private fun loadedEdit(movementId: String, vararg extra: Pair<String, Any?>): MovementEditViewModel {
        val vm = editVm(movementId, *extra)
        awaitValue("formulario cargado") { vm.state.value.takeUnless { it.loading } }
        return vm
    }

    // ---- corrección ----------------------------------------------------------------------

    @Test
    fun theFormStartsWithTheCurrentValuesAndSavingWithoutChangesCreatesNothing() {
        val client = newClient()
        register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3, "Transferencia")
        val revisionBefore = revision()
        val vm = loadedEdit(abono)

        assertEquals("15.000", vm.state.value.amountText)
        assertEquals(oct3, vm.state.value.date)
        assertEquals("Transferencia", vm.state.value.description)
        assertEquals(false, vm.state.value.isDirty)

        vm.review()

        assertTrue(vm.state.value.noChanges)
        assertNull(vm.state.value.preview)
        assertEquals(0L, count("movimiento_revision"))
        assertEquals(revisionBefore, revision())
    }

    @Test
    fun theReasonIsRequiredBeforeShowingWhatWillChange() {
        val client = newClient()
        register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3)
        val vm = loadedEdit(abono)
        vm.onAmountChange("20000")

        vm.review()
        assertEquals(ReasonError.REQUIRED, vm.state.value.reasonError)
        assertNull(vm.state.value.preview)

        vm.onReasonChange("   ")
        vm.review()
        assertEquals(ReasonError.REQUIRED, vm.state.value.reasonError)
        assertEquals("20000", vm.state.value.amountText) // lo escrito se conserva
        assertEquals(0L, count("movimiento_revision"))
    }

    @Test
    fun reviewShowsExactlyWhatChangesAndTheNewBalanceBeforeSaving() {
        val client = newClient()
        register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3, "uno")
        val vm = loadedEdit(abono)
        vm.onAmountChange("20.000")
        vm.onDescriptionChange("dos")
        vm.onReasonChange("  Error de valor  ")

        vm.review()

        val preview = awaitValue("vista previa") { vm.state.value.preview }
        assertEquals(
            listOf(FieldChange.Amount(15_000, 20_000), FieldChange.Description("uno", "dos")),
            preview.changes,
        )
        assertEquals("Error de valor", preview.reason)
        assertEquals(40_000L, preview.newBalanceCop)
        assertEquals(0L, count("movimiento_revision")) // todavía no se guardó nada
        vm.dismissPreview()
        assertNull(vm.state.value.preview)
    }

    @Test
    fun doubleTapOnConfirmCreatesASingleRevision() {
        val client = newClient()
        register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3)
        val revisionBefore = revision()
        val vm = loadedEdit(abono)
        vm.onAmountChange("20000")
        vm.onReasonChange("Error de valor")
        vm.review()
        awaitValue("vista previa") { vm.state.value.preview }

        vm.confirm()
        vm.confirm() // segundo toque inmediato
        vm.confirm()

        val version = awaitValue("guardado") { vm.state.value.savedVersion }
        vm.confirm() // un toque tardío tampoco hace nada
        assertEquals(2L, version)
        assertEquals(1L, count("movimiento_revision"))
        assertEquals(revisionBefore + 1, revision())
        assertEquals(20_000L, movement(abono).amountCop)
    }

    @Test
    fun aRejectedCorrectionKeepsEverythingTypedAndChangesNothing() {
        val client = newClient()
        val fiado = register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "40000", oct3)
        val revisionBefore = revision()
        val vm = loadedEdit(fiado)
        vm.onAmountChange("30000")
        vm.onDescriptionChange("Nota nueva")
        vm.onReasonChange("Creí que era menos")

        // El rechazo se anuncia al revisar, antes de pedir confirmación y sin mostrar un saldo imposible.
        vm.review()

        val rejection = awaitValue("rechazo") { vm.state.value.rejection }
        assertTrue(rejection is HistoryRejection.UncoveredAbono)
        assertEquals(abono, (rejection as HistoryRejection.UncoveredAbono).abono.id)
        assertEquals(40_000L, rejection.abono.amountCop)
        assertNull(vm.state.value.preview)
        assertEquals(false, vm.state.value.saving)
        assertEquals("30000", vm.state.value.amountText)
        assertEquals("Nota nueva", vm.state.value.description)
        assertEquals("Creí que era menos", vm.state.value.reason)
        assertEquals(0L, count("movimiento_revision"))
        assertEquals(revisionBefore, revision())
        assertEquals(60_000L, movement(fiado).amountCop)
    }

    @Test
    fun aMovementChangedFromAnotherScreenIsNotOverwritten() {
        val client = newClient()
        register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3)
        val vm = loadedEdit(abono) // la usuaria abre la corrección en la versión 1
        vm.onAmountChange("30000")
        vm.onReasonChange("Segunda pantalla")
        vm.review()
        awaitValue("vista previa") { vm.state.value.preview }
        // Mientras tanto, otra pantalla corrige el mismo movimiento.
        val other = runBlocking { movements.correct(randomId(), abono, 1, CorrectionInput("20000", oct3, "", "Primera pantalla")) }
        assertTrue(other is ChangeMovementResult.Saved)

        vm.confirm()

        awaitValue("conflicto") { vm.state.value.failure.takeIf { it == EditFailure.VERSION_CONFLICT } }
        assertEquals(1L, count("movimiento_revision"))
        assertEquals(20_000L, movement(abono).amountCop) // el cambio de la otra pantalla sigue ahí
        // Una pantalla abierta tras recrear el proceso con la versión vieja guardada avisa al cargar.
        val recreated = editVm(abono, "expectedVersion" to 1L)
        awaitValue("aviso al cargar") { recreated.state.value.failure.takeIf { it == EditFailure.VERSION_CONFLICT } }
    }

    @Test
    fun anAnnulledMovementCannotBeOpenedForCorrection() {
        val client = newClient()
        val fiado = register(client, MovementType.FIADO, "60000", oct1)
        assertTrue(runBlocking { movements.annul(randomId(), fiado, 1, "Error") } is ChangeMovementResult.Saved)

        val vm = editVm(fiado)

        awaitValue("bloqueado") { vm.state.value.failure.takeIf { it == EditFailure.ALREADY_ANNULLED } }
    }

    // ---- vista previa y confirmación: el contenido revisado es el que se guarda ----------------

    /** Evaluador que se queda esperando una compuerta por llamada: permite controlar cuándo termina cada evaluación. */
    private class GatedEvaluator(private val delegate: CorrectionEvaluator) : CorrectionEvaluator {
        val gates = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.CompletableDeferred<Unit>>()
        val finished = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun evaluate(
            original: com.example.pagaya.data.local.MovementEntity,
            origin: com.example.pagaya.data.local.MovementOrigin,
            newAmountCop: Long,
            newDate: LocalDate,
        ): CorrectionEvaluation {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            gates += gate
            gate.await()
            return delegate.evaluate(original, origin, newAmountCop, newDate).also { finished.incrementAndGet() }
        }
    }

    private fun gatedEditVm(movementId: String, evaluator: GatedEvaluator): MovementEditViewModel {
        val vm = MovementEditViewModel(
            SavedStateHandle(mapOf(MovementEditViewModel.MOVEMENT_ID_ARG to movementId)),
            clients,
            movements,
            evaluator,
        )
        awaitValue("formulario cargado") { vm.state.value.takeUnless { it.loading } }
        return vm
    }

    @Test
    fun editsWhileThePreviewIsBeingEvaluatedAreBlockedAndTheConfirmedContentIsExactlyTheReviewedOne() {
        val client = newClient()
        register(client, MovementType.FIADO, "100000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3, "uno")
        val evaluator = GatedEvaluator(HistoryCorrectionEvaluator(movements))
        val vm = gatedEditVm(abono, evaluator)
        vm.onAmountChange("20000")
        vm.onDescriptionChange("dos")
        vm.onReasonChange("Corrección revisada")

        vm.review()
        awaitValue("evaluación iniciada") { evaluator.gates.firstOrNull() }
        assertTrue(vm.state.value.reviewing)
        assertNull(vm.state.value.preview)

        // Mientras espera, el formulario no admite cambios ni una segunda evaluación.
        vm.onAmountChange("99999")
        vm.onDateChange(oct1)
        vm.onDescriptionChange("texto distinto")
        vm.onReasonChange("otro motivo")
        vm.review()
        vm.review()
        vm.confirm() // sin vista previa todavía: no hace nada
        assertEquals("20000", vm.state.value.amountText)
        assertEquals(oct3, vm.state.value.date)
        assertEquals("dos", vm.state.value.description)
        assertEquals("Corrección revisada", vm.state.value.reason)
        assertEquals(1, evaluator.gates.size) // ni una evaluación duplicada
        assertEquals(0L, count("movimiento_revision"))

        evaluator.gates[0].complete(Unit)
        val preview = awaitValue("vista previa") { vm.state.value.preview }
        assertEquals(1, evaluator.finished.get())
        assertEquals(CorrectionRequest("20000", oct3, "dos", "Corrección revisada", expectedVersion = 1), preview.request)
        assertEquals(listOf(FieldChange.Amount(15_000, 20_000), FieldChange.Description("uno", "dos")), preview.changes)

        // Con la vista previa abierta tampoco se puede cambiar nada, y lo confirmado es lo mostrado.
        vm.onAmountChange("77777")
        vm.onDescriptionChange("cambiado tarde")
        vm.confirm()
        vm.confirm()
        awaitValue("guardado") { vm.state.value.savedVersion }

        val stored = movement(abono)
        assertEquals(20_000L, stored.amountCop)
        assertEquals("dos", stored.description)
        assertEquals(oct3, stored.date)
        assertEquals("Corrección revisada", db.movementRevisionDao().findByMovement(abono).single().reason)
        assertEquals(1L, count("movimiento_revision"))
        assertEquals(1, evaluator.gates.size)
    }

    @Test
    fun cancellingAPendingEvaluationDiscardsItsLateResult() {
        val client = newClient()
        register(client, MovementType.FIADO, "100000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3)
        val evaluator = GatedEvaluator(HistoryCorrectionEvaluator(movements))
        val vm = gatedEditVm(abono, evaluator)
        vm.onAmountChange("20000")
        vm.onReasonChange("Primera revisión")
        vm.review()
        awaitValue("evaluación iniciada") { evaluator.gates.firstOrNull() }

        vm.dismissPreview() // cancela la evaluación pendiente
        assertEquals(false, vm.state.value.reviewing)
        evaluator.gates[0].complete(Unit)
        awaitValue("evaluación tardía terminada") { evaluator.finished.get().takeIf { it == 1 } }
        Thread.sleep(200) // da tiempo a que un resultado tardío, si se aplicara, apareciera

        assertNull(vm.state.value.preview)
        assertEquals(false, vm.state.value.reviewing)
        assertEquals(0L, count("movimiento_revision"))
        // El formulario vuelve a ser editable.
        vm.onAmountChange("21000")
        assertEquals("21000", vm.state.value.amountText)
    }

    @Test
    fun aLateResultOfAnInvalidatedEvaluationNeverReplacesTheCurrentPreview() {
        val client = newClient()
        register(client, MovementType.FIADO, "100000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3)
        val evaluator = GatedEvaluator(HistoryCorrectionEvaluator(movements))
        val vm = gatedEditVm(abono, evaluator)
        vm.onAmountChange("20000")
        vm.onReasonChange("Primera")
        vm.review()
        awaitValue("primera evaluación") { evaluator.gates.firstOrNull() }
        vm.dismissPreview()

        // Segunda revisión con otro contenido, todavía pendiente.
        vm.onAmountChange("30000")
        vm.onReasonChange("Segunda")
        vm.review()
        awaitValue("segunda evaluación") { evaluator.gates.getOrNull(1) }

        // La primera, ya invalidada, termina mientras la segunda sigue esperando: no debe mostrar nada.
        evaluator.gates[0].complete(Unit)
        awaitValue("primera terminada") { evaluator.finished.get().takeIf { it == 1 } }
        Thread.sleep(200)
        assertNull(vm.state.value.preview)
        assertTrue(vm.state.value.reviewing)

        // Cuando termina la segunda, la vista previa es la suya.
        evaluator.gates[1].complete(Unit)
        val preview = awaitValue("segunda vista previa") { vm.state.value.preview }
        assertEquals("30000", preview.request.amountText)
        assertEquals("Segunda", preview.request.reason)
        assertEquals(listOf(FieldChange.Amount(15_000, 30_000)), preview.changes)

        vm.confirm()
        awaitValue("guardado") { vm.state.value.savedVersion }
        assertEquals(30_000L, movement(abono).amountCop)
        assertEquals("Segunda", db.movementRevisionDao().findByMovement(abono).single().reason)
    }

    // ---- detalle y anulación -------------------------------------------------------------

    @Test
    fun theDetailShowsTheMovementItsClientAndHowManyRevisionsItHas() {
        val client = newClient()
        register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3, "Transferencia")
        val vm = detailVm(abono)
        val first = awaitValue("detalle") { vm.state.value as? MovementDetailState.Loaded }
        assertEquals(0, first.revisionCount)
        assertEquals("Transferencia", first.movement.description)
        assertEquals("Cliente Sintético", first.account.client.name)

        runBlocking { movements.correct(randomId(), abono, 1, CorrectionInput("20000", oct3, "Transferencia", "Error")) }

        val updated = awaitValue("revisiones") { (vm.state.value as? MovementDetailState.Loaded)?.takeIf { it.revisionCount == 1 } }
        assertEquals(2L, updated.movement.version)
    }

    @Test
    fun annullingRequiresAReasonAndDoubleTapCreatesASingleRevision() {
        val client = newClient()
        register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3)
        val vm = detailVm(abono)
        awaitValue("detalle") { vm.state.value as? MovementDetailState.Loaded }
        vm.openAnnul()
        assertTrue(vm.annul.value is AnnulState.Open)

        vm.confirmAnnul() // sin motivo
        assertEquals(ReasonError.REQUIRED, (vm.annul.value as AnnulState.Open).reasonError)
        assertEquals(0L, count("movimiento_revision"))

        vm.onAnnulReasonChange("Lo anoté dos veces")
        vm.confirmAnnul()
        vm.confirmAnnul() // doble toque
        vm.confirmAnnul()

        awaitValue("anulado") { vm.annulled.value.takeIf { it } }
        assertEquals(AnnulState.Hidden, vm.annul.value)
        assertEquals(1L, count("movimiento_revision"))
        assertEquals(false, movement(abono).active)
        val loaded = awaitValue("detalle anulado") { (vm.state.value as? MovementDetailState.Loaded)?.takeIf { !it.movement.active } }
        assertEquals(60_000L, loaded.account.balanceCop)
    }

    @Test
    fun annullingAFiadoThatLeavesAnAbonoUncoveredShowsWhichOneAndKeepsTheDialogOpen() {
        val client = newClient()
        val fiado = register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "40000", oct3)
        val vm = detailVm(fiado)
        awaitValue("detalle") { vm.state.value as? MovementDetailState.Loaded }
        vm.openAnnul()
        vm.onAnnulReasonChange("Me equivoqué de cliente")

        vm.confirmAnnul()

        val open = awaitValue("rechazo") { (vm.annul.value as? AnnulState.Open)?.takeIf { it.rejection != null } }
        assertEquals(abono, (open.rejection as HistoryRejection.UncoveredAbono).abono.id)
        assertEquals("Me equivoqué de cliente", open.reason)
        assertEquals(false, open.saving)
        assertEquals(true, movement(fiado).active)
        assertEquals(0L, count("movimiento_revision"))
    }

    @Test
    fun annullingAMovementChangedMeanwhileReportsAVersionConflict() {
        val client = newClient()
        register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3)
        val vm = detailVm(abono)
        awaitValue("detalle") { vm.state.value as? MovementDetailState.Loaded }
        vm.openAnnul() // ve la versión 1
        vm.onAnnulReasonChange("Duplicado")
        runBlocking { movements.correct(randomId(), abono, 1, CorrectionInput("20000", oct3, "", "Otra pantalla")) }

        vm.confirmAnnul()

        val open = awaitValue("conflicto") { (vm.annul.value as? AnnulState.Open)?.takeIf { it.failure != null } }
        assertEquals(AnnulFailure.VERSION_CONFLICT, open.failure)
        assertTrue(movement(abono).active)
        assertEquals(1L, count("movimiento_revision"))
    }

    // ---- ver cambios ---------------------------------------------------------------------

    @Test
    fun theChangesViewListsEveryRevisionNewestFirst() {
        val client = newClient()
        register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3, "uno")
        runBlocking { movements.correct(randomId(), abono, 1, CorrectionInput("20000", oct3, "uno", "Valor mal escrito")) }
        runBlocking { movements.annul(randomId(), abono, 2, "Duplicado") }

        val vm = MovementChangesViewModel(SavedStateHandle(mapOf(MovementDetailViewModel.MOVEMENT_ID_ARG to abono)), movements)

        val loaded = awaitValue("cambios") { (vm.state.value as? MovementChangesState.Loaded)?.takeIf { it.changes.size == 2 } }
        assertEquals(listOf("Duplicado", "Valor mal escrito"), loaded.changes.map { it.reason })
        assertEquals(listOf(FieldChange.Amount(15_000, 20_000)), loaded.changes[1].changes)
    }

    @Test
    fun aDamagedSnapshotIsReportedAsAnErrorInsteadOfShowingMisleadingChanges() {
        val client = newClient()
        register(client, MovementType.FIADO, "60000", oct1)
        val abono = register(client, MovementType.ABONO, "15000", oct3)
        runBlocking { movements.correct(randomId(), abono, 1, CorrectionInput("20000", oct3, "", "Valor mal escrito")) }
        // El snapshot es inmutable por el flujo normal; se simula el daño con un trigger temporal desactivado.
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER pagaya_movimiento_revision_inmutable_update")
        db.openHelper.writableDatabase.execSQL("UPDATE movimiento_revision SET estado_anterior_json = '{\"roto\":1}'")

        val vm = MovementChangesViewModel(SavedStateHandle(mapOf(MovementDetailViewModel.MOVEMENT_ID_ARG to abono)), movements)

        awaitValue("error") { vm.state.value as? MovementChangesState.Error }
    }
}
