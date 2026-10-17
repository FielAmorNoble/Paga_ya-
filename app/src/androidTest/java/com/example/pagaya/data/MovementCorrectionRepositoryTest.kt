package com.example.pagaya.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pagaya.data.local.LedgerMetadataEntity
import com.example.pagaya.data.local.MovementEntity
import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.MutableClock
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.data.local.RevisionAction
import com.example.pagaya.data.local.TestDatabaseFile
import com.example.pagaya.data.local.randomId
import com.example.pagaya.domain.AmountError
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.CorrectionInput
import com.example.pagaya.domain.DateError
import com.example.pagaya.domain.FieldChange
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.MovementInput
import com.example.pagaya.domain.ReasonError
import com.example.pagaya.domain.SnapshotJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Corrección y anulación de movimientos con Room real: trazabilidad, integridad y atomicidad. */
@RunWith(AndroidJUnit4::class)
class MovementCorrectionRepositoryTest {

    private val file = TestDatabaseFile()
    private val clock = MutableClock(TestDatabaseFile.FIXED_CLOCK.millis()) // 5 oct 2026, 10:00 en Bogotá
    private var db: PagaYaDatabase = file.open(clock)
    private var clients = ClientRepository(db, clock)
    private var movements = MovementRepository(db, clock)

    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct3 = LocalDate.of(2026, 10, 3)
    private val oct4 = LocalDate.of(2026, 10, 4)
    private val oct5 = LocalDate.of(2026, 10, 5)

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    // ---- ayudas --------------------------------------------------------------------------

    private fun newClient(name: String = "Cliente Sintético"): String = runBlocking {
        (clients.create(randomId(), ClientInput(name, "", "")) as SaveClientResult.Saved).clientId
    }

    private fun register(clientId: String, type: MovementType, amount: Long, date: LocalDate, description: String = ""): String = runBlocking {
        val result = movements.register(randomId(), clientId, type, MovementInput(amount.toString(), date, description))
        (result as RegisterMovementResult.Saved).movementId
    }

    private fun fiado(clientId: String, amount: Long, date: LocalDate = oct1, description: String = "") =
        register(clientId, MovementType.FIADO, amount, date, description)

    private fun abono(clientId: String, amount: Long, date: LocalDate = oct3, description: String = "") =
        register(clientId, MovementType.ABONO, amount, date, description)

    private fun movement(id: String): MovementEntity = db.movementDao().findById(id)!!

    private fun correct(
        id: String,
        amount: String,
        date: LocalDate,
        description: String = "",
        reason: String = "Me equivoqué",
        version: Long = movement(id).version,
        requestId: String = randomId(),
    ) = runBlocking { movements.correct(requestId, id, version, CorrectionInput(amount, date, description, reason)) }

    private fun annul(id: String, reason: String = "Registrado por error", version: Long = movement(id).version, requestId: String = randomId()) =
        runBlocking { movements.annul(requestId, id, version, reason) }

    private fun assertSaved(result: ChangeMovementResult) = assertTrue("Se esperaba Saved: $result", result is ChangeMovementResult.Saved)

    private fun balance(clientId: String) = runBlocking { clients.observeClient(clientId).first()!!.balanceCop }

    private fun history(clientId: String) = runBlocking { movements.observeHistory(clientId).first() }

    private fun revisions(id: String) = db.movementRevisionDao().findByMovement(id)

    private fun metadata(): LedgerMetadataEntity = db.ledgerMetadataDao().get()!!

    private fun count(table: String): Long =
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { it.moveToFirst(); it.getLong(0) }

    /** Todo lo observable de la libreta: nada de esto cambia cuando un cambio se rechaza o no hay cambios. */
    private data class Snapshot(
        val movements: List<MovementEntity>,
        val revisions: Long,
        val dataRevision: Long,
        val nextOrder: Long,
    )

    private fun snapshot(vararg ids: String) =
        Snapshot(ids.map { movement(it) }, count("movimiento_revision"), metadata().dataRevision, metadata().nextEntryOrder)

    // ---- corregir ------------------------------------------------------------------------

    @Test
    fun correctingAmountDateAndDescriptionUpdatesOnlyThoseFieldsAndKeepsTheOriginal() {
        val client = newClient()
        fiado(client, 60_000, oct1, "Pedido de revista")
        val id = abono(client, 15_000, oct3, "Transferencia")
        val before = movement(id)
        val metaBefore = metadata()
        clock.millis += 60_000

        val result = correct(id, "20.000", oct4, "Efectivo", reason = "  Me equivoqué de valor  ")

        assertEquals(ChangeMovementResult.Saved(id, newVersion = 2), result)
        val after = movement(id)
        assertEquals(20_000L, after.amountCop)
        assertEquals(oct4, after.date)
        assertEquals("Efectivo", after.description)
        // No cambian: ID, cliente, tipo, orden de registro, creación ni estado.
        assertEquals(before.id, after.id)
        assertEquals(before.clientId, after.clientId)
        assertEquals(before.type, after.type)
        assertEquals(before.entryOrder, after.entryOrder)
        assertEquals(before.createdAtMillis, after.createdAtMillis)
        assertTrue(after.active)
        // Sí cambian: versión y fecha de modificación.
        assertEquals(2L, after.version)
        assertEquals(clock.millis, after.modifiedAtMillis)
        assertEquals(before.createdAtMillis, before.modifiedAtMillis) // recién registrado: sin modificación previa
        // Contadores: revision_datos +1; el orden de registro no se consume.
        assertEquals(metaBefore.dataRevision + 1, metadata().dataRevision)
        assertEquals(metaBefore.nextEntryOrder, metadata().nextEntryOrder)
        assertEquals(40_000L, balance(client))
    }

