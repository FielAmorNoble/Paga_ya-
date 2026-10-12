package com.example.pagaya.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.PagaYaDatabase
import com.example.pagaya.data.local.TestDatabaseFile
import com.example.pagaya.data.local.movement
import com.example.pagaya.data.local.randomId
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.NameError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Reloj controlable para comprobar instantes de creación y modificación. */
private class MutableClock(var millis: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
}

@RunWith(AndroidJUnit4::class)
class ClientRepositoryTest {

    private val file = TestDatabaseFile()
    private val clock = MutableClock(TestDatabaseFile.FIXED_CLOCK.millis())
    private var db: PagaYaDatabase = file.open(clock)
    private var repository = ClientRepository(db, clock)

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    private fun dataRevision() = db.ledgerMetadataDao().get()!!.dataRevision

    private fun create(name: String, phone: String = "", note: String = "", id: String = randomId()): String = runBlocking {
        val result = repository.create(id, ClientInput(name, phone, note))
        assertTrue("Se esperaba guardado: $result", result is SaveClientResult.Saved)
        (result as SaveClientResult.Saved).clientId
    }

    private fun search(query: String) = runBlocking { repository.observeActiveClients(query).first().map { it.client.name } }

    @Test
    fun createdClientCanBeRetrievedWithNullOptionalsAndNoMovements() = runBlocking {
        val id = create("  Cliente Sintético Uno  ", phone = " ", note = "")

        val account = repository.observeClient(id).first()!!
        assertEquals("Cliente Sintético Uno", account.client.name)
        assertEquals("cliente sintetico uno", account.client.searchName)
        assertNull(account.client.phone)
        assertNull(account.client.note)
        assertEquals(clock.millis, account.client.createdAtMillis)
        assertEquals(clock.millis, account.client.modifiedAtMillis)
        assertTrue(account.client.active)
        assertEquals(0, account.activeMovementCount)
        assertEquals(0L, account.balanceCop)
    }

    @Test
    fun invalidInputIsRejectedWithoutWritingAnything() = runBlocking {
        val before = dataRevision()
        val id = randomId()

        val result = repository.create(id, ClientInput("   ", "abc", ""))

        assertTrue(result is SaveClientResult.Invalid)
        assertEquals(NameError.REQUIRED, (result as SaveClientResult.Invalid).errors.name)
        assertNull(db.clientDao().findById(id))
        assertEquals(before, dataRevision())
    }

    @Test
    fun searchIgnoresCaseAndAccentsKeepsHomonymsAndOrdersAlphabetically() {
        create("María Pérez", phone = "300 000 0001")
        create("Maria Perez", phone = "300 000 0002")
        create("Ana Torres")
        create("Ángela Ruiz")

        val homonyms = setOf("María Pérez", "Maria Perez")
        assertEquals(homonyms, search("maria").toSet())
        assertEquals(2, search("maria").size)
        assertEquals(homonyms, search("MARÍA").toSet())
        assertEquals(homonyms, search("  pérez ").toSet())
        assertEquals(listOf("Ángela Ruiz"), search("ANGELA"))

        // Orden alfabético sin distinguir acentos: «Ángela» va entre «Ana» y «María».
        val all = search("")
        assertEquals(listOf("Ana Torres", "Ángela Ruiz"), all.take(2))
        assertEquals(homonyms, all.drop(2).toSet())
    }

    @Test
    fun wildcardCharactersAreSearchedLiterally() {
        create("100% Ana")
        create("Ana_B")
        create("AnaXB")
        create("Barra \\ Invertida")

        assertEquals(listOf("100% Ana"), search("%"))
        assertEquals(listOf("Ana_B"), search("_"))
        assertEquals(listOf("Ana_B"), search("a_b"))
        assertEquals(listOf("Barra \\ Invertida"), search("\\"))
        assertEquals(emptyList<String>(), search("'; DROP TABLE cliente; --"))
        assertEquals(4, search("").size)
    }

    @Test
    fun homonymsAreCountedButNeverBlocked() = runBlocking {
        val first = create("María Pérez")
        assertEquals(1, repository.countHomonyms("maria  perez", excludeId = null))
        assertEquals(0, repository.countHomonyms("María Pérez", excludeId = first))

        create("MARIA PEREZ")

        assertEquals(2, repository.countHomonyms("María Pérez", excludeId = null))
    }

