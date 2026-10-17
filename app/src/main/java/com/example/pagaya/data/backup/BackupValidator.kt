package com.example.pagaya.data.backup

import com.example.pagaya.data.backup.BackupFormat.Manifest
import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.RevisionAction
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.ClientRules
import com.example.pagaya.domain.ClientValidation
import com.example.pagaya.domain.HistoryCheck
import com.example.pagaya.domain.LedgerEntry
import com.example.pagaya.domain.LedgerRules
import com.example.pagaya.domain.MovementChangeLog
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.domain.MovementSnapshot
import com.example.pagaya.domain.RevisionRecord
import com.example.pagaya.domain.SnapshotJson
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Lo que contiene un respaldo válido, para comparar con la captura o, más adelante, para la vista previa. */
data class BackupSummary(
    val manifest: BackupManifest,
    val ledgerCreatedAtMillis: Long,
    val nextEntryOrder: Long,
    val activeClients: Int,
    val archivedClients: Int,
    val annulledMovements: Int,
    /** Suma de los saldos recalculados; no se lee del archivo. */
    val totalPendingCop: Long,
)

sealed interface BackupValidation {
    data class Valid(val summary: BackupSummary) : BackupValidation

    data class Invalid(val problem: BackupProblem, val detail: String) : BackupValidation
}

/**
 * Validador único del archivo de respaldo, para la copia recién creada, el archivo exportado releído y,
 * en la siguiente iteración, la restauración. Solo lee el archivo: nunca toca la base operativa, no
 * extrae entradas al sistema de archivos y no carga el ZIP completo en memoria.
 *
 * Comprueba en este orden: contenedor (directorio central y entradas locales), manifiesto y versiones,
 * `data.json` por streaming con límites reales, longitud y SHA-256, conteos e identidad, y por último las
 * reglas de la libreta reutilizando el dominio (ClientRules, MovementRules, SnapshotJson,
 * MovementChangeLog y LedgerRules).
 */
class BackupValidator(private val limits: BackupLimits = BackupLimits()) {

    /**
     * @param today si se indica, las fechas posteriores son un error ([BackupProblem.FUTURE_DATE]). La
     * exportación no lo pasa: un reloj atrasado no debe impedir guardar la copia.
     * @throws java.io.IOException si el archivo no se puede leer (distinto de un archivo inválido).
     */
    fun validate(file: File, today: LocalDate? = null): BackupValidation = try {
        BackupValidation.Valid(check(file, today))
    } catch (e: BackupInvalidException) {
        BackupValidation.Invalid(e.problem, e.detail)
    }

