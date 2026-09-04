package org.jjgroup.xproxy.fuzzer.core

import org.jjgroup.xproxy.AttackHandler
import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.kits.core.XappCodecHelper
import org.jjgroup.xproxy.ui.table.OutputHandler

import org.python.util.PythonInterpreter
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap

class Scripts {
    companion object {
        private fun loadResourceText(vararg paths: String): String {
            for (path in paths) {
                val normalized = if (path.startsWith("/")) path else "/$path"
                val fromClass = Scripts::class.java.getResource(normalized)
                if (fromClass != null) {
                    return fromClass.readText()
                }
                val fromLoader = Scripts::class.java.classLoader?.getResource(normalized.removePrefix("/"))
                if (fromLoader != null) {
                    return fromLoader.readText()
                }
            }
            throw IllegalStateException("Missing required bundled resource: ${paths.joinToString(", ")}")
        }

        val SCRIPTENVIRONMENT = loadResourceText("/IntruderRuntime.py")
        val SAMPLEBURPSCRIPT = loadResourceText("/examples/Sniper.py", "/intruder/bruteforce/Sniper.py")

        val DEFAULT_RAW_REQUEST: String = listOf(
            "GET /?user={{user}} HTTP/1.1",
            "Host: ipwho.is",
            "User-Agent: curl/7.61.0",
            "Accept: */*",
            "Content-Length: 0",
        ).joinToString("\r\n")
    }
}

class Target(val req: String, val rawreq: ByteArray, val endpoint: String, val base_input: String)

data class HttpService(val host: String, val port: Int, val protocol: String)

data class SeedRequest(val request: ByteArray, val service: HttpService)

class Wordlist(val bruteforce: Bruteforce, val observed_words: ConcurrentHashMap.KeySetView<String, Boolean>, val clipboard: MutableList<String>)

class IntruderScriptContext(
    val codec: XappCodecHelper = XappCodecHelper()
)

fun evalJython(code: String, baseRequest: String, rawRequest: ByteArray, endpoint: String, host: String, base_input: String, outputHandler: OutputHandler, handler: AttackHandler, reqs: MutableList<Any>?) {
    val pyInterp = PythonInterpreter()
    try {
        Utils.out("Starting attack...")
        handler.code = code
        handler.baseRequest = baseRequest
        handler.rawRequest = rawRequest
        pyInterp.set("target", Target(baseRequest, rawRequest, endpoint, base_input))
        val savedWords = Utils.witnessedWords.savedWords
        if (savedWords.isEmpty()) {
            savedWords.add("To use this wordlist, enable 'learn observed words'")
        }
        pyInterp.set("wordlists", Wordlist(Bruteforce(), Utils.witnessedWords.savedWords, Utils.getClipboard()))
        pyInterp.set("handler", handler)
        pyInterp.set("outputHandler", outputHandler)
        pyInterp.set("table", outputHandler)
        pyInterp.set("requests", reqs)
        pyInterp.set("host", host)
        val intruderContext = IntruderScriptContext()
        pyInterp.set("ctx", intruderContext)
        pyInterp.set("codec", intruderContext.codec)
        pyInterp.exec(Scripts.SCRIPTENVIRONMENT)
        pyInterp.exec(code)
        pyInterp.exec(
            """
            if 'queue_requests' in globals():
                queue_requests(target, wordlists)
            else:
                raise NameError('Missing required entrypoint: queue_requests(target, wordlists)')
            """.trimIndent()
        )
        handler.setComplete()
        pyInterp.exec("completed(outputHandler)")
        val reportedIssues = handler.getReportedIssues()
        if (reportedIssues.isNotEmpty()) {
            Utils.out("Reported issues: ${reportedIssues.size}")
        }
    } catch (ex: Exception) {
        var error = ex
        var stackTrace = errorToStacktrace(error)
        if (stackTrace.contains("Cannot queue any more items - the attack has finished")) {
            Utils.out("Attack aborted with items waiting to be queued.")
            try {
                pyInterp.exec("completed(outputHandler)")
                handler.abort()
                return
            } catch (ex2: Exception) {
                error = ex2
                stackTrace = errorToStacktrace(ex2)
            }
        }

        val message = error.cause?.message ?: error.toString()
        handler.overrideStatus(I18n.t("fuzzer.status_python_error", "message" to message))
            Utils.out("There was an error executing your Python script. This is probably due to a flaw in your script, rather than a bug in XProxy :)")
            Utils.out("If you think it is an XProxy issue, try out this script: https://raw.githubusercontent.com/TheKingOfDuck/xproxy/master/resources/examples/debug.py")
        Utils.out("For your convenience, here's the full stack trace:")
        Utils.out(stackTrace)

        handler.abort()
    }
}

fun errorToStacktrace(ex: Exception) = StringWriter().also { ex.printStackTrace(PrintWriter(it)) }.toString()
