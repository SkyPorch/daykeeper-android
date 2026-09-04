package com.skyporch.daykeeper.verification

import com.skyporch.daykeeper.DaykeeperException
import com.skyporch.daykeeper.DaykeeperTokenProvider
import com.skyporch.daykeeper.ui.DaykeeperMessengerState
import org.junit.Assert.*
import org.junit.Test

class ConsumerTest {
    @Test
    fun packagedCoreRejectsInsecureRemoteGatewayWithoutCallingProvider() {
        try {
            customerClient(
                "http://support.example",
                DaykeeperTokenProvider { error("must not be called") },
            )
            fail("Expected configuration rejection")
        } catch (error: DaykeeperException) {
            assertEquals("INVALID_CONFIGURATION", error.code)
        }
    }

    @Test
    fun packagedUiStateStartsWithNoCustomerData() {
        val state = DaykeeperMessengerState()
        assertTrue(state.suspended)
        assertTrue(state.messages.isEmpty())
        assertEquals("", state.draft)
    }
}
