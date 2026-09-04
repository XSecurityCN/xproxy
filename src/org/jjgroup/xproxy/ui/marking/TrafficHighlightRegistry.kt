package org.jjgroup.xproxy.ui.marking

import org.jjgroup.xproxy.project.core.ProjectDataStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 流量高亮运行时注册表(跨面板共享单例,与 `ScriptIssueHub`/`XappContextMenuHub` 同构)。
 *
 * - **共享**:`ProxyPanel` 历史表、WS 历史表与 `TargetPanel` 内容表展示同一份内存映射,
 *   故一处标记两处可见(代理历史 id 与 Target 内容表同源)。
 * - **持久化**:bind 时从项目 DB hydrate 全量;set/clear 经 [ProjectDataStore] 落库。
 * - **线程安全**:可被 xapp scan 线程与 EDT 菜单并发调用;repaint 经监听器在 EDT 触发
 *   (不用 Swing Timer,符合既有去抖约束)。
 *
 * 生命周期镜像 `XproxyAppContext`:项目加载后 `bind(store)`,退出/切换项目时 `clear()`。
 */
object TrafficHighlightRegistry {

    enum class Kind(val storeKey: String) { HTTP("http"), WS("ws") }

    @Volatile
    private var store: ProjectDataStore? = null

    private val httpHighlights = ConcurrentHashMap<Long, TrafficHighlight>()
    private val wsHighlights = ConcurrentHashMap<Long, TrafficHighlight>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun mapFor(kind: Kind): ConcurrentHashMap<Long, TrafficHighlight> =
        if (kind == Kind.HTTP) httpHighlights else wsHighlights

    /** 项目加载后调用:绑定持久层并 hydrate 全量高亮。重复 bind 会先重置再重新载入。 */
    fun bind(store: ProjectDataStore?) {
        this.store = store
        httpHighlights.clear()
        wsHighlights.clear()
        if (store != null) {
            store.loadAllHighlights(Kind.HTTP.storeKey).forEach { (id, color) ->
                httpHighlights[id] = TrafficHighlight.parse(color)
            }
            store.loadAllHighlights(Kind.WS.storeKey).forEach { (id, color) ->
                wsHighlights[id] = TrafficHighlight.parse(color)
            }
        }
        notifyListeners()
    }

    /** 退出/项目切换时调用:清空内存与监听器(store 引用置空)。 */
    fun clear() {
        store = null
        httpHighlights.clear()
        wsHighlights.clear()
        listeners.clear()
    }

    fun get(kind: Kind, id: Long): TrafficHighlight =
        mapFor(kind)[id] ?: TrafficHighlight.NONE

    /** 设置高亮(NONE 等价于清除)。id<=0 忽略(xapp rewrite 路径无有效 entry id)。 */
    fun set(kind: Kind, id: Long, color: TrafficHighlight) {
        if (id <= 0L) return
        val map = mapFor(kind)
        if (color == TrafficHighlight.NONE) {
            if (map.remove(id) == null) return
            store?.deleteHighlight(kind.storeKey, id)
        } else {
            map[id] = color
            store?.upsertHighlight(kind.storeKey, id, color.apiName)
        }
        notifyListeners()
    }

    fun clearOne(kind: Kind, id: Long) {
        set(kind, id, TrafficHighlight.NONE)
    }

    /** 批量清除(历史删除时级联)。 */
    fun clearMany(kind: Kind, ids: Set<Long>) {
        if (ids.isEmpty()) return
        val map = mapFor(kind)
        var changed = false
        ids.forEach { if (map.remove(it) != null) changed = true }
        if (changed) {
            store?.deleteHighlightsByIds(kind.storeKey, ids)
            notifyListeners()
        }
    }

    /** 注册变化监听(返回 unsubscribe)。监听器应自行在 EDT repaint 表格。 */
    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }
}
