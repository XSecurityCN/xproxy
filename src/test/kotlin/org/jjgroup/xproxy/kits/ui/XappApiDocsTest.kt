package org.jjgroup.xproxy.kits.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XappApiDocsTest {

    @Test
    fun `context menu docs follow api entry style`() {
        val code = apiDocEntries().single { it.name == "ContextMenus" }.code

        assertTrue(code.startsWith("def register_context_menu(api):"))
        assertTrue(code.contains("# Called once when the plugin is loaded or reloaded."))
        assertTrue(code.contains("# --- Demo ---"))
        assertTrue(code.contains("# def set_custom_header(ctx):"))
        assertTrue(code.contains("api.add_menu_item("))
        assertTrue(code.contains("class XappHttpMenuContext:"))
        assertTrue(code.contains("ctx.apply_request(raw: str = None) -> bool"))
        assertFalse(code.contains("Register BurpSuite-like HTTP context menu items."))
    }
}
