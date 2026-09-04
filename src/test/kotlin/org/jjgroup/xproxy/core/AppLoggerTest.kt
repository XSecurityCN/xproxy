package org.jjgroup.xproxy.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.nio.file.Files

class AppLoggerTest {

    @AfterEach
    fun cleanup() {
        AppLogger.reset()
    }

    @Test
    fun `writes error messages and stack traces to log file`() {
        val tmpDir = Files.createTempDirectory("xproxy-log-test")
        AppLogger.init(tmpDir)

        // Utils.err 经 System.err(tee) -> 文件;异常经 printStackTrace -> 文件(多行聚合成一条)。
        Utils.err("a plain error message")
        Utils.err("with exception", RuntimeException("boom-detail"))
        // 显式 API:file + 控制台(本测试不校验控制台)。
        AppLogger.error("direct api error", IllegalStateException("direct-detail"))
        AppLogger.reset() // flush + close 后再读取

        val logFile = File(tmpDir.toFile(), "xproxy.log")
        assertTrue(logFile.exists(), "日志文件应被创建: ${logFile.absolutePath}")
        val content = logFile.readText()
        assertTrue(content.contains(" ERROR "), "应含 ERROR 级别标记:\n$content")
        assertTrue(content.contains("a plain error message"), content)
        assertTrue(content.contains("with exception"), content)
        assertTrue(content.contains("RuntimeException") && content.contains("boom-detail"), content)
        assertTrue(content.contains("direct api error"), content)
        assertTrue(content.contains("IllegalStateException") && content.contains("direct-detail"), content)
    }

    @Test
    fun `uninitialized logger falls back to raw console without writing files`() {
        // 未 init 时(如测试环境)Utils.err 退化为原始 System.err.println,不抛异常、不创建日志文件。
        val tmpDir = Files.createTempDirectory("xproxy-log-test2")
        Utils.err("pre-init error should not crash")
        AppLogger.error("pre-init direct", null)
        assertTrue(!File(tmpDir.toFile(), "xproxy.log").exists(), "未初始化时不应写日志文件")
    }
}
