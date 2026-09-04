package org.jjgroup.xproxy.issue.core

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.issue.model.ReportedIssue
import java.util.concurrent.CopyOnWriteArrayList

object ScriptIssueHub {
    private val listeners = CopyOnWriteArrayList<(ReportedIssue) -> Unit>()

    fun publish(issue: ReportedIssue) {
        listeners.forEach { listener ->
            runCatching { listener(issue) }
                .onFailure { ex ->
                    Utils.out("Issue listener failed: ${ex.message}")
                }
        }
    }

    fun subscribe(listener: (ReportedIssue) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}
