package org.jjgroup.xproxy.proxy.runtime

import com.github.monkeywie.proxyee.crt.CertPool
import com.github.monkeywie.proxyee.crt.CertUtil
import com.github.monkeywie.proxyee.exception.HttpProxyExceptionHandle
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer
import com.github.monkeywie.proxyee.server.HttpProxyCACertFactory
import com.github.monkeywie.proxyee.server.HttpProxyServerConfig
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import org.jjgroup.xproxy.proxy.core.ProtocolSnifferHandler
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 proxyee 的 MITM 代理 runtime。不再使用 proxyee 的 HttpProxyServer(它的 pipeline 固定为
 * HttpServerCodec + HttpProxyServerHandler,无法在外层注入协议嗅探),改为自研统一 Netty server:
 * childHandler 最前装 [ProtocolSnifferHandler],在同一端口按首字节分流 HTTP/HTTPS/SOCKS5 三种代理。
 *
 * serverConfig 的证书/上游 SSL 配置复制自 proxyee HttpProxyServer.init()(MITM 动态签证所需),
 * 再叠加 XProxy 的定制(configureServer:maxHeaderSize、mitmMatcher 等)。
 */
class ProxyeeRuntime(
    private val running: AtomicBoolean,
    private val onStatusChanged: ((Boolean, String) -> Unit)?,
    private val createCaCertFactory: () -> HttpProxyCACertFactory,
    private val createInterceptInitializer: () -> HttpProxyInterceptInitializer,
    private val configureServer: (HttpProxyServerConfig) -> Unit = {},
    private val registerServerRef: (Channel?) -> Unit,
    private val listeningStatusProvider: () -> String
) : ProxyRuntime {

    private val lifecycleLock = Any()
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var serverChannel: Channel? = null
    private var serverConfig: HttpProxyServerConfig? = null

    override fun start(bindHost: String, bindPort: Int, handleSsl: Boolean) {
        synchronized(lifecycleLock) {
            if (running.get()) {
                onStatusChanged?.invoke(true, "Proxy already running")
                return
            }
        }

        val config = HttpProxyServerConfig().apply {
            setHandleSsl(handleSsl)
            configureServer(this)
        }
        // 复制 proxyee HttpProxyServer.init() 的证书/上游 SSL 配置
        try {
            config.proxyLoopGroup = NioEventLoopGroup(config.proxyGroupThreads)
            val clientSslCtxBuilder = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
            if (config.ciphers != null) {
                clientSslCtxBuilder.ciphers(config.ciphers)
            }
            config.clientSslCtx = clientSslCtxBuilder.build()
            if (handleSsl) {
                val caCertFactory = createCaCertFactory()
                val caCert = caCertFactory.getCACert()
                val caPriKey = caCertFactory.getCAPriKey()
                config.issuer = CertUtil.getSubject(caCert)
                config.caNotBefore = caCert.notBefore
                config.caNotAfter = caCert.notAfter
                config.caPriKey = caPriKey
                val keyPair = CertUtil.genKeyPair()
                config.serverPriKey = keyPair.private
                config.serverPubKey = keyPair.public
            }
        } catch (ex: Exception) {
            config.isHandleSsl = false
        }
        val interceptInitializer = createInterceptInitializer()
        val exceptionHandle = HttpProxyExceptionHandle()

        // HTTPS 代理(TLS-on-listener)用的监听端 SslContext:用 CA 给 "localhost" 签证书,懒加载并缓存。
        // 仅 handleSsl=true 时可用;curl -k -x https://127.0.0.1:port 走这条路径(监听端先 TLS 握手,内层 HTTP 代理)。
        val listenerSslContextLazy = lazy<SslContext?> {
            if (!config.isHandleSsl || config.serverPriKey == null) {
                null
            } else {
                try {
                    val cert = CertPool.getCert(bindPort, "localhost", config)
                    SslContextBuilder.forServer(config.serverPriKey, cert).build()
                } catch (ex: Exception) {
                    null
                }
            }
        }
        val listenerSslContextProvider: () -> SslContext? = { listenerSslContextLazy.value }

        val boss = NioEventLoopGroup(config.bossGroupThreads)
        val worker = NioEventLoopGroup(config.workerGroupThreads)
        val bootstrap = ServerBootstrap()
            .group(boss, worker)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(
                        "sniffer",
                        ProtocolSnifferHandler(
                            config,
                            interceptInitializer,
                            null,
                            exceptionHandle,
                            listenerSslContextProvider
                        )
                    )
                }
            })

        try {
            synchronized(lifecycleLock) {
                bossGroup = boss
                workerGroup = worker
                serverConfig = config
                running.set(true)
            }
            onStatusChanged?.invoke(true, listeningStatusProvider())
            val channel = bootstrap.bind(bindHost, bindPort).sync().channel()
            synchronized(lifecycleLock) {
                serverChannel = channel
                registerServerRef(channel)
            }
            // 阻塞直到 server channel 关闭(stop() 触发)
            channel.closeFuture().sync()
            synchronized(lifecycleLock) {
                serverChannel = null
                registerServerRef(null)
                running.set(false)
            }
            onStatusChanged?.invoke(false, "Proxy stopped")
        } catch (ex: Exception) {
            synchronized(lifecycleLock) {
                serverChannel = null
                registerServerRef(null)
                running.set(false)
            }
            onStatusChanged?.invoke(false, "Failed to start proxy: ${ex.message}")
            throw ex
        } finally {
            shutdownGroups()
        }
    }

    override fun stop() {
        val channel: Channel?
        synchronized(lifecycleLock) {
            channel = serverChannel
        }
        try {
            channel?.close()?.syncUninterruptibly()
        } finally {
            synchronized(lifecycleLock) {
                serverChannel = null
                registerServerRef(null)
                running.set(false)
            }
            shutdownGroups()
            onStatusChanged?.invoke(false, "Proxy stopped")
        }
    }

    override fun isRunning(): Boolean = running.get()

    private fun shutdownGroups() {
        val config: HttpProxyServerConfig?
        val boss: EventLoopGroup?
        val worker: EventLoopGroup?
        synchronized(lifecycleLock) {
            config = serverConfig
            boss = bossGroup
            worker = workerGroup
            serverConfig = null
            bossGroup = null
            workerGroup = null
        }
        config?.proxyLoopGroup?.let { if (!it.isShutdown && !it.isShuttingDown) it.shutdownGracefully() }
        boss?.let { if (!it.isShutdown && !it.isShuttingDown) it.shutdownGracefully() }
        worker?.let { if (!it.isShutdown && !it.isShuttingDown) it.shutdownGracefully() }
        CertPool.clear()
    }
}
