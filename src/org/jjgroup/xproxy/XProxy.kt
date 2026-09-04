package org.jjgroup.xproxy

import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.core.Scripts
import org.jjgroup.xproxy.fuzzer.core.SeedRequest
import org.jjgroup.xproxy.fuzzer.ui.IntruderFrame
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.project.core.ProjectBootstrapData
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.project.core.ProjectRegistry
import org.jjgroup.xproxy.project.ui.ProjectLauncher
import org.jjgroup.xproxy.settings.core.UiThemeSettings
import org.jjgroup.xproxy.settings.core.UiThemePalette
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JProgressBar
import javax.swing.SwingWorker
import javax.swing.SwingUtilities

data class LoadingProjectText(
    val title: String,
    val loadingData: String,
    val initializingStorage: String,
    val loadingProxyHistory: String,
    val loadingWsHistory: String,
    val loadingFuzzerTabs: String,
    val finalizing: String,
    val loadComplete: String,
    val loadFailedTitle: String,
    val loadingTabHistory: (Int, Int) -> String,
    val failedMessage: (String?) -> String
)

fun loadingProjectTextForTests(): LoadingProjectText = loadingProjectText()

private fun loadingProjectText(): LoadingProjectText = LoadingProjectText(
    title = I18n.t("project.loading.title"),
    loadingData = I18n.t("project.loading.data"),
    initializingStorage = I18n.t("project.loading.initializing_storage"),
    loadingProxyHistory = I18n.t("project.loading.proxy_history"),
    loadingWsHistory = I18n.t("project.loading.ws_history"),
    loadingFuzzerTabs = I18n.t("project.loading.fuzzer_tabs"),
    finalizing = I18n.t("project.loading.finalizing"),
    loadComplete = I18n.t("project.loading.complete"),
    loadFailedTitle = I18n.t("project.loading.failed_title"),
    loadingTabHistory = { current, total ->
        I18n.t("project.loading.tab_history", "current" to current, "total" to total)
    },
    failedMessage = { error -> I18n.t("project.loading.failed_message", "error" to error.orEmpty()) }
)

fun parseMode(args: Array<String>): String {
    var mode = "client"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-m", "--mode" -> {
                if (i + 1 < args.size) {
                    mode = args[++i]
                } else {
                    Utils.out("Missing value for ${args[i]}")
                }
            }
            "-h", "--help" -> {
                printUsage()
                System.exit(0)
            }
        }
        i++
    }
    return mode
}

fun printUsage() {
    Utils.out("""
Usage: java -jar xproxy.jar [options]

Options:
  -m, --mode <mode>   Run mode: client (default), server
  -h, --help          Show this help message

Modes:
  client    Launch graphical client (default)
  server    Start in server mode (not yet implemented)

Examples:
  java -jar xproxy.jar
  java -jar xproxy.jar -m client
  java -jar xproxy.jar -m server
""".trimIndent())
}

fun main(args: Array<String>) {
    // 最先初始化日志器:重定向 System.err(tee)以捕获后续所有 Utils.err / printStackTrace / 库错误到 ~/.xproxy/logs/。
    AppLogger.init()
    val mode = parseMode(args)

    when (mode) {
        "client" -> launchClient()
        "server" -> {
            Utils.out("Server mode is not yet implemented.")
        }
        else -> {
            Utils.out("Unknown mode: $mode")
            printUsage()
        }
    }
}

