package com.example.pagaya.data

import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pagaya.data.local.LedgerMetadataEntity
import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.MutableClock
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.data.local.TestDatabaseFile
import com.example.pagaya.data.local.randomId
import com.example.pagaya.domain.AmountError
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.DateError
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.MovementInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MovementRepositoryTest {

    // El reloj fijo es el 5 de octubre de 2026, 10:00 en Bogotá.
    private val file = TestDatabaseFile()
    private val clock = MutableClock(TestDatabaseFile.FIXED_CLOCK.millis())
    private var db: PagaYaDatabase = file.open(clock)
    private var clients = ClientRepository(db, clock)
    private var movements = MovementRepository(db, clock)

    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct3 = LocalDate.of(2026, 10, 3)
    private val oct5 = LocalDate.of(2026, 10, 5)

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    // ---- ayudas --------------------------------------------------------------------------

    private fun newClient(name: String = "Cliente Sintético"): String = runBlocking {
        val result = clients.create(randomId(), ClientInput(name, "", ""))
        (result as SaveClientResult.Saved).clientId
    }

    private fun register(
        clientId: String,
        type: MovementType,
        amount: String,
        date: LocalDate = oct5,
        description: String = "",
        requestId: String = randomId(),
    ) = runBlocking { movements.register(requestId, clientId, type, MovementInput(amount, date, description)) }

    private fun fiado(clientId: String, amount: Long, date: LocalDate = oct5, description: String = "") =
        register(clientId, MovementType.FIADO, amount.toString(), date, description)

    private fun abono(clientId: String, amount: Long, date: LocalDate = oct5, description: String = "") =
        register(clientId, MovementType.ABONO, amount.toString(), date, description)

    private fun payFull(clientId: String, confirmed: Long, requestId: String = randomId(), date: LocalDate = oct5) =
        runBlocking { movements.payFullBalance(requestId, clientId, confirmed, date) }

    private fun assertSaved(result: RegisterMovementResult) = assertTrue("Se esperaba Saved: $result", result is RegisterMovementResult.Saved)

    private fun account(clientId: String) = runBlocking { clients.observeClient(clientId).first()!! }

    private fun balance(clientId: String) = account(clientId).balanceCop

    private fun history(clientId: String) = runBlocking { movements.observeHistory(clientId).first() }

    private fun metadata(): LedgerMetadataEntity = db.ledgerMetadataDao().get()!!

    private fun movementCount(): Long =
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM movimiento")).use { it.moveToFirst(); it.getLong(0) }

    /** Estado global observable de la libreta: nada de esto debe cambiar cuando un alta se rechaza. */
    private data class Snapshot(val movements: Long, val dataRevision: Long, val nextOrder: Long)

    private fun snapshot() = Snapshot(movementCount(), metadata().dataRevision, metadata().nextEntryOrder)

    private fun assertRejectedWithoutChanges(before: Snapshot, result: RegisterMovementResult) {
        assertTrue("Se esperaba rechazo: $result", result !is RegisterMovementResult.Saved)
        assertEquals(before, snapshot())
    }

    // ---- recorrido de referencia (docs/10, dataset base) --------------------------------

    @Test
    fun referenceJourneyFiadoAbonosAndFullPayment() {
        val maria = newClient("María Sintética")
        assertEquals(0, account(maria).activeMovementCount)

        assertSaved(fiado(maria, 60_000, oct1, "Pedido de revista"))
        assertEquals(60_000L, balance(maria))

        assertSaved(abono(maria, 15_000, oct3))
        assertEquals(45_000L, balance(maria))

        assertSaved(abono(maria, 20_000, oct5))
        assertEquals(25_000L, balance(maria))

        assertSaved(payFull(maria, confirmed = 25_000))
        val paid = account(maria)
        assertEquals(0L, paid.balanceCop)
        assertEquals(4, paid.activeMovementCount) // «Pagado ✓»: saldo cero con historia vigente

        val rows = history(maria)
        assertEquals(listOf(0L, 25_000L, 45_000L, 60_000L), rows.map { it.balanceAfterCop })
        assertEquals(
            listOf(MovementType.ABONO, MovementType.ABONO, MovementType.ABONO, MovementType.FIADO),
            rows.map { it.entry.type },
        )
        assertEquals(MovementOrigin.PAGO_TOTAL, rows.first().entry.origin)
        assertEquals(listOf(MovementOrigin.MANUAL), rows.drop(1).map { it.entry.origin }.distinct())
        assertEquals("Pedido de revista", rows.last().entry.description)
        assertNull(rows.first().entry.description)
    }

    @Test
    fun debtAfterPaymentShowsTheNewBalance() {
        val client = newClient()
        assertSaved(fiado(client, 10_000, oct1))
        assertSaved(payFull(client, 10_000))
        assertEquals(0L, balance(client))
        assertSaved(fiado(client, 5_000))
        assertEquals(5_000L, balance(client))
    }

    @Test
    fun clientListReflectsBalancesAutomatically() = runBlocking {
        val debtor = newClient("Ana Deudora")
        val paid = newClient("Beto Pagado")
        val untouched = newClient("Carla Nueva")
        assertSaved(fiado(debtor, 83_000))
        assertSaved(fiado(paid, 10_000, oct1))
        assertSaved(abono(paid, 10_000, oct3))

        val byName = clients.observeActiveClients("").first().associateBy { it.client.name }
        assertEquals(83_000L to 1, byName.getValue("Ana Deudora").let { it.balanceCop to it.activeMovementCount })
        assertEquals(0L to 2, byName.getValue("Beto Pagado").let { it.balanceCop to it.activeMovementCount })
        assertEquals(0L to 0, byName.getValue("Carla Nueva").let { it.balanceCop to it.activeMovementCount })
        assertEquals(untouched, byName.getValue("Carla Nueva").client.id)
    }

    // ---- rechazos ------------------------------------------------------------------------

    @Test
    fun abonoAboveTheBalanceIsRejectedWithoutChanges() {
        val client = newClient()
        assertSaved(fiado(client, 45_000, oct1))
        val before = snapshot()

        val result = abono(client, 45_001)

        assertRejectedWithoutChanges(before, result)
        assertEquals(RegisterMovementResult.Rejected(HistoryRejection.ExceedsBalance(45_000)), result)
        assertEquals(45_000L, balance(client))
    }

    @Test
    fun abonoWithoutDebtIsRejectedWithoutChanges() {
        val client = newClient()
        val before = snapshot()
        assertRejectedWithoutChanges(before, abono(client, 1_000))
        assertEquals(RegisterMovementResult.Rejected(HistoryRejection.NoDebt), abono(client, 1_000))

        assertSaved(fiado(client, 1_000, oct1))
        assertSaved(abono(client, 1_000, oct3))
        val paidSnapshot = snapshot()
        assertRejectedWithoutChanges(paidSnapshot, abono(client, 1))
    }

    @Test
    fun invalidAmountsAndDatesAreRejectedWithoutChanges() {
        val client = newClient()
        assertSaved(fiado(client, 50_000, oct1))
        val before = snapshot()

        val amountCases = mapOf(
            "" to AmountError.NOT_POSITIVE,
            "0" to AmountError.NOT_POSITIVE,
            "-1" to AmountError.NOT_POSITIVE,
            "20,5" to AmountError.NOT_WHOLE,
            "20.5" to AmountError.NOT_WHOLE,
            "20.000,50" to AmountError.NOT_WHOLE,
            "abc" to AmountError.INVALID,
            "1e3" to AmountError.INVALID,
            "1.000.000.000" to AmountError.TOO_LARGE,
        )
        for ((text, expected) in amountCases) {
            val result = register(client, MovementType.FIADO, text)
            assertEquals("'$text'", expected, (result as RegisterMovementResult.Invalid).errors.amount)
            assertEquals(before, snapshot())
        }

        val future = register(client, MovementType.FIADO, "1000", date = oct5.plusDays(1))
        assertEquals(DateError.FUTURE, (future as RegisterMovementResult.Invalid).errors.date)
        val ancient = register(client, MovementType.FIADO, "1000", date = LocalDate.of(1899, 12, 31))
        assertEquals(DateError.TOO_OLD, (ancient as RegisterMovementResult.Invalid).errors.date)
        assertSaved(register(client, MovementType.FIADO, "1000", date = LocalDate.of(1900, 1, 1)))
    }

    @Test
    fun amountsAtTheLimitAreAcceptedAndTheNextOneIsNot() {
        val client = newClient()
        assertSaved(register(client, MovementType.FIADO, "999.999.999"))
        assertEquals(999_999_999L, balance(client))
        assertTrue(register(client, MovementType.FIADO, "1.000.000.000") is RegisterMovementResult.Invalid)
    }

    @Test
    fun retroactiveAbonoThatCreatesANegativeIntermediateBalanceIsRejected() {
        val client = newClient()
        assertSaved(fiado(client, 60_000, oct3))
        val before = snapshot()

        // El total (50.000) alcanza, pero el 1 de octubre aún no había deuda.
        val result = abono(client, 10_000, oct1)

        assertRejectedWithoutChanges(before, result)
        assertEquals(RegisterMovementResult.Rejected(HistoryRejection.NegativeAtDate(atNewEntry = true)), result)
        assertEquals(60_000L, balance(client))
    }

    @Test
    fun retroactiveAbonoThatLeavesALaterAbonoUncoveredIsRejectedEvenWithAPositiveFinalBalance() {
        val client = newClient()
        assertSaved(fiado(client, 100_000, oct1))
        assertSaved(abono(client, 100_000, LocalDate.of(2026, 10, 4)))
        assertSaved(fiado(client, 50_000, LocalDate.of(2026, 10, 5)))
        val before = snapshot()
        assertEquals(50_000L, balance(client))

        val result = abono(client, 10_000, oct3)

        assertRejectedWithoutChanges(before, result)
        assertEquals(RegisterMovementResult.Rejected(HistoryRejection.NegativeAtDate(atNewEntry = false)), result)
    }

    @Test
    fun retroactiveEntriesThatKeepEveryPrefixNonNegativeAreAccepted() {
        val client = newClient()
        assertSaved(fiado(client, 60_000, oct3))
        assertSaved(fiado(client, 10_000, oct1)) // fiado retroactivo
        assertSaved(abono(client, 10_000, oct1)) // ahora sí hay deuda ese día
        assertEquals(60_000L, balance(client))
        // El historial se muestra por fecha, no por orden de registro.
        assertEquals(listOf(oct3, oct1, oct1), history(client).map { it.entry.date })
        assertEquals(listOf(60_000L, 0L, 10_000L), history(client).map { it.balanceAfterCop })
    }

    // ---- orden estable -------------------------------------------------------------------

    @Test
    fun sameDayEntriesKeepRegistrationOrder() {
        val client = newClient()
        assertSaved(fiado(client, 10_000, oct5, "primero"))
        assertSaved(abono(client, 4_000, oct5, "segundo"))
        assertSaved(fiado(client, 1_000, oct5, "tercero"))

        val rows = history(client)
        assertEquals(listOf("tercero", "segundo", "primero"), rows.map { it.entry.description })
        assertEquals(listOf(7_000L, 6_000L, 10_000L), rows.map { it.balanceAfterCop })
        val orders = rows.map { it.entry.order }
        assertEquals(orders.sortedDescending(), orders)
        assertEquals(orders.size, orders.toSet().size)
    }

    @Test
    fun abonoRegisteredBeforeItsDebtTheSameDayIsRejected() {
        val client = newClient()
        assertTrue(abono(client, 5_000, oct5) is RegisterMovementResult.Rejected)
        assertSaved(fiado(client, 5_000, oct5))
        assertSaved(abono(client, 5_000, oct5))
    }

    @Test
    fun registrationOrderIsGlobalIncreasingAndNeverSkipsOnRejection() {
        val a = newClient("Cliente A")
        val b = newClient("Cliente B")
        assertEquals(1L, metadata().nextEntryOrder)
        assertSaved(fiado(a, 1_000))
        assertTrue(abono(a, 5_000) is RegisterMovementResult.Rejected) // no consume orden
        assertSaved(fiado(b, 2_000))
        assertSaved(abono(a, 1_000))

        assertEquals(4L, metadata().nextEntryOrder)
        val orders = (history(a) + history(b)).map { it.entry.order }.sorted()
        assertEquals(listOf(1L, 2L, 3L), orders)
    }

    // ---- idempotencia --------------------------------------------------------------------

    @Test
    fun retryWithTheSameRequestIdDoesNotDuplicateOrCountAgain() {
        val client = newClient()
        assertSaved(fiado(client, 60_000, oct1))
        val requestId = randomId()
        val first = register(client, MovementType.ABONO, "15000", oct3, "Transferencia", requestId)
        assertSaved(first)
        val after = snapshot()

        val retry = register(client, MovementType.ABONO, "15000", oct3, "Transferencia", requestId)

        assertEquals(first, retry)
        assertEquals(after, snapshot())
        assertEquals(45_000L, balance(client))
    }

    @Test
    fun theSameRequestIdWithDifferentContentIsAConflictAndChangesNothing() {
        val client = newClient()
        val other = newClient("Otro Cliente")
        assertSaved(fiado(client, 60_000, oct1))
        val requestId = randomId()
        assertSaved(register(client, MovementType.ABONO, "15000", oct3, requestId = requestId))
        val after = snapshot()

        assertEquals(RegisterMovementResult.Conflict, register(client, MovementType.ABONO, "16000", oct3, requestId = requestId))
        assertEquals(RegisterMovementResult.Conflict, register(client, MovementType.ABONO, "15000", oct5, requestId = requestId))
        assertEquals(RegisterMovementResult.Conflict, register(client, MovementType.FIADO, "15000", oct3, requestId = requestId))
        assertEquals(RegisterMovementResult.Conflict, register(other, MovementType.ABONO, "15000", oct3, requestId = requestId))
        assertEquals(after, snapshot())
        assertEquals(45_000L, balance(client))
    }

    @Test
    fun doubleTapRunningConcurrentlyCreatesOneMovement() = runBlocking {
        val client = newClient()
        assertSaved(fiado(client, 60_000, oct1))
        val before = snapshot()
        val requestId = randomId()

        val results = (1..4).map {
            async(Dispatchers.Default) {
                movements.register(requestId, client, MovementType.ABONO, MovementInput("10000", oct3, ""))
            }
        }.awaitAll()

        results.forEach { assertSaved(it) }
        assertEquals(1, results.map { (it as RegisterMovementResult.Saved).movementId }.toSet().size)
        assertEquals(before.copy(movements = before.movements + 1, dataRevision = before.dataRevision + 1, nextOrder = before.nextOrder + 1), snapshot())
        assertEquals(50_000L, balance(client))
    }

    @Test
    fun twoIntentionalIdenticalAbonosAreBothRecorded() {
        val client = newClient()
        assertSaved(fiado(client, 60_000, oct1))

        assertSaved(abono(client, 20_000, oct3, "Transferencia"))
        assertSaved(abono(client, 20_000, oct3, "Transferencia"))

        assertEquals(20_000L, balance(client))
        assertEquals(3, account(client).activeMovementCount)
        assertEquals(listOf(20_000L, 40_000L, 60_000L), history(client).map { it.balanceAfterCop })
    }

    // ---- pago completo -------------------------------------------------------------------

    @Test
    fun fullPaymentRecordsAnAbonoWithPagoTotalOriginAndZeroBalance() {
        val client = newClient()
        assertSaved(fiado(client, 45_000, oct1))

        val result = payFull(client, 45_000)

        assertSaved(result)
        val saved = db.movementDao().findById((result as RegisterMovementResult.Saved).movementId)!!
        assertEquals(MovementType.ABONO, saved.type)
        assertEquals(MovementOrigin.PAGO_TOTAL, saved.origin)
        assertEquals(45_000L, saved.amountCop)
        assertEquals(oct5, saved.date)
        assertEquals(0L, balance(client))
    }

    @Test
    fun fullPaymentIsRejectedWhenTheBalanceChangedSinceTheConfirmation() {
        val client = newClient()
        assertSaved(fiado(client, 60_000, oct1))
        val confirmed = balance(client) // la usuaria confirma $60.000...
        assertSaved(abono(client, 10_000, oct3)) // ...y el saldo cambia antes de guardar
        val before = snapshot()

        val result = payFull(client, confirmed)

        assertEquals(RegisterMovementResult.BalanceChanged(50_000), result)
        assertEquals(before, snapshot())
        assertEquals(50_000L, balance(client))
        // Tras revisar el nuevo importe, confirmarlo sí funciona.
        assertSaved(payFull(client, 50_000))
        assertEquals(0L, balance(client))
    }

    @Test
    fun fullPaymentIsRejectedWhenTheBalanceIncreasedSinceTheConfirmation() {
        val client = newClient()
        assertSaved(fiado(client, 60_000, oct1))
        assertSaved(fiado(client, 5_000, oct5))
        val before = snapshot()
        assertEquals(RegisterMovementResult.BalanceChanged(65_000), payFull(client, 60_000))
        assertEquals(before, snapshot())
    }

    @Test
    fun fullPaymentWithoutDebtDoesNotWriteAnything() {
        val client = newClient()
        val before = snapshot()
        assertEquals(RegisterMovementResult.BalanceChanged(0), payFull(client, 1_000))
        assertEquals(before, snapshot())
    }

    @Test
    fun fullPaymentAboveThePerMovementLimitAsksToSplitIntoAbonos() {
        val client = newClient()
        assertSaved(fiado(client, 999_999_999, oct1))
        assertSaved(fiado(client, 999_999_999, oct3))
        assertEquals(1_999_999_998L, balance(client))
        val before = snapshot()

        assertEquals(RegisterMovementResult.ExceedsLimit(1_999_999_998), payFull(client, 1_999_999_998))
        assertEquals(before, snapshot())

        // Dividido en abonos sí se puede liquidar.
        assertSaved(abono(client, 999_999_999, oct5))
        assertSaved(payFull(client, 999_999_999))
        assertEquals(0L, balance(client))
    }

    @Test
    fun retryingAFullPaymentThatAlreadySucceededDoesNotFailOrDuplicate() {
        val client = newClient()
        assertSaved(fiado(client, 45_000, oct1))
        val requestId = randomId()
        val first = payFull(client, 45_000, requestId)
        assertSaved(first)
        val after = snapshot()

        // El saldo ya es 0, pero el mismo intento se reconoce antes de releer el saldo.
        assertEquals(first, payFull(client, 45_000, requestId))
        assertEquals(after, snapshot())
        assertEquals(RegisterMovementResult.Conflict, payFull(client, 44_000, requestId))
    }

    @Test
    fun fullPaymentWithAFutureDateIsInvalid() {
        val client = newClient()
        assertSaved(fiado(client, 1_000, oct1))
        val before = snapshot()
        val result = payFull(client, 1_000, date = oct5.plusDays(1))
        assertEquals(DateError.FUTURE, (result as RegisterMovementResult.Invalid).errors.date)
        assertEquals(before, snapshot())
    }

    // ---- clientes ------------------------------------------------------------------------

    @Test
    fun accountsOfDifferentClientsAreIsolated() {
        val a = newClient("Cliente A")
        val b = newClient("Cliente B")
        assertSaved(fiado(a, 60_000, oct1))
        assertSaved(fiado(b, 10_000, oct1))
        assertSaved(abono(a, 15_000, oct3))

        assertEquals(45_000L, balance(a))
        assertEquals(10_000L, balance(b))
        assertEquals(2, history(a).size)
        assertEquals(1, history(b).size)
        // La deuda de A no sirve para abonar a B.
        assertEquals(RegisterMovementResult.Rejected(HistoryRejection.ExceedsBalance(10_000)), abono(b, 11_000))
        assertEquals(10_000L, balance(b))
        assertTrue(history(b).none { it.entry.id in history(a).map { row -> row.entry.id } })
    }

    @Test
    fun archivedAndUnknownClientsDoNotAcceptNewMovements() {
        val client = newClient()
        assertSaved(fiado(client, 1_000))
        db.clientDao().update(db.clientDao().findById(client)!!.copy(active = false))
        val before = snapshot()

        assertEquals(RegisterMovementResult.ClientArchived, fiado(client, 1_000))
        assertEquals(RegisterMovementResult.ClientArchived, payFull(client, 1_000))
        assertEquals(RegisterMovementResult.ClientNotFound, fiado(randomId(), 1_000))
        assertEquals(before, snapshot())
    }

    // ---- metadatos y transacción ---------------------------------------------------------

    @Test
    fun everySavedMovementAddsExactlyOneToDataRevisionAndNextOrder() {
        val client = newClient()
        val start = metadata()

        assertSaved(fiado(client, 3_000, oct1))
        assertSaved(abono(client, 1_000, oct3))
        assertSaved(payFull(client, 2_000))

        val end = metadata()
        assertEquals(start.dataRevision + 3, end.dataRevision)
        assertEquals(start.nextEntryOrder + 3, end.nextEntryOrder)
        assertEquals(start.ledgerId, end.ledgerId)
        assertEquals(start.localEpoch, end.localEpoch)
    }

    @Test
    fun aFailureWhileUpdatingTheMetadataRollsBackTheWholeRegistration() {
        val client = newClient()
        assertSaved(fiado(client, 60_000, oct1))
        val before = snapshot()
        // Fallo inyectado en el último paso: la revisión de datos. El movimiento y el orden ya escritos deben deshacerse.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_fail_revision BEFORE UPDATE OF revision_datos ON libreta_metadata " +
                "BEGIN SELECT RAISE(ABORT, 'fallo inyectado'); END",
        )

        try {
            abono(client, 10_000, oct3)
            fail("Se esperaba una excepción del fallo inyectado")
        } catch (e: Exception) {
            assertTrue("Inesperado: $e", generateSequence<Throwable>(e) { it.cause }.any { it.message.orEmpty().contains("fallo inyectado") })
        }

        assertEquals(before, snapshot())
        assertEquals(60_000L, balance(client))
        assertEquals(1, history(client).size)
        // Sin el fallo, el mismo registro funciona y recibe el orden que no se consumió.
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER test_fail_revision")
        assertSaved(abono(client, 10_000, oct3))
        assertEquals(before.nextOrder, history(client).first { it.entry.type == MovementType.ABONO }.entry.order)
    }

    @Test
    fun aFailureWhileInsertingTheMovementLeavesTheMetadataUntouched() {
        val client = newClient()
        val before = snapshot()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_fail_insert BEFORE INSERT ON movimiento BEGIN SELECT RAISE(ABORT, 'fallo inyectado'); END",
        )

        try {
            fiado(client, 1_000)
            fail("Se esperaba una excepción del fallo inyectado")
        } catch (e: Exception) {
            // esperado
        }
        assertEquals(before, snapshot())
        assertEquals(0, account(client).activeMovementCount)
        assertTrue((db as RoomDatabase).isOpen)
    }

    @Test
    fun committedMovementsSurviveClosingAndReopeningTheDatabase() {
        val client = newClient("Cliente Persistente")
        assertSaved(fiado(client, 60_000, oct1, "Pedido de revista"))
        assertSaved(abono(client, 15_000, oct3))
        val savedRows = history(client)
        val savedMetadata = metadata()

        db.close()
        db = file.open(clock)
        clients = ClientRepository(db, clock)
        movements = MovementRepository(db, clock)

        assertEquals(45_000L, balance(client))
        assertEquals(savedRows, history(client))
        assertEquals(savedMetadata, metadata())
        // Tras reabrir sigue funcionando y continúa la numeración.
        assertSaved(abono(client, 5_000, oct5))
        assertEquals(savedMetadata.nextEntryOrder + 1, metadata().nextEntryOrder)
        assertEquals(40_000L, balance(client))
    }
}
