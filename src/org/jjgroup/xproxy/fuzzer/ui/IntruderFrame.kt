package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.Info
import org.jjgroup.xproxy.core.Settings
import org.jjgroup.xproxy.fuzzer.core.Scripts
import org.jjgroup.xproxy.fuzzer.core.SeedRequest
import org.jjgroup.xproxy.project.core.ProjectBootstrapData
import org.jjgroup.xproxy.project.core.ProjectRecord

import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.JComboBox
import javax.swing.JFrame

class IntruderFrame(
    private val seed: SeedRequest,
    private val fixedScript: String?,
    private val requestOverride: ByteArray?,
    private val selectedProject: ProjectRecord?,
    private val bootstrapData: ProjectBootstrapData? = null
) : ActionListener, JFrame(formatMainWindowTitle(selectedProject)) {

    private fun getDefaultScript(): String {
        fixedScript?.let { return it }
        val defaultScript = Settings.getString("defaultScript", "")
        return defaultScript.ifBlank { Scripts.SAMPLEBURPSCRIPT }
    }

    override fun actionPerformed(e: ActionEvent?) {
        buildIntruderUI(this, seed, fixedScript, requestOverride, getDefaultScript(), selectedProject, bootstrapData)
    }

    fun readScriptDirectories(codeCombo: JComboBox<Any>) {
        readScriptDirectoriesIntoCombo(codeCombo, null)
    }
}

internal fun formatMainWindowTitle(selectedProject: ProjectRecord?): String =
    "XProxy ${Info.version} - ${selectedProject?.baseName ?: "No Project"}"
