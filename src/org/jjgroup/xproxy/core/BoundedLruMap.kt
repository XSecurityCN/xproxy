package org.jjgroup.xproxy.core

import java.util.Collections
import java.util.LinkedHashMap

/**
 * 有界 LRU 缓存:基于 `LinkedHashMap(access-order=true)` + `removeEldestEntry`,外层套 `synchronizedMap` 保证线程安全。
 *
 * 替换原本无界的 `ConcurrentHashMap` 历史详情 / WS 载荷缓存——后者会把每条 ≤256KB 的流量完整 request/response body
 * 永久驻留内存,随流量线性增长(13 万条 ≈ 数 GB),是"内存随流量持续升高直至 OOM"的根因。
 *
 * - 读 `get` 会更新访问顺序,使最近查看的条目驻留;超出 [maxEntries] 时淘汰最久未访问者。
 * - 所有操作经 `synchronizedMap` 串行,适用于"偶发读(行选中/关键词过滤)+ 每请求一次写"的中低争用场景;
 *   调用方仅使用 `[]` / `get` / `remove`,无遍历,故粗粒度锁不构成瓶颈。
 * - 缓存未命中时由调用方回退到 DB 按需加载(`loadHistoryById`),与既有冷缓存路径一致。
 */
internal class BoundedLruMap<K, V>(
    private val maxEntries: Int,
    initialCapacity: Int = 16,
    loadFactor: Float = 0.75f,
) : LinkedHashMap<K, V>(initialCapacity, loadFactor, /*accessOrder*/ true) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive: $maxEntries" }
    }

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
}

/** 构造一个线程安全的有界 LRU `MutableMap`(每个操作 synchronized)。 */
internal fun <K, V> boundedLruMap(maxEntries: Int, initialCapacity: Int = 16): MutableMap<K, V> =
    Collections.synchronizedMap(BoundedLruMap<K, V>(maxEntries, initialCapacity.coerceAtLeast(16)))
