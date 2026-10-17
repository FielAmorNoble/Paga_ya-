package com.example.pagaya.data.backup

import com.example.pagaya.data.local.ClientEntity
import com.example.pagaya.data.local.MovementEntity
import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementRevisionEntity
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.RevisionAction
import com.example.pagaya.domain.ClientRules
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.domain.MovementSnapshot
import com.example.pagaya.domain.SnapshotJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipInputStream

/**
 * Libreta sintética construida con las mismas transiciones que los repositorios (alta, corrección,
 * anulación, archivo). Ningún dato corresponde a personas reales.
 */
internal class LedgerBuilder(val ledgerId: String = uuid(9_000)) {
    private var nextId = 1
    var now = 1_791_000_000_000L
    var revision = 0L
    var nextOrder = 1L
    val clients = LinkedHashMap<String, ClientEntity>()
    val movements = LinkedHashMap<String, MovementEntity>()
    val revisions = ArrayList<MovementRevisionEntity>()

    private fun id() = uuid(nextId++)

    private fun tick(): Long {
        now += 60_000
        revision++
        return now
    }

    fun client(name: String, phone: String? = null, note: String? = null): String {
        val at = tick()
        val id = id()
        clients[id] = ClientEntity(id, name, ClientRules.searchKey(name), phone, note, at, at, active = true)
        return id
    }

    fun archive(clientId: String) {
        val at = tick()
        clients[clientId] = clients.getValue(clientId).copy(active = false, modifiedAtMillis = at)
    }

    fun fiado(clientId: String, amount: Long, date: LocalDate, description: String? = null) =
        movement(clientId, MovementType.FIADO, MovementOrigin.MANUAL, amount, date, description)

    fun abono(clientId: String, amount: Long, date: LocalDate, description: String? = null, origin: MovementOrigin = MovementOrigin.MANUAL) =
        movement(clientId, MovementType.ABONO, origin, amount, date, description)

    private fun movement(clientId: String, type: MovementType, origin: MovementOrigin, amount: Long, date: LocalDate, description: String?): String {
        val at = tick()
        val id = id()
        movements[id] = MovementEntity(id, clientId, date, nextOrder++, type, amount, description, origin, at, at, true, 1)
        return id
    }

    fun edit(movementId: String, amount: Long? = null, date: LocalDate? = null, description: String? = null, reason: String = "Corrección sintética") {
        val current = movements.getValue(movementId)
        val newAmount = amount ?: current.amountCop
        val next = current.copy(
            amountCop = newAmount,
            date = date ?: current.date,
            description = description ?: current.description,
            origin = MovementRules.originAfterCorrection(current.origin, current.amountCop, newAmount),
        )
        change(current, next, RevisionAction.EDITAR, reason)
    }

    fun annul(movementId: String, reason: String = "Anulación sintética") {
        val current = movements.getValue(movementId)
        change(current, current.copy(active = false), RevisionAction.ANULAR, reason)
    }

    private fun change(current: MovementEntity, next: MovementEntity, action: RevisionAction, reason: String) {
        val at = tick()
        revisions += MovementRevisionEntity(
            id = id(),
            movementId = current.id,
            previousVersion = current.version,
            newVersion = current.version + 1,
            action = action,
            previousStateJson = SnapshotJson.encode(current.toSnapshot()),
            changedAtMillis = at,
            reason = reason,
        )
        movements[current.id] = next.copy(modifiedAtMillis = at, version = current.version + 1)
    }

    fun capture(backupId: String = uuid(8_000)) = LedgerCapture(
        backupId = backupId,
        capturedAtMillis = now + 1,
        ledgerId = ledgerId,
        ledgerCreatedAtMillis = 1_790_000_000_000L,
        dataRevision = revision,
        nextEntryOrder = nextOrder,
        clients = clients.values.toList(),
        movements = movements.values.toList(),
        revisions = revisions.toList(),
    )

    companion object {
        fun uuid(n: Int): String = "%08x-0000-4000-8000-%012x".format(n, n)
    }
}

internal fun MovementEntity.toSnapshot() = MovementSnapshot(
    id, clientId, date, entryOrder, type, amountCop, description, origin, createdAtMillis, modifiedAtMillis, active, version,
)

