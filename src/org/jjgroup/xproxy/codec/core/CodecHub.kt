package org.jjgroup.xproxy.codec.core

object CodecHub {
    private data class Binding(
        val tabTitlesProvider: () -> List<String>,
        val sendHandler: (String, String?) -> Unit,
        val processHandler: (String, String?) -> String
    )

    private val bindings = LinkedHashMap<String, Binding>()
    private val messageListeners = LinkedHashMap<String, (String, String?) -> Unit>()

    @Synchronized
    fun register(
        ownerId: String,
        tabTitlesProvider: () -> List<String>,
        sendHandler: (String, String?) -> Unit,
        processHandler: (String, String?) -> String
    ) {
        bindings[ownerId] = Binding(tabTitlesProvider, sendHandler, processHandler)
    }

    @Synchronized
    fun unregister(ownerId: String? = null) {
        if (ownerId == null) {
            bindings.clear()
            messageListeners.clear()
        } else {
            bindings.remove(ownerId)
            messageListeners.remove(ownerId)
        }
    }

    @Synchronized
    fun registerMessageListener(ownerId: String, listener: (String, String?) -> Unit) {
        messageListeners[ownerId] = listener
    }

    @Synchronized
    fun unregisterMessageListener(ownerId: String) {
        messageListeners.remove(ownerId)
    }

    fun hasReceiver(): Boolean {
        return synchronized(this) { bindings.isNotEmpty() }
    }

    fun tabTitles(): List<String> {
        return synchronized(this) { bindings.values.lastOrNull()?.tabTitlesProvider?.invoke().orEmpty() }
    }

    fun send(text: String, targetTabTitle: String? = null) {
        if (text.isBlank()) {
            return
        }
        synchronized(this) {
            bindings.values.lastOrNull()?.sendHandler?.invoke(text, targetTabTitle)
            messageListeners.values.forEach { listener -> listener(text, targetTabTitle) }
        }
    }

    fun process(text: String, targetTabTitle: String? = null): String? {
        if (text.isBlank()) {
            return null
        }
        return synchronized(this) {
            val output = bindings.values.lastOrNull()?.processHandler?.invoke(text, targetTabTitle)
            if (output != null) {
                messageListeners.values.forEach { listener -> listener(text, targetTabTitle) }
            }
            output
        }
    }
}
