package com.example.pagaya.ui.clients

import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.data.RegisterMovementResult
import com.example.pagaya.data.SaveClientResult
import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.MutableClock
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.data.local.TestDatabaseFile
import com.example.pagaya.data.local.randomId
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.MovementInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Pruebas de los ViewModels con Room real: el doble toque, los reintentos del formulario y el
 * pago completo cuyo saldo cambia entre la confirmación y el guardado.
 */
@RunWith(AndroidJUnit4::class)
class MovementViewModelsTest {

    private val file = TestDatabaseFile()
    private val clock = MutableClock(TestDatabaseFile.FIXED_CLOCK.millis())
    private val db: PagaYaDatabase = file.open(clock)
    private val clients = ClientRepository(db, clock)
    private val movements = MovementRepository(db, clock)
    private val oct1 = LocalDate.of(2026, 10, 1)

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    private fun newClient(): String = runBlocking {
        (clients.create(randomId(), ClientInput("Cliente Sintético", "", "")) as SaveClientResult.Saved).clientId
    }

    private fun seedFiado(clientId: String, amount: String = "60000") = runBlocking {
        val result = movements.register(randomId(), clientId, MovementType.FIADO, MovementInput(amount, oct1, ""))
        assertTrue(result is RegisterMovementResult.Saved)
    }

    private fun movementCount(): Long =
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM movimiento")).use { it.moveToFirst(); it.getLong(0) }

    private fun revision() = db.ledgerMetadataDao().get()!!.dataRevision

    private fun formViewModel(clientId: String, type: MovementType, vararg extra: Pair<String, Any?>) = MovementFormViewModel(
        SavedStateHandle(mapOf(MovementFormViewModel.CLIENT_ID_ARG to clientId, MovementFormViewModel.TYPE_ARG to type.name) + extra),
        clients,
        movements,
    )

    private fun detailViewModel(clientId: String) = ClientDetailViewModel(
        SavedStateHandle(mapOf(ClientFormViewModel.CLIENT_ID_ARG to clientId)),
        clients,
        movements,
    )