    @Test
    fun theRevisionKeepsTheCompletePreviousSnapshotAndTheReason() {
        val client = newClient()
        fiado(client, 60_000)
        val id = abono(client, 15_000, oct3, "Transferencia")
        val before = movement(id)
        clock.millis += 1_000

        assertSaved(correct(id, "20000", oct4, "", reason = "  Error al escribir  "))

        val revision = revisions(id).single()
        assertEquals(1L, revision.previousVersion)
        assertEquals(2L, revision.newVersion)
        assertEquals(RevisionAction.EDITAR, revision.action)
        assertEquals("Error al escribir", revision.reason) // recortado
        assertEquals(clock.millis, revision.changedAtMillis)
        val snap = SnapshotJson.decode(revision.previousStateJson)
        assertEquals(before.id, snap.id)
        assertEquals(before.clientId, snap.clientId)
        assertEquals(oct3, snap.date)
        assertEquals(before.entryOrder, snap.order)
        assertEquals(MovementType.ABONO, snap.type)
        assertEquals(15_000L, snap.amountCop)
        assertEquals("Transferencia", snap.description)
        assertEquals(MovementOrigin.MANUAL, snap.origin)
        assertEquals(before.createdAtMillis, snap.createdAtMillis)
        assertEquals(before.modifiedAtMillis, snap.modifiedAtMillis)
        assertTrue(snap.active)
        assertEquals(1L, snap.version)
        assertTrue(revision.previousStateJson.startsWith("{") && revision.previousStateJson.endsWith("}"))
    }

    @Test
    fun correctingTheDateRecalculatesEveryPositionOfTheHistory() {
        val client = newClient()
        val f = fiado(client, 60_000, oct1)
        val first = abono(client, 15_000, oct3)
        val second = abono(client, 20_000, oct5)
        assertEquals(listOf(second, first, f), history(client).map { it.entry.id })
        assertEquals(listOf(25_000L, 45_000L, 60_000L), history(client).map { it.balanceAfterCop })

        // El abono de 20.000 (registrado al final) pasa al 2 de oct.: ahora va antes que el de 15.000.
        assertSaved(correct(second, "20000", LocalDate.of(2026, 10, 2)))

        val rows = history(client)
        assertEquals(listOf(first, second, f), rows.map { it.entry.id })
        assertEquals(listOf(25_000L, 40_000L, 60_000L), rows.map { it.balanceAfterCop })
        assertEquals(25_000L, balance(client))
        // El orden de registro del movimiento corregido no cambió: sigue siendo el último registrado.
        assertTrue(movement(first).entryOrder < movement(second).entryOrder)
    }

    @Test
    fun correctingTheAmountMovesTheBalanceAndTheRowsAfterIt() {
        val client = newClient()
        fiado(client, 60_000, oct1)
        val a = abono(client, 15_000, oct3)
        abono(client, 20_000, oct5)

        assertSaved(correct(a, "10000", oct3))

        assertEquals(30_000L, balance(client))
        assertEquals(listOf(30_000L, 50_000L, 60_000L), history(client).map { it.balanceAfterCop })
    }

    @Test
    fun correctingTheDateReordersTheHistoryByDateAndRegistrationOrder() {
        val client = newClient()
        val f = fiado(client, 60_000, oct1, "primero")
        val a = abono(client, 10_000, oct3, "abono A")
        val b = abono(client, 20_000, oct4, "abono B")
        assertEquals(listOf(b, a, f), history(client).map { it.entry.id })

        // El abono A pasa del 3 al 5 de oct.: ahora es el más reciente.
        assertSaved(correct(a, "10000", oct5, "abono A"))

        val rows = history(client)
        assertEquals(listOf(a, b, f), rows.map { it.entry.id })
        assertEquals(listOf(30_000L, 40_000L, 60_000L), rows.map { it.balanceAfterCop })
    }

    @Test
    fun correctionsThatLeaveAnIntermediateNegativeBalanceAreRejectedWithoutChanges() {
        val client = newClient()
        val f1 = fiado(client, 100_000, oct1)
        val a = abono(client, 100_000, oct4)
        val f2 = fiado(client, 50_000, oct5)
        assertEquals(50_000L, balance(client))
        val before = snapshot(f1, a, f2)

        // El saldo final sería 40.000, pero el 4 de octubre quedaría en -10.000.
        val result = correct(f1, "90000", oct1)

        assertEquals(ChangeMovementResult.Rejected(HistoryRejection.UncoveredAbono(history(client).first { it.entry.id == a }.entry)), result)
        assertEquals(before, snapshot(f1, a, f2))
    }