private fun launchClient() {
    SwingUtilities.invokeLater {
        UiThemeSettings.registerSettings()
        UiThemeSettings.applyCurrentTheme()
        I18n.registerSettings()
        I18n.syncUserBundles()
        I18n.reload()
        val registry = ProjectRegistry()
        val selectedProject = ProjectLauncher.selectProject(registry) ?: return@invokeLater
        val initialLoadingText = loadingProjectText()
        val loadingDialog = JDialog(null as java.awt.Frame?, initialLoadingText.title, true)
        loadingDialog.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
        loadingDialog.layout = java.awt.BorderLayout(0, 0)
        runCatching {
            val iconUrl = UiThemeSettings::class.java.classLoader.getResource("xproxy-icon.png")
            iconUrl?.let { loadingDialog.setIconImage(javax.imageio.ImageIO.read(it)) }
        }
        val loadingLabel = JLabel(initialLoadingText.loadingData)
        loadingLabel.font = loadingLabel.font.deriveFont(java.awt.Font.BOLD, 15f)
        val subtitleLabel = JLabel(selectedProject.baseName)
        subtitleLabel.foreground = UiThemePalette.mutedText
        subtitleLabel.font = subtitleLabel.font.deriveFont(java.awt.Font.PLAIN, 12f)
        val loadingBar = JProgressBar(0, 100)
        loadingBar.value = 0
        loadingBar.isStringPainted = true
        loadingBar.foreground = UiThemePalette.accent
        val headerPanel = javax.swing.JPanel(java.awt.BorderLayout(2, 2))
        headerPanel.add(loadingLabel, java.awt.BorderLayout.NORTH)
        headerPanel.add(subtitleLabel, java.awt.BorderLayout.SOUTH)
        val content = javax.swing.JPanel(java.awt.BorderLayout(8, 8))
        content.border = javax.swing.BorderFactory.createEmptyBorder(22, 24, 22, 24)
        content.add(headerPanel, java.awt.BorderLayout.NORTH)
        content.add(loadingBar, java.awt.BorderLayout.CENTER)
        loadingDialog.add(content, java.awt.BorderLayout.CENTER)
        loadingDialog.setSize(440, 168)
        loadingDialog.setLocationRelativeTo(null)

        val worker = object : SwingWorker<ProjectBootstrapData, Pair<Int, String>>() {
            override fun doInBackground(): ProjectBootstrapData {
                publish(10 to loadingProjectText().initializingStorage)
                val store = ProjectDataStore(selectedProject)

                publish(35 to loadingProjectText().loadingProxyHistory)
                val proxyHistory = store.loadHistoryMetadata()

                publish(48 to loadingProjectText().loadingWsHistory)
                val wsHistory = store.loadWsHistoryMetadata()

                publish(60 to loadingProjectText().loadingFuzzerTabs)
                val fuzzerTabs = store.loadFuzzerTabs()

                val historyMap = LinkedHashMap<String, List<org.jjgroup.xproxy.project.core.FuzzerTabHistoryRecord>>()
                if (fuzzerTabs.isNotEmpty()) {
                    fuzzerTabs.forEachIndexed { index, tab ->
                        val percent = 60 + ((index + 1) * 35 / fuzzerTabs.size)
                        publish(percent to loadingProjectText().loadingTabHistory(index + 1, fuzzerTabs.size))
                        historyMap[tab.tabId] = store.loadFuzzerTabHistory(tab.tabId)
                    }
                }

                publish(98 to loadingProjectText().finalizing)
                return ProjectBootstrapData(
                    proxyHistory = proxyHistory,
                    wsHistory = wsHistory,
                    fuzzerTabs = fuzzerTabs,
                    fuzzerTabHistories = historyMap
                )
            }

            override fun process(chunks: MutableList<Pair<Int, String>>) {
                val latest = chunks.lastOrNull() ?: return
                loadingBar.value = latest.first.coerceIn(0, 100)
                loadingLabel.text = latest.second
            }

            override fun done() {
                try {
                    val bootstrap = get()
                    loadingBar.value = 100
                    loadingLabel.text = loadingProjectText().loadComplete
                    val service = HttpService("ipwho.is", 443, "https")
                    val seed = SeedRequest(Scripts.DEFAULT_RAW_REQUEST.toByteArray(Charsets.ISO_8859_1), service)
                    IntruderFrame(seed, null, null, selectedProject, bootstrap).actionPerformed(null)
                    loadingDialog.dispose()
                } catch (ex: Exception) {
                    loadingDialog.dispose()
                    JOptionPane.showMessageDialog(
                        null,
                        loadingProjectText().failedMessage(ex.message),
                        loadingProjectText().loadFailedTitle,
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }

        worker.execute()
        loadingDialog.isVisible = true
    }
}