/** Una libreta completa: activos, archivado saldado, anulados, corregidos varias veces y textos difíciles. */
internal fun richLedger(): LedgerBuilder = LedgerBuilder().apply {
    val oct1 = LocalDate.of(2026, 10, 1)
    val oct3 = LocalDate.of(2026, 10, 3)
    val maria = client("María José Ñúñez \"La Tienda\"", phone = "+57 300 123 4567", note = "Línea 1\nLínea 2 \\ barra 😀")
    val pedro = client("Pedro Sintético")
    val big = fiado(maria, MovementRules.AMOUNT_MAX, oct1, "Máximo permitido")
    fiado(maria, 1, oct1)
    val abono = abono(maria, 15_000, oct3, "Transferencia «sin» datos reales\t✓")
    edit(abono, amount = 20_000)
    edit(abono, date = LocalDate.of(2026, 10, 4), description = "Corregido dos veces")
    edit(abono, amount = 25_000, reason = "Tercera corrección")
    val wrong = fiado(maria, 7_777, oct3)
    annul(wrong)
    fiado(pedro, 10_000, oct1)
    abono(pedro, 10_000, oct3, origin = MovementOrigin.PAGO_TOTAL)
    archive(pedro)
    client("Cliente Sin Movimientos")
    check(movements.getValue(big).amountCop == MovementRules.AMOUNT_MAX)
}

/** Entrada de un ZIP construido byte a byte, para casos que ZipOutputStream no permite generar. */
internal class RawEntry(
    val name: String,
    val data: ByteArray,
    val deflate: Boolean = false,
    val flags: Int = 0,
    val madeBy: Int = 20,
    val externalAttributes: Long = 0,
    /** Tamaño sin comprimir declarado; por defecto el real. */
    val declaredSize: Long? = null,
)

internal fun rawZip(entries: List<RawEntry>): ByteArray {
    val out = ByteArrayOutputStream()
    val central = ByteArrayOutputStream()
    for (entry in entries) {
        val offset = out.size().toLong()
        val crc = CRC32().apply { update(entry.data) }.value
        val payload = if (entry.deflate) deflate(entry.data) else entry.data
        val name = entry.name.toByteArray(Charsets.UTF_8)
        val size = entry.declaredSize ?: entry.data.size.toLong()
        val method = if (entry.deflate) 8 else 0
        out.write(le(4, 0x04034b50L) + le(2, 20) + le(2, entry.flags.toLong()) + le(2, method.toLong()) + le(2, 0) + le(2, 0x21) +
            le(4, crc) + le(4, payload.size.toLong()) + le(4, size) + le(2, name.size.toLong()) + le(2, 0))
        out.write(name)
        out.write(payload)
        central.write(le(4, 0x02014b50L) + le(2, entry.madeBy.toLong()) + le(2, 20) + le(2, entry.flags.toLong()) +
            le(2, method.toLong()) + le(2, 0) + le(2, 0x21) + le(4, crc) + le(4, payload.size.toLong()) + le(4, size) +
            le(2, name.size.toLong()) + le(2, 0) + le(2, 0) + le(2, 0) + le(2, 0) + le(4, entry.externalAttributes) + le(4, offset))
        central.write(name)
    }
    val cdOffset = out.size().toLong()
    out.write(central.toByteArray())
    out.write(le(4, 0x06054b50L) + le(2, 0) + le(2, 0) + le(2, entries.size.toLong()) + le(2, entries.size.toLong()) +
        le(4, central.size().toLong()) + le(4, cdOffset) + le(2, 0))
    return out.toByteArray()
}

private fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    deflater.setInput(data)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
    deflater.end()
    return out.toByteArray()
}

private fun le(size: Int, value: Long): ByteArray {
    val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
    return buffer.array().copyOf(size)
}

/** Las dos entradas de un archivo generado por [BackupWriter], como texto. */
internal class ArchiveParts(val manifest: String, val data: String)

internal fun readParts(archive: File): ArchiveParts {
    val entries = LinkedHashMap<String, String>()
    ZipInputStream(archive.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
        }
    }
    return ArchiveParts(entries.getValue(BackupFormat.MANIFEST_ENTRY), entries.getValue(BackupFormat.DATA_ENTRY))
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Sustituye un valor del manifiesto (texto JSON compacto) por [rawValue]. */
internal fun String.withManifestValue(key: String, rawValue: String): String {
    val pattern = Regex("\"$key\":(\"[^\"]*\"|-?[0-9]+)")
    check(pattern.containsMatchIn(this)) { "No está la clave $key" }
    return replace(pattern, Regex.escapeReplacement("\"$key\":$rawValue"))
}
