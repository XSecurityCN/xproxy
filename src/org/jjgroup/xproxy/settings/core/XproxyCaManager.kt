package org.jjgroup.xproxy.settings.core

import org.jjgroup.xproxy.project.core.ProjectPaths
import com.github.monkeywie.proxyee.crt.CertUtil
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

object XproxyCaManager {
    private const val SUBJECT = "CN=xproxy, O=bubi, OU=jjgroup"
    private const val SUBJECT_CN = "CN=xproxy"
    private const val SUBJECT_O = "O=bubi"
    private const val SUBJECT_OU = "OU=jjgroup"

    val caCertPath: Path = ProjectPaths.globalRoot.resolve("xproxy_ca.crt")
    private val caPrivateKeyPath: Path = ProjectPaths.globalRoot.resolve("xproxy_ca_private.der")

    @Synchronized
    fun ensureCaMaterial() {
        Files.createDirectories(ProjectPaths.globalRoot)
        if (Files.exists(caCertPath) && Files.exists(caPrivateKeyPath)) {
            try {
                val cert = CertUtil.loadCert(caCertPath.toString())
                val subject = cert.subjectX500Principal.name
                if (subject.contains(SUBJECT_CN) && subject.contains(SUBJECT_O) && subject.contains(SUBJECT_OU)) {
                    return
                }
            } catch (_: Exception) {
            }
        }

        val keyPair = CertUtil.genKeyPair()
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650))
        val caCert = CertUtil.genCACert(SUBJECT, notBefore, notAfter, keyPair)

        Files.write(caCertPath, caCert.encoded)
        Files.write(caPrivateKeyPath, keyPair.private.encoded)
    }

    @Synchronized
    fun loadCaCert(): X509Certificate {
        ensureCaMaterial()
        return CertUtil.loadCert(caCertPath.toString())
    }

    @Synchronized
    fun loadCaPrivateKey(): PrivateKey {
        ensureCaMaterial()
        return CertUtil.loadPriKey(caPrivateKeyPath.toString())
    }
}
