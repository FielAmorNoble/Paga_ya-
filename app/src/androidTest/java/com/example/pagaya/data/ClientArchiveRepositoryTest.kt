package com.example.pagaya.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pagaya.data.local.ClientEntity
import com.example.pagaya.data.local.MovementEntity
import com.example.pagaya.data.local.MovementRevisionEntity
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.MutableClock
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.data.local.TestDatabaseFile
import com.example.pagaya.data.local.randomId
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.CorrectionInput
import com.example.pagaya.domain.MovementInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CyclicBarrier

/** Archivar y reactivar clientes con Room real: saldo cero, sin escrituras parciales ni repetidas. */
@RunWith(AndroidJUnit4::class)
class ClientArchiveRepositoryTest {

    private val file = TestDatabaseFile()
    private val clock = MutableClock(TestDatabaseFile.FIXED_CLOCK.millis()) // 5 oct 2026, 10:00 en Bogotá
    private var db: PagaYaDatabase = file.open(clock)
    private var clients = ClientRepository(db, clock)
    private var movements = MovementRepository(db, clock)

    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct3 = LocalDate.of(2026, 10, 3)
    private val oct4 = LocalDate.of(2026, 10, 4)

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    // ---- ayudas --------------------------------------------------------------------------

    private fun newClient(name: String = "Cliente Sintético", phone: String = "", note: String = ""): String = runBlocking {
        (clients.create(randomId(), ClientInput(name, phone, note)) as SaveClientResult.Saved).clientId
    }

    private fun register(clientId: String, type: MovementType, amount: Long, date: LocalDate): String = runBlocking {
        val result = movements.register(randomId(), clientId, type, MovementInput(amount.toString(), date, ""))
        (result as RegisterMovementResult.Saved).movementId
    }

    private fun fiado(clientId: String, amount: Long, date: LocalDate = oct1) = register(clientId, MovementType.FIADO, amount, date)

    private fun abono(clientId: String, amount: Long, date: LocalDate = oct3) = register(clientId, MovementType.ABONO, amount, date)

    private fun archive(id: String) = runBlocking { clients.archive(id) }

    private fun reactivate(id: String) = runBlocking { clients.reactivate(id) }

    private fun client(id: String): ClientEntity = db.clientDao().findById(id)!!

    private fun dataRevision() = db.ledgerMetadataDao().get()!!.dataRevision

    private fun activeNames(query: String = "") = runBlocking { clients.observeActiveClients(query).first().map { it.client.name } }

    private fun archivedNames(query: String = "") = runBlocking { clients.observeArchivedClients(query).first().map { it.client.name } }

    private fun balance(id: String) = runBlocking { clients.observeClient(id).first()!!.balanceCop }

    private fun movementsOf(id: String): List<MovementEntity> = db.movementDao().findActiveByClient(id) + allAnnulled(id)

    private fun allAnnulled(id: String): List<MovementEntity> =
        db.query(SimpleSQLiteQuery("SELECT id FROM movimiento WHERE cliente_id = ? AND activo = 0 ORDER BY orden_registro", arrayOf(id)))
            .use { c -> buildList { while (c.moveToNext()) add(db.movementDao().findById(c.getString(0))!!) } }

    private fun revisionsOf(movementIds: List<String>): List<MovementRevisionEntity> =
        movementIds.flatMap { db.movementRevisionDao().findByMovement(it) }

    /** Todo lo que un rechazo o una llamada sin cambios no debe tocar. */
    private data class State(val client: ClientEntity, val movements: List<MovementEntity>, val revisions: List<MovementRevisionEntity>, val metadata: Any)

    private fun state(id: String): State {
        val ms = movementsOf(id)
        return State(client(id), ms, revisionsOf(ms.map { it.id }), db.ledgerMetadataDao().get()!!)
    }

    // ---- archivar ------------------------------------------------------------------------

    @Test
    fun aClientWithoutMovementsIsArchivedKeepingItsDataAndCountingOneRevision() {
        val id = newClient("Cliente Sin Movimientos", phone = "300 000 0001", note = "Nota sintética")
        val before = client(id)
        val revisionBefore = dataRevision()
        clock.millis += 60_000

        assertEquals(ClientStatusResult.Changed, archive(id))

        val after = client(id)
        assertFalse(after.active)
        assertEquals(before.copy(active = false, modifiedAtMillis = clock.millis), after)
        assertEquals(before.createdAtMillis, after.createdAtMillis)
        assertEquals(revisionBefore + 1, dataRevision())
        assertEquals(emptyList<String>(), activeNames())
        assertEquals(listOf("Cliente Sin Movimientos"), archivedNames())
    }

