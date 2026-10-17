package com.example.pagaya.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Coherencia entre directorio central, encabezados locales, descriptores de datos y contenido real.
 * Cada caso altera solo la estructura: `data.json` y su SHA-256 siguen siendo válidos, así que el
 * rechazo se debe únicamente a la incoherencia del contenedor.
 */
class BackupContainerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val validator = BackupValidator()

    /** Archivo real de [BackupWriter]: DEFLATE con descriptor de datos (bit 3) en ambas entradas. */
    private fun writerArchive(): ByteArray {
        val archive = temp.newFile()
        BackupWriter.write(richLedger().capture(), "0.1.0-test", temp.newFile(), archive)
        return archive.readBytes()
    }

    /** ZIP sin descriptores (STORED), con CRC y tamaños en el encabezado local. */
    private fun storedArchive(): ByteArray {
        val parts = readParts(temp.newFile().also { BackupWriter.write(richLedger().capture(), "0.1.0-test", temp.newFile(), it) })
        return rawZip(listOf(RawEntry("manifest.json", parts.manifest.toByteArray()), RawEntry("data.json", parts.data.toByteArray())))
    }

    private fun file(bytes: ByteArray) = temp.newFile().apply { writeBytes(bytes) }

    private fun assertContainerRejected(bytes: ByteArray, case: String) {
        when (val result = validator.validate(file(bytes))) {
            is BackupValidation.Valid -> throw AssertionError("$case: se aceptó un ZIP incoherente")
            is BackupValidation.Invalid -> assertEquals("$case: ${result.detail}", BackupProblem.CONTAINER, result.problem)
        }
    }

    /** Posición de la entrada del directorio central de [name]. */
    private fun central(bytes: ByteArray, name: String): Int {
        var i = 0
        while (i < bytes.size - 46) {
            if (u32(bytes, i) == 0x02014b50L) {
                val nameLength = u16(bytes, i + 28)
                if (String(bytes, i + 46, nameLength, Charsets.US_ASCII) == name) return i
            }
            i++
        }
        error("No está $name")
    }

    private fun local(bytes: ByteArray, name: String): Int = u32(bytes, central(bytes, name) + 42).toInt()

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte()
        b[i + 1] = (v shr 8).toByte()
    }

    private fun put32(b: ByteArray, i: Int, v: Long) {
        ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt())
    }

    private fun ByteArray.edit(change: ByteArray.() -> Unit): ByteArray = copyOf().apply(change)

    @Test
    fun theWriterAndAStoredArchiveAreAcceptedAsTheyAre() {
        assertTrue(validator.validate(file(writerArchive())) is BackupValidation.Valid)
        assertTrue(validator.validate(file(storedArchive())) is BackupValidation.Valid)
        val bytes = writerArchive()
        assertEquals("el escritor usa descriptor de datos", 0x0008, u16(bytes, local(bytes, "data.json") + 6) and 0x0008)
    }

    @Test
    fun aCentralOffsetThatDoesNotPointToTheLocalEntryIsRejected() {
        val bytes = writerArchive()
        val cen = central(bytes, "data.json")
        val offset = u32(bytes, cen + 42)
        assertContainerRejected(bytes.edit { put32(this, cen + 42, offset + 1) }, "offset +1")
        assertContainerRejected(bytes.edit { put32(this, cen + 42, offset - 1) }, "offset −1")
        val stored = storedArchive()
        val storedCen = central(stored, "data.json")
        assertContainerRejected(stored.edit { put32(this, storedCen + 42, u32(stored, storedCen + 42) + 4) }, "offset STORED")
    }

    @Test
    fun centralCrcOrSizesThatDifferFromTheEntryAreRejected() {
        for (bytes in listOf(writerArchive(), storedArchive())) {
            for (name in listOf("manifest.json", "data.json")) {
                val cen = central(bytes, name)
                assertContainerRejected(bytes.edit { put32(this, cen + 16, u32(bytes, cen + 16) xor 1) }, "CRC central de $name")
                assertContainerRejected(bytes.edit { put32(this, cen + 20, u32(bytes, cen + 20) + 1) }, "tamaño comprimido central de $name")
                assertContainerRejected(bytes.edit { put32(this, cen + 24, u32(bytes, cen + 24) + 1) }, "tamaño central de $name")
            }
        }
    }

    @Test
    fun aDataDescriptorThatDiffersFromTheCentralDirectoryIsRejected() {
        val bytes = writerArchive()
        // El descriptor de data.json está justo antes del directorio central: firma + CRC + tamaños.
        val descriptor = central(bytes, "manifest.json") - 16
        assertEquals(0x08074b50L, u32(bytes, descriptor))
        assertContainerRejected(bytes.edit { put32(this, descriptor + 4, u32(bytes, descriptor + 4) xor 1) }, "CRC del descriptor")
        assertContainerRejected(bytes.edit { put32(this, descriptor + 12, u32(bytes, descriptor + 12) + 1) }, "tamaño del descriptor")
    }

    @Test
    fun localCrcOrSizesThatDifferFromTheCentralDirectoryAreRejected() {
        val bytes = storedArchive()
        val loc = local(bytes, "data.json")
        assertContainerRejected(bytes.edit { put32(this, loc + 14, u32(bytes, loc + 14) xor 1) }, "CRC local")
        assertContainerRejected(bytes.edit { put32(this, loc + 22, u32(bytes, loc + 22) + 1) }, "tamaño local")
        // Con descriptor, un encabezado local con valores distintos de cero debe coincidir.
        val writer = writerArchive()
        val writerLoc = local(writer, "data.json")
        assertContainerRejected(writer.edit { put32(this, writerLoc + 14, 0x12345678) }, "CRC local con descriptor")
    }

    @Test
    fun incompatibleMethodsOrFlagsAreRejected() {
        val bytes = writerArchive()
        val cen = central(bytes, "data.json")
        val loc = local(bytes, "data.json")
        assertContainerRejected(bytes.edit { put16(this, cen + 10, 0) }, "método central STORED, local DEFLATE")
        assertContainerRejected(bytes.edit { put16(this, loc + 8, 0) }, "método local STORED, central DEFLATE")
        assertContainerRejected(bytes.edit { put16(this, cen + 8, u16(bytes, cen + 8) xor 0x0800) }, "flag UTF-8 solo en el central")
        assertContainerRejected(bytes.edit { put16(this, loc + 6, u16(bytes, loc + 6) and 0x0008.inv()) }, "descriptor solo en el central")
        val stored = storedArchive()
        val storedLoc = local(stored, "data.json")
        assertContainerRejected(stored.edit { put16(this, storedLoc + 6, 0x0008) }, "descriptor solo en el local")
    }

    @Test
    fun aConsistentButWrongCrcIsCaughtByReadingTheRealContent() {
        // CRC alterado igual en el directorio central y en el encabezado local: la estructura cuadra, pero el
        // contenido leído no corresponde. data.json y su SHA-256 no cambian.
        val bytes = storedArchive()
        val cen = central(bytes, "data.json")
        val loc = local(bytes, "data.json")
        val wrong = u32(bytes, cen + 16) xor 0x00ff00ffL
        assertContainerRejected(bytes.edit { put32(this, cen + 16, wrong); put32(this, loc + 14, wrong) }, "CRC coherente pero falso")
    }

    @Test
    fun theLocalNameMustMatchTheCentralName() {
        val bytes = storedArchive()
        val loc = local(bytes, "data.json")
        // "data.json" → "data.jsoN" solo en el encabezado local.
        assertContainerRejected(bytes.edit { this[loc + 30 + 8] = 'N'.code.toByte() }, "nombre local distinto")
    }

    @Test
    fun bytesBetweenEntriesAreRejected() {
        val bytes = storedArchive()
        val cen = central(bytes, "manifest.json")
        // Un byte extra antes del directorio central, ajustando su posición en el fin de directorio.
        val padded = bytes.copyOfRange(0, cen) + byteArrayOf(0) + bytes.copyOfRange(cen, bytes.size)
        val eocd = padded.size - 22
        put32(padded, eocd + 16, u32(padded, eocd + 16) + 1)
        assertContainerRejected(padded, "relleno entre entradas")
    }

    @Test
    fun compressedSizeMustMatchTheDeflateStreamActuallyConsumed() {
        val bytes = writerArchive()
        val oldCentral = central(bytes, "manifest.json")
        val oldDataCentral = central(bytes, "data.json")
        // Conservar el descriptor original donde termina DEFLATE y añadir otro antes del índice.
        // El índice y el segundo descriptor declaran ambos 16 bytes comprimidos adicionales. Sus
        // CRC y tamaños descomprimidos son válidos, igual que JSON y SHA. Solo la lectura real
        // sabe que DEFLATE consumió menos bytes que los declarados por el índice.
        val padded = bytes.copyOfRange(0, oldCentral) + bytes.copyOfRange(oldCentral - 16, oldCentral) + bytes.copyOfRange(oldCentral, bytes.size)
        val newDataCentral = oldDataCentral + 16
        val compressed = u32(bytes, oldDataCentral + 20)
        put32(padded, newDataCentral + 20, compressed + 16)
        put32(padded, oldCentral + 8, compressed + 16)
        put32(padded, padded.size - 22 + 16, oldCentral + 16L)
        assertContainerRejected(padded, "descriptor duplicado ocultando bytes comprimidos")
    }

    @Test
    fun zip64ExtraFieldsAreRejectedEvenWithoutSentinelSizes() {
        val bytes = storedArchive()
        val oldCentral = central(bytes, "manifest.json")
        val oldDataCentral = central(bytes, "data.json")
        val insertion = 30 + u16(bytes, 26)
        fun withExtra(tag: Int): ByteArray {
            val extra = byteArrayOf(tag.toByte(), (tag shr 8).toByte(), 0, 0)
            val result = bytes.copyOfRange(0, insertion) + extra + bytes.copyOfRange(insertion, bytes.size)
            put16(result, 28, 4)
            put32(result, oldDataCentral + 4 + 42, u32(bytes, oldDataCentral + 42) + 4)
            put32(result, result.size - 22 + 16, oldCentral + 4L)
            return result
        }
        assertTrue(validator.validate(file(withExtra(0x9999))) is BackupValidation.Valid)
        assertContainerRejected(withExtra(0x0001), "ZIP64 en extra local")
        val centralExtraAt = oldCentral + 46 + u16(bytes, oldCentral + 28)
        val centralExtra = bytes.copyOfRange(0, centralExtraAt) + byteArrayOf(1, 0, 0, 0) + bytes.copyOfRange(centralExtraAt, bytes.size)
        put16(centralExtra, oldCentral + 30, 4)
        put32(centralExtra, centralExtra.size - 22 + 12, u32(bytes, bytes.size - 22 + 12) + 4)
        assertContainerRejected(centralExtra, "ZIP64 en extra central")
    }
}
