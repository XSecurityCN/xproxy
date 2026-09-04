package org.jjgroup.xproxy.core

import java.util.concurrent.ConcurrentHashMap

class WordRecorder {
    val savedWords: ConcurrentHashMap.KeySetView<String, Boolean> = ConcurrentHashMap.newKeySet()
}