    @Test
    fun editingKeepsIdAndCreationAndUpdatesModificationAndSearchName() = runBlocking {
        val id = create("Cliente Original", phone = "3000000000", note = "Nota inicial")
        val created = db.clientDao().findById(id)!!
        clock.millis += 60_000

        val result = repository.update(id, ClientInput("Clienta Editada Ñandú", "", "Nueva nota"))

        assertEquals(SaveClientResult.Saved(id), result)
        val edited = db.clientDao().findById(id)!!
        assertEquals(id, edited.id)
        assertEquals(created.createdAtMillis, edited.createdAtMillis)
        assertEquals(clock.millis, edited.modifiedAtMillis)
        assertEquals("Clienta Editada Ñandú", edited.name)
        assertEquals("clienta editada nandu", edited.searchName)
        assertNull(edited.phone)
        assertEquals("Nueva nota", edited.note)
        assertTrue(edited.active)
        assertEquals(listOf("Clienta Editada Ñandú"), search("nandu"))
        assertEquals(SaveClientResult.NotFound, repository.update(randomId(), ClientInput("X", "", "")))
    }

    @Test
    fun createAndEditIncrementDataRevisionOncePerChange() = runBlocking {
        val start = dataRevision()
        val id = create("Cliente Sintético")
        assertEquals(start + 1, dataRevision())

        repository.update(id, ClientInput("Cliente Sintético", "", "Nota"))
        assertEquals(start + 2, dataRevision())

        // Guardar de nuevo los mismos datos no es un cambio.
        repository.update(id, ClientInput(" Cliente Sintético ", "", "Nota "))
        assertEquals(start + 2, dataRevision())
    }

    @Test
    fun aFailedWriteRollsBackTheRevisionIncrement() = runBlocking {
        val before = dataRevision()

        // El repositorio incrementa la revisión antes de insertar; el ID inválido hace fallar
        // la inserción (restricción de la base) y la transacción completa debe revertirse.
        val failure = runCatching { repository.create("no-es-uuid", ClientInput("Cliente Sintético", "", "")) }

        assertTrue(failure.isFailure)
        assertEquals(before, dataRevision())
        assertEquals(emptyList<String>(), search(""))
    }

    @Test
    fun retryingTheSameRequestDoesNotDuplicateTheClient() = runBlocking {
        val requestId = randomId()
        val input = ClientInput("Cliente Reintento", "3000000000", "")
        val start = dataRevision()

        assertEquals(SaveClientResult.Saved(requestId), repository.create(requestId, input))
        assertEquals(SaveClientResult.Saved(requestId), repository.create(requestId, input))
        assertEquals(SaveClientResult.Saved(requestId), repository.create(requestId, input.copy(name = " Cliente Reintento ")))

        assertEquals(listOf("Cliente Reintento"), search(""))
        assertEquals(start + 1, dataRevision())
        // El mismo ID con otros datos no sobrescribe el cliente guardado.
        assertEquals(SaveClientResult.Conflict, repository.create(requestId, input.copy(name = "Otro Nombre")))
        assertEquals("Cliente Reintento", db.clientDao().findById(requestId)!!.name)
    }

    @Test
    fun clientsPersistAfterClosingAndReopeningTheDatabase() = runBlocking {
        val id = create("Cliente Persistente", phone = "3000000000", note = "Nota sintética")
        val stored = db.clientDao().findById(id)
        val revision = dataRevision()
        db.close()

        db = file.open(clock)
        repository = ClientRepository(db, clock)

        assertEquals(stored, repository.observeClient(id).first()!!.client)
        assertEquals(listOf("Cliente Persistente"), search("persistente"))
        assertEquals(revision, dataRevision())
    }

    @Test
    fun accountStateIsDerivedFromActiveMovementsOnly() = runBlocking {
        val withHistory = create("Cliente Con Movimientos")
        val withoutHistory = create("Cliente Sin Movimientos")
        db.movementDao().insert(movement(withHistory, entryOrder = 1, amountCop = 60_000))
        db.movementDao().insert(movement(withHistory, entryOrder = 2, type = MovementType.ABONO, amountCop = 15_000))
        val annulled = movement(withHistory, entryOrder = 3, type = MovementType.ABONO, amountCop = 5_000)
        db.movementDao().insert(annulled.copy(active = false))

        val accounts = repository.observeActiveClients("").first().associateBy { it.client.id }

        assertEquals(2, accounts.getValue(withHistory).activeMovementCount)
        assertEquals(45_000L, accounts.getValue(withHistory).balanceCop)
        assertEquals(0, accounts.getValue(withoutHistory).activeMovementCount)
        assertEquals(0L, accounts.getValue(withoutHistory).balanceCop)
        assertEquals(accounts.getValue(withHistory), repository.observeClient(withHistory).first())
    }

    @Test
    fun theObservedListUpdatesAfterACreation() = runBlocking {
        val flow = repository.observeActiveClients("")
        assertEquals(emptyList<String>(), flow.first().map { it.client.name })

        create("Cliente Nuevo")

        assertEquals(listOf("Cliente Nuevo"), flow.first().map { it.client.name })
    }
}
