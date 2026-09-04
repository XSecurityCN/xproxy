package org.jjgroup.xproxy.kits.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.kits.model.StoreXapp
import org.jjgroup.xproxy.project.core.ProjectPaths
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class XappStoreClient {

    companion object {
        const val REMOTE_BASE_URL = "https://raw.githubusercontent.com/TheKingOfDuck/xapp-store/main"
        const val LOCAL_BASE_URL = "http://127.0.0.1:9528"
        private val LOCAL_STORE_DIR: Path = Path.of("xapp-store")
        private val CACHE_PATH: Path = ProjectPaths.globalRoot.resolve("xapp-store-index.json")
        private val XAPP_ROOT: Path = ProjectPaths.globalRoot.resolve("xapp")
    }

    private val useLocal = Files.isDirectory(LOCAL_STORE_DIR)
    val baseUrl: String = if (useLocal) LOCAL_BASE_URL else REMOTE_BASE_URL

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val objectMapper = ObjectMapper()

    init {
        if (useLocal) {
            Utils.out("XappStore: local dev mode enabled (xapp-store/ detected), using $LOCAL_BASE_URL")
        }
    }

    fun fetchIndex(): List<StoreXapp> {
        val indexUrl = "$baseUrl/index.json"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(indexUrl))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("HTTP ${response.statusCode()}")
        }
        val body = response.body()
        Files.createDirectories(CACHE_PATH.parent)
        Files.writeString(CACHE_PATH, body, Charsets.UTF_8)
        return parseIndex(body)
    }

    fun loadCachedIndex(): List<StoreXapp> {
        if (!Files.exists(CACHE_PATH)) return emptyList()
        return try {
            parseIndex(Files.readString(CACHE_PATH, Charsets.UTF_8))
        } catch (e: Exception) {
            Utils.err("Failed to read cached index: ${e.message}")
            emptyList()
        }
    }

    fun downloadXapp(xapp: StoreXapp) {
        val targetDir = XAPP_ROOT.resolve(xapp.id)
        Files.createDirectories(targetDir)
        try {
            for (filename in xapp.files) {
                val url = "$baseUrl/xapps/${xapp.id}/$filename"
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() != 200) {
                    throw RuntimeException("Failed to download $filename: HTTP ${response.statusCode()}")
                }
                val filePath = targetDir.resolve(filename)
                Files.createDirectories(filePath.parent)
                Files.write(filePath, response.body())
            }
        } catch (e: Exception) {
            deleteDirectoryRecursively(targetDir)
            throw e
        }
    }

    fun isInstalled(xappId: String): Boolean {
        return Files.exists(XAPP_ROOT.resolve(xappId).resolve("xapp.json"))
    }

    fun getInstalledVersion(xappId: String): String? {
        val manifestPath = XAPP_ROOT.resolve(xappId).resolve("xapp.json")
        if (!Files.exists(manifestPath)) return null
        return try {
            val root = objectMapper.readTree(Files.newBufferedReader(manifestPath, Charsets.UTF_8))
            root.path("version").asText(null)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseIndex(json: String): List<StoreXapp> {
        val root = objectMapper.readTree(json)
        val xapps = root.path("xapps")
        if (!xapps.isArray) return emptyList()
        return xapps.map { node ->
            StoreXapp(
                id = node.path("id").asText(""),
                name = node.path("name").asText(""),
                version = node.path("version").asText(""),
                author = node.path("author").asText(""),
                description = node.path("description").asText(""),
                files = node.path("files").map { it.asText() }
            )
        }.filter { it.id.isNotBlank() }
    }

    private fun deleteDirectoryRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .forEach { runCatching { Files.delete(it) } }
    }
}
