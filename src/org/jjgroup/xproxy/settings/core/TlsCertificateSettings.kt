package org.jjgroup.xproxy.settings.core

import org.jjgroup.xproxy.core.Settings

object TlsCertificateSettings {
    private const val KEY_LAST_EXPORT_DIR = "tls.cert.lastExportDir"

    fun registerSettings() = Settings.registerSetting(KEY_LAST_EXPORT_DIR, "")

    fun getLastExportDir(): String = Settings.getString(KEY_LAST_EXPORT_DIR, "")

    fun setLastExportDir(path: String) = Settings.setString(KEY_LAST_EXPORT_DIR, path)
}