    private fun check(file: File, today: LocalDate?): BackupSummary {
        val size = file.length()
        if (size > limits.maxArchiveBytes) fail(BackupProblem.LIMIT_EXCEEDED, "archivo de $size bytes")
        val (manifestEntry, dataEntry) = checkContainer(file, size)

        val parsed: ParsedData
        val manifest: BackupManifest
        val dataBytes: Long
        val dataSha: String
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
                val first = zip.nextEntry ?: fail(BackupProblem.CONTAINER, "ZIP sin entradas legibles")
                if (first.name != BackupFormat.MANIFEST_ENTRY) fail(BackupProblem.ENTRIES, "primera entrada inesperada")
                val manifestStream = EntryStream(zip, limits.maxManifestBytes, BackupFormat.MANIFEST_ENTRY)
                manifest = readManifest(manifestStream)
                manifestStream.finish(manifestEntry, first)

                val second = zip.nextEntry ?: fail(BackupProblem.ENTRIES, "falta ${BackupFormat.DATA_ENTRY}")
                if (second.name != BackupFormat.DATA_ENTRY) fail(BackupProblem.ENTRIES, "segunda entrada inesperada")
                if (manifest.dataBytes > limits.maxDataBytes) {
                    fail(BackupProblem.LIMIT_EXCEEDED, "bytes_datos declara ${manifest.dataBytes}")
                }
                val dataStream = EntryStream(zip, limits.maxDataBytes, BackupFormat.DATA_ENTRY)
                val digest = MessageDigest.getInstance("SHA-256")
                val hashed = DigestInputStream(dataStream, digest)
                parsed = DataParser(StrictJsonReader(hashed, limits.maxJsonDepth), limits).parse()
                // El lector ya llegó al final; se drena por si quedara algo que no pasó por él.
                val rest = ByteArray(8192)
                while (hashed.read(rest) >= 0) Unit
                dataStream.finish(dataEntry, second)
                dataBytes = dataStream.count
                dataSha = digest.digest().joinToString("") { "%02x".format(it) }

                if (zip.nextEntry != null) fail(BackupProblem.ENTRIES, "entradas adicionales")
            }
        } catch (e: ZipException) {
            fail(BackupProblem.CONTAINER, "ZIP dañado: ${e.javaClass.simpleName}")
        } catch (e: EOFException) {
            fail(BackupProblem.CONTAINER, "ZIP truncado")
        }

        if (dataBytes != manifest.dataBytes) fail(BackupProblem.SIZE_MISMATCH, "data.json mide $dataBytes, el manifiesto dice ${manifest.dataBytes}")
        if (dataSha != manifest.dataSha256) fail(BackupProblem.CHECKSUM_MISMATCH, "SHA-256 de data.json distinto")
        if (parsed.clients.size.toLong() != manifest.clients ||
            parsed.movements.size.toLong() != manifest.movements ||
            parsed.revisions.size.toLong() != manifest.revisions
        ) {
            fail(BackupProblem.COUNT_MISMATCH, "conteos reales ${parsed.clients.size}/${parsed.movements.size}/${parsed.revisions.size}")
        }
        if (parsed.ledger.id != manifest.ledgerId) fail(BackupProblem.IDENTITY_MISMATCH, "libreta_id distinto")
        if (parsed.ledger.dataRevision != manifest.dataRevision) fail(BackupProblem.IDENTITY_MISMATCH, "revision_datos distinto")

        return LedgerChecker(parsed, today).check(manifest)
    }

    // ---- contenedor ------------------------------------------------------------------------

    /** Lo que el directorio central dice de una entrada. */
    private class CentralEntry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localOffset: Long,
    )

    /**
     * Entrada leída por [ZipInputStream] con límite real de bytes, contando los bytes descomprimidos y su
     * CRC-32. [finish] exige que coincidan con el directorio central: lo que se lee es lo que se declaró.
     */
    private class EntryStream(input: InputStream, max: Long, private val what: String) : InputStream() {
        private val limited = LimitedInputStream(input, max, what)
        private val crc = CRC32()
        val count: Long get() = limited.count

        override fun read(): Int = limited.read().also { if (it >= 0) crc.update(it) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            limited.read(b, off, len).also { if (it > 0) crc.update(b, off, it) }

        fun finish(central: CentralEntry, local: ZipEntry) {
            val rest = ByteArray(8192)
            while (read(rest) >= 0) Unit
            // Tras EOF, ZipInputStream publica los tamaños del flujo DEFLATE realmente consumido.
            // El cruce estático no basta: un descriptor duplicado podría ocultar relleno comprimido.
            if (limited.count != central.size || crc.value != central.crc ||
                local.compressedSize != central.compressedSize || local.size != central.size || local.crc != central.crc
            ) {
                fail(BackupProblem.CONTAINER, "$what no coincide con el directorio central")
            }
        }
    }

    /**
     * Estructura completa del ZIP, sin confiar en ninguna representación por separado:
     * 1. Directorio central: exactamente dos entradas con los nombres permitidos y en orden; sin cifrado,
     *    ZIP64, enlaces, carpetas ni métodos distintos de STORED/DEFLATED.
     * 2. Encabezado local de cada entrada en la posición que declara el directorio central: mismo nombre,
     *    flags y método; CRC y tamaños iguales, o a cero si usa descriptor de datos (bit 3, como escribe
     *    ZipOutputStream con DEFLATE). En ese caso el descriptor, con o sin firma, debe repetirlos.
     * 3. Contigüidad: la primera entrada empieza en 0, cada una termina donde empieza la siguiente y la
     *    última donde empieza el directorio central. No caben bytes ni entradas ocultas entre ellas.
     * Después, la lectura secuencial compara nombres, CRC y tamaños reales con estas entradas.
     */
    private fun checkContainer(file: File, size: Long): Pair<CentralEntry, CentralEntry> {
        if (size < EOCD_SIZE) fail(BackupProblem.CONTAINER, "demasiado corto para ser un ZIP")
        RandomAccessFile(file, "r").use { raf ->
            val tailSize = minOf(size, (EOCD_SIZE + 0xFFFF).toLong()).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(size - tailSize)
            raf.readFully(tail)
            var eocd = -1
            for (i in tailSize - EOCD_SIZE downTo 0) {
                if (tail.u32(i) == EOCD_SIG && tail.u16(i + 20) == tailSize - i - EOCD_SIZE) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) fail(BackupProblem.CONTAINER, "sin directorio central")
            val eocdPosition = size - tailSize + eocd
            if (tail.u16(eocd + 4) != 0 || tail.u16(eocd + 6) != 0) fail(BackupProblem.CONTAINER, "ZIP multivolumen")
            val onDisk = tail.u16(eocd + 8)
            val total = tail.u16(eocd + 10)
            val cdSize = tail.u32(eocd + 12)
            val cdOffset = tail.u32(eocd + 16)
            if (onDisk == 0xFFFF || total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                fail(BackupProblem.CONTAINER, "ZIP64 no soportado")
            }
            if (onDisk != total) fail(BackupProblem.CONTAINER, "directorio central inconsistente")
            if (total != 2) fail(BackupProblem.ENTRIES, "$total entradas")
            if (cdOffset + cdSize != eocdPosition || cdSize > MAX_CENTRAL_DIRECTORY) {
                fail(BackupProblem.CONTAINER, "directorio central fuera de lugar")
            }
            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(cd)

            val entries = ArrayList<CentralEntry>()
            var p = 0
            repeat(total) {
                if (p + CEN_SIZE > cd.size || cd.u32(p) != CEN_SIG) fail(BackupProblem.CONTAINER, "entrada central inválida")
                val madeBy = cd.u16(p + 4)
                val flags = cd.u16(p + 8)
                val method = cd.u16(p + 10)
                val crc = cd.u32(p + 16)
                val compressed = cd.u32(p + 20)
                val uncompressed = cd.u32(p + 24)
                val nameLength = cd.u16(p + 28)
                val extraLength = cd.u16(p + 30)
                val commentLength = cd.u16(p + 32)
                val external = cd.u32(p + 38)
                val localOffset = cd.u32(p + 42)
                if (flags and (FLAG_ENCRYPTED or FLAG_STRONG_ENCRYPTION or FLAG_MASKED_HEADERS) != 0) {
                    fail(BackupProblem.CONTAINER, "entrada cifrada")
                }
                if (method != METHOD_STORED && method != METHOD_DEFLATED) fail(BackupProblem.CONTAINER, "método de compresión $method")
                if (compressed == 0xFFFFFFFFL || uncompressed == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                    fail(BackupProblem.CONTAINER, "ZIP64 no soportado")
                }
                val end = p + CEN_SIZE + nameLength + extraLength + commentLength
                if (end > cd.size) fail(BackupProblem.CONTAINER, "entrada central truncada")
                val nameBytes = cd.copyOfRange(p + CEN_SIZE, p + CEN_SIZE + nameLength)
                checkExtraFields(cd, p + CEN_SIZE + nameLength, extraLength)
                // Solo los dos nombres exactos: cualquier ruta, carpeta o "../" queda fuera.
                val name = String(nameBytes, Charsets.US_ASCII)
                if (nameBytes.any { it < 0x20 || it > 0x7e } || name !in ALLOWED_ENTRIES) {
                    fail(BackupProblem.ENTRIES, "entrada no permitida")
                }
                if (entries.any { it.name == name }) fail(BackupProblem.ENTRIES, "entrada repetida $name")
                val unixMode = (external ushr 16) and 0xF000L
                if ((madeBy ushr 8) == HOST_UNIX && unixMode != 0L && unixMode != UNIX_REGULAR_FILE) {
                    fail(BackupProblem.ENTRIES, "entrada que no es un archivo normal")
                }
                if (external and DOS_DIRECTORY != 0L) fail(BackupProblem.ENTRIES, "entrada de carpeta")
                if (method == METHOD_STORED && compressed != uncompressed) fail(BackupProblem.CONTAINER, "$name: tamaños STORED distintos")
                entries.add(CentralEntry(name, flags, method, crc, compressed, uncompressed, localOffset))
                p = end
            }
            if (p != cd.size) fail(BackupProblem.CONTAINER, "directorio central con datos sobrantes")
            if (entries.map { it.name } != ALLOWED_ENTRIES) fail(BackupProblem.ENTRIES, "orden de entradas inesperado")

            var expectedOffset = 0L
            for (entry in entries) {
                if (entry.localOffset != expectedOffset) fail(BackupProblem.CONTAINER, "${entry.name}: posición local inesperada")
                expectedOffset = checkLocalEntry(raf, entry, cdOffset)
            }
            if (expectedOffset != cdOffset) fail(BackupProblem.CONTAINER, "bytes entre las entradas y el directorio central")
            return entries[0] to entries[1]
        }
    }

    /** Comprueba el encabezado local (y el descriptor) de [entry]; devuelve dónde termina la entrada. */
    private fun checkLocalEntry(raf: RandomAccessFile, entry: CentralEntry, cdOffset: Long): Long {
        val where = entry.name
        if (entry.localOffset + LOC_SIZE > cdOffset) fail(BackupProblem.CONTAINER, "$where: encabezado local fuera de lugar")
        val header = ByteArray(LOC_SIZE)
        raf.seek(entry.localOffset)
        raf.readFully(header)
        if (header.u32(0) != LOC_SIG) fail(BackupProblem.CONTAINER, "$where: sin encabezado local")
        val flags = header.u16(6)
        val method = header.u16(8)
        val crc = header.u32(14)
        val compressed = header.u32(18)
        val uncompressed = header.u32(22)
        val nameLength = header.u16(26)
        val extraLength = header.u16(28)
        if (flags != entry.flags) fail(BackupProblem.CONTAINER, "$where: flags distintos entre encabezados")
        if (method != entry.method) fail(BackupProblem.CONTAINER, "$where: método distinto entre encabezados")
        val dataStart = entry.localOffset + LOC_SIZE + nameLength + extraLength
        if (dataStart > cdOffset) fail(BackupProblem.CONTAINER, "$where: encabezado local truncado")
        val name = ByteArray(nameLength)
        raf.readFully(name)
        if (name.any { it < 0x20 || it > 0x7e } || String(name, Charsets.US_ASCII) != entry.name) {
            fail(BackupProblem.CONTAINER, "$where: nombre distinto entre encabezados")
        }
        val extra = ByteArray(extraLength)
        raf.readFully(extra)
        checkExtraFields(extra, 0, extra.size)
        val dataEnd = dataStart + entry.compressedSize
        if (dataEnd > cdOffset) fail(BackupProblem.CONTAINER, "$where: datos fuera de lugar")

        if (flags and FLAG_DATA_DESCRIPTOR == 0) {
            if (crc != entry.crc || compressed != entry.compressedSize || uncompressed != entry.size) {
                fail(BackupProblem.CONTAINER, "$where: CRC o tamaños distintos entre encabezados")
            }
            return dataEnd
        }
        // Con descriptor, el encabezado local lleva ceros (ZipOutputStream) o los valores reales.
        if ((crc != 0L || compressed != 0L || uncompressed != 0L) &&
            (crc != entry.crc || compressed != entry.compressedSize || uncompressed != entry.size)
        ) {
            fail(BackupProblem.CONTAINER, "$where: CRC o tamaños distintos entre encabezados")
        }
        if (dataEnd + 12 > cdOffset) fail(BackupProblem.CONTAINER, "$where: falta el descriptor de datos")
        val descriptor = ByteArray(16)
        raf.seek(dataEnd)
        raf.readFully(descriptor, 0, minOf(16L, cdOffset - dataEnd).toInt())
        val signed = descriptor.u32(0) == DESCRIPTOR_SIG && dataEnd + 16 <= cdOffset
        val base = if (signed) 4 else 0
        if (descriptor.u32(base) != entry.crc || descriptor.u32(base + 4) != entry.compressedSize ||
            descriptor.u32(base + 8) != entry.size
        ) {
            fail(BackupProblem.CONTAINER, "$where: descriptor de datos distinto del directorio central")
        }
        return dataEnd + base + 12
    }

    /** Los campos extra también pueden declarar ZIP64 aun sin tamaños centinela de 32 bits. */
    private fun checkExtraFields(bytes: ByteArray, start: Int, length: Int) {
        var p = start
        val end = start + length
        while (p < end) {
            if (p + 4 > end) fail(BackupProblem.CONTAINER, "campo extra ZIP truncado")
            val tag = bytes.u16(p)
            val size = bytes.u16(p + 2)
            if (tag == 0x0001) fail(BackupProblem.CONTAINER, "ZIP64 no soportado")
            p += 4 + size
            if (p > end) fail(BackupProblem.CONTAINER, "campo extra ZIP fuera de lugar")
        }
    }

    // ---- manifiesto ------------------------------------------------------------------------

    private fun readManifest(input: InputStream): BackupManifest {
        val reader = StrictJsonReader(input, limits.maxJsonDepth, fieldStringLimits = mapOf(
            Manifest.FORMAT to BackupFormat.FORMAT.length,
            Manifest.APP_VERSION to BackupFormat.VERSION_APP_MAX,
            Manifest.BACKUP_ID to BackupFormat.ReadLimits.ID,
            Manifest.LEDGER_ID to BackupFormat.ReadLimits.ID,
            Manifest.ZONE to BackupFormat.BUSINESS_ZONE.length,
            Manifest.CURRENCY to BackupFormat.CURRENCY.length,
            Manifest.DATA_SHA256 to 64,
        ))
        val root = reader.nextAny() as? Map<*, *> ?: fail(BackupProblem.TYPE, "el manifiesto no es un objeto")
        reader.endDocument()

        // Primero formato y versiones: un formato futuro se reconoce aunque tenga otras claves.
        if (root[Manifest.FORMAT] != BackupFormat.FORMAT) fail(BackupProblem.INCOMPATIBLE, "no es un respaldo de Paga Ya!")
        for (key in listOf(Manifest.FORMAT_VERSION, Manifest.DATA_VERSION)) {
            val version = (root[key] as? JsonNumber)?.text?.toLongStrict()
                ?: fail(BackupProblem.TYPE, "«$key» debe ser un entero")
            if (version > 1) fail(BackupProblem.UNSUPPORTED_VERSION, "$key = $version")
            if (version < 1) fail(BackupProblem.TYPE, "$key = $version")
        }
        val unknown = root.keys - Manifest.ALL.toSet()
        if (unknown.isNotEmpty()) fail(BackupProblem.TYPE, "claves desconocidas en el manifiesto: $unknown")
        val missing = Manifest.ALL - root.keys
        if (missing.isNotEmpty()) fail(BackupProblem.TYPE, "faltan claves en el manifiesto: $missing")

        fun text(key: String) = root[key] as? String ?: fail(BackupProblem.TYPE, "«$key» debe ser texto")
        fun long(key: String, min: Long = 0) = ((root[key] as? JsonNumber)?.text?.toLongStrict()
            ?: fail(BackupProblem.TYPE, "«$key» debe ser un entero")).also {
            if (it < min) fail(BackupProblem.TYPE, "«$key» fuera de rango")
        }

        if (text(Manifest.ZONE) != BackupFormat.BUSINESS_ZONE) fail(BackupProblem.INCOMPATIBLE, "zona de negocio distinta")
        if (text(Manifest.CURRENCY) != BackupFormat.CURRENCY) fail(BackupProblem.INCOMPATIBLE, "moneda distinta")
        val appVersion = text(Manifest.APP_VERSION)
        if (appVersion.isBlank() || appVersion.codePointCount(0, appVersion.length) > BackupFormat.VERSION_APP_MAX) fail(BackupProblem.TYPE, "version_app inválida")
        val backupId = text(Manifest.BACKUP_ID)
        val ledgerId = text(Manifest.LEDGER_ID)
        if (!BackupFormat.isUuid(backupId) || !BackupFormat.isUuid(ledgerId)) fail(BackupProblem.TYPE, "identificador inválido")
        val sha = text(Manifest.DATA_SHA256)
        if (!SHA256_HEX.matches(sha)) fail(BackupProblem.TYPE, "sha256_datos inválido")

        val manifest = BackupManifest(
            appVersion = appVersion,
            backupId = backupId,
            ledgerId = ledgerId,
            capturedAtMillis = long(Manifest.CAPTURED_AT),
            dataRevision = long(Manifest.DATA_REVISION),
            clients = long(Manifest.CLIENTS),
            movements = long(Manifest.MOVEMENTS),
            revisions = long(Manifest.REVISIONS),
            dataBytes = long(Manifest.DATA_BYTES),
            dataSha256 = sha,
        )
        if (manifest.clients > limits.maxClients || manifest.movements > limits.maxMovements || manifest.revisions > limits.maxRevisions) {
            fail(BackupProblem.LIMIT_EXCEEDED, "el manifiesto declara más filas que el límite")
        }
        return manifest
    }

    private fun String.toLongStrict(): Long? = if (INTEGER.matches(this)) toLongOrNull() else null

    private companion object {
        const val EOCD_SIG = 0x06054b50L
        const val CEN_SIG = 0x02014b50L
        const val LOC_SIG = 0x04034b50L
        const val DESCRIPTOR_SIG = 0x08074b50L
        const val LOC_SIZE = 30
        const val FLAG_DATA_DESCRIPTOR = 0x0008
        const val EOCD_SIZE = 22
        const val CEN_SIZE = 46
        const val MAX_CENTRAL_DIRECTORY = 64L * 1024
        const val FLAG_ENCRYPTED = 0x0001
        const val FLAG_STRONG_ENCRYPTION = 0x0040
        const val FLAG_MASKED_HEADERS = 0x2000
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
        const val HOST_UNIX = 3
        const val UNIX_REGULAR_FILE = 0x8000L
        const val DOS_DIRECTORY = 0x10L
        val ALLOWED_ENTRIES = listOf(BackupFormat.MANIFEST_ENTRY, BackupFormat.DATA_ENTRY)
        val SHA256_HEX = Regex("[0-9a-f]{64}")
        val INTEGER = Regex("-?(0|[1-9][0-9]*)")

        fun ByteArray.u16(i: Int): Int = (this[i].toInt() and 0xff) or ((this[i + 1].toInt() and 0xff) shl 8)

        fun ByteArray.u32(i: Int): Long = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
    }
}

