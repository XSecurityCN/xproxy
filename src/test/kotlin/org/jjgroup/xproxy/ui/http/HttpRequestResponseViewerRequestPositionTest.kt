package org.jjgroup.xproxy.ui.http

import org.jjgroup.xproxy.kits.core.HttpViewerToolContext
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nLocaleOption
import org.jjgroup.xproxy.kits.core.XappContextMenuHub
import org.jjgroup.xproxy.kits.core.XappHttpMenuSnapshot
import org.jjgroup.xproxy.kits.core.XappMenuItemDefinition
import org.jjgroup.xproxy.kits.core.XappMenuMessagePart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.swing.JPopupMenu
import javax.swing.JSeparator

class HttpRequestResponseViewerRequestPositionTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun clearXappMenus() {
        XappContextMenuHub.clearAllForTests()
        I18n.configureForTests(temp.resolve("bundled"), temp.resolve("user"), persistedLanguage = I18nLocaleOption.SYSTEM.key)
        I18n.registerSettings()
        I18n.syncUserBundles()
        I18n.setLocaleOption(I18nLocaleOption.EN)
    }

    @Test
    fun `showRequest resets request caret to top`() {
        val viewer = HttpRequestResponseViewer()

        viewer.showRequest(requestRaw("/alpha"))
        viewer.requestRawArea.caretPosition = viewer.requestRawArea.document.length
        viewer.requestPrettyArea.caretPosition = viewer.requestPrettyArea.document.length

        viewer.showRequest(requestRaw("/beta"))

        assertEquals(0, viewer.requestRawArea.caretPosition)
        assertEquals(0, viewer.requestPrettyArea.caretPosition)
    }

    @Test
    fun `switching to original request view resets caret to top`() {
        val viewer = HttpRequestResponseViewer()
        viewer.showRequest(
            rawText = requestRaw("/modified"),
            originalRawText = requestRaw("/original")
        )

        viewer.requestRawArea.caretPosition = viewer.requestRawArea.document.length
        viewer.requestPrettyArea.caretPosition = viewer.requestPrettyArea.document.length
        viewer.requestViewMode = PayloadViewMode.ORIGINAL
        viewer.renderRequest(viewer.resolveDisplayedRequestRaw())

        assertEquals(0, viewer.requestRawArea.caretPosition)
        assertEquals(0, viewer.requestPrettyArea.caretPosition)
    }

    @Test
    fun `request context menu puts xapp in separated section without trailing separator`() {
        XappContextMenuHub.replacePluginDefinitions(
            "p1",
            listOf(
                XappMenuItemDefinition(
                    pluginId = "p1",
                    pluginName = "Plugin",
                    labelPath = listOf("Action"),
                    contexts = setOf(XappMenuMessagePart.REQUEST),
                    tools = setOf(HttpViewerToolContext.FUZZER),
                    requiresEditable = false,
                    handlerName = "handle",
                    generation = 1L
                )
            )
        )
        val viewer = HttpRequestResponseViewer(toolContext = HttpViewerToolContext.FUZZER)
        viewer.showRequest(requestRaw("/menu"))

        val menu = viewer.requestPrettyContextMenu
        assertTrue(menu != null)
        menu!!.popupMenuListeners
            .filter { it.javaClass.name.contains("HttpRequestViewerMenus") }
            .forEach { it.popupMenuWillBecomeVisible(null) }
        assertEquals(listOf("Send to Fuzzer", "Use Codec", "---", "Xapp", "---", "Copy", "Copy URL"), menu.visibleLabels())
    }

    @Test
    fun `request context menu has one separator when no xapp items match`() {
        val viewer = HttpRequestResponseViewer(toolContext = HttpViewerToolContext.FUZZER)
        viewer.showRequest(requestRaw("/menu"))

        val menu = viewer.requestPrettyContextMenu
        assertTrue(menu != null)
        menu!!.popupMenuListeners
            .filter { it.javaClass.name.contains("HttpRequestViewerMenus") }
            .forEach { it.popupMenuWillBecomeVisible(null) }

        assertEquals(listOf("Send to Fuzzer", "Use Codec", "---", "Copy", "Copy URL"), menu.visibleLabels())
    }

    private fun JPopupMenu.visibleLabels(): List<String> = (0 until componentCount).mapNotNull { idx ->
        val component = getComponent(idx)
        if (!component.isVisible) return@mapNotNull null
        if (component is JSeparator) "---" else (component as? javax.swing.JMenuItem)?.text.orEmpty()
    }

    private fun requestRaw(path: String): String =
        "GET $path HTTP/1.1\r\nHost: example.com\r\nX-Test: value\r\n\r\nbody"
}
