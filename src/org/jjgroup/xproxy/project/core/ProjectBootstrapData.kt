package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry

data class ProjectBootstrapData(
    val proxyHistory: List<ProxyHistoryEntry>,
    val wsHistory: List<ProxyWsHistoryEntry>,
    val fuzzerTabs: List<FuzzerTabRecord>,
    val fuzzerTabHistories: Map<String, List<FuzzerTabHistoryRecord>>
)