private fun fail(problem: BackupProblem, detail: String): Nothing = throw BackupInvalidException(problem, detail)

// ---- data.json -------------------------------------------------------------------------------

internal class LedgerRow(val id: String, val createdAtMillis: Long, val dataRevision: Long, val nextEntryOrder: Long)

internal class ClientRow(
    val id: String,
    val name: String,
    val phone: String?,
    val note: String?,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
    val active: Boolean,
)

internal class MovementRow(
    val id: String,
    val clientId: String,
    val date: String,
    val order: Long,
    val type: String,
    val amountCop: Long,
    val description: String?,
    val origin: String,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
    val active: Boolean,
    val version: Long,
)

internal class RevisionRow(
    val id: String,
    val movementId: String,
    val previousVersion: Long,
    val newVersion: Long,
    val action: String,
    val previousStateJson: String,
    val changedAtMillis: Long,
    val reason: String,
)

internal class ParsedData(
    val ledger: LedgerRow,
    val clients: List<ClientRow>,
    val movements: List<MovementRow>,
    val revisions: List<RevisionRow>,
)

/** Lee `data.json` con tipos exactos y el conjunto exacto de claves de cada objeto. */
private class DataParser(private val json: StrictJsonReader, private val limits: BackupLimits) {

    fun parse(): ParsedData {
        var ledger: LedgerRow? = null
        var clients: List<ClientRow>? = null
        var movements: List<MovementRow>? = null
        var revisions: List<RevisionRow>? = null
        json.beginObject()
        while (json.hasNext()) {
            when (val key = json.nextName()) {
                BackupFormat.Data.LEDGER -> ledger = readLedger()
                BackupFormat.Data.CLIENTS -> clients = readList(limits.maxClients, "clientes", ::readClient)
                BackupFormat.Data.MOVEMENTS -> movements = readList(limits.maxMovements, "movimientos", ::readMovement)
                BackupFormat.Data.REVISIONS -> revisions = readList(limits.maxRevisions, "revisiones", ::readRevision)
                else -> fail(BackupProblem.TYPE, "clave desconocida «$key» en data.json")
            }
        }
        json.endObject()
        json.endDocument()
        return ParsedData(
            ledger ?: missing(BackupFormat.Data.LEDGER),
            clients ?: missing(BackupFormat.Data.CLIENTS),
            movements ?: missing(BackupFormat.Data.MOVEMENTS),
            revisions ?: missing(BackupFormat.Data.REVISIONS),
        )
    }

