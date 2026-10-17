package com.example.pagaya.data.backup

import com.example.pagaya.data.backup.BackupFormat.Manifest
import com.example.pagaya.data.local.ClientEntity
import com.example.pagaya.data.local.MovementEntity
import com.example.pagaya.data.local.MovementRevisionEntity
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Una revisión coherente de la libreta, leída en una sola transacción. Solo contiene datos portables:
 * ni saldos, ni `nombre_busqueda` en la salida, ni época local, ni registros de backup.
 */
data class LedgerCapture(
    val backupId: String,
    val capturedAtMillis: Long,
    val ledgerId: String,
    val ledgerCreatedAtMillis: Long,
    val dataRevision: Long,
    val nextEntryOrder: Long,
    val clients: List<ClientEntity>,
    val movements: List<MovementEntity>,
    val revisions: List<MovementRevisionEntity>,
)

/** Contenido de `manifest.json`. */
data class BackupManifest(
    val appVersion: String,
    val backupId: String,
    val ledgerId: String,
    val capturedAtMillis: Long,
    val dataRevision: Long,
    val clients: Long,
    val movements: Long,
    val revisions: Long,
    val dataBytes: Long,
    val dataSha256: String,
)

/** Serializa una [LedgerCapture] en `data.json` y el ZIP final, en archivos privados. */
object BackupWriter {

    /**
     * Escribe [dataFile] (UTF-8 sin BOM, JSON compacto, orden determinista) y después [archiveFile] con
     * `manifest.json` y `data.json`. El SHA-256 se calcula sobre los bytes exactos de `data.json` mientras
     * se escriben, no sobre un objeto reserializado.
     */
    fun write(capture: LedgerCapture, appVersion: String, dataFile: File, archiveFile: File): BackupManifest {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(BufferedOutputStream(FileOutputStream(dataFile)), digest).use { out ->
            val writer = BufferedWriter(OutputStreamWriter(
                out,
                StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT),
            ))
            writeData(capture, JsonOut(writer))
            writer.flush()
        }
        val manifest = BackupManifest(
            appVersion = appVersion,
            backupId = capture.backupId,
            ledgerId = capture.ledgerId,
            capturedAtMillis = capture.capturedAtMillis,
            dataRevision = capture.dataRevision,
            clients = capture.clients.size.toLong(),
            movements = capture.movements.size.toLong(),
            revisions = capture.revisions.size.toLong(),
            dataBytes = dataFile.length(),
            dataSha256 = digest.digest().toHex(),
        )
        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zip ->
            zip.setMethod(ZipOutputStream.DEFLATED)
            zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST_ENTRY).apply { time = capture.capturedAtMillis })
            zip.write(manifestJson(manifest).toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupFormat.DATA_ENTRY).apply { time = capture.capturedAtMillis })
            dataFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return manifest
    }

    fun manifestJson(m: BackupManifest): String {
        val text = StringBuilder()
        JsonOut(text).obj {
            str(Manifest.FORMAT, BackupFormat.FORMAT)
            num(Manifest.FORMAT_VERSION, BackupFormat.FORMAT_VERSION)
            num(Manifest.DATA_VERSION, BackupFormat.DATA_VERSION)
            str(Manifest.APP_VERSION, m.appVersion)
            str(Manifest.BACKUP_ID, m.backupId)
            str(Manifest.LEDGER_ID, m.ledgerId)
            num(Manifest.CAPTURED_AT, m.capturedAtMillis)
            str(Manifest.ZONE, BackupFormat.BUSINESS_ZONE)
            str(Manifest.CURRENCY, BackupFormat.CURRENCY)
            num(Manifest.DATA_REVISION, m.dataRevision)
            num(Manifest.CLIENTS, m.clients)
            num(Manifest.MOVEMENTS, m.movements)
            num(Manifest.REVISIONS, m.revisions)
            num(Manifest.DATA_BYTES, m.dataBytes)
            str(Manifest.DATA_SHA256, m.dataSha256)
        }
        return text.toString()
    }

    private fun writeData(c: LedgerCapture, json: JsonOut) = json.obj {
        key(BackupFormat.Data.LEDGER)
        obj {
            str("libreta_id", c.ledgerId)
            num("creado_en", c.ledgerCreatedAtMillis)
            num("revision_datos", c.dataRevision)
            num("siguiente_orden_registro", c.nextEntryOrder)
        }
        key(BackupFormat.Data.CLIENTS)
        array(c.clients.sortedBy { it.id }) { cl ->
            obj {
                str("id", cl.id)
                str("nombre", cl.name)
                strOrNull("telefono", cl.phone)
                strOrNull("nota", cl.note)
                num("creado_en", cl.createdAtMillis)
                num("modificado_en", cl.modifiedAtMillis)
                bool("activo", cl.active)
            }
        }
        key(BackupFormat.Data.MOVEMENTS)
        array(c.movements.sortedBy { it.entryOrder }) { m ->
            obj {
                str("id", m.id)
                str("cliente_id", m.clientId)
                str("fecha", m.date.toString())
                num("orden_registro", m.entryOrder)
                str("tipo", m.type.name)
                num("valor_cop", m.amountCop)
                strOrNull("descripcion", m.description)
                str("origen", m.origin.name)
                num("creado_en", m.createdAtMillis)
                num("modificado_en", m.modifiedAtMillis)
                bool("activo", m.active)
                num("version", m.version)
            }
        }
        key(BackupFormat.Data.REVISIONS)
        array(c.revisions.sortedWith(compareBy({ it.movementId }, { it.previousVersion }))) { r ->
            obj {
                str("id", r.id)
                str("movimiento_id", r.movementId)
                num("version_anterior", r.previousVersion)
                num("version_nueva", r.newVersion)
                str("accion", r.action.name)
                // El snapshot se exporta como el texto exacto guardado, no como un objeto reserializado.
                str("estado_anterior_json", r.previousStateJson)
                num("cambiado_en", r.changedAtMillis)
                str("motivo", r.reason)
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Escritor JSON compacto mínimo: objetos, listas, texto, enteros, booleanos y `null`. */
internal class JsonOut(private val out: Appendable) {
    private var needComma = false

    fun obj(body: JsonOut.() -> Unit) {
        comma()
        out.append('{')
        needComma = false
        body()
        out.append('}')
        needComma = true
    }

    fun <T> array(items: List<T>, item: JsonOut.(T) -> Unit) {
        comma()
        out.append('[')
        needComma = false
        items.forEach { item(it) }
        out.append(']')
        needComma = true
    }

    fun key(name: String) {
        comma()
        quote(name)
        out.append(':')
        needComma = false
    }

    fun str(name: String, value: String) {
        key(name)
        quote(value)
        needComma = true
    }

    fun strOrNull(name: String, value: String?) {
        key(name)
        if (value == null) out.append("null") else quote(value)
        needComma = true
    }

    fun num(name: String, value: Long) {
        key(name)
        out.append(value.toString())
        needComma = true
    }

    fun bool(name: String, value: Boolean) {
        key(name)
        out.append(if (value) "true" else "false")
        needComma = true
    }

    private fun comma() {
        if (needComma) out.append(',')
    }

    private fun quote(value: String) {
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
