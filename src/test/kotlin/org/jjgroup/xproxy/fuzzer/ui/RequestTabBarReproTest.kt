package org.jjgroup.xproxy.fuzzer.ui

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.JPanel

class RequestTabBarReproTest {

    private fun paintTo(bar: RequestTabBar) {
        bar.size = Dimension(400, 200)
        bar.doLayout()
        val img = BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB)
        bar.paint(img.graphics)
    }

    @Test
    fun `paint does not NPE after build, restore and theme switch`() {
        FlatLightLaf.setup()
        val bar = RequestTabBar().apply { applyChrome() }

        // 标签条容器必须透明，透出父面板背景，避免 dark 模式下出现更深的突兀色块
        val container = JPanel()
        applyRequestTabBarContainerTheme(container)
        assertFalse(container.isOpaque, "tab bar container must be non-opaque to inherit panel background")
        // 切换主题后仍应保持透明（updateUI 重入会重新应用 theme）
        FlatDarkLaf.setup()
        FlatLaf.updateUI()
        applyRequestTabBarContainerTheme(container)
        assertFalse(container.isOpaque, "tab bar container must stay non-opaque after theme switch")

        // simulate restore: add tabs with custom header components, then rebuild
        bar.addTab("tab1", JPanel())
        bar.addTab("tab2", JPanel())
        bar.setTabComponentAt(0, JLabel("hdr1"))
        bar.setTabComponentAt(1, JLabel("hdr2"))
        bar.removeAll()
        bar.addTab("tab3", JPanel())

        // before any theme switch: custom delegate must be installed (tabPane != null)
        assertEquals(true, bar.ui is javax.swing.plaf.basic.BasicTabbedPaneUI)
        paintTo(bar)

        // switch theme -> FlatLaf.updateUI() walks the tree and calls our updateUI();
        // the custom delegate must be re-installed, not field-assigned.
        FlatDarkLaf.setup()
        FlatLaf.updateUI()
        assertEquals(true, bar.ui is javax.swing.plaf.basic.BasicTabbedPaneUI)
        paintTo(bar)

        // switch back
        FlatLightLaf.setup()
        FlatLaf.updateUI()
        assertEquals(true, bar.ui is javax.swing.plaf.basic.BasicTabbedPaneUI)
        paintTo(bar)
    }
}
