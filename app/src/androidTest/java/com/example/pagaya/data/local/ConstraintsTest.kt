package com.example.pagaya.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConstraintsTest {

    private val file = TestDatabaseFile()
    private lateinit var db: PagaYaDatabase
    private lateinit var sql: SupportSQLiteDatabase
    private lateinit var metadata: LedgerMetadataEntity
    private val owner = client()
    private var nextOrder = 1L

    @Before
    fun setUp() {
        db = file.open()
        sql = db.openHelper.writableDatabase
        metadata = db.ledgerMetadataDao().get()!!
        db.clientDao().insert(owner)
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    private fun newMovement(
        type: MovementType = MovementType.FIADO,
        origin: MovementOrigin = MovementOrigin.MANUAL,
        amountCop: Long = 60_000,
    ) = movement(owner.id, nextOrder++, type = type, origin = origin, amountCop = amountCop)

    /** INSERT con SQL directo, para valores que los tipos de Kotlin no permiten construir. */
    private fun insertRaw(table: String, values: Map<String, Any?>) {
        sql.execSQL(
            "INSERT INTO $table (${values.keys.joinToString()}) VALUES (${values.keys.joinToString { "?" }})",
            values.values.toTypedArray(),
        )
    }

    private fun rawMovement(vararg overrides: Pair<String, Any?>) = linkedMapOf<String, Any?>(
        "id" to randomId(), "cliente_id" to owner.id, "fecha" to "2026-10-01", "orden_registro" to nextOrder++,
        "tipo" to "FIADO", "valor_cop" to 1_000L, "descripcion" to null, "origen" to "MANUAL",
        "creado_en" to T0, "modificado_en" to T0, "activo" to 1L, "version" to 1L,
    ).apply { putAll(overrides) }

    // --- Claves foráneas ---

    @Test
    fun orphanReferencesAreRejected() {
        assertConstraint(messageFragment = "FOREIGN KEY") { db.movementDao().insert(movement(randomId(), entryOrder = 99)) }
        assertConstraint(messageFragment = "FOREIGN KEY") { db.movementRevisionDao().insert(revision(randomId())) }
    }

    @Test
    fun referencedRowsCannotBeDeleted() {
        val movement = newMovement().also(db.movementDao()::insert)
        db.movementRevisionDao().insert(revision(movement.id))

        assertConstraint(messageFragment = "FOREIGN KEY") { sql.execSQL("DELETE FROM cliente WHERE id = ?", arrayOf(owner.id)) }
        assertConstraint(messageFragment = "FOREIGN KEY") { sql.execSQL("DELETE FROM movimiento WHERE id = ?", arrayOf(movement.id)) }
        assertEquals(owner, db.clientDao().findById(owner.id))
        assertEquals(movement, db.movementDao().findById(movement.id))
    }

    // --- Unicidad ---

    @Test
    fun duplicateIdentifiersOrdersAndVersionsAreRejected() {
        val movement = newMovement().also(db.movementDao()::insert)
        val firstRevision = revision(movement.id).also(db.movementRevisionDao()::insert)

        assertConstraint(messageFragment = "cliente.id") { db.clientDao().insert(client(id = owner.id, name = "Cliente Sintético B")) }
        assertConstraint(messageFragment = "movimiento.id") { db.movementDao().insert(movement.copy(entryOrder = nextOrder++)) }
        assertConstraint(messageFragment = "movimiento.orden_registro") {
            db.movementDao().insert(movement.copy(id = randomId()))
        }
        assertConstraint(messageFragment = "movimiento_revision.movimiento_id") {
            db.movementRevisionDao().insert(revision(movement.id, previousVersion = 1))
        }
        assertConstraint(messageFragment = "movimiento_revision.id") {
            db.movementRevisionDao().insert(revision(movement.id, previousVersion = 2, id = firstRevision.id))
        }
    }

    @Test
    fun oneBackupCanBeRecordedPerDestinationWithoutCollisions() {
        val record = backupRecord(metadata.ledgerId, metadata.localEpoch)
        db.backupRecordDao().insert(record)
        db.backupRecordDao().insert(record.copy(destination = BackupDestination.DRIVE))

        assertConstraint(messageFragment = "backup_registro.backup_id") { db.backupRecordDao().insert(record) }
        assertEquals(record, db.backupRecordDao().find(record.backupId, BackupDestination.ARCHIVO))
    }

    // --- Importes, tipos y combinaciones ---

    @Test
    fun amountsOutsideTheCopRangeAreRejected() {
        listOf(0L, -1L, 1_000_000_000L).forEach { amount ->
            assertConstraint(rule = "movimiento.valor_cop") { db.movementDao().insert(newMovement(amountCop = amount)) }
        }
        listOf("abc", 20.5).forEach { value ->
            assertConstraint(rule = "movimiento.valor_cop") { insertRaw("movimiento", rawMovement("valor_cop" to value)) }
        }
        db.movementDao().insert(newMovement(amountCop = 1))
        db.movementDao().insert(newMovement(amountCop = 999_999_999))
    }

    @Test
    fun typeAndOriginCombinationsAreEnforced() {
        assertConstraint(rule = "movimiento.tipo_origen") {
            db.movementDao().insert(newMovement(type = MovementType.FIADO, origin = MovementOrigin.PAGO_TOTAL))
        }
        assertConstraint(rule = "movimiento.tipo") { insertRaw("movimiento", rawMovement("tipo" to "COMPRA")) }
        assertConstraint(rule = "movimiento.origen") { insertRaw("movimiento", rawMovement("origen" to "OTRO")) }

        db.movementDao().insert(newMovement(type = MovementType.ABONO, origin = MovementOrigin.PAGO_TOTAL, amountCop = 1))
        db.movementDao().insert(newMovement(type = MovementType.ABONO, origin = MovementOrigin.MANUAL, amountCop = 1))
    }

    @Test
    fun movementRowValuesAreValidated() {
        // date() de SQLite no sirve de validador: en Android 15 devuelve '2026-02-30' sin normalizar.
        listOf("2026-02-30", "2026-02-31", "2026-04-31", "2026-02-29", "1900-02-29", "2026-13-01", "2026-00-10",
            "2026-10-00", "2026-1-05", "05/10/2026").forEach { date ->
            assertConstraint(rule = "movimiento.fecha", case = date) { insertRaw("movimiento", rawMovement("fecha" to date)) }
        }
        listOf("2024-02-29", "2000-02-29", "2026-12-31").forEach { date -> insertRaw("movimiento", rawMovement("fecha" to date)) }
        assertConstraint(rule = "movimiento.activo") { insertRaw("movimiento", rawMovement("activo" to 2L)) }
        assertConstraint(rule = "movimiento.orden_registro") { insertRaw("movimiento", rawMovement("orden_registro" to 0L)) }
        assertConstraint(rule = "movimiento.descripcion") { insertRaw("movimiento", rawMovement("descripcion" to "")) }
        assertConstraint(rule = "movimiento.id") { insertRaw("movimiento", rawMovement("id" to "no-es-un-uuid")) }
        assertConstraint(rule = "movimiento.version") { db.movementDao().insert(newMovement().copy(version = 0)) }
    }

    @Test
    fun clientRowValuesAreValidated() {
        listOf("", "   ", " Cliente con espacios ", "x".repeat(121)).forEach { name ->
            assertConstraint(rule = "cliente.nombre") { db.clientDao().insert(client(name = name)) }
        }
        assertConstraint(rule = "cliente.telefono") { db.clientDao().insert(client().copy(phone = "")) }
        assertConstraint(rule = "cliente.telefono") { db.clientDao().insert(client().copy(phone = "1".repeat(31))) }
        assertConstraint(rule = "cliente.nota") { db.clientDao().insert(client().copy(note = "n".repeat(1001))) }
        assertConstraint(rule = "cliente.id") { db.clientDao().insert(client(id = randomId().uppercase())) }

        db.clientDao().insert(client(name = "x".repeat(120)).copy(phone = "+57 300 000 0000", note = "n".repeat(1000)))
    }

    @Test
    fun revisionRowValuesAreValidatedAndRevisionsAreImmutable() {
        val movement = newMovement().also(db.movementDao()::insert)

        assertConstraint(rule = "movimiento_revision.versiones") {
            db.movementRevisionDao().insert(revision(movement.id).copy(newVersion = 3))
        }
        assertConstraint(rule = "movimiento_revision.versiones") {
            db.movementRevisionDao().insert(revision(movement.id, previousVersion = 0))
        }
        assertConstraint(rule = "movimiento_revision.motivo") { db.movementRevisionDao().insert(revision(movement.id).copy(reason = "   ")) }
        assertConstraint(rule = "movimiento_revision.estado_anterior_json") {
            db.movementRevisionDao().insert(revision(movement.id).copy(previousStateJson = "texto"))
        }

        val stored = revision(movement.id).also(db.movementRevisionDao()::insert)
        assertConstraint(rule = "movimiento_revision.inmutable") {
            sql.execSQL("UPDATE movimiento_revision SET motivo = 'Otro motivo' WHERE id = ?", arrayOf(stored.id))
        }
        assertEquals(listOf(stored), db.movementRevisionDao().findByMovement(movement.id))
    }

    @Test
    fun backupRecordRowValuesAreValidated() {
        val record = backupRecord(metadata.ledgerId, metadata.localEpoch)
        assertConstraint(rule = "backup_registro.verificacion") {
            db.backupRecordDao().insert(record.copy(state = BackupState.VERIFICADO, verifiedAtMillis = null))
        }
        assertConstraint(rule = "backup_registro.verificacion") {
            db.backupRecordDao().insert(record.copy(state = BackupState.FALLIDO, verifiedAtMillis = T0))
        }
        assertConstraint(rule = "backup_registro.error_codigo") {
            db.backupRecordDao().insert(record.copy(state = BackupState.FALLIDO, errorCode = "Saldo 60000 de cliente"))
        }
        assertConstraint(rule = "backup_registro.cuenta_drive_huella") {
            db.backupRecordDao().insert(record.copy(driveAccountFingerprint = "huella"))
        }

        db.backupRecordDao().insert(record.copy(state = BackupState.FALLIDO, errorCode = "IO_ERROR_2"))
    }

    // --- Las mismas restricciones al actualizar ---

    @Test
    fun movementConstraintsAlsoApplyOnUpdate() {
        val movement = newMovement().also(db.movementDao()::insert)
        val otherClient = client(name = "Cliente Sintético B").also(db.clientDao()::insert)

        assertConstraint(rule = "movimiento.valor_cop") { db.movementDao().update(movement.copy(amountCop = 0)) }
        assertConstraint(rule = "movimiento.tipo_origen") { db.movementDao().update(movement.copy(origin = MovementOrigin.PAGO_TOTAL)) }
        assertConstraint(rule = "movimiento.version") { db.movementDao().update(movement.copy(version = 0)) }
        assertConstraint(rule = "movimiento.activo") {
            sql.execSQL("UPDATE movimiento SET activo = 5 WHERE id = ?", arrayOf(movement.id))
        }
        assertConstraint(rule = "movimiento.inmutables") { db.movementDao().update(movement.copy(clientId = otherClient.id)) }
        assertConstraint(rule = "movimiento.inmutables") { db.movementDao().update(movement.copy(type = MovementType.ABONO)) }
        assertConstraint(rule = "movimiento.inmutables") { db.movementDao().update(movement.copy(entryOrder = 500)) }
        assertConstraint(rule = "movimiento.inmutables") { db.movementDao().update(movement.copy(createdAtMillis = T0 + 1)) }
        assertEquals(movement, db.movementDao().findById(movement.id))

        // Una corrección válida de una fila sí se acepta.
        val corrected = movement.copy(amountCop = 50_000, version = 2, modifiedAtMillis = T0 + 1, active = false)
        assertEquals(1, db.movementDao().update(corrected))
        assertEquals(corrected, db.movementDao().findById(movement.id))
    }

    @Test
    fun clientMetadataAndBackupConstraintsAlsoApplyOnUpdate() {
        assertConstraint(rule = "cliente.nombre") { db.clientDao().update(owner.copy(name = "")) }
        assertConstraint(rule = "cliente.inmutables") { db.clientDao().update(owner.copy(createdAtMillis = T0 + 1)) }
        assertEquals(owner, db.clientDao().findById(owner.id))

        val metadataDao = db.ledgerMetadataDao()
        assertConstraint(rule = "libreta_metadata.siguiente_orden_registro") { metadataDao.update(metadata.copy(nextEntryOrder = 0)) }
        assertConstraint(rule = "libreta_metadata.revision_datos") { metadataDao.update(metadata.copy(dataRevision = -1)) }
        assertConstraint(rule = "libreta_metadata.libreta_id") { metadataDao.update(metadata.copy(ledgerId = "libreta")) }
        assertConstraint(rule = "libreta_metadata.restauracion") { metadataDao.update(metadata.copy(lastRestoreId = randomId())) }
        assertEquals(metadata, metadataDao.get())

        val record = backupRecord(metadata.ledgerId, metadata.localEpoch).also(db.backupRecordDao()::insert)
        assertConstraint(rule = "backup_registro.verificacion") { db.backupRecordDao().update(record.copy(state = BackupState.VERIFICADO)) }
        assertConstraint(rule = "backup_registro.inmutables") { db.backupRecordDao().update(record.copy(dataRevision = 5)) }
        assertConstraint(rule = "backup_registro.error_codigo") { db.backupRecordDao().update(record.copy(errorCode = "disk full")) }
        assertEquals(record, db.backupRecordDao().find(record.backupId, record.destination))

        val verified = record.copy(state = BackupState.VERIFICADO, verifiedAtMillis = T0 + 1, locator = "content://sintetico/1")
        assertEquals(1, db.backupRecordDao().update(verified))
        assertEquals(verified, db.backupRecordDao().find(record.backupId, record.destination))
    }
}