    private fun missing(key: String): Nothing = fail(BackupProblem.TYPE, "falta «$key» en data.json")

    // Los textos se limitan durante la lectura con el máximo del contrato: uno más largo es inválido igual
    // que lo sería tras leerlo (INTEGRITY), pero se rechaza sin construirlo.
    private fun id(what: String) = json.nextString(BackupFormat.ReadLimits.ID, BackupProblem.INTEGRITY, what)

    private fun text(max: Int, what: String) = json.nextString(max, BackupProblem.INTEGRITY, what)

    private fun optional(max: Int, what: String) = json.nextStringOrNull(max, BackupProblem.INTEGRITY, what)

    private fun <T> readList(max: Int, what: String, item: () -> T): List<T> {
        val result = ArrayList<T>()
        json.beginArray()
        while (json.hasNext()) {
            if (result.size >= max) fail(BackupProblem.LIMIT_EXCEEDED, "más de $max $what")
            result.add(item())
        }
        json.endArray()
        return result
    }

    /** Lee un objeto plano: cada clave de [keys] exactamente una vez y ninguna otra. */
    private inline fun row(what: String, keys: List<String>, read: (String) -> Unit) {
        val seen = HashSet<String>()
        json.beginObject()
        while (json.hasNext()) {
            val key = json.nextName()
            if (key !in keys) fail(BackupProblem.TYPE, "clave desconocida «$key» en $what")
            seen.add(key)
            read(key)
        }
        json.endObject()
        if (seen.size != keys.size) fail(BackupProblem.TYPE, "faltan ${keys - seen} en $what")
    }