    private inline fun <T> awaitValue(describe: String, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(25)
        }
        fail("Tiempo agotado esperando: $describe")
        throw IllegalStateException()
    }

    @Test
    fun doubleTapOnSaveCreatesASingleMovement() {
        val client = newClient()
        seedFiado(client)
        val before = movementCount()
        val revisionBefore = revision()
        val vm = formViewModel(client, MovementType.ABONO)
        awaitValue("cuenta cargada") { vm.state.value.account }
        vm.onAmountChange("15.000")
        vm.onDescriptionChange("Transferencia")

        vm.save()
        vm.save() // segundo toque inmediato
        vm.save()

        val saved = awaitValue("guardado") { vm.state.value.savedMovementId }
        vm.save() // un toque tardío tampoco crea nada
        assertEquals(before + 1, movementCount())
        assertEquals(revisionBefore + 1, revision())
        val movement = db.movementDao().findById(saved)!!
        assertEquals(15_000L, movement.amountCop)
        assertEquals(MovementType.ABONO, movement.type)
        assertEquals(MovementOrigin.MANUAL, movement.origin)
        assertEquals(LocalDate.of(2026, 10, 5), movement.date) // fecha inicial: hoy en Bogotá
        assertEquals("Transferencia", movement.description)
    }

    @Test
    fun errorsKeepWhatWasTypedAndTheRetryReusesTheSameRequestId() {
        val client = newClient()
        seedFiado(client)
        val vm = formViewModel(client, MovementType.ABONO)
        awaitValue("cuenta cargada") { vm.state.value.account }

        // 1. Entrada inválida: no llega al repositorio y conserva lo escrito.
        vm.onAmountChange("20,5")
        vm.onDescriptionChange("Nota que no debe perderse")
        vm.save()
        assertNotNull(vm.state.value.amountError)
        assertEquals("20,5", vm.state.value.amountText)
        assertEquals("Nota que no debe perderse", vm.state.value.description)
        assertEquals(1L, movementCount())

        // 2. Sobreabono: lo rechaza el historial y el formulario conserva todo.
        vm.onAmountChange("60.001")
        vm.save()
        val rejection = awaitValue("rechazo") { vm.state.value.rejection }
        assertEquals(HistoryRejection.ExceedsBalance(60_000), rejection)
        assertEquals("60.001", vm.state.value.amountText)
        assertEquals(false, vm.state.value.saving)
        assertEquals(1L, movementCount())

        // 3. Corregido el valor, el mismo formulario guarda; solo hay un movimiento nuevo.
        vm.onAmountChange("10.000")
        vm.save()
        awaitValue("guardado") { vm.state.value.savedMovementId }
        assertEquals(2L, movementCount())
    }

    @Test
    fun theFormStateSurvivesProcessRecreationWithTheSameRequestId() {
        val client = newClient()
        seedFiado(client)
        val first = formViewModel(client, MovementType.ABONO)
        awaitValue("cuenta cargada") { first.state.value.account }
        first.onAmountChange("5000")
        first.save()
        val id = awaitValue("guardado") { first.state.value.savedMovementId }

        // Segundo ViewModel con el estado guardado del primero (recreación del proceso).
        val savedKeys = mapOf(
            MovementFormViewModel.CLIENT_ID_ARG to client,
            MovementFormViewModel.TYPE_ARG to MovementType.ABONO.name,
            "requestId" to id,
            "amount" to "5000",
        )
        val second = MovementFormViewModel(SavedStateHandle(savedKeys), clients, movements)
        awaitValue("cuenta cargada") { second.state.value.account }
        assertEquals("5000", second.state.value.amountText)
        second.save() // reintento del mismo intento ya guardado
        awaitValue("reintento") { second.state.value.savedMovementId }

        assertEquals(2L, movementCount())
        assertEquals(id, second.state.value.savedMovementId)
    }

    @Test
    fun twoFormsWithTheSameValuesRecordTwoMovements() {
        val client = newClient()
        seedFiado(client)
        repeat(2) {
            val vm = formViewModel(client, MovementType.ABONO)
            awaitValue("cuenta cargada") { vm.state.value.account }
            vm.onAmountChange("20000")
            vm.save()
            val id = awaitValue("guardado") { vm.state.value.savedMovementId }
            assertNotNull(id)
        }
        assertEquals(3L, movementCount())
        assertEquals(20_000L, runBlocking { clients.observeClient(client).first()!!.balanceCop })
    }

    @Test
    fun fullPaymentDoubleTapCreatesASinglePayment() {
        val client = newClient()
        seedFiado(client)
        val vm = detailViewModel(client)
        awaitValue("cuenta cargada") { vm.state.value as? ClientDetailState.Loaded }
        vm.startPayFull()
        assertEquals(PayFullState.Confirm(60_000), vm.payFull.value)

        vm.confirmPayFull()
        vm.confirmPayFull()
        vm.confirmPayFull()

        awaitValue("pago guardado") { vm.paymentSaved.value.takeIf { it } }
        assertEquals(PayFullState.Hidden, vm.payFull.value)
        assertEquals(2L, movementCount())
        // Cuenta e historial son flujos distintos: el saldo puede llegar antes que la segunda fila.
        val loaded = awaitValue("saldo cero e historial actualizado") {
            (vm.state.value as? ClientDetailState.Loaded)?.takeIf { it.account.balanceCop == 0L && it.history.size == 2 }
        }
        assertEquals(MovementOrigin.PAGO_TOTAL, loaded.history.first().entry.origin)
        assertEquals(0L, loaded.history.first().balanceAfterCop)
    }

    @Test
    fun fullPaymentWhoseBalanceChangedAfterConfirmationAsksToReviewAndSavesNothing() {
        val client = newClient()
        seedFiado(client)
        val vm = detailViewModel(client)
        awaitValue("cuenta cargada") { vm.state.value as? ClientDetailState.Loaded }
        vm.startPayFull() // la usuaria ve y confirma $60.000
        assertEquals(PayFullState.Confirm(60_000), vm.payFull.value)

        // Antes de guardar, el saldo baja a $50.000.
        runBlocking { movements.register(randomId(), client, MovementType.ABONO, MovementInput("10000", oct1, "")) }
        val revisionBefore = revision()

        vm.confirmPayFull()

        val state = awaitValue("aviso") { vm.payFull.value as? PayFullState.BalanceChanged }
        assertEquals(50_000L, state.currentBalanceCop)
        assertEquals(false, vm.paymentSaved.value)
        assertEquals(2L, movementCount())
        assertEquals(revisionBefore, revision())

        // Revisar cierra el aviso; volver a pagar muestra y confirma el nuevo importe.
        vm.dismissPayFull()
        assertEquals(PayFullState.Hidden, vm.payFull.value)
        awaitValue("saldo actualizado") { (vm.state.value as? ClientDetailState.Loaded)?.takeIf { it.account.balanceCop == 50_000L } }
        vm.startPayFull()
        assertEquals(PayFullState.Confirm(50_000), vm.payFull.value)
        vm.confirmPayFull()
        awaitValue("pago guardado") { vm.paymentSaved.value.takeIf { it } }
        assertEquals(3L, movementCount())
    }

    @Test
    fun fullPaymentAboveTheLimitExplainsThatItMustBeSplit() {
        val client = newClient()
        seedFiado(client, "999999999")
        runBlocking { movements.register(randomId(), client, MovementType.FIADO, MovementInput("999999999", oct1, "")) }
        val vm = detailViewModel(client)
        awaitValue("saldo cargado") { (vm.state.value as? ClientDetailState.Loaded)?.takeIf { it.account.balanceCop == 1_999_999_998L } }

        vm.startPayFull()

        assertEquals(PayFullState.TooLarge(1_999_999_998), vm.payFull.value)
        assertEquals(2L, movementCount())
    }

    @Test
    fun fullPaymentIsNotOfferedWithoutDebt() {
        val client = newClient()
        val vm = detailViewModel(client)
        awaitValue("cuenta cargada") { vm.state.value as? ClientDetailState.Loaded }
        vm.startPayFull()
        assertEquals(PayFullState.Hidden, vm.payFull.value)
    }
}
