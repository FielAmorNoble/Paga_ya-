package com.example.pagaya.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Bases de prueba aisladas en archivo; nunca usa ni borra [PagaYaDatabase.FILE_NAME]. */
internal class TestDatabaseFile {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val fileName = "test-${UUID.randomUUID()}.db"

    init {
        check(fileName != PagaYaDatabase.FILE_NAME)
    }

    fun open(clock: Clock = FIXED_CLOCK, newId: () -> String = ::randomId): PagaYaDatabase =
        PagaYaDatabase.build(context, fileName, clock, newId)

    fun delete() {
        check(fileName != PagaYaDatabase.FILE_NAME)
        context.deleteDatabase(fileName)
    }

    companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-05T15:00:00Z"), ZoneOffset.UTC)
    }
}

internal fun randomId(): String = UUID.randomUUID().toString()

internal val T0: Long = TestDatabaseFile.FIXED_CLOCK.millis()

// Datos sintéticos; ninguno corresponde a personas reales.
internal fun client(id: String = randomId(), name: String = "Cliente Sintético A") = ClientEntity(
    id = id,
    name = name,
    searchName = "cliente sintetico a",
    phone = null,
    note = null,
    createdAtMillis = T0,
    modifiedAtMillis = T0,
    active = true,
)

internal fun movement(
    clientId: String,
    entryOrder: Long,
    id: String = randomId(),
    type: MovementType = MovementType.FIADO,
    origin: MovementOrigin = MovementOrigin.MANUAL,
    amountCop: Long = 60_000,
) = MovementEntity(
    id = id,
    clientId = clientId,
    date = LocalDate.of(2026, 10, 1),
    entryOrder = entryOrder,
    type = type,
    amountCop = amountCop,
    description = null,
    origin = origin,
    createdAtMillis = T0,
    modifiedAtMillis = T0,
    active = true,
    version = 1,
)

internal fun revision(movementId: String, previousVersion: Long = 1, id: String = randomId()) = MovementRevisionEntity(
    id = id,
    movementId = movementId,
    previousVersion = previousVersion,
    newVersion = previousVersion + 1,
    action = RevisionAction.EDITAR,
    previousStateJson = "{\"valor_cop\":60000}",
    changedAtMillis = T0,
    reason = "Corrección sintética",
)

internal fun backupRecord(
    ledgerId: String,
    localEpoch: String,
    backupId: String = randomId(),
    destination: BackupDestination = BackupDestination.ARCHIVO,
) = BackupRecordEntity(
    backupId = backupId,
    destination = destination,
    ledgerId = ledgerId,
    localEpoch = localEpoch,
    dataRevision = 0,
    capturedAtMillis = T0,
    state = BackupState.PREPARANDO,
    verifiedAtMillis = null,
    locator = null,
    driveAccountFingerprint = null,
    errorCode = null,
)

/**
 * Exige que [block] falle por una restricción de SQLite. Si se indica [rule]
 * (`tabla.regla` de [SchemaConstraints]), comprueba que la rechazó ese trigger.
 */
internal fun assertConstraint(
    rule: String? = null,
    messageFragment: String? = null,
    case: String? = null,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: Throwable) {
        var cause: Throwable? = e
        while (cause != null && cause !is SQLiteConstraintException) cause = cause.cause
        if (cause == null) throw AssertionError("Se esperaba SQLiteConstraintException y se obtuvo $e", e)
        val message = cause.message.orEmpty()
        rule?.let { assertTrue("Mensaje '$message' no contiene la regla $it", message.contains(SchemaConstraints.ERROR_PREFIX + it)) }
        messageFragment?.let { assertTrue("Mensaje '$message' no contiene '$it'", message.contains(it)) }
        return
    }
    fail("Se esperaba una violación de restricción" + (rule?.let { " ($it)" } ?: "") + (case?.let { " para $it" } ?: ""))
}