    private fun readLedger(): LedgerRow {
        var id = ""
        var created = 0L
        var revision = 0L
        var next = 0L
        row("libreta", BackupFormat.Data.LEDGER_KEYS) { key ->
            when (key) {
                "libreta_id" -> id = id("libreta_id")
                "creado_en" -> created = json.nextLong()
                "revision_datos" -> revision = json.nextLong()
                "siguiente_orden_registro" -> next = json.nextLong()
            }
        }
        return LedgerRow(id, created, revision, next)
    }

    private fun readClient(): ClientRow {
        var id = ""
        var name = ""
        var phone: String? = null
        var note: String? = null
        var created = 0L
        var modified = 0L
        var active = false
        row("cliente", BackupFormat.Data.CLIENT_KEYS) { key ->
            when (key) {
                "id" -> id = id("id")
                "nombre" -> name = text(ClientRules.NAME_MAX, "nombre")
                "telefono" -> phone = optional(ClientRules.PHONE_MAX, "telefono")
                "nota" -> note = optional(ClientRules.NOTE_MAX, "nota")
                "creado_en" -> created = json.nextLong()
                "modificado_en" -> modified = json.nextLong()
                "activo" -> active = json.nextBoolean()
            }
        }
        return ClientRow(id, name, phone, note, created, modified, active)
    }

