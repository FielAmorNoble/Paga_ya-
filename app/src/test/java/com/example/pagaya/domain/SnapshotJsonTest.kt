package com.example.pagaya.domain

import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.data.local.RevisionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class SnapshotJsonTest {

    private val snapshot = MovementSnapshot(
        id = "11111111-1111-4111-8111-111111111111",
        clientId = "22222222-2222-4222-8222-222222222222",
        date = LocalDate.of(2026, 10, 3),
        order = 7,
        type = MovementType.ABONO,
        amountCop = 25_000,
        description = "Transferencia",
        origin = MovementOrigin.PAGO_TOTAL,
        createdAtMillis = 1_790_000_000_000,
        modifiedAtMillis = 1_790_000_100_000,
        active = true,
        version = 1,
    )

    private fun assertRejected(json: String, why: String) {
        try {
            SnapshotJson.decode(json)
            fail("Se esperaba rechazo ($why): $json")
        } catch (e: IllegalArgumentException) {
            // esperado
        }
    }

    @Test
    fun theSnapshotHasEveryMovementFieldWithTheLogicalSpanishKeys() {
        val json = SnapshotJson.encode(snapshot)
        assertTrue(json.startsWith("{") && json.endsWith("}"))
        for (key in listOf("id", "cliente_id", "fecha", "orden_registro", "tipo", "valor_cop", "descripcion", "origen", "creado_en", "modificado_en", "activo", "version")) {
            assertTrue("falta $key en $json", json.contains("\"$key\":"))
        }
        assertTrue(json.contains("\"fecha\":\"2026-10-03\""))
        assertTrue(json.contains("\"valor_cop\":25000"))
        assertTrue(json.contains("\"tipo\":\"ABONO\"") && json.contains("\"origen\":\"PAGO_TOTAL\""))
        assertTrue(json.contains("\"activo\":true"))
    }

    @Test
    fun roundTripKeepsEveryField() {
        assertEquals(snapshot, SnapshotJson.decode(SnapshotJson.encode(snapshot)))
        val annulled = snapshot.copy(active = false, version = 3, description = null, type = MovementType.FIADO, origin = MovementOrigin.MANUAL)
        assertEquals(annulled, SnapshotJson.decode(SnapshotJson.encode(annulled)))
        assertTrue(SnapshotJson.encode(annulled).contains("\"descripcion\":null"))
    }

    @Test
    fun specialCharactersSurviveTheRoundTrip() {
        for (text in listOf("dice \"hola\"", "ruta C:\\temp\\x", "línea 1\nlínea 2\r\n\ttab", "tilde ñandú ÁÉÍÓÚ", "emoji 😀 y {llaves}, [corchetes]: coma", "ctrl \u0001\u001f fin", "", "  espacios  ")) {
            val s = snapshot.copy(description = text)
            val json = SnapshotJson.encode(s)
            assertEquals("texto «$text»", s, SnapshotJson.decode(json))
            // El JSON no contiene saltos de línea ni caracteres de control crudos.
            assertTrue(json.none { it < ' ' })
        }
    }

    @Test
    fun decoderIsStrict() {
        val good = SnapshotJson.encode(snapshot)
        assertRejected("", "vacío")
        assertRejected("[]", "no es objeto")
        assertRejected(good.dropLast(1), "truncado")
        assertRejected("$good basura", "texto sobrante")
        assertRejected(good.replace("\"version\":1", "\"version\":\"1\""), "tipo incorrecto")
        assertRejected(good.replace("\"activo\":true", "\"activo\":1"), "booleano como entero")
        assertRejected(good.replace("\"tipo\":\"ABONO\"", "\"tipo\":\"AJUSTE\""), "tipo desconocido")
        assertRejected(good.replace("2026-10-03", "2026-02-30"), "fecha inexistente")
        assertRejected(good.replace("\"orden_registro\":7,", ""), "falta clave")
        assertRejected(good.replace("\"descripcion\":\"Transferencia\",", ""), "falta descripcion")
        assertRejected(good.dropLast(1) + ",\"version\":2}", "clave repetida")
        assertRejected(good.replace("\"valor_cop\":25000", "\"valor_cop\":25000.5"), "decimal")
        assertRejected(good.replace("\"valor_cop\":25000", "\"valor_cop\":1e3"), "exponente")
        assertRejected(good.replace("Transferencia", "Trans\\q"), "escape desconocido")
        assertRejected(good.replace("Transferencia", "Trans\\u12"), "escape incompleto")
    }

    // ---- bitácora de cambios ------------------------------------------------------------

    private val original = snapshot.copy(origin = MovementOrigin.MANUAL, version = 1, modifiedAtMillis = 1_790_000_000_000)
    private val afterEdit = original.copy(amountCop = 20_000, date = LocalDate.of(2026, 10, 5), description = "Nueva nota", modifiedAtMillis = 2_000, version = 2)
    private val afterAnnul = afterEdit.copy(active = false, modifiedAtMillis = 3_000, version = 3)

    private fun record(action: RevisionAction, before: MovementSnapshot, at: Long, reason: String) =
        RevisionRecord(action, at, reason, before.version, SnapshotJson.encode(before))

    @Test
    fun changeLogExplainsEachRevisionNewestFirst() {
        val log = MovementChangeLog.build(
            listOf(
                record(RevisionAction.ANULAR, afterEdit, at = 3_000, reason = "Duplicado"),
                record(RevisionAction.EDITAR, original, at = 2_000, reason = "Error al escribir"),
            ),
            afterAnnul,
        )
        assertEquals(listOf(RevisionAction.ANULAR, RevisionAction.EDITAR), log.map { it.action })
        assertEquals(listOf("Duplicado", "Error al escribir"), log.map { it.reason })
        assertEquals(listOf(3_000L, 2_000L), log.map { it.changedAtMillis })

        val edit = log[1]
        assertEquals(original, edit.before)
        assertEquals(afterEdit, edit.after)
        assertEquals(
            listOf(
                FieldChange.Amount(25_000, 20_000),
                FieldChange.Date(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5)),
                FieldChange.Description("Transferencia", "Nueva nota"),
            ),
            edit.changes,
        )
        assertEquals(listOf(FieldChange.Status(before = true, after = false)), log[0].changes)
    }

    @Test
    fun changingTheAmountOfAFullPaymentIsReportedAsAnOriginChange() {
        val payment = snapshot
        val corrected = payment.copy(amountCop = 24_000, origin = MovementOrigin.MANUAL, version = 2)
        val log = MovementChangeLog.build(listOf(record(RevisionAction.EDITAR, payment, 10, "Corrección")), corrected)
        assertEquals(
            listOf(FieldChange.Amount(25_000, 24_000), FieldChange.Origin(MovementOrigin.PAGO_TOTAL, MovementOrigin.MANUAL)),
            log.single().changes,
        )
    }

    @Test
    fun clearingTheNoteIsAChangeAndEqualStatesAreNot() {
        assertEquals(listOf(FieldChange.Description("Transferencia", null)), snapshot.changesTo(snapshot.copy(description = null)))
        assertTrue(snapshot.changesTo(snapshot).isEmpty())
        // Cliente, tipo, ID, orden y creación nunca figuran como cambio.
        assertTrue(snapshot.changesTo(snapshot.copy(modifiedAtMillis = 5, version = 9)).isEmpty())
    }

    @Test
    fun noRevisionsMeansNoChanges() {
        assertTrue(MovementChangeLog.build(emptyList(), original).isEmpty())
    }

    @Test
    fun aBrokenVersionSequenceIsReportedInsteadOfShown() {
        val first = record(RevisionAction.EDITAR, original, 2_000, "a")
        val second = record(RevisionAction.ANULAR, afterEdit, 3_000, "b")
        // Falta una revisión.
        assertFailsWith<IllegalStateException> { MovementChangeLog.build(listOf(second), afterAnnul) }
        // El movimiento dice que tiene más versiones que revisiones.
        assertFailsWith<IllegalStateException> { MovementChangeLog.build(listOf(first), afterAnnul) }
        // Una revisión de más.
        assertFailsWith<IllegalStateException> { MovementChangeLog.build(listOf(first, second), afterEdit) }
        // El snapshot guardado no corresponde a la versión de la revisión.
        assertFailsWith<IllegalStateException> {
            MovementChangeLog.build(listOf(first.copy(previousStateJson = SnapshotJson.encode(afterEdit))), afterEdit)
        }
        // Un snapshot dañado.
        assertFailsWith<IllegalArgumentException> { MovementChangeLog.build(listOf(first.copy(previousStateJson = "{}")), afterEdit) }
        assertFalse(MovementChangeLog.build(listOf(first, second), afterAnnul).isEmpty())
    }

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw AssertionError("Se esperaba ${T::class.simpleName} y se obtuvo $e", e)
        }
        fail("Se esperaba ${T::class.simpleName}")
    }
}