    @Test
    fun reducingTheFiadoOfTheDocumentedExampleIsRejectedAndNamesTheUncoveredAbono() {
        val client = newClient()
        val f = fiado(client, 60_000, oct1)
        val a = abono(client, 40_000, oct3)
        val before = snapshot(f, a)

        val result = correct(f, "30000", oct1)

        val rejection = (result as ChangeMovementResult.Rejected).reason as HistoryRejection.UncoveredAbono
        assertEquals(a, rejection.abono.id)
        assertEquals(40_000L, rejection.abono.amountCop)
        assertEquals(oct3, rejection.abono.date)
        assertEquals(before, snapshot(f, a))
        assertEquals(20_000L, balance(client))
        // Reducirlo hasta cubrir exactamente el abono sí es válido.
        assertSaved(correct(f, "40000", oct1))
        assertEquals(0L, balance(client))
    }

    @Test
    fun anAbonoCannotBeCorrectedAboveWhatWasOwedOrBeforeItsDebt() {
        val client = newClient()
        fiado(client, 60_000, oct1)
        val a = abono(client, 40_000, oct3)
        val before = snapshot(a)

        assertEquals(ChangeMovementResult.Rejected(HistoryRejection.ExceedsBalance(60_000)), correct(a, "60001", oct3))
        assertEquals(ChangeMovementResult.Rejected(HistoryRejection.NegativeAtDate(atNewEntry = true)), correct(a, "40000", LocalDate.of(2026, 9, 30)))
        assertEquals(before, snapshot(a))
        assertSaved(correct(a, "60000", oct1)) // el mismo día de la deuda, registrada antes: válido
    }

    @Test
    fun invalidValuesAndMissingReasonsAreRejectedWithoutChanges() {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000)
        val before = snapshot(a)

        fun invalid(result: ChangeMovementResult) = (result as ChangeMovementResult.Invalid).errors

