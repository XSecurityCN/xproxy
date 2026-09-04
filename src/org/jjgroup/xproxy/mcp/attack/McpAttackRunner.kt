package org.jjgroup.xproxy.mcp.attack

import org.jjgroup.xproxy.AttackHandler
import org.jjgroup.xproxy.Request
import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.fuzzer.ui.AttackLaunch
import org.jjgroup.xproxy.fuzzer.ui.launchMcpAttack
import org.jjgroup.xproxy.mcp.XproxyAppContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** 攻击运行状态。 */
enum class AttackState { RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * 一次攻击的运行时句柄。可见路径下,evalJython 由 fuzzer UI 侧的 [AttackLaunch] 在独立线程驱动,
 * 结果落进可见的 RequestTable;此处仅持有 handler 与结果快照源,按 attackId 暴露状态/结果/取消。
 */
class RunningAttack(
    val attackId: String,
    val baseRequestHash: String,
    val baseRequest: String,
    handler: AttackHandler,
    resultSource: () -> List<Request>,
    val startedAtMillis: Long
) {
    // 先用占位 handler / resultSource 注册(规避 evalJython 极快完成时 onCompleted 先于注册到达的竞态),
    // 待 fuzzer UI 侧 [AttackLaunch] 就绪后由 [wireToLaunch] 接到真实 handler / 结果源。
    @Volatile var handler: AttackHandler = handler
        private set
    @Volatile private var resultSource: () -> List<Request> = resultSource

    val state: AtomicReference<AttackState> = AtomicReference(AttackState.RUNNING)
    @Volatile var errorMessage: String? = null

    /** 线程安全地快照当前结果列表(RequestTable 内部已同步)。 */
    fun snapshotResults(): List<Request> = runCatching { resultSource() }.getOrDefault(emptyList())

    /** 把占位句柄接到真实的 [AttackLaunch](evalJython 已由 launchMcpAttack 在后台线程驱动)。 */
    internal fun wireToLaunch(launch: AttackLaunch) {
        handler = launch.handler
        resultSource = { launch.requestTable.getAllRquests() }
    }
}

/**
 * MCP 攻击运行器:以**可见**方式经 fuzzer UI 发起 attack(新建 fuzzer tab + 脚本载入编辑器 +
 * 弹出 RequestTable 结果窗口),按 attackId 暴露状态/结果/取消。
 *
 * 不再使用 headless 收集器:agent 发起的攻击与手工点击 Attack 走同一条 UI 路径,人工可直观看到
 * 基础请求、脚本与实时结果。要求应用 UI 就绪(MCP 运行于应用进程内,正常使用时 UI 总已装配);
 * UI 未就绪时返回错误,引导 agent 重试,而非静默 headless 执行。
 *
 * 线程安全:attacks map 与各 RunningAttack 的状态字段均为并发安全;结果读取走 RequestTable 同步快照。
 */
class McpAttackRunner {
    private val attacks = ConcurrentHashMap<String, RunningAttack>()

    fun shutdown() {
        attacks.values.forEach { runCatching { it.handler.abort() } }
    }

    /**
     * 以可见方式启动一次攻击。
     * @param rawRequest 含 `{{placeholder}}` 的基础请求模板(ISO-8859-1 文本)
     * @param scriptCode 攻击脚本源码(queue_requests/handle_response 契约)
     * @param baseInput 被占位符替换的原始选区文本(透传给脚本的 target.base_input)
     * @param protocolOverride 显式 scheme;null 则按端口推断
     * @return attackId
     * @throws IllegalStateException UI 未就绪,无法发起可见攻击
     */
    fun startAttack(
        rawRequest: String,
        scriptCode: String,
        baseInput: String = "",
        protocolOverride: String? = null
    ): String {
        // 协议/目标推断仅用于校验请求可解析;可见路径下目标由 fuzzer tab 从请求自行推断。
        RawRequestParser.parse(rawRequest, protocolOverride)
        val baseRequestHash = hashBaseRequest(rawRequest)
        val attackId = UUID.randomUUID().toString()

        val ui = XproxyAppContext.intruderUiContext()
        val store = XproxyAppContext.projectDataStore()
        if (ui == null) {
            error("Fuzzer UI is not available; MCP run_attack requires the running app UI (non-headless).")
        }

        // 先注册占位 RunningAttack,避免 evalJython 极快完成时 onCompleted 先于注册到达导致状态卡 RUNNING。
        val attack = RunningAttack(
            attackId = attackId,
            baseRequestHash = baseRequestHash,
            baseRequest = rawRequest,
            handler = AttackHandler(),
            resultSource = { emptyList() },
            startedAtMillis = System.currentTimeMillis()
        )
        attacks[attackId] = attack

        val onCompleted: (Throwable?) -> Unit = { err ->
            if (err != null) markFailed(attackId, err.message ?: err.javaClass.simpleName)
            else markCompleted(attackId)
        }
        var launchRef: AttackLaunch? = null
        val ranOnEdt = runOnEdtWithTimeout(5000) {
            launchRef = ui.launchMcpAttack(rawRequest, scriptCode, baseInput, store, onCompleted)
        }
        val launch = launchRef
        if (!ranOnEdt || launch == null) {
            // EDT 超时或 tab 创建失败:移除占位,告知 agent 重试(不静默 headless)。
            attacks.remove(attackId)
            AppLogger.warn("MCP run_attack: fuzzer UI not ready (EDT timeout or tab creation failed).")
            error("Fuzzer UI is not ready to launch a visible attack. Open the Fuzzer tab and retry.")
        }

        // 把占位句柄接到真实的 handler 与结果源上(evalJython 已由 launchMcpAttack 在后台线程驱动)。
        attack.wireToLaunch(launch)
        return attackId
    }

