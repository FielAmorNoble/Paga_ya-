package com.example.pagaya.data.backup

import android.database.Cursor
import com.example.pagaya.data.local.*
import com.example.pagaya.domain.ClientRules
import java.time.LocalDate

/** Mapea el único contrato ya parseado a Room; no contiene un lector JSON alternativo. */
internal object BackupDatabaseTransfer {
    fun import(data: ParsedData, target: PagaYaDatabase) = target.runInTransaction {
        data.clients.forEach { target.clientDao().insert(it.entity()) }
        data.movements.forEach { target.movementDao().insert(it.entity()) }
        data.revisions.forEach { target.movementRevisionDao().insert(it.entity()) }
        val local = target.ledgerMetadataDao().get()!!
        check(target.ledgerMetadataDao().update(local.copy(
            ledgerId = data.ledger.id, createdAtMillis = data.ledger.createdAtMillis,
            dataRevision = data.ledger.dataRevision, nextEntryOrder = data.ledger.nextEntryOrder,
        )) == 1)
    }

    /** Segundo control sobre lo efectivamente persistido. Se reutilizan las mismas reglas por cuenta;
     * FK/PK/orden único se comprueban globalmente en SQLite. No se cargan todas las entidades y otra
     * representación completa a la vez. La cuenta mayor aún puede requerir memoria proporcional.
     */
    fun inspect(db: PagaYaDatabase, manifest: BackupManifest, today: LocalDate?, validator: BackupValidator): BackupSummary =
        db.runInTransaction<BackupSummary> {
            db.query("PRAGMA integrity_check", emptyArray()).use {
                check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext())
            }
            db.query("PRAGMA foreign_key_check", emptyArray()).use { check(!it.moveToFirst()) }
            val count = db.backupRecordDao().measureLedger()
            check(count.clients == manifest.clients && count.movements == manifest.movements && count.revisions == manifest.revisions)
            val meta = db.ledgerMetadataDao().get()!!
            check(meta.ledgerId == manifest.ledgerId && meta.dataRevision == manifest.dataRevision)
            val ledger = LedgerRow(meta.ledgerId, meta.createdAtMillis, meta.dataRevision, meta.nextEntryOrder)
            var active = 0
            var archived = 0
            var annulled = 0
            var total = 0L
            // Valida también los metadatos de una libreta vacía.
            validator.checkRows(ParsedData(ledger, emptyList(), emptyList(), emptyList()), manifest, today)
            db.query("SELECT * FROM cliente", emptyArray()).use { clients ->
                while (clients.moveToNext()) {
                    val client = clients.clientRow()
                    check(clients.text("nombre_busqueda") == ClientRules.searchKey(client.name))
                    val movements = ArrayList<MovementRow>()
                    db.query("SELECT * FROM movimiento WHERE cliente_id = ?", arrayOf(client.id)).use {
                        while (it.moveToNext()) movements.add(it.movementRow())
                    }
                    val revisions = ArrayList<RevisionRow>()
                    db.query("SELECT r.* FROM movimiento_revision r JOIN movimiento m ON m.id = r.movimiento_id WHERE m.cliente_id = ?", arrayOf(client.id)).use {
                        while (it.moveToNext()) revisions.add(it.revisionRow())
                    }
                    val summary = validator.checkRows(ParsedData(ledger, listOf(client), movements, revisions), manifest, today)
                    active += summary.activeClients
                    archived += summary.archivedClients
                    annulled += summary.annulledMovements
                    total = Math.addExact(total, summary.totalPendingCop)
                }
            }
            BackupSummary(manifest, meta.createdAtMillis, meta.nextEntryOrder, active, archived, annulled, total)
        }

    /** Invocado dentro de la transacción de reemplazo; recorre cursores y escribe una fila cada vez. */
    fun copyRows(source: PagaYaDatabase, target: PagaYaDatabase, checkpoint: (RestoreCheckpoint) -> Unit) {
        source.query("SELECT * FROM cliente", emptyArray()).use { while (it.moveToNext()) target.clientDao().insert(it.clientRow().entity()) }
        checkpoint(RestoreCheckpoint.AFTER_CLIENTS)
        source.query("SELECT * FROM movimiento", emptyArray()).use { while (it.moveToNext()) target.movementDao().insert(it.movementRow().entity()) }
        checkpoint(RestoreCheckpoint.AFTER_MOVEMENTS)
        source.query("SELECT * FROM movimiento_revision", emptyArray()).use { while (it.moveToNext()) target.movementRevisionDao().insert(it.revisionRow().entity()) }
        checkpoint(RestoreCheckpoint.AFTER_REVISIONS)
    }

    private fun ClientRow.entity() = ClientEntity(id, name, ClientRules.searchKey(name), phone, note, createdAtMillis, modifiedAtMillis, active)
    private fun MovementRow.entity() = MovementEntity(id, clientId, LocalDate.parse(date), order, MovementType.valueOf(type), amountCop,
        description, MovementOrigin.valueOf(origin), createdAtMillis, modifiedAtMillis, active, version)
    private fun RevisionRow.entity() = MovementRevisionEntity(id, movementId, previousVersion, newVersion, RevisionAction.valueOf(action),
        previousStateJson, changedAtMillis, reason)

    private fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.number(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.optional(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.clientRow() = ClientRow(text("id"), text("nombre"), optional("telefono"), optional("nota"), number("creado_en"), number("modificado_en"), number("activo") == 1L)
    private fun Cursor.movementRow() = MovementRow(text("id"), text("cliente_id"), text("fecha"), number("orden_registro"), text("tipo"), number("valor_cop"), optional("descripcion"), text("origen"), number("creado_en"), number("modificado_en"), number("activo") == 1L, number("version"))
    private fun Cursor.revisionRow() = RevisionRow(text("id"), text("movimiento_id"), number("version_anterior"), number("version_nueva"), text("accion"), text("estado_anterior_json"), number("cambiado_en"), text("motivo"))
}
