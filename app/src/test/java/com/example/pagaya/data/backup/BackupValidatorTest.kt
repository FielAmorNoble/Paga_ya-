package com.example.pagaya.data.backup

import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.domain.MovementRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** Generación y validación del archivo de respaldo con datos sintéticos, en la JVM. */
class BackupValidatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val validator = BackupValidator()
    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct3 = LocalDate.of(2026, 10, 3)

    @Test
    fun maximumUnicodeFieldsAndWorstEscapedSnapshotsRemainAdmissible() {
        val builder = LedgerBuilder()
        val client = builder.client("😀".repeat(120), "+" + "1".repeat(29), "😀".repeat(1000))
        val movement = builder.fiado(client, 1, oct1, "x" + "\u0001".repeat(998) + "x")
        builder.edit(movement, amount = 2, reason = "😀".repeat(300))
        val data = temp.newFile()
        val archive = temp.newFile()
        val manifest = BackupWriter.write(builder.capture(), "😀".repeat(100), data, archive)
        assertEquals(manifest, valid(archive).manifest)
        assertTrue(builder.revisions.single().previousStateJson.length < BackupFormat.ReadLimits.SNAPSHOT)
        assertEquals(manifest, valid(archive, BackupValidator(BackupLimits(maxDataBytes = data.length()))).manifest)
    }

    // ---- ayudas --------------------------------------------------------------------------

    private fun write(capture: LedgerCapture): Pair<File, BackupManifest> {
        val data = temp.newFile()
        val archive = temp.newFile()
        val manifest = BackupWriter.write(capture, "0.1.0-test", data, archive)
        return archive to manifest
    }

    private fun valid(file: File, v: BackupValidator = validator, today: LocalDate? = null): BackupSummary =
        when (val result = v.validate(file, today)) {
            is BackupValidation.Valid -> result.summary
            is BackupValidation.Invalid -> throw AssertionError("Se esperaba válido: ${result.problem} ${result.detail}")
        }

    private fun assertInvalid(expected: BackupProblem, file: File, v: BackupValidator = validator, today: LocalDate? = null) {
        when (val result = v.validate(file, today)) {
            is BackupValidation.Valid -> fail("Se esperaba $expected y el archivo fue válido")
            is BackupValidation.Invalid -> assertEquals("Detalle: ${result.detail}", expected, result.problem)
        }
    }

    private fun fileOf(bytes: ByteArray): File = temp.newFile().apply { writeBytes(bytes) }

    /** ZIP con [manifest] y [data]; por defecto el manifiesto se ajusta a la longitud y el SHA-256 reales. */
    private fun archiveOf(manifest: String, data: ByteArray, fixIntegrity: Boolean = true): File {
        val m = if (fixIntegrity) {
            manifest.withManifestValue("bytes_datos", data.size.toString())
                .withManifestValue("sha256_datos", "\"${sha256(data)}\"")
        } else {
            manifest
        }
        return fileOf(rawZip(listOf(RawEntry("manifest.json", m.toByteArray()), RawEntry("data.json", data, deflate = true))))
    }

    private fun richParts(): ArchiveParts = readParts(write(richLedger().capture()).first)

    /** Cambia el texto de data.json y reconstruye un archivo íntegro: solo falla la regla que se prueba. */
    private fun mutated(parts: ArchiveParts = richParts(), change: (String) -> String): File {
        val data = change(parts.data)
        check(data != parts.data) { "La mutación no cambió nada" }
        return archiveOf(parts.manifest, data.toByteArray())
    }

    // ---- archivos válidos ----------------------------------------------------------------

    @Test
    fun anEmptyLedgerIsAValidBackup() {
        val capture = LedgerBuilder().capture()
        val (file, manifest) = write(capture)
        val summary = valid(file)
        assertEquals(manifest, summary.manifest)
        assertEquals(0L, manifest.clients)
        assertEquals(0L, manifest.movements)
        assertEquals(0L, manifest.revisions)
        assertEquals(0L, summary.totalPendingCop)
        assertEquals("""{"libreta":{"libreta_id":"${capture.ledgerId}","creado_en":1790000000000,"revision_datos":0,""" +
            """"siguiente_orden_registro":1},"clientes":[],"movimientos":[],"revisiones":[]}""", readParts(file).data)
    }

    @Test
    fun aFullLedgerWithArchivedAnnulledAndCorrectedMovementsIsValid() {
        val builder = richLedger()
        val (file, manifest) = write(builder.capture())
        val summary = valid(file)
        assertEquals(3L, manifest.clients)
        assertEquals(builder.movements.size.toLong(), manifest.movements)
        assertEquals(4L, manifest.revisions)
        assertEquals(builder.revision, manifest.dataRevision)
        assertEquals(2, summary.activeClients)
        assertEquals(1, summary.archivedClients)
        assertEquals(1, summary.annulledMovements)
        assertEquals(MovementRules.AMOUNT_MAX + 1 - 25_000, summary.totalPendingCop)
    }

    @Test
    fun theManifestDescribesTheExactBytesOfDataJson() {
        val (file, manifest) = write(richLedger().capture())
        val parts = readParts(file)
        val bytes = parts.data.toByteArray(Charsets.UTF_8)
        assertEquals(bytes.size.toLong(), manifest.dataBytes)
        assertEquals(sha256(bytes), manifest.dataSha256)
        assertTrue("UTF-8 sin BOM", bytes[0] == '{'.code.toByte())
        assertEquals(BackupWriter.manifestJson(manifest), parts.manifest)
        for (key in BackupFormat.Manifest.ALL) assertTrue(key, parts.manifest.contains("\"$key\":"))
        assertTrue(parts.manifest.contains("\"formato\":\"paga-ya-backup\",\"version_formato\":1,\"version_datos\":1"))
        assertTrue(parts.manifest.contains("\"zona_negocio\":\"America/Bogota\",\"moneda\":\"COP\""))
    }

    @Test
    fun amountsTextsAndSnapshotsArePreservedExactly() {
        val builder = richLedger()
        val (file, _) = write(builder.capture())
        val root = StrictJsonReader(readParts(file).data.byteInputStream(), 20).nextAny() as Map<*, *>

        val clients = (root["clientes"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
        for (c in builder.clients.values) {
            val row = clients.getValue(c.id)
            assertEquals(c.name, row["nombre"])
            assertEquals(c.phone, row["telefono"])
            assertEquals(c.note, row["nota"])
            assertEquals(JsonNumber(c.createdAtMillis.toString()), row["creado_en"])
            assertEquals(c.active, row["activo"])
            assertTrue("nombre_busqueda no se exporta", "nombre_busqueda" !in row)
        }
        val movements = (root["movimientos"] as List<*>).map { it as Map<*, *> }
        assertEquals("ordenados por orden_registro", movements.map { (it["orden_registro"] as JsonNumber).text.toLong() }.sorted(),
            movements.map { (it["orden_registro"] as JsonNumber).text.toLong() })
        val byId = movements.associateBy { it["id"] }
        for (m in builder.movements.values) {
            val row = byId.getValue(m.id)
            assertEquals(JsonNumber(m.amountCop.toString()), row["valor_cop"])
            assertEquals(m.date.toString(), row["fecha"])
            assertEquals(m.description, row["descripcion"])
            assertEquals(m.origin.name, row["origen"])
            assertEquals(m.active, row["activo"])
            assertEquals(JsonNumber(m.version.toString()), row["version"])
        }
        assertTrue(byId.values.any { it["valor_cop"] == JsonNumber("999999999") })
        assertTrue(byId.values.any { it["origen"] == MovementOrigin.PAGO_TOTAL.name })
        val revisions = (root["revisiones"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
        for (r in builder.revisions) {
            val row = revisions.getValue(r.id)
            assertEquals("snapshot idéntico al guardado", r.previousStateJson, row["estado_anterior_json"])
            assertEquals(r.reason, row["motivo"])
            assertEquals(r.action.name, row["accion"])
        }
        val text = readParts(file).data
        for (forbidden in listOf("saldo", "nombre_busqueda", "epoca_local", "backup_registro", "localizador")) {
            assertTrue("No se exporta $forbidden", !text.contains(forbidden))
        }
    }

    @Test
    fun theFileNameUsesTheBogotaCaptureTimeAndTheFullBackupId() {
        // 2026-10-06T02:30:45Z es el 5 de octubre a las 21:30:45 en Bogotá.
        val millis = java.time.Instant.parse("2026-10-06T02:30:45Z").toEpochMilli()
        val id = LedgerBuilder.uuid(42)
        assertEquals("backup_2026-10-05_213045_$id.pagaya.zip", BackupFormat.fileName(millis, id))
    }

    @Test
    fun futureDatesAreOnlyRejectedWhenTheCallerAsksForIt() {
        val builder = LedgerBuilder().apply { fiado(client("Cliente Fecha"), 5_000, LocalDate.of(2026, 12, 31)) }
        val (file, _) = write(builder.capture())
        valid(file)
        assertInvalid(BackupProblem.FUTURE_DATE, file, today = LocalDate.of(2026, 10, 16))
    }

    // ---- integridad del manifiesto y del contenido ---------------------------------------

    @Test
    fun aWrongChecksumIsRejected() {
        val parts = richParts()
        val other = "0".repeat(64)
        assertInvalid(BackupProblem.CHECKSUM_MISMATCH, archiveOf(parts.manifest.withManifestValue("sha256_datos", "\"$other\""), parts.data.toByteArray(), fixIntegrity = false))
    }

    @Test
    fun aModifiedDataJsonWithTheOldManifestIsRejected() {
        val parts = richParts()
        // Misma longitud, otro contenido: solo el SHA-256 lo detecta.
        val data = parts.data.replaceFirst("Pedro Sintético", "Pedro Sintétiko")
        assertInvalid(BackupProblem.CHECKSUM_MISMATCH, archiveOf(parts.manifest, data.toByteArray(), fixIntegrity = false))
    }

    @Test
    fun aWrongDataLengthIsRejected() {
        val parts = richParts()
        val data = parts.data.toByteArray()
        val manifest = parts.manifest.withManifestValue("bytes_datos", (data.size + 1).toString())
        assertInvalid(BackupProblem.SIZE_MISMATCH, archiveOf(manifest, data, fixIntegrity = false))
    }

    @Test
    fun countsMustMatchTheRealRows() {
        val parts = richParts()
        for (key in listOf("clientes", "movimientos", "revisiones")) {
            val manifest = parts.manifest.withManifestValue(key, "1")
            assertInvalid(BackupProblem.COUNT_MISMATCH, archiveOf(manifest, parts.data.toByteArray()))
        }
    }

    @Test
    fun ledgerIdentityAndRevisionMustMatchTheManifest() {
        val parts = richParts()
        assertInvalid(BackupProblem.IDENTITY_MISMATCH, archiveOf(parts.manifest.withManifestValue("libreta_id", "\"${LedgerBuilder.uuid(777)}\""), parts.data.toByteArray()))
        assertInvalid(BackupProblem.IDENTITY_MISMATCH, archiveOf(parts.manifest.withManifestValue("revision_datos", "999"), parts.data.toByteArray()))
    }

    @Test
    fun futureFormatsAndIncompatibleLedgersAreRejected() {
        val parts = richParts()
        val data = parts.data.toByteArray()
        assertInvalid(BackupProblem.UNSUPPORTED_VERSION, archiveOf(parts.manifest.withManifestValue("version_formato", "2"), data))
        assertInvalid(BackupProblem.UNSUPPORTED_VERSION, archiveOf(parts.manifest.withManifestValue("version_datos", "2"), data))
        // Un formato futuro con claves nuevas se reconoce como futuro, no como dañado.
        val future = parts.manifest.withManifestValue("version_formato", "3").replaceFirst("{", "{\"cifrado\":{\"algoritmo\":\"x\"},")
        assertInvalid(BackupProblem.UNSUPPORTED_VERSION, archiveOf(future, data))
        assertInvalid(BackupProblem.INCOMPATIBLE, archiveOf(parts.manifest.withManifestValue("formato", "\"otra-app\""), data))
        assertInvalid(BackupProblem.INCOMPATIBLE, archiveOf(parts.manifest.withManifestValue("moneda", "\"USD\""), data))
        assertInvalid(BackupProblem.INCOMPATIBLE, archiveOf(parts.manifest.withManifestValue("zona_negocio", "\"America/Lima\""), data))
        assertInvalid(BackupProblem.TYPE, archiveOf(parts.manifest.withManifestValue("version_formato", "0"), data))
    }

    @Test
    fun manifestFieldsNeedTheirExactType() {
        val parts = richParts()
        val data = parts.data.toByteArray()
        assertInvalid(BackupProblem.TYPE, archiveOf(parts.manifest.withManifestValue("version_formato", "\"1\""), data))
        assertInvalid(BackupProblem.TYPE, archiveOf(parts.manifest.replace("\"version_formato\":1", "\"version_formato\":1.0"), data))
        assertInvalid(BackupProblem.TYPE, archiveOf(parts.manifest.withManifestValue("capturado_en", "\"hoy\""), data))
        assertInvalid(BackupProblem.TYPE, archiveOf(parts.manifest.withManifestValue("backup_id", "\"no-es-uuid\""), data))
        assertInvalid(BackupProblem.TYPE, archiveOf(parts.manifest.replaceFirst("{", "{\"extra\":1,"), data))
        assertInvalid(BackupProblem.TYPE, archiveOf(parts.manifest.replace(Regex(",\"version_app\":\"[^\"]*\""), ""), data))
        assertInvalid(BackupProblem.DUPLICATE_KEY, archiveOf(parts.manifest.replaceFirst("{", "{\"moneda\":\"COP\","), data))
    }

    @Test
    fun dataFieldsNeedTheirExactType() {
        val cases = mapOf<String, (String) -> String>(
            "importe como texto" to { it.replace("\"valor_cop\":1,", "\"valor_cop\":\"1\",") },
            "importe decimal" to { it.replace("\"valor_cop\":1,", "\"valor_cop\":1.0,") },
            "importe con exponente" to { it.replace("\"valor_cop\":1,", "\"valor_cop\":1e0,") },
            "booleano como entero" to { it.replaceFirst("\"activo\":true", "\"activo\":1") },
            "fecha como número" to { it.replaceFirst(Regex("\"fecha\":\"[0-9-]+\""), "\"fecha\":20261001") },
            "clave desconocida" to { it.replaceFirst("\"nombre\":", "\"saldo\":5,\"nombre\":") },
            "falta una clave" to { it.replaceFirst(Regex("\"telefono\":(null|\"[^\"]*\"),"), "") },
            "nulo donde no se admite" to { it.replaceFirst(Regex("\"motivo\":\"[^\"]*\""), "\"motivo\":null") },
        )
        for ((name, change) in cases) {
            val result = validator.validate(mutated(change = change))
            assertTrue("$name: $result", result is BackupValidation.Invalid && result.problem == BackupProblem.TYPE)
        }
    }

    @Test
    fun duplicateKeysInsideDataAreRejected() {
        assertInvalid(BackupProblem.DUPLICATE_KEY, mutated { it.replaceFirst("\"activo\":true", "\"activo\":true,\"activo\":true") })
    }

    @Test
    fun unknownCodesAndOutOfRangeValuesAreRejected() {
        val cases = mapOf<String, (String) -> String>(
            "tipo desconocido" to { it.replaceFirst("\"tipo\":\"FIADO\"", "\"tipo\":\"COMPRA\"") },
            "importe cero" to { it.replace("\"valor_cop\":1,", "\"valor_cop\":0,") },
            "importe mayor al máximo" to { it.replace("\"valor_cop\":999999999,", "\"valor_cop\":1000000000,") },
            "fecha imposible" to { it.replaceFirst(Regex("\"fecha\":\"[0-9-]+\""), "\"fecha\":\"2026-02-30\"") },
            "fecha anterior a 1900" to { it.replaceFirst(Regex("\"fecha\":\"[0-9-]+\""), "\"fecha\":\"1899-12-31\"") },
            "nombre vacío" to { it.replace("\"nombre\":\"Pedro Sintético\"", "\"nombre\":\"\"") },
            "nombre sin recortar" to { it.replace("\"nombre\":\"Pedro Sintético\"", "\"nombre\":\" Pedro Sintético\"") },
            "teléfono vacío" to { it.replace("\"telefono\":\"+57 300 123 4567\"", "\"telefono\":\"\"") },
            "id no canónico" to { it.replaceFirst("\"id\":\"${LedgerBuilder.uuid(1)}\"", "\"id\":\"${LedgerBuilder.uuid(1).dropLast(1)}A\"") },
            "orden menor que 1" to { it.replaceFirst("\"orden_registro\":1,", "\"orden_registro\":0,") },
            "fiado con pago total" to { it.replaceFirst("\"tipo\":\"FIADO\",\"valor_cop\":10000,\"descripcion\":null,\"origen\":\"MANUAL\"", "\"tipo\":\"FIADO\",\"valor_cop\":10000,\"descripcion\":null,\"origen\":\"PAGO_TOTAL\"") },
            "siguiente orden no mayor" to { it.replace(Regex("\"siguiente_orden_registro\":[0-9]+"), "\"siguiente_orden_registro\":3") },
            "motivo largo" to { it.replaceFirst("Tercera corrección", "x".repeat(301)) },
        )
        for ((name, change) in cases) {
            val result = validator.validate(mutated(change = change))
            assertTrue("$name: $result", result is BackupValidation.Invalid && result.problem == BackupProblem.INTEGRITY)
        }
    }

    @Test
    fun brokenReferencesAndDuplicatesAreRejected() {
        val ghost = LedgerBuilder.uuid(55_555)
        val builder = richLedger()
        val clientId = builder.clients.keys.first()
        val movementId = builder.movements.keys.first()
        val cases = mapOf<String, (String) -> String>(
            "movimiento sin cliente" to { it.replace("\"cliente_id\":\"$clientId\"", "\"cliente_id\":\"$ghost\"") },
            "cliente repetido" to { it.replaceFirst("\"clientes\":[", "\"clientes\":[" + clientRow(it, clientId) + ",") },
            "movimiento repetido" to { it.replaceFirst("\"movimientos\":[", "\"movimientos\":[" + movementRow(it, movementId) + ",") },
            "orden repetido" to { it.replaceFirst("\"orden_registro\":2,", "\"orden_registro\":1,") },
        )
        val parts = readParts(write(builder.capture()).first)
        for ((name, change) in cases) {
            val data = change(parts.data)
            val result = validator.validate(archiveOf(parts.manifest, data.toByteArray()))
            assertTrue("$name: $result", result is BackupValidation.Invalid && result.problem in setOf(BackupProblem.INTEGRITY, BackupProblem.COUNT_MISMATCH))
        }
        // Con el conteo ajustado, un cliente repetido se detecta como integridad, no como conteo.
        val duplicated = parts.data.replaceFirst("\"clientes\":[", "\"clientes\":[" + clientRow(parts.data, clientId) + ",")
        val manifest = parts.manifest.withManifestValue("clientes", (builder.clients.size + 1).toString())
        assertInvalid(BackupProblem.INTEGRITY, archiveOf(manifest, duplicated.toByteArray()))
        val orphan = parts.data.replace("\"movimiento_id\":\"${builder.revisions.first().movementId}\"", "\"movimiento_id\":\"$ghost\"")
        assertInvalid(BackupProblem.INTEGRITY, archiveOf(parts.manifest, orphan.toByteArray()))
    }

    /** Fila de revisión (con su coma previa): el snapshot contiene llaves, así que se delimita por «motivo». */
    private fun revisionRow(id: String) = Regex(",?\\{\"id\":\"$id\",\"movimiento_id\".*?\"motivo\":\"[^\"]*\"\\}")

    private fun clientRow(data: String, id: String): String = Regex("\\{\"id\":\"$id\"[^}]*\\}").find(data)!!.value

    private fun movementRow(data: String, id: String): String = Regex("\\{\"id\":\"$id\",\"cliente_id\"[^}]*\\}").find(data)!!.value

    @Test
    fun invalidRevisionChainsAreRejected() {
        val builder = richLedger()
        val parts = readParts(write(builder.capture()).first)
        val corrected = builder.revisions.first().movementId
        val annulledRevision = builder.revisions.last()
        fun archive(data: String) = archiveOf(parts.manifest, data.toByteArray())

        // Hueco: la versión 2→3 desaparece pero el movimiento sigue en la versión 4.
        val second = builder.revisions.single { it.movementId == corrected && it.previousVersion == 2L }
        val withoutSecond = parts.data.replace(revisionRow(second.id), "")
        val manifest = parts.manifest.withManifestValue("revisiones", (builder.revisions.size - 1).toString())
        assertInvalid(BackupProblem.REVISION_CHAIN, archiveOf(manifest, withoutSecond.toByteArray()))

        // version_nueva distinta de version_anterior + 1.
        assertInvalid(BackupProblem.REVISION_CHAIN, archive(parts.data.replaceFirst("\"version_anterior\":1,\"version_nueva\":2", "\"version_anterior\":1,\"version_nueva\":3")))

        // El movimiento dice versión 5 pero solo tiene tres revisiones.
        assertInvalid(BackupProblem.REVISION_CHAIN, archive(parts.data.replace(Regex("(\\{\"id\":\"$corrected\"[^}]*\"version\":)4"), "$15")))

        // Anulado sin revisión de anulación.
        val annulled = annulledRevision.movementId
        val withoutAnnul = parts.data.replace(revisionRow(annulledRevision.id), "")
            .replace(Regex("(\\{\"id\":\"$annulled\"[^}]*\"version\":)2"), "$11")
        assertInvalid(BackupProblem.REVISION_CHAIN, archiveOf(manifest, withoutAnnul.toByteArray()))

        // Snapshot que cambia un dato inmutable (el cliente).
        val other = builder.clients.keys.last()
        val tampered = SnapshotJsonTamper.replaceClient(parts.data, annulledRevision.previousStateJson, other)
        assertInvalid(BackupProblem.REVISION_CHAIN, archive(tampered))

        // Snapshot dañado o no canónico.
        val escapedSnapshot = jsonQuoted(annulledRevision.previousStateJson)
        assertInvalid(BackupProblem.REVISION_CHAIN, archive(parts.data.replace(escapedSnapshot, jsonQuoted("{\"valor_cop\":7777}"))))
        assertInvalid(BackupProblem.REVISION_CHAIN, archive(parts.data.replace(escapedSnapshot, jsonQuoted(" " + annulledRevision.previousStateJson))))

        // Acción incoherente: una anulación marcada como corrección.
        assertInvalid(BackupProblem.REVISION_CHAIN, archive(parts.data.replace(
            "\"id\":\"${annulledRevision.id}\",\"movimiento_id\":\"$annulled\",\"version_anterior\":1,\"version_nueva\":2,\"accion\":\"ANULAR\"",
            "\"id\":\"${annulledRevision.id}\",\"movimiento_id\":\"$annulled\",\"version_anterior\":1,\"version_nueva\":2,\"accion\":\"EDITAR\"",
        )))
    }

    @Test
    fun chronologicalBalancesMustStayNonNegative() {
        // Abono anterior a la deuda: el saldo intermedio sería negativo.
        val builder = LedgerBuilder().apply {
            val c = client("Cliente Orden")
            fiado(c, 10_000, oct3)
            abono(c, 5_000, oct3)
        }
        val parts = readParts(write(builder.capture()).first)
        val data = parts.data.replace("\"fecha\":\"2026-10-03\",\"orden_registro\":2", "\"fecha\":\"2026-10-01\",\"orden_registro\":2")
        assertInvalid(BackupProblem.BALANCE, archiveOf(parts.manifest, data.toByteArray()))
    }

    @Test
    fun archivedClientsCannotHaveDebt() {
        val builder = LedgerBuilder().apply { fiado(client("Cliente Deudor"), 10_000, oct1) }
        val parts = readParts(write(builder.capture()).first)
        assertInvalid(BackupProblem.ARCHIVED_WITH_DEBT, archiveOf(parts.manifest, parts.data.replaceFirst("\"activo\":true}", "\"activo\":false}").toByteArray()))
    }

    // ---- contenedor ----------------------------------------------------------------------

    @Test
    fun notAZipOrATruncatedZipIsRejected() {
        assertInvalid(BackupProblem.CONTAINER, fileOf("no es un zip".toByteArray()))
        assertInvalid(BackupProblem.CONTAINER, fileOf(ByteArray(0)))
        val bytes = write(richLedger().capture()).first.readBytes()
        assertInvalid(BackupProblem.CONTAINER, fileOf(bytes.copyOf(bytes.size / 2)))
        assertInvalid(BackupProblem.CONTAINER, fileOf(bytes.copyOf(bytes.size - 1)))
    }

    @Test
    fun corruptedCompressedDataIsRejected() {
        val (file, _) = write(richLedger().capture())
        val bytes = file.readBytes()
        // Un byte cambiado dentro de los datos comprimidos de data.json: inflado o CRC fallan.
        val dataStart = String(bytes, Charsets.ISO_8859_1).indexOf("data.json") + "data.json".length + 40
        bytes[dataStart] = (bytes[dataStart].toInt() xor 0x5a).toByte()
        val result = validator.validate(fileOf(bytes))
        assertTrue("$result", result is BackupValidation.Invalid)
    }

    @Test
    fun onlyTheTwoAllowedEntriesInOrderAreAccepted() {
        val parts = richParts()
        val manifest = RawEntry("manifest.json", parts.manifest.toByteArray())
        val data = RawEntry("data.json", parts.data.toByteArray())
        valid(fileOf(rawZip(listOf(manifest, data))))
        val cases = mapOf(
            "entrada repetida" to listOf(manifest, data, data),
            "manifiesto repetido" to listOf(manifest, manifest),
            "entrada extra" to listOf(manifest, data, RawEntry("leeme.txt", "x".toByteArray())),
            "orden invertido" to listOf(data, manifest),
            "ruta relativa" to listOf(manifest, RawEntry("../data.json", data.data)),
            "carpeta" to listOf(manifest, RawEntry("copia/data.json", data.data)),
            "ruta absoluta" to listOf(manifest, RawEntry("/data.json", data.data)),
            "entrada de directorio" to listOf(manifest, RawEntry("data.json/", ByteArray(0))),
            "solo el manifiesto" to listOf(manifest),
            "enlace simbólico" to listOf(manifest, RawEntry("data.json", data.data, madeBy = 0x0314, externalAttributes = 0xA1FFL shl 16)),
            "atributo de carpeta" to listOf(manifest, RawEntry("data.json", data.data, externalAttributes = 0x10)),
        )
        for ((name, entries) in cases) {
            val result = validator.validate(fileOf(rawZip(entries)))
            assertTrue("$name: $result", result is BackupValidation.Invalid && result.problem == BackupProblem.ENTRIES)
        }
    }

    @Test
    fun encryptedOrUnsupportedEntriesAreRejected() {
        val parts = richParts()
        val manifest = RawEntry("manifest.json", parts.manifest.toByteArray())
        assertInvalid(BackupProblem.CONTAINER, fileOf(rawZip(listOf(manifest, RawEntry("data.json", parts.data.toByteArray(), flags = 1)))))
        // Método 12 (bzip2) declarado en el directorio central.
        val bytes = rawZip(listOf(manifest, RawEntry("data.json", parts.data.toByteArray())))
        val cen = String(bytes, Charsets.ISO_8859_1).lastIndexOf("PK\u0001\u0002")
        bytes[cen + 10] = 12
        assertInvalid(BackupProblem.CONTAINER, fileOf(bytes))
    }

    @Test
    fun anEntryHiddenFromTheCentralDirectoryIsRejected() {
        val parts = richParts()
        val manifest = parts.manifest.toByteArray()
        val data = parts.data.toByteArray()
        // El directorio central lista dos entradas, pero los datos locales contienen una tercera al inicio.
        val honest = rawZip(listOf(RawEntry("manifest.json", manifest), RawEntry("data.json", data)))
        val smuggled = rawZip(listOf(RawEntry("data.json", "{}".toByteArray()))).let { it.copyOf(it.size - 22 - 46 - 9) }
        val result = validator.validate(fileOf(smuggled + honest))
        assertTrue("$result", result is BackupValidation.Invalid)
    }

    // ---- límites -------------------------------------------------------------------------

    @Test
    fun realSizesAreLimitedWithoutTrustingDeclaredSizes() {
        val parts = richParts()
        val data = parts.data.toByteArray()
        val small = BackupValidator(BackupLimits(maxDataBytes = data.size - 1L))
        assertInvalid(BackupProblem.LIMIT_EXCEEDED, archiveOf(parts.manifest, data), small)

        // data.json muy comprimible que declara un tamaño pequeño en el ZIP y en el manifiesto.
        val padded = (" ".repeat(200_000) + parts.data).toByteArray()
        val m = parts.manifest.withManifestValue("bytes_datos", "10").withManifestValue("sha256_datos", "\"${sha256(padded)}\"")
        val bomb = fileOf(rawZip(listOf(RawEntry("manifest.json", m.toByteArray()), RawEntry("data.json", padded, deflate = true, declaredSize = 10))))
        assertTrue("el archivo es pequeño", bomb.length() < 10_000)
        assertInvalid(BackupProblem.LIMIT_EXCEEDED, bomb, BackupValidator(BackupLimits(maxDataBytes = 64 * 1024)))

        val archive = write(richLedger().capture()).first
        assertInvalid(BackupProblem.LIMIT_EXCEEDED, archive, BackupValidator(BackupLimits(maxArchiveBytes = archive.length() - 1)))
        assertInvalid(BackupProblem.LIMIT_EXCEEDED, archive, BackupValidator(BackupLimits(maxManifestBytes = 100)))
    }

    @Test
    fun rowCountsAndDepthAreLimited() {
        val archive = write(richLedger().capture()).first
        assertInvalid(BackupProblem.LIMIT_EXCEEDED, archive, BackupValidator(BackupLimits(maxClients = 2)))
        assertInvalid(BackupProblem.LIMIT_EXCEEDED, archive, BackupValidator(BackupLimits(maxMovements = 3)))
        assertInvalid(BackupProblem.LIMIT_EXCEEDED, archive, BackupValidator(BackupLimits(maxRevisions = 1)))

        val parts = readParts(archive)
        val deep = parts.manifest.replaceFirst("{", "{\"x\":" + "[".repeat(25) + "]".repeat(25) + ",")
        assertInvalid(BackupProblem.LIMIT_EXCEEDED, archiveOf(deep, parts.data.toByteArray()))
    }

    @Test
    fun truncatedOrMalformedJsonIsRejected() {
        val parts = richParts()
        val data = parts.data.toByteArray()
        assertInvalid(BackupProblem.JSON_SYNTAX, archiveOf(parts.manifest, data.copyOf(data.size - 3)))
        assertInvalid(BackupProblem.JSON_SYNTAX, archiveOf(parts.manifest, data + "{}".toByteArray()))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + data
        assertInvalid(BackupProblem.ENCODING, archiveOf(parts.manifest, bom))
        val invalidUtf8 = parts.data.replace("Pedro", "P\u0000dro").toByteArray().also {
            val i = it.indexOf(0)
            it[i] = 0xC3.toByte() // byte inicial de dos sin continuación válida (le sigue 'd')
        }
        assertInvalid(BackupProblem.ENCODING, archiveOf(parts.manifest, invalidUtf8))
    }

    private fun ByteArray.indexOf(value: Int): Int = indices.first { this[it].toInt() == value }

    private fun jsonQuoted(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    /** Cambia el cliente dentro del snapshot exportado de una revisión, manteniéndolo canónico. */
    private object SnapshotJsonTamper {
        fun replaceClient(data: String, snapshot: String, newClient: String): String {
            val decoded = com.example.pagaya.domain.SnapshotJson.decode(snapshot)
            val changed = com.example.pagaya.domain.SnapshotJson.encode(decoded.copy(clientId = newClient))
            return data.replace(quote(snapshot), quote(changed))
        }

        private fun quote(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
