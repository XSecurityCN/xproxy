package org.jjgroup.xproxy.kits.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class KitsPanelDrawerTest {

    @Test
    fun `hide drawer does not mutate stored ratio during transient hide`() {
        val panel = KitsPanel(projectDataStore = null)
        panel.xappSplit.setSize(1000, 600)
        panel.drawerRatio = 0.58
        panel.drawerVisible = false
        panel.xappSplit.dividerSize = 8
        panel.xappSplit.setDividerLocation(420)
        panel.drawerVisible = true

        panel.hideDrawer()
        SwingUtilities.invokeAndWait { }

        assertEquals(0.58, panel.drawerRatio, 0.0001)
    }

    @Test
    fun `transient no-selection during reload does not collapse drawer`() {
        val panel = KitsPanel(projectDataStore = null)
        panel.drawerVisible = true
        panel.drawerRatio = 0.58
        panel.selectedPluginId = "demo"
        panel.xappSplit.dividerSize = 8
        panel.xappTableReloading = true

        panel.onPluginSelectionChanged()
        SwingUtilities.invokeAndWait { }

        assertTrue(panel.drawerVisible)
        assertEquals("demo", panel.selectedPluginId)
        assertEquals(0.58, panel.drawerRatio, 0.0001)
    }
}
