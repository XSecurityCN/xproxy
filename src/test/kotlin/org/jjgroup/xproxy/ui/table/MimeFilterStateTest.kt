package org.jjgroup.xproxy.ui.table

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 锁定 [MimeFilterState] 的默认选中语义:默认选中的 MIME 是 [MimeFilterState.defaultTypes]
 * (text/json/xml/html/sse,5 个),而非 [MimeFilterState.types] 全部(10 个)。
 *
 * 此前"恢复默认"按钮误用 types()(全部)而非 defaultTypes(),导致点击后变成"显示全部"而非真正的默认。
 * 本测试固化默认集合,防止再次混淆。
 */
class MimeFilterStateTest {

    @Test
    fun `default selected types are the defaultTypes subset, not all types`() {
        val state = MimeFilterState()
        val expectedDefault = setOf("text", "json", "xml", "html", "sse")
        assertEquals(expectedDefault, state.defaultTypes())
        assertEquals(state.defaultTypes(), state.selectedTypes())
        // 默认是 allTypes 的真子集(script/css/image/bin/other 默认未选)。
        assertTrue(state.types().containsAll(state.defaultTypes()))
        assertFalse(state.selectedTypes().containsAll(state.types()))
    }

    @Test
    fun `fresh state has default mime selection, all status buckets, no keyword, scopes on, AND`() {
        val state = MimeFilterState()
        assertEquals(state.defaultTypes(), state.selectedTypes())
        assertEquals(setOf("1xx", "2xx", "3xx", "4xx", "5xx"), state.selectedStatusBuckets().toSet())
        assertEquals("", state.keyword())
        assertFalse(state.keywordRegex())
        assertFalse(state.keywordCaseSensitive())
        assertTrue(state.keywordScopeRequestHeader())
        assertTrue(state.keywordScopeRequestBody())
        assertTrue(state.keywordScopeResponseHeader())
        assertTrue(state.keywordScopeResponseBody())
        assertEquals(MimeFilterState.LogicMode.AND, state.logicMode())
    }

    @Test
    fun `resetDefault restores the defaultTypes selection`() {
        val state = MimeFilterState()
        state.showAll() // 选中全部(含 script/css/image/bin/other)
        assertEquals(state.types().toSet(), state.selectedTypes())
        state.resetDefault()
        assertEquals(state.defaultTypes(), state.selectedTypes())
    }

    @Test
    fun `showAll selects every type, distinct from default`() {
        val state = MimeFilterState()
        state.showAll()
        assertEquals(state.types().toSet(), state.selectedTypes())
        // showAll 与默认不同:默认不含 script/css/image/bin/other。
        assertFalse(state.defaultTypes().containsAll(state.types()))
    }
}
