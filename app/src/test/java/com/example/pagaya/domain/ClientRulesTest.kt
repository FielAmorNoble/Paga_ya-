package com.example.pagaya.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientRulesTest {

    private fun valid(name: String, phone: String = "", note: String = ""): ValidClient {
        val result = ClientRules.validate(ClientInput(name, phone, note))
        assertTrue("Se esperaba válido: $result", result is ClientValidation.Valid)
        return (result as ClientValidation.Valid).client
    }

    private fun invalid(name: String, phone: String = "", note: String = ""): ClientValidation.Invalid {
        val result = ClientRules.validate(ClientInput(name, phone, note))
        assertTrue("Se esperaba inválido: $result", result is ClientValidation.Invalid)
        return result as ClientValidation.Invalid
    }

    @Test
    fun nameIsRequiredAndTrimmed() {
        assertEquals(NameError.REQUIRED, invalid("").name)
        assertEquals(NameError.REQUIRED, invalid(" \t ").name)
        assertEquals("María Pérez", valid("  María Pérez  ").name)
    }

    @Test
    fun nameLengthCountsUnicodeCharactersNotUtf16Units() {
        assertEquals("á".repeat(120), valid("á".repeat(120)).name)
        assertEquals(NameError.TOO_LONG, invalid("x".repeat(121)).name)
        // 120 emojis son 240 unidades UTF-16 pero 120 caracteres, como cuenta SQLite.
        val emoji = "😀"
        assertEquals(120, valid(emoji.repeat(120)).name.codePointCount(0, 240))
        assertEquals(NameError.TOO_LONG, invalid(emoji.repeat(121)).name)
    }

    @Test
    fun optionalFieldsAreStoredAsNullWhenBlank() {
        val client = valid("Cliente Sintético", phone = "   ", note = "\n ")
        assertNull(client.phone)
        assertNull(client.note)
    }

    @Test
    fun phoneAcceptsDigitsAndUsualSeparatorsOnly() {
        assertEquals("+57 (300) 000-00.00", valid("A", phone = " +57 (300) 000-00.00 ").phone)
        assertEquals(PhoneError.INVALID, invalid("A", phone = "llamar luego").phone)
        assertEquals(PhoneError.INVALID, invalid("A", phone = "+ - ( )").phone)
        assertEquals(PhoneError.TOO_LONG, invalid("A", phone = "1".repeat(31)).phone)
        assertEquals("1".repeat(30), valid("A", phone = "1".repeat(30)).phone)
    }

    @Test
    fun noteLengthIsLimited() {
        assertEquals(NoteError.TOO_LONG, invalid("A", note = "n".repeat(1001)).note)
        assertEquals(1000, valid("A", note = "n".repeat(1000)).note?.length)
    }

    @Test
    fun allInvalidFieldsAreReportedTogether() {
        val errors = invalid("", phone = "abc", note = "n".repeat(1001))
        assertEquals(ClientValidation.Invalid(NameError.REQUIRED, PhoneError.INVALID, NoteError.TOO_LONG), errors)
    }

    @Test
    fun searchKeyIgnoresCaseAccentsAndExtraSpaces() {
        assertEquals("maria perez", ClientRules.searchKey("  MARÍA   Pérez "))
        assertEquals(ClientRules.searchKey("María"), ClientRules.searchKey("maria"))
        assertEquals(ClientRules.searchKey("Muñoz"), ClientRules.searchKey("MUNOZ"))
        assertEquals("ines", ClientRules.searchKey("İnés"))
    }

    @Test
    fun likeFragmentTreatsWildcardsAsLiteralText() {
        assertEquals("100\\% ana", ClientRules.likeFragment("100% Ana"))
        assertEquals("ana\\_b", ClientRules.likeFragment("Ana_B"))
        assertEquals("a\\\\b", ClientRules.likeFragment("a\\b"))
        assertEquals("", ClientRules.likeFragment("   "))
    }
}
