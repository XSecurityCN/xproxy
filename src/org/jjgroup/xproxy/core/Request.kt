package org.jjgroup.xproxy
import org.jjgroup.xproxy.core.Floodgate
import org.jjgroup.xproxy.core.Utils
import java.io.ByteArrayOutputStream
import java.io.IOException

open class Request(val template: String, val words: List<String?>, val learnBoring: Int, var label: String = "") {

    var response: String? = null
    var interesting = false
    var _engine: RequestEngine? = null
    var engine: Any? = null
    var connectionID = -1
    var callback: ((Request, Boolean) -> Boolean)? = null
    var gate: Floodgate? = null
    var order = 0
    var time = 0L
    var sent = 0L
    var arrival = 0L
    var id = -1
    var pauseBefore = 0
    var pauseTime = 1000
    var pauseMarkers: List<String> = emptyList()
    var delayCompletion = 0L
    var endpointOverride: String? = null
    var autoFixContentLength = true
    var anomalyRank: Int? = null

    private val attributes: HashMap<String, Any> = HashMap()

    val code: Int get() = getAttribute("code") as Int
    val status: Int get() = code
    val length: Int get() = getAttribute("length") as Int
    val wordcount: Int get() = getAttribute("wordcount") as Int

    fun invokeCallback(isinteresting: Boolean) {
        callback?.invoke(this, isinteresting) ?: _engine!!.callback(this, isinteresting)
    }

    fun getAttribute(name: String): Any? {
        return attributes.getOrPut(name) {
            when(name) {
                "length" -> response?.length ?: 0
                "wordcount" -> (response ?: "").split(WORD_BOUNDARY_REGEX).size
                "code" -> calculateCode()
                else -> "Unknown attribute"
            }
        }
    }

    fun calculateCode(): Int {
        return response?.let { resp ->
            try {
                if (resp.startsWith(":status")) {
                    resp.split(" ", "\r", "\n", limit = 3)[1].toInt()
                } else {
                    resp.split(" ", ignoreCase = false, limit = 3)[1].toInt()
                }
            } catch (e: Exception) {
                0
            }
        } ?: 0
    }

    constructor(template: String): this(template, emptyList<String>(), 0, "")
    constructor(template: String, words: List<String?>): this(template, words, 0, "")
    constructor(template: String, words: List<String?>, learnBoring: Int): this(template, words, learnBoring, "")

    fun getPlaceholders() = PLACEHOLDER_REGEX.findAll(template).map { it.groupValues[1] }.toList()

    fun getRequest(): String {
        if (words.isEmpty()) {
            return fixContentLength(template)
        }

        val placeholders = PLACEHOLDER_REGEX.findAll(template).toList()
        if (placeholders.isEmpty()) {
            Utils.out("Bad base request - nowhere to inject payload")
            return fixContentLength(template)
        }

        if (placeholders.size != words.size) {
            Utils.out("Bad base request ${words.size} words and ${placeholders.size} placeholders")
            return fixContentLength(template)
        }

        val result = StringBuilder(template.length + (words.size * 8))
        var cursor = 0
        for (index in placeholders.indices) {
            val placeholder = placeholders[index]
            val start = placeholder.range.first
            val endExclusive = placeholder.range.last + 1
            result.append(template, cursor, start)
            result.append(words[index] ?: "")
            cursor = endExclusive
        }
        result.append(template, cursor, template.length)

        return fixContentLength(result.toString())
    }

    fun getRequestAsBytes() = fixContentLength(Utils.stringToBytes(getRequest()))

    fun getResponseAsBytes(): ByteArray? {
        if (response == null) {
            return "null".toByteArray()
        }
        return Utils.stringToBytes(response)
    }

    // todo fix performance
    fun fixContentLength(request: String) = String(fixContentLength(request.toByteArray(Charsets.ISO_8859_1)))

    fun fixContentLength(request: ByteArray): ByteArray {
        if (!autoFixContentLength) {
            return request
        }

        if (Utils.getHeaders(String(request)).contains("Content-Length: ")) {
            val start = Utils.getBodyStart(request)
            val contentLength = request.size - start
            try {
                return setHeader(request, "Content-Length", Integer.toString(contentLength))
            } catch (e: RuntimeException) {
                return request
            }

        } else {
            return request
        }
    }

    fun setHeader(request: ByteArray, header: String, value: String): ByteArray {
        val offsets = getHeaderOffsets(request, header)
        val outputStream = ByteArrayOutputStream()
        try {
            outputStream.write(request.copyOfRange(0, offsets[1]))
            outputStream.write(value.toByteArray(Charsets.ISO_8859_1))
            outputStream.write(request.copyOfRange(offsets[2], request.size))
            return outputStream.toByteArray()
        } catch (e: IOException) {
            throw RuntimeException("Request creation unexpectedly failed")
        } catch (e: NullPointerException) {
            Utils.out("header locating fail: $header")
            throw RuntimeException("Can't find the header")
        }

    }

    fun getHeaderOffsets(request: ByteArray, header: String): IntArray {
        var i = 0
        val end = request.size
        while (i < end) {
            val line_start = i

            // Make ' foo: bar' get interpreted as 'foo: bar'
            if (request[i] == ' '.code.toByte()) {
                i++
            }

            while (i < end && request[i++] != ' '.code.toByte()) {
            }
            val header_name = request.copyOfRange(line_start, i - 2)
            val headerValueStart = i
            while (i < end && request[i++] != '\n'.code.toByte()) {
            }
            if (i == end) {
                break
            }

            val header_str = String(header_name) // todo check this actually works

            if (header == header_str) {
                return intArrayOf(line_start, headerValueStart, i - 2)
            }

            if (i + 2 < end && request[i] == '\r'.code.toByte() && request[i + 1] == '\n'.code.toByte()) {
                break
            }
        }
        throw RuntimeException("Couldn't find header: '$header'")
    }

    companion object {
        val PLACEHOLDER_REGEX = Regex("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}\\}")
    }
}
