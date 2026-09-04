package org.jjgroup.xproxy.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsTest {
    @AfterEach
    fun cleanup() {
        Settings.setFlushHookForTests(null)
    }

    @Test
    fun `settings writes flush immediately so language survives restart`() {
        var flushCount = 0
        Settings.setFlushHookForTests { flushCount += 1 }

        Settings.setString("test.flush.key", "value")

        assertEquals(1, flushCount)
    }
}