    private fun readMovement(): MovementRow {
        var id = ""
        var clientId = ""
        var date = ""
        var order = 0L
        var type = ""
        var amount = 0L
        var description: String? = null
        var origin = ""
        var created = 0L
        var modified = 0L
        var active = false
        var version = 0L
        row("movimiento", BackupFormat.Data.MOVEMENT_KEYS) { key ->
            when (key) {
                "id" -> id = id("id")
                "cliente_id" -> clientId = id("cliente_id")
                "fecha" -> date = text(BackupFormat.ReadLimits.DATE, "fecha")
                "orden_registro" -> order = json.nextLong()
                "tipo" -> type = text(BackupFormat.ReadLimits.CODE, "tipo")
                "valor_cop" -> amount = json.nextLong()
                "descripcion" -> description = optional(MovementRules.DESCRIPTION_MAX, "descripcion")
                "origen" -> origin = text(BackupFormat.ReadLimits.CODE, "origen")
                "creado_en" -> created = json.nextLong()
                "modificado_en" -> modified = json.nextLong()
                "activo" -> active = json.nextBoolean()
                "version" -> version = json.nextLong()
            }
        }
        return MovementRow(id, clientId, date, order, type, amount, description, origin, created, modified, active, version)
    }

    private fun readRevision(): RevisionRow {
        var id = ""
        var movementId = ""
        var previous = 0L
        var next = 0L
        var action = ""
        var state = ""
        var changed = 0L
        var reason = ""
        row("revision", BackupFormat.Data.REVISION_KEYS) { key ->
            when (key) {
                "id" -> id = id("id")
                "movimiento_id" -> movementId = id("movimiento_id")
                "version_anterior" -> previous = json.nextLong()
                "version_nueva" -> next = json.nextLong()
                "accion" -> action = text(BackupFormat.ReadLimits.CODE, "accion")
                "estado_anterior_json" -> state = json.nextString(BackupFormat.ReadLimits.SNAPSHOT, BackupProblem.REVISION_CHAIN, "estado_anterior_json")
                "cambiado_en" -> changed = json.nextLong()
                "motivo" -> reason = text(MovementRules.REASON_MAX, "motivo")
            }
        }
        return RevisionRow(id, movementId, previous, next, action, state, changed, reason)
    }
}

