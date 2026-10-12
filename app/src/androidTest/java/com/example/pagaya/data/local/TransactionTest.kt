package com.example.pagaya.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionTest {

    private val file = TestDatabaseFile()
    private var db: PagaYaDatabase = file.open()

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    @Test
    fun aFailureMidTransactionRollsBackEarlierWrites() {
        val metadataBefore = db.ledgerMetadataDao().get()!!
        val client = client()

        assertConstraint(rule = "movimiento.valor_cop") {
            db.runInTransaction {
                db.clientDao().insert(client)
                db.ledgerMetadataDao().update(metadataBefore.copy(dataRevision = 1, nextEntryOrder = 2))
                db.movementDao().insert(movement(client.id, entryOrder = 1, amountCop = 0))
            }
        }

        assertNull(db.clientDao().findById(client.id))
        assertEquals(metadataBefore, db.ledgerMetadataDao().get())
    }

    @Test
    fun aRejectedRevisionLeavesTheMovementUnchanged() {
        val client = client().also(db.clientDao()::insert)
        val movement = movement(client.id, entryOrder = 1).also(db.movementDao()::insert)

        assertConstraint(rule = "movimiento_revision.motivo") {
            db.runInTransaction {
                db.movementDao().update(movement.copy(amountCop = 50_000, version = 2))
                db.movementRevisionDao().insert(revision(movement.id).copy(reason = " "))
            }
        }

        assertEquals(movement, db.movementDao().findById(movement.id))
        assertTrue(db.movementRevisionDao().findByMovement(movement.id).isEmpty())
    }

    @Test
    fun aCommittedTransactionSurvivesReopeningTheFile() {
        val metadataBefore = db.ledgerMetadataDao().get()!!
        val client = client()
        val movement = movement(client.id, entryOrder = 1)
        val metadataAfter = metadataBefore.copy(dataRevision = 1, nextEntryOrder = 2)

        db.runInTransaction {
            db.clientDao().insert(client)
            db.movementDao().insert(movement)
            db.ledgerMetadataDao().update(metadataAfter)
        }
        db.close()
        db = file.open()

        assertEquals(client, db.clientDao().findById(client.id))
        assertEquals(movement, db.movementDao().findById(movement.id))
        assertEquals(metadataAfter, db.ledgerMetadataDao().get())
    }
}
