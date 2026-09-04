package org.jjgroup.xproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedLruMapTest {

    @Test
    fun `evicts least recently used beyond cap`() {
        val map = boundedLruMap<Int, String>(3)
        map[1] = "a"
        map[2] = "b"
        map[3] = "c"
        // 访问 1 使其成为最近使用,2 成为最久未使用
        map.get(1)
        map[4] = "d" // 超出 cap=3,应淘汰 key=2

        assertEquals(3, map.size)
        assertTrue(map.containsKey(1))
        assertTrue(map.containsKey(3))
        assertTrue(map.containsKey(4))
        assertFalse(map.containsKey(2))
    }

    @Test
    fun `access order promotes entries and prevents eviction`() {
        val map = boundedLruMap<Int, String>(2)
        map[1] = "a"
        map[2] = "b"
        // 反复访问 1,再插入 3 应淘汰 2 而非 1
        map.get(1)
        map[3] = "c"
        assertEquals("a", map[1])
        assertNull(map[2])
        assertEquals("c", map[3])
    }

    @Test
    fun `put of existing key updates value and promotes`() {
        val map = boundedLruMap<Int, String>(2)
        map[1] = "a"
        map[2] = "b"
        map[1] = "updated" // 覆盖并提升 1
        map[3] = "c"        // 应淘汰 2
        assertEquals("updated", map[1])
        assertNull(map[2])
        assertEquals("c", map[3])
    }

    @Test
    fun `remove deletes entry`() {
        val map = boundedLruMap<Int, String>(3)
        map[1] = "a"
        map.remove(1)
        assertNull(map[1])
        assertEquals(0, map.size)
    }

    @Test
    fun `concurrent access from multiple threads does not corrupt`() {
        val map = boundedLruMap<Int, Int>(500)
        val threads = 8
        val perThread = 1000
        val latch = CountDownLatch(threads)
        val pool = (0 until threads).map { t ->
            Thread {
                try {
                    repeat(perThread) { i ->
                        val key = t * perThread + i
                        map[key] = key
                        map.get(key)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        pool.forEach { it.start() }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        // cap=500,写入总量远超 cap,但 map 大小必等于 cap(无并发损坏导致越界)
        assertEquals(500, map.size)
    }
}