// ---- reglas de la libreta -------------------------------------------------------------------

/** Reglas que cruzan filas, con las mismas funciones de dominio que usan los repositorios. */
private class LedgerChecker(private val data: ParsedData, private val today: LocalDate?) {

    fun check(manifest: BackupManifest): BackupSummary {
        val ledger = data.ledger
        if (!BackupFormat.isUuid(ledger.id)) integrity("libreta_id inválido")
        if (ledger.createdAtMillis < 0 || ledger.dataRevision < 0) integrity("metadatos de libreta fuera de rango")
        if (ledger.nextEntryOrder < 1) integrity("siguiente_orden_registro menor que 1")

        val clients = HashMap<String, ClientRow>()
        for (c in data.clients) {
            if (!BackupFormat.isUuid(c.id)) integrity("cliente con id inválido")
            if (clients.put(c.id, c) != null) integrity("cliente repetido ${c.id}")
            val valid = (ClientRules.validate(ClientInput(c.name, c.phone.orEmpty(), c.note.orEmpty())) as? ClientValidation.Valid)?.client
            if (valid == null || valid.name != c.name || valid.phone != c.phone || valid.note != c.note) {
                integrity("cliente ${c.id}: nombre, teléfono o nota inválidos")
            }
            if (c.createdAtMillis < 0 || c.modifiedAtMillis < 0) integrity("cliente ${c.id}: instantes negativos")
        }

        val movements = HashMap<String, MovementSnapshot>()
        val orders = HashSet<Long>()
        var maxOrder = 0L
        for (m in data.movements) {
            if (!BackupFormat.isUuid(m.id)) integrity("movimiento con id inválido")
            if (m.id in movements) integrity("movimiento repetido ${m.id}")
            if (m.clientId !in clients) integrity("movimiento ${m.id}: cliente inexistente")
            if (m.order < 1 || !orders.add(m.order)) integrity("movimiento ${m.id}: orden_registro inválido o repetido")
            maxOrder = maxOf(maxOrder, m.order)
            if (m.version < 1) integrity("movimiento ${m.id}: version menor que 1")
            val snapshot = MovementSnapshot(
                id = m.id,
                clientId = m.clientId,
                date = parseDate(m.date) ?: integrity("movimiento ${m.id}: fecha inválida"),
                order = m.order,
                type = enumOrNull<MovementType>(m.type) ?: integrity("movimiento ${m.id}: tipo desconocido"),
                amountCop = m.amountCop,
                description = m.description,
                origin = enumOrNull<MovementOrigin>(m.origin) ?: integrity("movimiento ${m.id}: origen desconocido"),
                createdAtMillis = m.createdAtMillis,
                modifiedAtMillis = m.modifiedAtMillis,
                active = m.active,
                version = m.version,
            )
            checkFields(snapshot, "movimiento ${m.id}")
            movements[m.id] = snapshot
        }
        if (ledger.nextEntryOrder <= maxOrder) integrity("siguiente_orden_registro no es mayor que todos los órdenes")

        val revisionIds = HashSet<String>()
        val byMovement = HashMap<String, MutableList<RevisionRow>>()
        val versionKeys = HashSet<Pair<String, Long>>()
        for (r in data.revisions) {
            if (!BackupFormat.isUuid(r.id)) integrity("revisión con id inválido")
            if (!revisionIds.add(r.id)) integrity("revisión repetida ${r.id}")
            if (r.movementId !in movements) integrity("revisión ${r.id}: movimiento inexistente")
            if (r.previousVersion < 1 || r.newVersion != r.previousVersion + 1) chain("revisión ${r.id}: versiones inválidas")
            if (!versionKeys.add(r.movementId to r.previousVersion)) chain("revisión ${r.id}: versión anterior repetida")
            if (enumOrNull<RevisionAction>(r.action) == null) integrity("revisión ${r.id}: acción desconocida")
            if (MovementRules.validateReason(r.reason) != null || r.reason != r.reason.trim()) integrity("revisión ${r.id}: motivo inválido")
            if (r.changedAtMillis < 0) integrity("revisión ${r.id}: instante negativo")
            byMovement.getOrPut(r.movementId) { ArrayList() }.add(r)
        }

        for ((id, current) in movements) checkChain(current, byMovement[id].orEmpty())

        // Saldos: misma regla que los repositorios, solo movimientos vigentes en orden (fecha, orden_registro).
        val byClient = movements.values.filter { it.active }.groupBy { it.clientId }
        var totalPending = 0L
        for (c in data.clients) {
            val entries = byClient[c.id].orEmpty().map {
                LedgerEntry(it.id, it.date, it.order, it.type, it.origin, it.amountCop, it.description)
            }
            val balance = when (val result = LedgerRules.check(entries)) {
                is HistoryCheck.Valid -> result.finalBalanceCop
                is HistoryCheck.NegativeBalance -> fail(BackupProblem.BALANCE, "saldo negativo tras el movimiento ${result.entryId}")
                HistoryCheck.Overflow -> fail(BackupProblem.BALANCE, "saldo desbordado en el cliente ${c.id}")
            }
            if (!c.active && balance != 0L) fail(BackupProblem.ARCHIVED_WITH_DEBT, "cliente archivado ${c.id} con saldo")
            totalPending = try {
                Math.addExact(totalPending, balance)
            } catch (e: ArithmeticException) {
                fail(BackupProblem.BALANCE, "total pendiente desbordado")
            }
        }

        return BackupSummary(
            manifest = manifest,
            ledgerCreatedAtMillis = ledger.createdAtMillis,
            nextEntryOrder = ledger.nextEntryOrder,
            activeClients = data.clients.count { it.active },
            archivedClients = data.clients.count { !it.active },
            annulledMovements = movements.values.count { !it.active },
            totalPendingCop = totalPending,
        )
    }