        assertEquals(AmountError.NOT_WHOLE, invalid(correct(a, "20,5", oct3)).amount)
        assertEquals(AmountError.NOT_POSITIVE, invalid(correct(a, "0", oct3)).amount)
        assertEquals(AmountError.TOO_LARGE, invalid(correct(a, "1000000000", oct3)).amount)
        assertEquals(DateError.FUTURE, invalid(correct(a, "10000", oct5.plusDays(1))).date)
        assertEquals(DateError.TOO_OLD, invalid(correct(a, "10000", LocalDate.of(1899, 12, 31))).date)
        assertEquals(ReasonError.REQUIRED, invalid(correct(a, "10000", oct3, reason = "   ")).reason)
        assertEquals(ReasonError.TOO_LONG, invalid(correct(a, "10000", oct3, reason = "x".repeat(301))).reason)
        assertEquals(ReasonError.REQUIRED, invalid(annul(a, reason = "")).reason)
        assertEquals(before, snapshot(a))
    }

    @Test
    fun savingWithoutChangesCreatesNoRevisionAndCountsNothing() {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000, oct3, "Transferencia")
        val before = snapshot(a)

        // Mismos valores; la nota con espacios sobrantes equivale a la misma nota. Ni siquiera exige motivo.
        assertEquals(ChangeMovementResult.Unchanged, correct(a, "15.000", oct3, "  Transferencia ", reason = ""))
        assertEquals(ChangeMovementResult.Unchanged, correct(a, "15000", oct3, "Transferencia", reason = "otra vez"))

        assertEquals(before, snapshot(a))
        assertEquals(1L, movement(a).version)
    }

    @Test
    fun correctingTheAmountOfAFullPaymentChangesItsOriginToManual() {
        val client = newClient()
        fiado(client, 45_000, oct1)
        val payment = (runBlocking { movements.payFullBalance(randomId(), client, 45_000, oct5) } as RegisterMovementResult.Saved).movementId
        assertEquals(MovementOrigin.PAGO_TOTAL, movement(payment).origin)

        // Cambiar solo la fecha o la nota no altera el origen.
        assertSaved(correct(payment, "45000", oct5, "Con nota"))
        assertEquals(MovementOrigin.PAGO_TOTAL, movement(payment).origin)

        // Cambiar el importe sí.
        assertSaved(correct(payment, "44000", oct5, "Con nota"))
        assertEquals(MovementOrigin.MANUAL, movement(payment).origin)
        assertEquals(44_000L, movement(payment).amountCop)
        assertEquals(1_000L, balance(client))
        // La revisión conserva el origen y el valor anteriores.
        val previous = SnapshotJson.decode(revisions(payment).last().previousStateJson)
        assertEquals(MovementOrigin.PAGO_TOTAL, previous.origin)
        assertEquals(45_000L, previous.amountCop)
    }

    // ---- anular --------------------------------------------------------------------------

    @Test
    fun annullingAnAbonoIncreasesTheDebtAndKeepsTheRowVisibleWithoutBalance() {
        val client = newClient()
        val f = fiado(client, 60_000, oct1)
        val a = abono(client, 15_000, oct3, "Transferencia")
        val metaBefore = metadata()

        val result = annul(a, reason = "Lo registré dos veces")

        assertEquals(ChangeMovementResult.Saved(a, newVersion = 2), result)
        assertEquals(60_000L, balance(client)) // la deuda vuelve a subir
        val stored = movement(a)
        assertFalse(stored.active)
        assertEquals(15_000L, stored.amountCop) // se conserva el valor como referencia
        assertEquals("Transferencia", stored.description)
        // No se borra físicamente y la fila sigue en el historial, sin saldo resultante.
        assertEquals(2L, count("movimiento"))
        val rows = history(client)
        assertEquals(listOf(a, f), rows.map { it.entry.id })
        assertEquals(listOf(null, 60_000L), rows.map { it.balanceAfterCop })
        assertFalse(rows.first().entry.active)
        // Contadores: +1 revisión de datos, sin consumir orden.
        assertEquals(metaBefore.dataRevision + 1, metadata().dataRevision)
        assertEquals(metaBefore.nextEntryOrder, metadata().nextEntryOrder)
        val revision = revisions(a).single()
        assertEquals(RevisionAction.ANULAR, revision.action)
        assertEquals("Lo registré dos veces", revision.reason)
        assertTrue(SnapshotJson.decode(revision.previousStateJson).active)
    }

    @Test
    fun annullingAFiadoThatNoAbonoDependsOnReducesTheDebt() {
        val client = newClient()
        val f1 = fiado(client, 60_000, oct1)
        val f2 = fiado(client, 20_000, oct3)
        val a = abono(client, 50_000, oct4)
        assertEquals(30_000L, balance(client))

        assertSaved(annul(f2))

        assertEquals(10_000L, balance(client))
        assertEquals(listOf(a, f2, f1), history(client).map { it.entry.id })
        assertEquals(listOf(10_000L, null, 60_000L), history(client).map { it.balanceAfterCop })
    }

    @Test
    fun annullingAFiadoThatLeavesAbonosUncoveredIsRejectedWithoutChanges() {
        val client = newClient()
        val f = fiado(client, 60_000, oct1)
        val a = abono(client, 40_000, oct3)
        val before = snapshot(f, a)

        val result = annul(f)

        val rejection = (result as ChangeMovementResult.Rejected).reason as HistoryRejection.UncoveredAbono
        assertEquals(a, rejection.abono.id)
        assertEquals(before, snapshot(f, a))
        assertTrue(movement(f).active)
        assertEquals(20_000L, balance(client))
    }

    @Test
    fun anAccountWithOnlyAnnulledMovementsHasNoActiveMovementsButHasHistory() {
        val client = newClient()
        val f = fiado(client, 10_000)
        assertSaved(annul(f))

        val account = runBlocking { clients.observeClient(client).first()!! }
        assertEquals(0, account.activeMovementCount)
        assertEquals(1, account.totalMovementCount)
        assertEquals(0L, account.balanceCop)
        assertEquals(1, history(client).size)
    }

    @Test
    fun anAnnulledMovementCannotBeCorrectedOrAnnulledAgain() {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000)
        assertSaved(annul(a))
        val before = snapshot(a)

        assertEquals(ChangeMovementResult.AlreadyAnnulled, annul(a, version = 2))
        assertEquals(ChangeMovementResult.AlreadyAnnulled, annul(a, reason = "otra vez", version = 1))
        assertEquals(ChangeMovementResult.AlreadyAnnulled, correct(a, "10000", oct3, version = 2))
        assertEquals(before, snapshot(a))
        assertEquals(1, revisions(a).size)
    }

    // ---- versiones, doble toque y reintentos ---------------------------------------------

    @Test
    fun aStaleVersionIsRejectedAndNeverOverwritesTheNewerChange() {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000, oct3, "original")
        // Dos pantallas abren el movimiento en la versión 1.
        assertSaved(correct(a, "20000", oct3, "original", reason = "Primera pantalla", version = 1))
        val afterFirst = snapshot(a)

        val second = correct(a, "30000", oct3, "original", reason = "Segunda pantalla", version = 1)
        val annulStale = annul(a, version = 1)

        assertEquals(ChangeMovementResult.VersionConflict, second)
        assertEquals(ChangeMovementResult.VersionConflict, annulStale)
        assertEquals(afterFirst, snapshot(a))
        assertEquals(20_000L, movement(a).amountCop)
        assertEquals(1, revisions(a).size)
        // Tras recargar y revisar, la segunda pantalla puede corregir sobre la versión nueva.
        assertSaved(correct(a, "30000", oct3, "original", reason = "Segunda pantalla", version = 2))
        assertEquals(listOf(1L, 2L), revisions(a).map { it.previousVersion })
    }

    @Test
    fun retryingTheSameRequestDoesNotDuplicateTheRevisionOrTheCounters() {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000)
        val requestId = randomId()
        val first = correct(a, "20000", oct3, reason = "Error de valor", version = 1, requestId = requestId)
        assertSaved(first)
        val after = snapshot(a)

        // Reintento tras un fallo de red o un doble toque: la versión que vio sigue siendo la 1.
        assertEquals(first, correct(a, "20000", oct3, reason = "Error de valor", version = 1, requestId = requestId))
        assertEquals(first, correct(a, "20.000", oct3, reason = " Error de valor ", version = 1, requestId = requestId))

        assertEquals(after, snapshot(a))
        assertEquals(1, revisions(a).size)
    }

    @Test
    fun theSameRequestWithDifferentContentIsAConflictAndChangesNothing() {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000)
        val requestId = randomId()
        assertSaved(correct(a, "20000", oct3, reason = "Error de valor", version = 1, requestId = requestId))
        val after = snapshot(a)

        assertEquals(ChangeMovementResult.Conflict, correct(a, "25000", oct3, reason = "Error de valor", version = 1, requestId = requestId))
        assertEquals(ChangeMovementResult.Conflict, correct(a, "20000", oct3, reason = "Otro motivo", version = 1, requestId = requestId))
        assertEquals(ChangeMovementResult.Conflict, annul(a, reason = "Error de valor", version = 1, requestId = requestId))
        assertEquals(after, snapshot(a))
    }

    @Test
    fun aRetryStillSucceedsAfterLaterChangesToTheSameMovement() {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000)
        val requestId = randomId()
        assertSaved(correct(a, "20000", oct3, reason = "Primero", version = 1, requestId = requestId))
        assertSaved(correct(a, "25000", oct3, reason = "Segundo", version = 2))
        val after = snapshot(a)

        // El primer intento llega de nuevo cuando el movimiento ya va por la versión 3.
        assertEquals(ChangeMovementResult.Saved(a, 2), correct(a, "20000", oct3, reason = "Primero", version = 1, requestId = requestId))
        assertEquals(after, snapshot(a))
        assertEquals(25_000L, movement(a).amountCop)
    }

    @Test
    fun retryOfAnEarlierCorrectionIsComparedAgainstTheStateItProducedNotTheCurrentOne() {
        val client = newClient()
        fiado(client, 100_000, oct1)
        val a = abono(client, 15_000, oct3, "uno")
        val requestA = randomId()
        // A: de 15.000 / 3 oct / «uno» a 20.000 / 4 oct / «dos».
        assertEquals(
            ChangeMovementResult.Saved(a, 2),
            correct(a, "20000", oct4, "dos", reason = "Corrección A", version = 1, requestId = requestA),
        )
        // B, posterior: 25.000 / 3 oct / «tres».
        assertEquals(ChangeMovementResult.Saved(a, 3), correct(a, "25000", oct3, "tres", reason = "Corrección B", version = 2))
        val after = snapshot(a)

        // Reintento idéntico de A: éxito, sin escrituras.
        assertEquals(
            ChangeMovementResult.Saved(a, 2),
            correct(a, "20000", oct4, "dos", reason = "Corrección A", version = 1, requestId = requestA),
        )
        assertEquals(after, snapshot(a))

        // El mismo ID de A con otro importe, otra fecha o otra descripción: Conflict, sin escrituras.
        val different = listOf(
            Triple("21000", oct4, "dos"),
            Triple("20000", oct5, "dos"),
            Triple("20000", oct4, "otra nota"),
            Triple("20000", oct4, ""),
            // El contenido del estado ACTUAL (el de B): antes se aceptaba por error al ignorar el estado que produjo A.
            Triple("25000", oct3, "tres"),
        )
        for ((amount, date, description) in different) {
            assertEquals(
                "«$amount» $date «$description»",
                ChangeMovementResult.Conflict,
                correct(a, amount, date, description, reason = "Corrección A", version = 1, requestId = requestA),
            )
            assertEquals(after, snapshot(a))
        }
        // Otro motivo, otra acción o una versión esperada distinta del mismo ID también son otro intento.
        assertEquals(ChangeMovementResult.Conflict, correct(a, "20000", oct4, "dos", reason = "Otro motivo", version = 1, requestId = requestA))
        assertEquals(ChangeMovementResult.Conflict, annul(a, reason = "Corrección A", version = 1, requestId = requestA))
        assertEquals(ChangeMovementResult.Conflict, correct(a, "20000", oct4, "dos", reason = "Corrección A", version = 2, requestId = requestA))
        assertEquals(after, snapshot(a))
        assertEquals(listOf(1L, 2L), revisions(a).map { it.previousVersion })
    }

    @Test
    fun retryOfACorrectionStillComparesCorrectlyAfterTheMovementWasAnnulled() {
        val client = newClient()
        fiado(client, 100_000, oct1)
        val a = abono(client, 15_000, oct3, "uno")
        val requestA = randomId()
        assertSaved(correct(a, "20000", oct4, "dos", reason = "Corrección A", version = 1, requestId = requestA))
        assertSaved(annul(a, reason = "Duplicado", version = 2))
        val after = snapshot(a)
        assertFalse(movement(a).active)

        assertEquals(ChangeMovementResult.Saved(a, 2), correct(a, "20000", oct4, "dos", reason = "Corrección A", version = 1, requestId = requestA))
        // Lo que había antes de A (15.000 / 3 oct / «uno») tampoco es lo que produjo A.
        assertEquals(ChangeMovementResult.Conflict, correct(a, "15000", oct3, "uno", reason = "Corrección A", version = 1, requestId = requestA))
        assertEquals(ChangeMovementResult.Conflict, correct(a, "20000", oct4, "otra", reason = "Corrección A", version = 1, requestId = requestA))
        assertEquals(after, snapshot(a))
    }

    @Test
    fun aMissingFollowingRevisionIsAnInconsistencyNotASilentAnswer() {
        val client = newClient()
        fiado(client, 100_000, oct1)
        val a = abono(client, 15_000, oct3)
        val requestA = randomId()
        assertSaved(correct(a, "20000", oct4, reason = "Corrección A", version = 1, requestId = requestA))
        assertSaved(correct(a, "25000", oct4, reason = "Corrección B", version = 2))
        // Se pierde la revisión que sigue a A (daño simulado: solo la restauración completa puede borrar filas).
        db.openHelper.writableDatabase.execSQL("DELETE FROM movimiento_revision WHERE movimiento_id = '$a' AND version_anterior = 2")
        val before = snapshot(a)

        try {
            correct(a, "20000", oct4, reason = "Corrección A", version = 1, requestId = requestA)
            fail("Se esperaba una excepción por el historial inconsistente")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Falta la revisión"))
        }
        assertEquals(before, snapshot(a))
    }

    @Test
    fun anActiveChangesObserverOnlySeesConsistentSetsWhileCorrectionsAreMade() = runBlocking {
        val client = newClient()
        fiado(client, 1_000_000, oct1)
        val a = abono(client, 10_000, oct3)
        val total = 40
        val reasons = (1..total).map { "Motivo $it" }
        val emissions = java.util.concurrent.CopyOnWriteArrayList<List<com.example.pagaya.domain.MovementChange>>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val collector = launch(Dispatchers.IO) {
            try {
                movements.observeChanges(a).collect { emissions += it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        withTimeout(10_000) { while (emissions.isEmpty()) delay(10) }

        // Varias correcciones mientras el observador sigue activo, escritas desde otro hilo.
        withContext(Dispatchers.Default) {
            reasons.forEachIndexed { index, reason ->
                val result = movements.correct(randomId(), a, index + 1L, CorrectionInput((10_000 + (index + 1) * 100).toString(), oct3, "n${index + 1}", reason))
                assertSaved(result)
                if (index % 3 == 0) delay(5)
            }
        }
        withTimeout(10_000) { while (emissions.lastOrNull()?.size != total && failure.get() == null) delay(10) }
        collector.cancelAndJoin()

        assertNull("El observador falló: ${failure.get()}", failure.get())
        assertTrue("Debe haber varias emisiones, hubo ${emissions.size}", emissions.size >= 2)
        emissions.forEachIndexed { position, emission ->
            val n = emission.size
            // Cada emisión es un conjunto consistente: las n primeras revisiones, del más reciente al más antiguo.
            assertEquals("emisión $position", reasons.take(n).reversed(), emission.map { it.reason })
            // Y encadenadas: lo posterior de cada cambio es lo anterior del cambio que le sigue.
            emission.zipWithNext().forEach { (newer, older) -> assertEquals(older.after, newer.before) }
        }
        assertEquals(total, emissions.last().size)
        // La última emisión coincide con el estado real.
        assertEquals(movement(a).amountCop, emissions.last().first().after.amountCop)
        assertEquals(total + 1L, movement(a).version)
    }

    @Test
    fun doubleTapRunningConcurrentlyCreatesOneRevision() = runBlocking {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000)
        val before = snapshot(a)
        val requestId = randomId()

        val results = (1..4).map {
            async(Dispatchers.Default) {
                movements.correct(requestId, a, 1, CorrectionInput("20000", oct3, "", "Error de valor"))
            }
        }.awaitAll()

        results.forEach { assertSaved(it) }
        assertEquals(before.revisions + 1, count("movimiento_revision"))
        assertEquals(before.dataRevision + 1, metadata().dataRevision)
        assertEquals(2L, movement(a).version)
        assertEquals(20_000L, movement(a).amountCop)
    }

    @Test
    fun twoDifferentFormsOpenedOnTheSameVersionLeaveExactlyOneWinner() = runBlocking {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000)

        val results = listOf("21000", "22000").map { amount ->
            async(Dispatchers.Default) {
                movements.correct(randomId(), a, 1, CorrectionInput(amount, oct3, "", "Error de valor"))
            }
        }.awaitAll()

        assertEquals(1, results.count { it is ChangeMovementResult.Saved })
        assertEquals(1, results.count { it == ChangeMovementResult.VersionConflict })
        assertEquals(1, revisions(a).size)
        assertEquals(2L, movement(a).version)
    }

    @Test
    fun annulRetryIsIdempotent() {
        val client = newClient()
        val f = fiado(client, 10_000)
        val requestId = randomId()
        val first = annul(f, version = 1, requestId = requestId)
        assertSaved(first)
        val after = snapshot(f)

        assertEquals(first, annul(f, version = 1, requestId = requestId))
        assertEquals(after, snapshot(f))
        assertEquals(1, revisions(f).size)
    }

    // ---- continuidad y trazabilidad ------------------------------------------------------

    @Test
    fun versionsFormAContinuousSequenceAndEveryRevisionKeepsItsPreviousState() {
        val client = newClient()
        fiado(client, 100_000, oct1)
        val a = abono(client, 15_000, oct3, "uno")
        val states = mutableListOf(SnapshotJson.decode(SnapshotJson.encode(snapshotOf(movement(a)))))

        clock.millis += 1_000
        assertSaved(correct(a, "20000", oct3, "dos", reason = "Primera corrección"))
        states += snapshotOf(movement(a))
        clock.millis += 1_000
        assertSaved(correct(a, "25000", oct4, "tres", reason = "Segunda corrección"))
        states += snapshotOf(movement(a))
        clock.millis += 1_000
        assertSaved(annul(a, reason = "Ya no corresponde"))

        val stored = revisions(a)
        assertEquals(listOf(1L, 2L, 3L), stored.map { it.previousVersion })
        assertEquals(listOf(2L, 3L, 4L), stored.map { it.newVersion })
        assertEquals(listOf(RevisionAction.EDITAR, RevisionAction.EDITAR, RevisionAction.ANULAR), stored.map { it.action })
        assertEquals(states, stored.map { SnapshotJson.decode(it.previousStateJson) })
        // movimiento.version = revisiones + 1 (docs/13).
        assertEquals(stored.size + 1L, movement(a).version)
        assertEquals(3, count("movimiento_revision").toInt())
    }

    @Test
    fun theChangeLogShowsPreviousAndNewValuesReasonAndDateNewestFirst() {
        val client = newClient()
        fiado(client, 100_000, oct1)
        val a = abono(client, 15_000, oct3, "uno")
        clock.millis += 60_000
        val firstChangeAt = clock.millis
        assertSaved(correct(a, "20000", oct4, "dos", reason = "Valor mal escrito"))
        clock.millis += 60_000
        val annulAt = clock.millis
        assertSaved(annul(a, reason = "Duplicado"))

        val log = runBlocking { movements.observeChanges(a).first() }

        assertEquals(2, log.size)
        assertEquals(listOf(RevisionAction.ANULAR, RevisionAction.EDITAR), log.map { it.action })
        assertEquals(listOf("Duplicado", "Valor mal escrito"), log.map { it.reason })
        assertEquals(listOf(annulAt, firstChangeAt), log.map { it.changedAtMillis })
        assertEquals(listOf(FieldChange.Status(before = true, after = false)), log[0].changes)
        assertEquals(
            listOf(
                FieldChange.Amount(15_000, 20_000),
                FieldChange.Date(oct3, oct4),
                FieldChange.Description("uno", "dos"),
            ),
            log[1].changes,
        )
        assertEquals(15_000L, log[1].before.amountCop)
        assertEquals(20_000L, log[1].after.amountCop)
    }

    @Test
    fun aMovementWithoutRevisionsHasNoChanges() {
        val client = newClient()
        val f = fiado(client, 10_000)
        assertTrue(runBlocking { movements.observeChanges(f).first() }.isEmpty())
        assertEquals(0, runBlocking { movements.observeMovementDetail(f).first()!!.revisionCount })
    }

    // ---- otros bloqueos y aislamiento ----------------------------------------------------

    @Test
    fun archivedClientsAndUnknownMovementsAreNotChanged() {
        val client = newClient()
        val f = fiado(client, 1_000)
        db.clientDao().update(db.clientDao().findById(client)!!.copy(active = false))
        val before = snapshot(f)

        assertEquals(ChangeMovementResult.ClientArchived, correct(f, "2000", oct1))
        assertEquals(ChangeMovementResult.ClientArchived, annul(f))
        assertEquals(ChangeMovementResult.NotFound, runBlocking { movements.annul(randomId(), randomId(), 1, "motivo") })
        assertEquals(before, snapshot(f))
    }

    @Test
    fun correctionsOfOneClientNeverTouchAnotherAccount() {
        val a = newClient("Cliente A")
        val b = newClient("Cliente B")
        fiado(a, 60_000, oct1)
        val abonoA = abono(a, 40_000, oct3)
        fiado(b, 10_000, oct1)
        val abonoB = abono(b, 5_000, oct3)

        assertSaved(correct(abonoA, "10000", oct3))
        assertSaved(annul(abonoB))

        assertEquals(50_000L, balance(a))
        assertEquals(10_000L, balance(b))
        assertEquals(1, revisions(abonoA).size)
        assertEquals(1, revisions(abonoB).size)
        assertEquals(2, history(a).size)
        // El abono de A no puede superar lo que debe A, aunque B tenga deuda.
        assertEquals(ChangeMovementResult.Rejected(HistoryRejection.ExceedsBalance(60_000)), correct(abonoA, "65000", oct3))
    }

    // ---- atomicidad ----------------------------------------------------------------------

    @Test
    fun aFailureWhileUpdatingTheDataRevisionRollsBackTheRevisionAndTheMovement() {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000, oct3, "uno")
        val before = snapshot(a)
        // Fallo inyectado en el último paso: la revisión ya insertada y el movimiento ya actualizado deben deshacerse.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_fail_revision BEFORE UPDATE OF revision_datos ON libreta_metadata " +
                "BEGIN SELECT RAISE(ABORT, 'fallo inyectado'); END",
        )

        expectInjectedFailure { correct(a, "20000", oct4, "dos") }
        expectInjectedFailure { annul(a) }

        assertEquals(before, snapshot(a))
        assertEquals(15_000L, movement(a).amountCop)
        assertTrue(revisions(a).isEmpty())
        // Sin el fallo, el mismo cambio funciona: no quedó nada a medias.
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER test_fail_revision")
        assertSaved(correct(a, "20000", oct4, "dos"))
        assertEquals(1, revisions(a).size)
    }

    @Test
    fun aFailureWhileUpdatingTheMovementRollsBackTheAlreadyInsertedRevision() {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000)
        val before = snapshot(a)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_fail_update BEFORE UPDATE ON movimiento BEGIN SELECT RAISE(ABORT, 'fallo inyectado'); END",
        )

        expectInjectedFailure { correct(a, "20000", oct3) }

        assertEquals(before, snapshot(a))
        assertTrue(revisions(a).isEmpty())
    }

    @Test
    fun aFailureWhileInsertingTheRevisionLeavesTheMovementUntouched() {
        val client = newClient()
        fiado(client, 60_000)
        val a = abono(client, 15_000)
        val before = snapshot(a)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_fail_insert BEFORE INSERT ON movimiento_revision BEGIN SELECT RAISE(ABORT, 'fallo inyectado'); END",
        )

        expectInjectedFailure { annul(a) }

        assertEquals(before, snapshot(a))
        assertTrue(movement(a).active)
    }

    private fun expectInjectedFailure(block: () -> Unit) {
        try {
            block()
            fail("Se esperaba una excepción del fallo inyectado")
        } catch (e: Exception) {
            assertTrue("Inesperado: $e", generateSequence<Throwable>(e) { it.cause }.any { it.message.orEmpty().contains("fallo inyectado") })
        }
    }

    // ---- persistencia --------------------------------------------------------------------

    @Test
    fun correctionsAnnulmentsAndRevisionsSurviveClosingAndReopeningTheDatabase() {
        val client = newClient("Cliente Persistente")
        val f = fiado(client, 100_000, oct1, "Pedido")
        val a = abono(client, 15_000, oct3, "uno")
        val b = abono(client, 10_000, oct4, "dos")
        assertSaved(correct(a, "20000", oct3, "uno corregido", reason = "Valor mal escrito"))
        assertSaved(annul(b, reason = "Duplicado"))
        val historyBefore = history(client)
        val revisionsBefore = revisions(a) + revisions(b)
        val metaBefore = metadata()
        val changesBefore = runBlocking { movements.observeChanges(a).first() }

        db.close()
        db = file.open(clock)
        clients = ClientRepository(db, clock)
        movements = MovementRepository(db, clock)

        assertEquals(80_000L, balance(client))
        assertEquals(historyBefore, history(client))
        assertEquals(revisionsBefore, revisions(a) + revisions(b))
        assertEquals(metaBefore, metadata())
        assertEquals(changesBefore, runBlocking { movements.observeChanges(a).first() })
        assertFalse(movement(b).active)
        assertEquals("uno corregido", movement(a).description)
        assertNotNull(movement(f))
        // Tras reabrir sigue funcionando: la versión continúa donde quedó.
        assertSaved(correct(a, "21000", oct3, "uno corregido", reason = "Otro ajuste"))
        assertEquals(listOf(1L, 2L), revisions(a).map { it.previousVersion })
        assertNull(revisions(f).firstOrNull())
    }

    private fun snapshotOf(m: MovementEntity) = com.example.pagaya.domain.MovementSnapshot(
        m.id, m.clientId, m.date, m.entryOrder, m.type, m.amountCop, m.description, m.origin, m.createdAtMillis, m.modifiedAtMillis, m.active, m.version,
    )
}
