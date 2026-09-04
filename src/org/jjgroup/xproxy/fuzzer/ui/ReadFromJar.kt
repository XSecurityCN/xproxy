package org.jjgroup.xproxy.fuzzer.ui

import java.io.File
import java.io.IOException
import java.util.jar.JarFile

class ReadFromJar {
    @Throws(IOException::class)
    fun getFiles(folder: String): List<String> {
        val jarFile = File(javaClass.protectionDomain.codeSource.location.path)
        JarFile(jarFile).use { jar ->
            return jar.entries().asSequence()
                .filter { it.name.startsWith("$folder/") }
                .map { it.name }
                .toList()
        }
    }
}
