package org.jjgroup.xproxy.ui.marking

import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.project.core.ProjectRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class TrafficHighlightRegistryTest {

    @AfterEach
    fun reset() {
        TrafficHighlightRegistry.clear()
    }

    private fun newStore(): ProjectDataStore {
        val dbPath = Files.createTempFile("xproxy-highlight-registry", ".db")
        val record = ProjectRecord(
            id = "test",
            displayName = "Test",
            baseName = "test",
            createdDate = "2026-07-22",
            projectDir = dbPath.parent.toString(),
            dbPath = dbPath.toAbsolutePath().toString(),
            createdAtMillis = 0L,
            lastOpenedMillis = 0L
        )
        return ProjectDataStore(record)
    }

    @Test
    fun `set then get returns color and persists`() {
        val store = newStore()
        TrafficHighlightRegistry.bind(store)

        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, 10L, TrafficHighlight.RED)
        assertEquals(TrafficHighlight.RED, TrafficHighlightRegistry.get(TrafficHighlightRegistry.Kind.HTTP, 10L))
        assertEquals("red", store.loadAllHighlights("http")[10L])
    }

    @Test
    fun `set NONE clears highlight and removes row`() {
        val store = newStore()
        TrafficHighlightRegistry.bind(store)
        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.WS, 5L, TrafficHighlight.BLUE)
        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.WS, 5L, TrafficHighlight.NONE)

        assertEquals(TrafficHighlight.NONE, TrafficHighlightRegistry.get(TrafficHighlightRegistry.Kind.WS, 5L))
        assertFalse(store.loadAllHighlights("ws").containsKey(5L))
    }

    @Test
    fun `set ignores non-positive id`() {
        val store = newStore()
        TrafficHighlightRegistry.bind(store)
        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, 0L, TrafficHighlight.RED)
        assertEquals(TrafficHighlight.NONE, TrafficHighlightRegistry.get(TrafficHighlightRegistry.Kind.HTTP, 0L))
        assertTrue(store.loadAllHighlights("http").isEmpty())
    }

    @Test
    fun `clearMany removes batch`() {
        val store = newStore()
        TrafficHighlightRegistry.bind(store)
        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, 1L, TrafficHighlight.RED)
        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, 2L, TrafficHighlight.GREEN)
        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, 3L, TrafficHighlight.BLUE)

        TrafficHighlightRegistry.clearMany(TrafficHighlightRegistry.Kind.HTTP, setOf(1L, 3L))
        assertEquals(TrafficHighlight.NONE, TrafficHighlightRegistry.get(TrafficHighlightRegistry.Kind.HTTP, 1L))
        assertEquals(TrafficHighlight.GREEN, TrafficHighlightRegistry.get(TrafficHighlightRegistry.Kind.HTTP, 2L))
        assertEquals(TrafficHighlight.NONE, TrafficHighlightRegistry.get(TrafficHighlightRegistry.Kind.HTTP, 3L))
    }

    @Test
    fun `listener fires on set and clear`() {
        TrafficHighlightRegistry.bind(newStore())
        val fired = AtomicInteger(0)
        val unsub = TrafficHighlightRegistry.addListener { fired.incrementAndGet() }

        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, 1L, TrafficHighlight.RED)
        TrafficHighlightRegistry.clearOne(TrafficHighlightRegistry.Kind.HTTP, 1L)
        val during = fired.get()
        assertTrue(during >= 2, "listener should fire on set and clear (got $during)")

        unsub.invoke()
        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, 2L, TrafficHighlight.GREEN)
        assertEquals(during, fired.get(), "listener must not fire after unsubscribe")
    }

    @Test
    fun `bind rehydrates previously persisted highlights`() {
        val store = newStore()
        TrafficHighlightRegistry.bind(store)
        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, 42L, TrafficHighlight.PINK)
        // 模拟重启:clear 内存后重新 bind 同一 store
        TrafficHighlightRegistry.clear()
        TrafficHighlightRegistry.bind(store)

        assertEquals(TrafficHighlight.PINK, TrafficHighlightRegistry.get(TrafficHighlightRegistry.Kind.HTTP, 42L))
    }
}