    @Test
    fun aSettledAccountWithFiadosAndAbonosCanBeArchivedWithItsHistoryIntact() {
        val id = newClient()
        fiado(id, 60_000)
        fiado(id, 25_000, oct3)
        abono(id, 45_000, oct3)
        abono(id, 40_000, oct4)
        assertEquals(0L, balance(id))
        val before = state(id)
        val historyBefore = runBlocking { movements.observeHistory(id).first() }

        assertEquals(ClientStatusResult.Changed, archive(id))

        assertEquals(before.movements, movementsOf(id))
        assertEquals(historyBefore, runBlocking { movements.observeHistory(id).first() })
        assertEquals(0L, balance(id))
        assertEquals(4, runBlocking { clients.observeClient(id).first()!!.activeMovementCount })
    }

    @Test
    fun anAccountWithOnlyAnnulledMovementsCanBeArchivedAndKeepsItsRevisions() {
        val id = newClient()
        val f = fiado(id, 30_000)
        val annulled = runBlocking { movements.annul(randomId(), f, 1, "Cliente equivocado") }
        assertTrue(annulled is ChangeMovementResult.Saved)
        val before = state(id)
        assertEquals(1, before.revisions.size)

        assertEquals(ClientStatusResult.Changed, archive(id))

        assertEquals(before.movements, movementsOf(id))
        assertEquals(before.revisions, revisionsOf(listOf(f)))
        val account = runBlocking { clients.observeClient(id).first()!! }
        assertEquals(0, account.activeMovementCount)
        assertEquals(1, account.totalMovementCount)
    }

    @Test
    fun aClientWithDebtIsNotArchivedAndNothingChanges() {
        val id = newClient()
        fiado(id, 60_000)
        abono(id, 15_000)
        val before = state(id)
        clock.millis += 60_000

        assertEquals(ClientStatusResult.BalanceNotZero(45_000), archive(id))

        assertEquals(before, state(id))
        assertTrue(client(id).active)
        assertEquals(listOf("Cliente Sintético"), activeNames())
        assertEquals(emptyList<String>(), archivedNames())
    }

    @Test
    fun aDebtLeftByAnAnnulledAbonoAlsoBlocksArchiving() {
        val id = newClient()
        fiado(id, 50_000)
        val a = abono(id, 50_000)
        assertEquals(ClientStatusResult.Changed, archive(id))
        assertEquals(ClientStatusResult.Changed, reactivate(id))
        assertTrue(runBlocking { movements.annul(randomId(), a, 1, "Abono duplicado") } is ChangeMovementResult.Saved)
        val before = state(id)

        assertEquals(ClientStatusResult.BalanceNotZero(50_000), archive(id))
        assertEquals(before, state(id))
    }

    @Test
    fun unknownClientsAreReportedWithoutWriting() {
        val revisionBefore = dataRevision()
        assertEquals(ClientStatusResult.NotFound, archive(randomId()))
        assertEquals(ClientStatusResult.NotFound, reactivate(randomId()))
        assertEquals(revisionBefore, dataRevision())
    }

    // ---- reactivar -----------------------------------------------------------------------

    @Test
    fun reactivatingKeepsIdCreationDataMovementsAndRevisions() {
        val id = newClient("Cliente Para Reactivar", phone = "300 000 0002")
        val f = fiado(id, 60_000)
        val a = abono(id, 60_000)
        assertTrue(runBlocking { movements.correct(randomId(), a, 1, CorrectionInput("60000", oct4, "Transferencia", "Fecha mal")) } is ChangeMovementResult.Saved)
        val original = client(id)
        val movementsBefore = movementsOf(id)
        val revisionsBefore = revisionsOf(listOf(f, a))
        clock.millis += 60_000
        archive(id)
        val revisionAfterArchive = dataRevision()
        clock.millis += 60_000

        assertEquals(ClientStatusResult.Changed, reactivate(id))

        val after = client(id)
        assertEquals(original.copy(modifiedAtMillis = clock.millis), after)
        assertTrue(after.active)
        assertEquals(revisionAfterArchive + 1, dataRevision())
        assertEquals(movementsBefore, movementsOf(id))
        assertEquals(revisionsBefore, revisionsOf(listOf(f, a)))
        assertEquals(listOf("Cliente Para Reactivar"), activeNames())
        assertEquals(emptyList<String>(), archivedNames())
        // Reactivado vuelve a admitir movimientos.
        fiado(id, 5_000, oct4)
        assertEquals(5_000L, balance(id))
    }

