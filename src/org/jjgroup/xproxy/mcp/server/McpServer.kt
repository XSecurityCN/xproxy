package org.jjgroup.xproxy.mcp.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.CharsetUtil
import org.jjgroup.xproxy.Info
import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.settings.core.McpSettings
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MCP Streamable HTTP 服务端:Netty `ServerBootstrap` 监听 `bindHost:port`,单端点 `POST /mcp` 接 JSON-RPC。
 *
 * 选 Netty 而非 JDK `com.sun.net.httpserver`:Netty 已在 classpath 且被代理子系使用,
 * jpackage fatJar 打包零模块风险(`jdk.httpserver` 非默认解析模块)。
 *
 * 默认仅绑 `127.0.0.1`(loopback),降低暴露面;鉴权默认开启(bearer token)。
 * dispatcher 在独立 worker 线程池执行(避免阻塞 I/O 事件循环;工具可发请求/查 DB)。
 */
class McpServer(
    private val bindHost: String,
    private val port: Int,
    private val dispatcher: McpDispatcher
) {
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var serverChannel: Channel? = null
    @Volatile private var running = false

    private val dispatchExecutor: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "xproxy-mcp-dispatch").apply { isDaemon = true }
    }

    @Synchronized
    fun start() {
        if (running) return
        val boss = NioEventLoopGroup(1)
        val worker = NioEventLoopGroup(1)
        try {
            val bootstrap = ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline()
                            .addLast("codec", HttpServerCodec())
                            .addLast("aggregator", HttpObjectAggregator(16 * 1024 * 1024))
                            .addLast("mcp", McpHttpHandler(dispatcher, dispatchExecutor))
                    }
                })
            serverChannel = bootstrap.bind(bindHost, port).sync().channel()
            bossGroup = boss
            workerGroup = worker
            running = true
            AppLogger.info("MCP server listening on http://$bindHost:$port/mcp (auth=${McpSettings.isAuthEnabled()})")
        } catch (e: Throwable) {
            boss.shutdownGracefully()
            worker.shutdownGracefully()
            AppLogger.error("MCP server failed to bind $bindHost:$port", e)
            throw e
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { serverChannel?.close()?.sync() }
        bossGroup?.shutdownGracefully()
        workerGroup?.shutdownGracefully()
        dispatchExecutor.shutdownNow()
        AppLogger.info("MCP server stopped")
    }

    fun isRunning(): Boolean = running

    /** 实际监听端口(绑 0 时由 OS 分配);未运行返回 -1。供测试与诊断使用。 */
    fun boundPort(): Int = runCatching {
        (serverChannel?.localAddress() as? java.net.InetSocketAddress)?.port ?: -1
    }.getOrDefault(-1)
}

/**
 * HTTP 入站处理器:路由 + 鉴权 + 派发。
 * `@Sharable` 因 dispatcher/executor 与单例 handler 无 per-channel 状态,理论上每 channel new 也可,
 * 这里每个 channel new 一个(见 [McpServer] initChannel),无需 Sharable,但保留无状态写法。
 */
internal class McpHttpHandler(
    private val dispatcher: McpDispatcher,
    private val dispatchExecutor: ExecutorService
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val uri = request.uri().substringBefore('?')
        val method = request.method()

        when {
            method.name() == "GET" && (uri == "/" || uri == "/health" || uri == "/mcp") -> {
                writeJson(ctx, request, HttpResponseStatus.OK, healthBody())
            }
            method.name() == "POST" && (uri == "/mcp" || uri == "/") -> {
                if (!authOk(request)) {
                    val body = McpJson.stringify(
                        mapOf("jsonrpc" to "2.0", "error" to mapOf("code" to -32001, "message" to "Unauthorized: invalid or missing bearer token"))
                    )
                    ctx.writeAndFlush(jsonResp(request, HttpResponseStatus.UNAUTHORIZED, body, "application/json"))
                    return
                }
                handleRpc(ctx, request)
            }
            else -> {
                val body = McpJson.stringify(mapOf("error" to "Not found: $uri"))
                ctx.writeAndFlush(jsonResp(request, HttpResponseStatus.NOT_FOUND, body, "application/json"))
            }
        }
        // SimpleChannelInboundHandler 在 channelRead0 返回后自动 release request,无需手动释放。
    }

    private fun handleRpc(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val body = request.content().toString(CharsetUtil.UTF_8)
        val keepAlive = io.netty.handler.codec.http.HttpUtil.isKeepAlive(request)
        val httpVersion = request.protocolVersion()
        // 在事件循环线程同步读取 body 后即可让 SimpleChannelInboundHandler 自动 release;
        // executor 内只使用已捕获的 body/keepAlive/httpVersion,不再接触 request。
        dispatchExecutor.execute {
            val response = runCatching { dispatcher.handle(body) }
                .getOrElse { ex ->
                    AppLogger.error("MCP dispatch threw", ex)
                    McpResponse(
                        McpJson.stringify(mapOf("jsonrpc" to "2.0", "error" to mapOf("code" to -32603, "message" to (ex.message ?: "Internal error")))),
                        200
                    )
                }
            val payload = response.body ?: ""
            val status = HttpResponseStatus.valueOf(response.httpStatus)
            val nettyResp = jsonRespVersioned(httpVersion, status, payload, "application/json", keepAlive)
            // writeAndFlush 跨线程调用会被调度到该 channel 的 event loop 执行(线程安全)。
            if (keepAlive) {
                ctx.writeAndFlush(nettyResp)
            } else {
                ctx.writeAndFlush(nettyResp).addListener(io.netty.channel.ChannelFutureListener.CLOSE)
            }
        }
    }

    private fun healthBody(): String = McpJson.stringify(
        mapOf(
            "server" to "xproxy",
            "version" to Info.version,
            "protocolVersion" to MCP_PROTOCOL_VERSION,
            "authRequired" to McpSettings.isAuthEnabled(),
            "endpoint" to "/mcp"
        )
    )

    private fun authOk(request: HttpRequest): Boolean {
        // 鉴权强制开启:每次请求都必须携带有效 bearer token。
        val expected = McpSettings.ensureAuthToken()
        if (expected.isBlank()) return false
        val header = request.headers().get(HttpHeaderNames.AUTHORIZATION) ?: return false
        val token = header.removePrefix("Bearer").trim()
        if (token.isEmpty()) return false
        // 常量时间比较,防时序侧信道。
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8)
        )
    }

    private fun writeJson(ctx: ChannelHandlerContext, request: FullHttpRequest, status: HttpResponseStatus, body: String) {
        val keepAlive = io.netty.handler.codec.http.HttpUtil.isKeepAlive(request)
        val resp = jsonResp(request, status, body, "application/json", keepAlive)
        if (keepAlive) {
            ctx.writeAndFlush(resp)
        } else {
            ctx.writeAndFlush(resp).addListener(io.netty.channel.ChannelFutureListener.CLOSE)
        }
    }

    private fun jsonResp(request: FullHttpRequest, status: HttpResponseStatus, body: String, contentType: String, keepAlive: Boolean = true): FullHttpResponse =
        jsonRespVersioned(request.protocolVersion(), status, body, contentType, keepAlive)

    private fun jsonRespVersioned(version: HttpVersion, status: HttpResponseStatus, body: String, contentType: String, keepAlive: Boolean): FullHttpResponse {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val content: ByteBuf = Unpooled.wrappedBuffer(bytes)
        val resp = DefaultFullHttpResponse(version, status, content)
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
        resp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store")
        if (keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        } else {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        }
        return resp
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        AppLogger.error("MCP HTTP handler exception", cause)
        ctx.close()
    }
}