    private fun markCompleted(attackId: String) {
        attacks[attackId]?.state?.compareAndSet(AttackState.RUNNING, AttackState.COMPLETED)
    }

    private fun markFailed(attackId: String, message: String) {
        val a = attacks[attackId] ?: return
        if (a.state.compareAndSet(AttackState.RUNNING, AttackState.FAILED)) {
            a.errorMessage = message
        }
    }

    /** 在 EDT 上执行 [block](带超时);返回是否在超时内执行完毕。已在 EDT 则同步执行。 */
    private fun runOnEdtWithTimeout(timeoutMs: Long, block: () -> Unit): Boolean {
        if (SwingUtilities.isEventDispatchThread()) {
            runCatching { block() }
            return true
        }
        val latch = CountDownLatch(1)
        SwingUtilities.invokeLater {
            try {
                runCatching { block() }
            } finally {
                latch.countDown()
            }
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun getStatus(attackId: String): Map<String, Any?>? {
        val attack = attacks[attackId] ?: return null
        return mapOf(
            "attackId" to attackId,
            "state" to attack.state.get().name.lowercase(),
            "status" to runCatching { attack.handler.statusString() }.getOrDefault(""),
            "results" to attack.snapshotResults().size,
            "issues" to attack.handler.getReportedIssues().size,
            "startedAtMillis" to attack.startedAtMillis,
            "errorMessage" to attack.errorMessage,
            "baseRequestHash" to attack.baseRequestHash
        )
    }

    fun getResults(attackId: String, limit: Int, offset: Int): List<Map<String, Any?>>? {
        val attack = attacks[attackId] ?: return null
        val all = attack.snapshotResults()
        val slice = all.drop(offset).take(limit.coerceAtLeast(1))
        return slice.mapIndexed { index, req ->
            mapOf(
                "index" to offset + index,
                "queueId" to req.id,
                "status" to req.code,
                "length" to req.length,
                "wordcount" to req.wordcount,
                "timeMicros" to req.time,
                "label" to req.label,
                "payload" to req.words,
                "anomalyRank" to req.anomalyRank
            )
        }
    }

    fun getResultDetail(attackId: String, index: Int): Map<String, Any?>? {
        val attack = attacks[attackId] ?: return null
        val all = attack.snapshotResults()
        val req = all.getOrNull(index) ?: return null
        return mapOf(
            "index" to index,
            "queueId" to req.id,
            "status" to req.code,
            "length" to req.length,
            "wordcount" to req.wordcount,
            "timeMicros" to req.time,
            "label" to req.label,
            "payload" to req.words,
            "anomalyRank" to req.anomalyRank,
            "requestRaw" to req.getRequest(),
            "responseRaw" to req.response
        )
    }

    fun stop(attackId: String): Boolean {
        val attack = attacks[attackId] ?: return false
        runCatching { attack.handler.abort() }
        attack.state.set(AttackState.CANCELLED)
        return true
    }

    fun listAttacks(): List<Map<String, Any?>> =
        attacks.keys.toList().mapNotNull { getStatus(it) }

    companion object {
        fun hashBaseRequest(raw: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.ISO_8859_1))
                .joinToString("") { "%02x".format(it) }
    }
}