    /**
     * Revisiones de un movimiento: [MovementChangeLog] exige versiones continuas 1..version-1 y que cada
     * snapshot sea de su versión. Además cada estado anterior debe ser vigente, del mismo movimiento y
     * coherente con la acción, tal como los escribe MovementRepository.
     */
    private fun checkChain(current: MovementSnapshot, revisions: List<RevisionRow>) {
        val where = "movimiento ${current.id}"
        if (revisions.isEmpty()) {
            if (current.version != 1L) chain("$where: versión ${current.version} sin revisiones")
            if (!current.active) chain("$where: anulado sin revisión de anulación")
            return
        }
        for (r in revisions) {
            val decoded = try {
                SnapshotJson.decode(r.previousStateJson)
            } catch (e: IllegalArgumentException) {
                chain("revisión ${r.id}: snapshot inválido")
            }
            if (SnapshotJson.encode(decoded) != r.previousStateJson) chain("revisión ${r.id}: snapshot no canónico")
        }
        val changes = try {
            MovementChangeLog.build(
                revisions.map { RevisionRecord(RevisionAction.valueOf(it.action), it.changedAtMillis, it.reason, it.previousVersion, it.previousStateJson) },
                current,
            )
        } catch (e: IllegalStateException) {
            chain("$where: ${e.message}")
        } catch (e: IllegalArgumentException) {
            chain("$where: snapshot inválido")
        }
        for (change in changes) {
            val before = change.before
            val after = change.after
            if (before.id != current.id || before.clientId != current.clientId || before.type != current.type ||
                before.order != current.order || before.createdAtMillis != current.createdAtMillis
            ) {
                chain("$where: un snapshot cambia datos inmutables")
            }
            checkFields(before, "snapshot de $where v${before.version}")
            if (!before.active) chain("$where: cambio sobre un estado anulado")
            if (after.modifiedAtMillis != change.changedAtMillis) chain("$where v${after.version}: modificado_en distinto del cambio")
            when (change.action) {
                RevisionAction.ANULAR ->
                    if (after.active || after.amountCop != before.amountCop || after.date != before.date ||
                        after.description != before.description || after.origin != before.origin
                    ) {
                        chain("$where: anulación incoherente")
                    }
                RevisionAction.EDITAR -> {
                    if (!after.active) chain("$where: corrección que anula")
                    if (after.amountCop == before.amountCop && after.date == before.date && after.description == before.description) {
                        chain("$where: corrección sin cambios")
                    }
                    if (after.origin != MovementRules.originAfterCorrection(before.origin, before.amountCop, after.amountCop)) {
                        chain("$where: origen incoherente tras corregir")
                    }
                }
            }
        }
    }

    /** Campos de un estado de movimiento (actual o anterior), con las reglas de MovementRules. */
    private fun checkFields(s: MovementSnapshot, where: String) {
        if (MovementRules.validateAmount(s.amountCop) != null) integrity("$where: valor_cop fuera de rango")
        if (s.date < MovementRules.MIN_DATE) integrity("$where: fecha anterior a ${MovementRules.MIN_DATE}")
        if (today != null && s.date > today) fail(BackupProblem.FUTURE_DATE, "$where: fecha posterior a hoy")
        val d = s.description
        if (d != null && (d != d.trim() || d.isEmpty() || d.codePointCount(0, d.length) > MovementRules.DESCRIPTION_MAX)) {
            integrity("$where: descripción inválida")
        }
        if (s.type == MovementType.FIADO && s.origin == MovementOrigin.PAGO_TOTAL) integrity("$where: fiado con origen PAGO_TOTAL")
        if (s.createdAtMillis < 0 || s.modifiedAtMillis < 0) integrity("$where: instantes negativos")
    }

    private fun parseDate(text: String): LocalDate? {
        if (!ISO_DAY.matches(text)) return null
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeException) {
            null
        }
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

    private fun integrity(detail: String): Nothing = fail(BackupProblem.INTEGRITY, detail)

    private fun chain(detail: String): Nothing = fail(BackupProblem.REVISION_CHAIN, detail)

    private companion object {
        val ISO_DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    }
}
