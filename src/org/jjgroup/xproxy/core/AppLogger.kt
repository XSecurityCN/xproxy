package org.jjgroup.xproxy.core

import org.jjgroup.xproxy.project.core.ProjectPaths
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 轻量内置文件日志器:把 [Utils.err]/[Utils.out] 与重定向后的 System.err 统一写入
 * `~/.xproxy/logs/xproxy.log`,便于离线分析项目错误。
 *
 * - 按大小轮转:单文件达到 [MAX_BYTES] 时滚动,保留 [MAX_FILES] 个归档(.1~.5)。
 * - 线程安全:所有写操作串行在 [lock] 上。
 * - 镜像到原始控制台(init 前保存的 System.out/err),保留终端输出。
 * - System.err 重定向为 tee:捕获 `Throwable.printStackTrace()` 与 Netty/proxyee/JDK 库错误。
 *   日志器镜像写到 [originalErr](真实 stderr)而非重定向后的 System.err,避免递归。
 * - init 失败永不抛出(日志是 best-effort),失败时退化为仅控制台。
 *
 * **未初始化(如测试)时**:[error]/[info] 退化为原始 `System.err/out.println(msg)`,
 * 与旧 [Utils] 行为完全一致,确保测试不受影响。
 */
object AppLogger {
    private const val MAX_BYTES = 10L * 1024 * 1024 // 单文件 10MB
    private const val MAX_FILES = 5 // 保留 5 个归档(.1 ~ .5)
    private const val LOG_FILE_NAME = "xproxy.log"

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private var logDir: File? = null
    private var currentFile: File? = null
    private var writer: BufferedWriter? = null
    private var bytesWritten = 0L
    private val lock = Any()

    // 重定向 System.err 前保存的原始流。日志器镜像写到这里(不写 System.err,避免 tee 递归)。
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null

    @Volatile
    private var installed = false

    /**
     * 初始化日志目录、打开日志文件、重定向 System.err(tee)。应在 `main` 最早期调用,幂等。
     * @param logDirectory 日志目录;默认 `~/.xproxy/logs/`,测试可传入临时目录。
     */
    fun init(logDirectory: Path? = null) {
        synchronized(lock) {
            if (installed) return
            originalOut = System.out
            originalErr = System.err
            try {
                val dir = (logDirectory ?: ProjectPaths.globalRoot.resolve("logs")).toFile()
                Files.createDirectories(dir.toPath())
                logDir = dir
                currentFile = File(dir, LOG_FILE_NAME)
                openWriter()
            } catch (e: Throwable) {
                safeErr("AppLogger init failed, falling back to console-only: ${e.message}")
            }
            try {
                System.setErr(TeeErrStream(originalErr ?: System.err))
            } catch (e: Throwable) {
                safeErr("AppLogger failed to redirect System.err: ${e.message}")
            }
            try {
                Runtime.getRuntime().addShutdownHook(Thread({ runCatching { shutdown() } }, "xproxy-logger-shutdown"))
            } catch (_: Throwable) {
            }
            installed = true
            val pid = runCatching { ProcessHandle.current().pid() }.getOrDefault(-1L)
            log(Level.INFO, "AppLogger initialized (dir=${logDir?.absolutePath ?: "console-only"}, pid=$pid)", null, mirrorToConsole = true)
        }
    }

    @JvmStatic
    fun error(message: String, throwable: Throwable? = null) {
        if (!installed) {
            // 未初始化(测试):退化为原始控制台,与旧 Utils 行为一致。
            System.err.println(message)
            throwable?.printStackTrace(System.err)
            return
        }
        log(Level.ERROR, message, throwable, mirrorToConsole = true)
    }

    @JvmStatic
    fun warn(message: String, throwable: Throwable? = null) {
        if (!installed) {
            System.err.println(message)
            throwable?.printStackTrace(System.err)
            return
        }
        log(Level.WARN, message, throwable, mirrorToConsole = true)
    }

    @JvmStatic
    fun info(message: String) {
        if (!installed) {
            System.out.println(message)
            return
        }
        log(Level.INFO, message, null, mirrorToConsole = false)
    }

    @JvmStatic
    fun debug(message: String) {
        if (!installed) {
            System.out.println(message)
            return
        }
        log(Level.DEBUG, message, null, mirrorToConsole = false)
    }

    /**
     * System.err tee 回调:把一行原始 stderr 文本写入文件(ERROR 级,tee 已写过控制台,不重复镜像)。
     * 以空白/"Caused by:"/"..."开头的行视为上一条异常堆栈的续行,原样写入不加时间戳头,
     * 使多行 printStackTrace 聚合成一条可读记录。
     */
    internal fun errorRaw(line: String) {
        if (line.isBlank()) return
        val continuation = line.startsWith(' ') || line.startsWith('\t') ||
            line.startsWith("Caused by:") || line.startsWith("...")
        synchronized(lock) {
            if (continuation) {
                writeLine(line)
            } else {
                val ts = LocalDateTime.now().format(timestampFormatter)
                val thread = Thread.currentThread().name
                writeLine("$ts [$thread] ${Level.ERROR.label} $line")
            }
            runCatching { writer?.flush() }
        }
    }

