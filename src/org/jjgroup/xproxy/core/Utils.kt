package org.jjgroup.xproxy.core

import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets


object Utils {
    @JvmField
    var unloaded = false

    @JvmField
    val witnessedWords = WordRecorder()

    @JvmStatic
    fun setIntruderFrameSize(size: Dimension?) {
        if (size == null) {
            return
        }
        Settings.setInt("intruderHeight", size.height)
        Settings.setInt("intruderWidth", size.width)
    }

    @JvmStatic
    fun bytesToString(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @JvmStatic
    fun getClipboard(): ArrayList<String> {
        var clipboard = ""
        try {
            clipboard = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
        } catch (_: Exception) {
            err("failed to read from clipboard")
        }
        return ArrayList(clipboard.split("\\r?\\n".toRegex()))
    }

    @JvmStatic
    fun setClipboard(contents: String) {
        val selection = StringSelection(contents)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }

    @JvmStatic
    fun getIntruderFrameSize(): Dimension {
        return try {
            var height = Settings.getInt("intruderHeight", 2400)
            if (height < 2400) {
                height = 2400
            }
            height -= 20
            val width = Settings.getInt("intruderWidth", 1280)
            Dimension(width, height)
        } catch (_: Exception) {
            Dimension(1280, 2400)
        }
    }

    @JvmStatic
    fun out(message: String) = println(message)

    @JvmStatic
    fun err(message: String) = System.err.println(message)

    /** 记录错误及异常堆栈。经 System.err(AppLogger tee 重定向后)同时写入日志文件与控制台。 */
    @JvmStatic
    fun err(message: String, throwable: Throwable) {
        System.err.println(message)
        throwable.printStackTrace(System.err)
    }

    @JvmStatic
    fun getHeaders(request: String): String {
        val bodyStart = request.indexOf("\r\n\r\n")
        if (bodyStart < 0) {
            return request
        }
        return request.substring(0, bodyStart)
    }

    @JvmStatic
    fun isHttp2(requestBytes: ByteArray?): Boolean {
        if (requestBytes == null || requestBytes.isEmpty()) {
            return false
        }
        val text = bytesToString(requestBytes)
        return text.startsWith("PRI * HTTP/2.0") || text.contains("HTTP/2")
    }

    fun getBodyBytes(response: ByteArray?): ByteArray? {
        return response?.let {
            val bodyStart = getBodyStart(it)
            it.copyOfRange(bodyStart, it.size)
        }
    }

    @JvmStatic
    fun getBodyStart(response: ByteArray) = indexOf(response, "\r\n\r\n".toByteArray()) + 4

    @JvmStatic
    fun indexOf(outerArray: ByteArray, smallerArray: ByteArray): Int {
        for (i in 0..outerArray.size - smallerArray.size) {
            var found = true
            for (j in smallerArray.indices) {
                if (outerArray[i + j] != smallerArray[j]) {
                    found = false
                    break
                }
            }
            if (found) {
                return i
            }
        }
        return -1
    }

    @JvmStatic
    fun indexOf(data: ByteArray?, pattern: ByteArray?, caseSensitive: Boolean, from: Int, to: Int): Int {
        if (data == null || pattern == null) {
            return -1
        }
        if (pattern.isEmpty()) {
            return from
        }
        val start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(data.size) - pattern.size
        for (i in start..end) {
            var found = true
            for (j in pattern.indices) {
                var b1 = data[i + j]
                var b2 = pattern[j]
                if (!caseSensitive) {
                    b1 = toLowerAscii(b1)
                    b2 = toLowerAscii(b2)
                }
                if (b1 != b2) {
                    found = false
                    break
                }
            }
            if (found) {
                return i
            }
        }
        return -1
    }

    private fun toLowerAscii(value: Byte): Byte {
        return if (value in 'A'.code.toByte()..'Z'.code.toByte()) {
            (value + 32).toByte()
        } else {
            value
        }
    }

    @JvmStatic
    fun stringToBytes(string: String?): ByteArray {
        return try {
            string!!.toByteArray(StandardCharsets.ISO_8859_1)
        } catch (_: Exception) {
            throw RuntimeException("failed to convert string to bytes")
        }
    }
}
