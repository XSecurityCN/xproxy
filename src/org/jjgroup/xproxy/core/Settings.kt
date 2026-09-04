package org.jjgroup.xproxy.core

import java.util.prefs.Preferences

object Settings {
    private val prefs: Preferences = Preferences.userRoot().node("xproxy")
    private var flushHook: (() -> Unit)? = null

    internal fun setFlushHookForTests(hook: (() -> Unit)?) {
        flushHook = hook
    }

    @JvmStatic
    fun registerSetting(key: String, defaultValue: String) {
        if (prefs.get(key, null) == null) {
            prefs.put(key, defaultValue)
            flush()
        }
    }

    @JvmStatic
    fun registerSetting(key: String, defaultValue: Int) {
        if (prefs.get(key, null) == null) {
            prefs.putInt(key, defaultValue)
            flush()
        }
    }

    @JvmStatic
    fun registerSetting(key: String, defaultValue: Boolean) {
        if (prefs.get(key, null) == null) {
            prefs.putBoolean(key, defaultValue)
            flush()
        }
    }

    @JvmStatic
    fun getString(key: String, default: String) = prefs.get(key, default)

    @JvmStatic
    fun setString(key: String, value: String) {
        prefs.put(key, value)
        flush()
    }

    @JvmStatic
    fun getInt(key: String, default: Int) = prefs.getInt(key, default)

    @JvmStatic
    fun setInt(key: String, value: Int) {
        prefs.putInt(key, value)
        flush()
    }

    @JvmStatic
    fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)

    @JvmStatic
    fun setBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
        flush()
    }

    private fun flush() {
        val hook = flushHook
        if (hook != null) {
            hook.invoke()
        } else {
            prefs.flush()
        }
    }
}