    private fun log(level: Level, message: String, throwable: Throwable?, mirrorToConsole: Boolean) {
        val ts = LocalDateTime.now().format(timestampFormatter)
        val thread = Thread.currentThread().name
        val line = "$ts [$thread] ${level.label} $message"
        val stack = if (throwable != null) formatStackTrace(throwable) else null
        synchronized(lock) {
            writeLine(line)
            if (stack != null) writeLine(stack)
            runCatching { writer?.flush() }
        }
        if (mirrorToConsole) {
            val out = originalOut ?: System.out
            val err = originalErr ?: System.err
            when (level) {
                Level.ERROR, Level.WARN -> {
                    err.println(line)
                    if (stack != null) err.println(stack)
                }
                Level.INFO -> out.println(line)
                Level.DEBUG -> Unit // DEBUG 仅文件
            }
        }
    }

    private fun writeLine(text: String) {
        val w = writer ?: return
        try {
            w.write(text)
            w.write("\n")
            bytesWritten += text.length.toLong() + 1
            if (bytesWritten >= MAX_BYTES) rollover()
        } catch (_: Throwable) {
            // 写失败忽略,避免日志拖垮主流程
        }
    }

    private fun rollover() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        val dir = logDir ?: return
        // 删除最旧归档,依次重命名 .4->.5 ... .1->.2, current->.1
        for (i in MAX_FILES downTo 2) {
            val from = File(dir, "$LOG_FILE_NAME.${i - 1}")
            val to = File(dir, "$LOG_FILE_NAME.$i")
            if (to.exists()) to.delete()
            if (from.exists()) from.renameTo(to)
        }
        val archive = File(dir, "$LOG_FILE_NAME.1")
        if (archive.exists()) archive.delete()
        currentFile?.renameTo(archive)
        currentFile = File(dir, LOG_FILE_NAME)
        openWriter()
        bytesWritten = 0
    }

    private fun openWriter() {
        val file = currentFile ?: return
        try {
            // append 模式续写,UTF-8;按当前文件长度初始化计数以便轮转判断
            bytesWritten = file.length()
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8))
        } catch (e: Throwable) {
            safeErr("AppLogger openWriter failed: ${e.message}")
            writer = null
        }
    }

    private fun shutdown() {
        synchronized(lock) {
            runCatching { writer?.flush(); writer?.close() }
            writer = null
        }
    }

    /** 仅供测试:刷新并关闭日志文件、还原 System.err、重置内部状态,避免污染其它测试。 */
    internal fun reset() {
        synchronized(lock) {
            runCatching { writer?.flush(); writer?.close() }
            writer = null
            originalErr?.let { runCatching { System.setErr(it) } }
            originalOut = null
            originalErr = null
            installed = false
            currentFile = null
            logDir = null
            bytesWritten = 0
        }
    }

    private fun formatStackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    private fun safeErr(message: String) {
        runCatching { originalErr?.println(message) ?: System.err.println(message) }
    }

    private enum class Level(val label: String) {
        ERROR("ERROR"),
        WARN(" WARN"),
        INFO(" INFO"),
        DEBUG("DEBUG")
    }

    /**
     * 重定向 System.err:每行同步写入日志(ERROR 级,不重复镜像控制台)+ 原始控制台。
     * 捕获 `Throwable.printStackTrace()` 与库内部错误。PrintStream 的 write 方法已同步,故 [pending] 访问串行。
     */
    private class TeeErrStream(console: PrintStream) : PrintStream(console, true, StandardCharsets.UTF_8) {
        private val pending = ByteArrayOutputStream(256)

        override fun write(b: Int) {
            super.write(b)
            pending.write(b)
            drain()
        }

        override fun write(buf: ByteArray, off: Int, len: Int) {
            super.write(buf, off, len)
            pending.write(buf, off, len)
            drain()
        }

        private fun drain() {
            val bytes = pending.toByteArray()
            var start = 0
            var i = 0
            while (i < bytes.size) {
                if (bytes[i] == '\n'.code.toByte()) {
                    val line = String(bytes, start, i - start, StandardCharsets.UTF_8).trimEnd('\r')
                    if (line.isNotEmpty()) errorRaw(line)
                    start = i + 1
                }
                i++
            }
            pending.reset()
            if (start < bytes.size) pending.write(bytes, start, bytes.size - start)
        }
    }
}
