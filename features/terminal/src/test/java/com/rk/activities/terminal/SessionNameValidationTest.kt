package com.rk.activities.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNameValidationTest {

    private fun matches(name: String): Boolean = Terminal.SESSION_NAME_REGEX.matches(name)

    @Test
    fun validNamesAccepted() {
        assertTrue(matches("main"))
        assertTrue(matches("main-1"))
        assertTrue(matches("A_b"))
        assertTrue(matches("x"))
        assertTrue(matches("0123456789"))
    }

    @Test
    fun traversalRejected() {
        assertFalse(matches(".."))
        assertFalse(matches("../x"))
        assertFalse(matches("x/../y"))
        assertFalse(matches("."))
        assertFalse(matches("..\\x"))
    }

    @Test
    fun slashesSpacesUnicodeEmptyRejected() {
        assertFalse(matches("a/b"))
        assertFalse(matches("/abs"))
        assertFalse(matches("a b"))
        assertFalse(matches(" leading"))
        assertFalse(matches("trailing "))
        assertFalse(matches("héllo"))
        assertFalse(matches("セッション"))
        assertFalse(matches(""))
    }

    @Test
    fun percentEncodedFormsRejected() {
        assertFalse(matches("%2e%2e"))
        assertFalse(matches("%2E%2E"))
        assertFalse(matches("%2e%2e%2fx"))
        assertFalse(matches("a%20b"))
    }
}