    // ---- repeticiones y doble toque ------------------------------------------------------

    @Test
    fun repeatedRequestsDoNotWriteOrCountAgain() {
        val id = newClient()
        val start = dataRevision()
        clock.millis += 60_000
        assertEquals(ClientStatusResult.Changed, archive(id))
        val archived = client(id)
        clock.millis += 60_000

        assertEquals(ClientStatusResult.Unchanged, archive(id))
        assertEquals(ClientStatusResult.Unchanged, archive(id))
        assertEquals(archived, client(id))
        assertEquals(start + 1, dataRevision())

        assertEquals(ClientStatusResult.Changed, reactivate(id))
        val active = client(id)
        clock.millis += 60_000
        assertEquals(ClientStatusResult.Unchanged, reactivate(id))
        assertEquals(active, client(id))
        assertEquals(start + 2, dataRevision())
    }

    @Test
    fun concurrentDoubleTapArchivesOnceAndCountsOneRevision() = runBlocking {
        val id = newClient()
        val start = dataRevision()

        val archives = (1..4).map { async(Dispatchers.Default) { clients.archive(id) } }.awaitAll()
        assertEquals(1, archives.count { it == ClientStatusResult.Changed })
        assertEquals(3, archives.count { it == ClientStatusResult.Unchanged })
        assertEquals(start + 1, dataRevision())

        val reactivations = (1..4).map { async(Dispatchers.Default) { clients.reactivate(id) } }.awaitAll()
        assertEquals(1, reactivations.count { it == ClientStatusResult.Changed })
        assertEquals(3, reactivations.count { it == ClientStatusResult.Unchanged })
        assertEquals(start + 2, dataRevision())
        assertTrue(client(id).active)
    }

    // ---- atomicidad ----------------------------------------------------------------------

    @Test
    fun aFailureWhileUpdatingTheClientRollsBackTheRevisionIncrement() {
        val id = newClient()
        val before = state(id)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_fail_client BEFORE UPDATE OF activo ON cliente BEGIN SELECT RAISE(ABORT, 'fallo inyectado'); END",
        )

        expectInjectedFailure { archive(id) }

        assertEquals(before, state(id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER test_fail_client")
        assertEquals(ClientStatusResult.Changed, archive(id))
        val archived = state(id)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_fail_client BEFORE UPDATE OF activo ON cliente BEGIN SELECT RAISE(ABORT, 'fallo inyectado'); END",
        )

        expectInjectedFailure { reactivate(id) }

        assertEquals(archived, state(id))
        assertFalse(client(id).active)
    }

    @Test
    fun aFailureWhileCountingTheRevisionLeavesTheClientUntouched() {
        val id = newClient()
        val before = state(id)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_fail_revision BEFORE UPDATE OF revision_datos ON libreta_metadata " +
                "BEGIN SELECT RAISE(ABORT, 'fallo inyectado'); END",
        )

        expectInjectedFailure { archive(id) }

