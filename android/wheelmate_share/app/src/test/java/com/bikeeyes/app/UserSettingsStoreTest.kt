package com.bikeeyes.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UserSettingsStoreTest {
    @Test
    fun normalizeServerAddressAddsSchemeAndTrailingSlash() {
        assertEquals(
            "http://192.168.0.10:8000/",
            UserSettingsStore.normalizeServerBaseUrl("192.168.0.10:8000")
        )
    }

    @Test
    fun passwordPolicyRequiresLengthAndSpecialCharacter() {
        assertTrue(AuthRepository.isValidPassword("password!"))
        assertFalse(AuthRepository.isValidPassword("password"))
        assertFalse(AuthRepository.isValidPassword("short!"))
    }

    @Test
    fun offlineFailureIsNotTreatedAsSessionExpiry() {
        assertFalse(AuthRepository.isDefinitiveSessionRejection(IOException("offline")))
    }
}