        assertEquals(before, state(id))
        assertTrue(client(id).active)
    }

    private fun expectInjectedFailure(block: () -> Unit) {
        try {
            block()
            fail("Se esperaba una excepción del fallo inyectado")
        } catch (e: Exception) {
            assertTrue("Inesperado: $e", generateSequence<Throwable>(e) { it.cause }.any { it.message.orEmpty().contains("fallo inyectado") })
        }
    }

    // ---- listas y búsqueda ---------------------------------------------------------------

    @Test
    fun activeAndArchivedListsAndSearchesNeverMix() {
        newClient("María Pérez")
        newClient("Ana Torres")
        val archivedMaria = newClient("Maria Perez")
        val angela = newClient("Ángela Ruiz")
        val percent = newClient("100% Ana_B")
        newClient("AnaXB")
        listOf(archivedMaria, angela, percent).forEach { assertEquals(ClientStatusResult.Changed, archive(it)) }

        // Lista principal: solo activos, en orden alfabético.
        assertEquals(listOf("Ana Torres", "AnaXB", "María Pérez"), activeNames())
        assertEquals(listOf("María Pérez"), activeNames("maria"))
        assertEquals(emptyList<String>(), activeNames("angela"))

        // Archivados: mismas reglas de mayúsculas, tildes, espacios y caracteres especiales.
        assertEquals(listOf("100% Ana_B", "Ángela Ruiz", "Maria Perez"), archivedNames())
        assertEquals(listOf("Maria Perez"), archivedNames("MARÍA"))
        assertEquals(listOf("Maria Perez"), archivedNames("  pérez "))
        assertEquals(listOf("Ángela Ruiz"), archivedNames("ANGELA"))
        assertEquals(listOf("100% Ana_B"), archivedNames("%"))
        assertEquals(listOf("100% Ana_B"), archivedNames("a_b"))
        assertEquals(emptyList<String>(), archivedNames("ana torres"))
        assertEquals(emptyList<String>(), archivedNames("'; DROP TABLE cliente; --"))

        // Reactivar mueve el cliente de una lista a la otra.
        reactivate(angela)
        assertEquals(listOf("Ana Torres", "AnaXB", "Ángela Ruiz", "María Pérez"), activeNames())
        assertEquals(listOf("100% Ana_B", "Maria Perez"), archivedNames())
        // Homónimos: el aviso cuenta también los archivados.
        assertEquals(2, runBlocking { clients.countHomonyms("maria perez", excludeId = null) })
    }

    @Test
    fun observedListsUpdateWhenArchivingAndReactivating() = runBlocking {
        val id = newClient("Cliente Observado")
        val active = clients.observeActiveClients("")
        val archived = clients.observeArchivedClients("")
        assertEquals(listOf("Cliente Observado"), active.first().map { it.client.name })
        assertEquals(emptyList<String>(), archived.first().map { it.client.name })

        clients.archive(id)
        assertEquals(emptyList<String>(), active.first().map { it.client.name })
        assertEquals(listOf("Cliente Observado"), archived.first().map { it.client.name })
        assertFalse(clients.observeClient(id).first()!!.client.active)

        clients.reactivate(id)
        assertEquals(listOf("Cliente Observado"), active.first().map { it.client.name })
        assertEquals(emptyList<String>(), archived.first().map { it.client.name })
    }

    // ---- persistencia --------------------------------------------------------------------

    @Test
    fun theArchivedStateSurvivesClosingAndReopeningTheDatabase() {
        val archivedId = newClient("Cliente Archivado Persistente")
        fiado(archivedId, 10_000)
        abono(archivedId, 10_000)
        val activeId = newClient("Cliente Activo Persistente")
        archive(archivedId)
        val before = state(archivedId)

        db.close()
        db = file.open(clock)
        clients = ClientRepository(db, clock)
        movements = MovementRepository(db, clock)

        assertEquals(before, state(archivedId))
        assertEquals(listOf("Cliente Archivado Persistente"), archivedNames())
        assertEquals(listOf("Cliente Activo Persistente"), activeNames())
        assertEquals(ClientStatusResult.Changed, reactivate(archivedId))
        assertEquals(ClientStatusResult.Changed, archive(activeId))
    }

    // ---- bloqueos de un cliente archivado ------------------------------------------------

    @Test
    fun anArchivedClientAcceptsNoNewMovementsOrPayments() {
        val id = newClient()
        archive(id)
        val before = state(id)
        val nextOrder = db.ledgerMetadataDao().get()!!.nextEntryOrder

        val fiado = runBlocking { movements.register(randomId(), id, MovementType.FIADO, MovementInput("10000", oct1, "")) }
        val abono = runBlocking { movements.register(randomId(), id, MovementType.ABONO, MovementInput("10000", oct1, "")) }
        val payment = runBlocking { movements.payFullBalance(randomId(), id, 10_000, oct3) }

        assertEquals(RegisterMovementResult.ClientArchived, fiado)
        assertEquals(RegisterMovementResult.ClientArchived, abono)
        assertEquals(RegisterMovementResult.ClientArchived, payment)
        assertEquals(before, state(id))
        assertEquals(nextOrder, db.ledgerMetadataDao().get()!!.nextEntryOrder)
    }

    @Test
    fun movementsOfAnArchivedClientCannotBeCorrectedOrAnnulled() {
        val id = newClient()
        val f = fiado(id, 60_000)
        val a = abono(id, 60_000)
        archive(id)
        val before = state(id)

        // Anular el abono crearía deuda en un cliente archivado; corregir cambiaría su saldo.
        assertEquals(ChangeMovementResult.ClientArchived, runBlocking { movements.annul(randomId(), a, 1, "Motivo") })
        assertEquals(ChangeMovementResult.ClientArchived, runBlocking { movements.annul(randomId(), f, 1, "Motivo") })
        assertEquals(
            ChangeMovementResult.ClientArchived,
            runBlocking { movements.correct(randomId(), f, 1, CorrectionInput("70000", oct1, "", "Motivo")) },
        )
        assertEquals(before, state(id))
        assertEquals(0L, balance(id))
    }

    // ---- carreras ------------------------------------------------------------------------

    /**
     * Archivar y registrar deuda a la vez: gana uno de los dos, nunca ambos. En cada ronda los dos
     * hilos arrancan juntos tras una barrera; al final ningún cliente queda archivado con deuda.
     */
    @Test
    fun racingAFiadoAgainstArchivingNeverLeavesAnArchivedClientWithDebt() = runBlocking {
        var archivedWins = 0
        var fiadoWins = 0
        repeat(40) { round ->
            val id = newClient("Cliente Carrera $round")
            val barrier = CyclicBarrier(2)
            val fiado = async(Dispatchers.Default) {
                barrier.await()
                movements.register(randomId(), id, MovementType.FIADO, MovementInput("5000", oct1, ""))
            }
            val archive = async(Dispatchers.Default) {
                barrier.await()
                clients.archive(id)
            }
            val fiadoResult = fiado.await()
            val archiveResult = archive.await()

            if (archiveResult == ClientStatusResult.Changed) {
                archivedWins++
                assertEquals(RegisterMovementResult.ClientArchived, fiadoResult)
                assertTrue(movementsOf(id).isEmpty())
            } else {
                fiadoWins++
                assertTrue("Ronda $round: $fiadoResult", fiadoResult is RegisterMovementResult.Saved)
                assertEquals(ClientStatusResult.BalanceNotZero(5_000), archiveResult)
            }
            val stored = client(id)
            assertFalse("Ronda $round: archivado con deuda", !stored.active && balance(id) != 0L)
        }
        assertEquals(40, archivedWins + fiadoWins)
        assertNoArchivedClientHasDebt()
    }

    /** Anular un abono (que vuelve a crear deuda) mientras se archiva: tampoco queda un archivado con deuda. */
    @Test
    fun racingAnAnnulmentAgainstArchivingNeverLeavesAnArchivedClientWithDebt() = runBlocking {
        repeat(30) { round ->
            val id = newClient("Cliente Anulación $round")
            fiado(id, 20_000)
            val a = abono(id, 20_000)
            val barrier = CyclicBarrier(2)
            val annul = async(Dispatchers.Default) {
                barrier.await()
                movements.annul(randomId(), a, 1, "Abono duplicado")
            }
            val archive = async(Dispatchers.Default) {
                barrier.await()
                clients.archive(id)
            }
            val annulResult = annul.await()
            val archiveResult = archive.await()

            if (archiveResult == ClientStatusResult.Changed) {
                assertEquals(ChangeMovementResult.ClientArchived, annulResult)
                assertEquals(0L, balance(id))
            } else {
                assertTrue("Ronda $round: $annulResult", annulResult is ChangeMovementResult.Saved)
                assertEquals(ClientStatusResult.BalanceNotZero(20_000), archiveResult)
            }
        }
        assertNoArchivedClientHasDebt()
    }

    private fun assertNoArchivedClientHasDebt() {
        val offenders = db.query(
            SimpleSQLiteQuery(
                "SELECT c.id FROM cliente c JOIN movimiento m ON m.cliente_id = c.id AND m.activo = 1 WHERE c.activo = 0 " +
                    "GROUP BY c.id HAVING SUM(CASE m.tipo WHEN 'FIADO' THEN m.valor_cop ELSE -m.valor_cop END) != 0",
            ),
        ).use { it.count }
        assertEquals(0, offenders)
    }
}
