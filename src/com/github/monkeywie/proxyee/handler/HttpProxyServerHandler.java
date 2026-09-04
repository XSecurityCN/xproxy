package com.github.monkeywie.proxyee.handler;

import com.github.monkeywie.proxyee.crt.CertPool;
import com.github.monkeywie.proxyee.exception.HttpProxyExceptionHandle;
import com.github.monkeywie.proxyee.intercept.HttpProxyIntercept;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline;
import com.github.monkeywie.proxyee.proxy.ProxyConfig;
import com.github.monkeywie.proxyee.proxy.ProxyHandleFactory;
import com.github.monkeywie.proxyee.server.HttpProxyServer;
import com.github.monkeywie.proxyee.server.HttpProxyServerConfig;
import com.github.monkeywie.proxyee.server.auth.HttpAuthContext;
import com.github.monkeywie.proxyee.server.auth.HttpProxyAuthenticationProvider;
import com.github.monkeywie.proxyee.server.auth.model.HttpToken;
import com.github.monkeywie.proxyee.util.ProtoUtil;
import com.github.monkeywie.proxyee.util.ProtoUtil.RequestProto;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.jjgroup.xproxy.proxy.runtime.h2bridge.H2NettyResponseBridge;
import org.jjgroup.xproxy.proxy.portal.ProxyPortal;
import org.jjgroup.xproxy.proxy.portal.ProxyPortalResult;
import org.jjgroup.xproxy.core.Utils;

import java.net.InetSocketAddress;
import java.net.URL;
import javax.net.ssl.SSLHandshakeException;
import java.util.LinkedList;
import java.util.List;

public class HttpProxyServerHandler extends ChannelInboundHandlerAdapter {
    private static final AttributeKey<Boolean> ATTR_H2_STREAM = AttributeKey.valueOf("xproxy.h2.stream");
    // 下游 H2 解码器(HpackDecoder)的头列表强制校验上限。Netty 4.1.114 默认 8192,会拒掉抖音这类
    // 大 Cookie 站点的 H2 请求(浏览器表现为 ERR_HTTP2_PROTOCOL_ERROR)。与 ProxyController
    // 的 DOWNSTREAM_MAX_HEADER_SIZE 对齐。通过 initialSettings() 配置,见下方 h2FrameCodec 构造。
    private static final int MAX_DOWNSTREAM_HEADER_LIST_SIZE = 64 * 1024;
    // H2 forward 的上游收发(TLS+H2、H2->H1 fallback、上游代理 socket)是阻塞调用,不能跑在 Netty 事件循环上
    // (否则 H2 多路复用的所有流 + 同 loop 其它连接全部串行化,抖音二维码等依赖并发 XHR 的功能超时/失败)。
    // offload 到有界 worker 线程池,完成后切回 client channel eventLoop 触发 afterResponse(见 forwardH2Offloop)。
    // AbortPolicy:池+队列饱和时抛 RejectedExecutionException,handleH2Forward 同步段 catch 后回 502,不阻塞事件循环。
    private static final java.util.concurrent.ThreadPoolExecutor H2_FORWARD_EXECUTOR =
        new java.util.concurrent.ThreadPoolExecutor(
            32, 32, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>(2048),
            new DefaultThreadFactory("xproxy-h2-forward", true),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    // H2 流子通道没有 "serverHandle"(已被移除);把当前 serverHandle 实例挂到属性上,
    // 供 HttpProxyInitializer(HttpProxyClientHandler 的上游初始化)与 HttpProxyClientHandler.resolveServerHandler
    // 取得 serverConfig / interceptPipeline,从而支持 H2 流降级到 H1 上游(如 SSE)。
    public static final AttributeKey<HttpProxyServerHandler> SERVER_HANDLE_KEY = AttributeKey.valueOf("xproxy.h2.serverHandle");

    private ChannelFuture cf;
    private RequestProto requestProto;
    private int status = 0;
    private final HttpProxyServerConfig serverConfig;
    private final ProxyConfig proxyConfig;
    private final HttpProxyInterceptInitializer interceptInitializer;
    private HttpProxyInterceptPipeline interceptPipeline;
    private final HttpProxyExceptionHandle exceptionHandle;
    private List requestList;
    private boolean isConnect;
    private byte[] httpTagBuf;
    private final H2NettyResponseBridge h2Bridge = new H2NettyResponseBridge();

    public HttpProxyServerHandler(HttpProxyServerConfig serverConfig, HttpProxyInterceptInitializer interceptInitializer, ProxyConfig proxyConfig, HttpProxyExceptionHandle exceptionHandle) {
        this.serverConfig = serverConfig;
        this.proxyConfig = proxyConfig;
        this.interceptInitializer = interceptInitializer;
        this.exceptionHandle = exceptionHandle;
    }

    protected ChannelFuture getChannelFuture() { return cf; }
    protected void setChannelFuture(ChannelFuture cf) { this.cf = cf; }
    public HttpProxyExceptionHandle getExceptionHandle() { return exceptionHandle; }
    public HttpProxyInterceptInitializer getInterceptInitializer() { return interceptInitializer; }
    protected boolean getIsConnect() { return isConnect; }
    protected void setIsConnect(boolean isConnect) { this.isConnect = isConnect; }
    protected List getRequestList() { return requestList; }
    protected void setRequestList(List requestList) { this.requestList = requestList; }
    public ProxyConfig getProxyConfig() { return proxyConfig; }
    protected RequestProto getRequestProto() { return requestProto; }
    protected void setRequestProto(RequestProto requestProto) { this.requestProto = requestProto; }
    public HttpProxyServerConfig getServerConfig() { return serverConfig; }
    protected int getStatus() { return status; }
    protected void setStatus(int status) { this.status = status; }
    public HttpProxyInterceptPipeline getInterceptPipeline() { return interceptPipeline; }
    protected void setInterceptPipeline(HttpProxyInterceptPipeline interceptPipeline) { this.interceptPipeline = interceptPipeline; }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            DecoderResult result = request.decoderResult();
            Throwable cause = result.cause();

            if (cause instanceof DecoderException) {
                setStatus(2);
                HttpResponse response = HttpProxyServerHandlerSupport.buildDecoderErrorResponse(cause);
                ctx.writeAndFlush(response);
                ReferenceCountUtil.release(msg);
                return;
            }

            if (getStatus() == 0) {
                setRequestProto(ProtoUtil.getRequestProto(request));
                if (getRequestProto() == null) {
                    ctx.channel().close();
                    return;
                }
                if (getServerConfig().getHttpProxyAcceptHandler() != null
                        && !getServerConfig().getHttpProxyAcceptHandler().onAccept(request, ctx.channel())) {
                    setStatus(2);
                    ctx.channel().close();
                    return;
                }
                if (!authenticate(ctx, request)) {
                    setStatus(2);
                    ctx.channel().close();
                    return;
                }
                setStatus(1);
                if (HttpMethod.CONNECT.name().equalsIgnoreCase(request.method().name())) {
                    setStatus(2);
                    HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpProxyServer.SUCCESS);
                    ctx.writeAndFlush(response);
                    ctx.channel().pipeline().remove("httpCodec");
                    ReferenceCountUtil.release(msg);
                    return;
                }
            }

            FullHttpResponse portalResponse = HttpProxyServerHandlerSupport.buildPortalResponse(
                    request.method().name(),
                    request.uri(),
                    request.headers().get(HttpHeaderNames.HOST),
                    HttpProxyServerHandlerSupport.localHost(ctx.channel()),
                    HttpProxyServerHandlerSupport.localPort(ctx.channel())
            );
            if (portalResponse != null) {
                setStatus(2);
                ctx.writeAndFlush(portalResponse);
                ReferenceCountUtil.release(msg);
                return;
            }

            setInterceptPipeline(buildPipeline());
            getInterceptPipeline().setRequestProto(getRequestProto().copy());
            if (request.uri().indexOf("/") != 0) {
                URL url = new URL(request.uri());
                request.setUri(url.getFile());
            }
            getInterceptPipeline().beforeRequest(ctx.channel(), request);
            ReferenceCountUtil.release(msg);
        } else if (msg instanceof HttpContent) {
            if (getStatus() != 2) {
                getInterceptPipeline().beforeRequest(ctx.channel(), (HttpContent) msg);
            } else {
                ReferenceCountUtil.release(msg);
                setStatus(1);
            }
        } else {
            ByteBuf byteBuf = (ByteBuf) msg;
            if (getServerConfig().isHandleSsl() && byteBuf.getByte(0) == 22 && doMitm()) {
                getRequestProto().setSsl(true);
                int port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
                SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
                SslContext sslCtx = SslContextBuilder
                        .forServer(getServerConfig().getServerPriKey(), CertPool.getCert(port, getRequestProto().getHost(), getServerConfig()))
                        .sslProvider(provider)
                        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                        .applicationProtocolConfig(HttpProxyServerHandlerSupport.buildDownstreamApplicationProtocolConfig())
                        .build();
                ctx.pipeline().addFirst("alpn", new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                    @Override
                    protected void configurePipeline(ChannelHandlerContext c, String protocol) {
                        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                            Utils.out("[xproxy][alpn] negotiated protocol=h2 host=" + getRequestProto().getHost());
                            c.pipeline().addBefore("serverHandle", "h2FrameCodec", Http2FrameCodecBuilder.forServer()
                                    .initialSettings(Http2Settings.defaultSettings().maxHeaderListSize(MAX_DOWNSTREAM_HEADER_LIST_SIZE))
                                    .build());
                            c.pipeline().addBefore("serverHandle", "h2Multiplex", new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                @Override
                                protected void initChannel(Channel ch) {
                                    ch.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true));
                                    ch.pipeline().addLast(new HttpObjectAggregator(getServerConfig().getMaxChunkSize() * 8));
                                    ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext streamCtx, FullHttpRequest req) throws Exception {
                                            FullHttpRequest retained = HttpProxyServerHandlerSupport.copyH2RequestForMutation(req);
                                            try {
                                                processH2Request(streamCtx.channel(), retained);
                                            } finally {
                                                ReferenceCountUtil.release(retained);
                                            }
                                        }

                                        @Override
                                        public void exceptionCaught(ChannelHandlerContext streamCtx, Throwable cause) {
                                            Utils.err("[xproxy][h2] stream handler error: " + cause.getMessage());
                                            streamCtx.close();
                                        }
                                    });
                                }
                            }));
                            c.pipeline().remove("serverHandle");
                            return;
                        }
                        Utils.out("[xproxy][alpn] negotiated protocol=" + protocol + " host=" + getRequestProto().getHost());
                        if (c.pipeline().get("httpCodec") == null) {
                            c.pipeline().addBefore("serverHandle", "httpCodec", new HttpServerCodec(
                                    getServerConfig().getMaxInitialLineLength(),
                                    getServerConfig().getMaxHeaderSize(),
                                    getServerConfig().getMaxChunkSize()));
                        }
                    }
                });
                ctx.pipeline().addFirst("sslHandle", sslCtx.newHandler(ctx.alloc()));
                ctx.pipeline().fireChannelRead(msg);
                return;
            }

            if (byteBuf.readableBytes() < 8) {
                httpTagBuf = new byte[byteBuf.readableBytes()];
                byteBuf.readBytes(httpTagBuf);
                ReferenceCountUtil.release(msg);
                return;
            }
            if (httpTagBuf != null) {
                byte[] tmp = new byte[byteBuf.readableBytes()];
                byteBuf.readBytes(tmp);
                byteBuf.writeBytes(httpTagBuf);
                byteBuf.writeBytes(tmp);
                httpTagBuf = null;
            }

            if (isHttp(byteBuf)) {
                if (ctx.pipeline().get("sslHandle") != null) {
                    ctx.pipeline().remove("sslHandle");
                }
                if (ctx.pipeline().get("alpn") != null) {
                    ctx.pipeline().remove("alpn");
                }
                if (ctx.pipeline().get("httpCodec") == null) {
                    ctx.pipeline().addBefore("serverHandle", "httpCodec", new HttpServerCodec(
                            getServerConfig().getMaxInitialLineLength(),
                            getServerConfig().getMaxHeaderSize(),
                            getServerConfig().getMaxChunkSize()));
                }
                ctx.fireChannelRead(msg);
                return;
            }
            handleProxyData(ctx.channel(), msg, false);
        }
    }

    private void processH2Request(Channel streamChannel, FullHttpRequest req) throws Exception {
        req.setProtocolVersion(HttpVersion.valueOf("HTTP/2.0"));
        stripInternalHttp2Headers(req.headers());
        if (getRequestProto() == null) {
            String host = req.headers().get(HttpHeaderNames.HOST);
            if (host == null) {
                host = req.headers().get(":authority");
            }
            if (host != null) {
                String h = host;
                int p = 443;
                int idx = host.lastIndexOf(':');
                if (idx > 0 && idx < host.length() - 1) {
                    h = host.substring(0, idx);
                    try {
                        p = Integer.parseInt(host.substring(idx + 1));
                    } catch (Exception ignored) {
                    }
                }
                setRequestProto(new RequestProto(h, p, true));
            }
        }
        if (getRequestProto() == null) {
            return;
        }
        setInterceptPipeline(buildPipeline());
        getInterceptPipeline().setRequestProto(getRequestProto().copy());
        streamChannel.attr(ATTR_H2_STREAM).set(Boolean.TRUE);
        streamChannel.attr(SERVER_HANDLE_KEY).set(this);
        getInterceptPipeline().beforeRequest(streamChannel, (HttpRequest) req);
    }

    private boolean isH2StreamChannel(Channel channel) {
        Boolean flag = channel.attr(ATTR_H2_STREAM).get();
        return Boolean.TRUE.equals(flag);
    }

    private boolean doMitm() {
        return getServerConfig().getMitmMatcher() == null || getServerConfig().getMitmMatcher().doMatch(getRequestProto());
    }

    private boolean isHttp(ByteBuf byteBuf) {
        byte[] bytes = new byte[8];
        byteBuf.getBytes(0, bytes);
        String methodToken = new String(bytes);
        return methodToken.startsWith("GET ") || methodToken.startsWith("POST ") || methodToken.startsWith("HEAD ")
                || methodToken.startsWith("PUT ") || methodToken.startsWith("DELETE ") || methodToken.startsWith("OPTIONS ")
                || methodToken.startsWith("CONNECT ") || methodToken.startsWith("TRACE ");
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        if (getChannelFuture() != null) {
            getChannelFuture().channel().close();
        }
        ctx.channel().close();
        if (getServerConfig().getHttpProxyAcceptHandler() != null) {
            getServerConfig().getHttpProxyAcceptHandler().onClose(ctx.channel());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (getChannelFuture() != null) {
            getChannelFuture().channel().close();
        }
        ctx.channel().close();
        exceptionHandle.beforeCatch(ctx.channel(), cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.channel().close();
        }
    }

    private boolean authenticate(ChannelHandlerContext ctx, HttpRequest request) {
        if (serverConfig.getAuthenticationProvider() != null) {
            HttpProxyAuthenticationProvider authProvider = serverConfig.getAuthenticationProvider();
            if (!authProvider.matches(request)) {
                return true;
            }
            HttpToken httpToken = authProvider.authenticate(request);
            if (httpToken == null) {
                HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpProxyServer.UNAUTHORIZED);
                response.headers().set(HttpHeaderNames.PROXY_AUTHENTICATE, authProvider.authType() + " realm=\"" + authProvider.authRealm() + "\"");
                ctx.writeAndFlush(response);
                return false;
            }
            HttpAuthContext.setToken(ctx.channel(), httpToken);
        }
        return true;
    }

    private void handleProxyData(Channel channel, Object msg, boolean isHttp) throws Exception {
        if (getInterceptPipeline() == null) {
            setInterceptPipeline(buildOnlyConnectPipeline());
            getInterceptPipeline().setRequestProto(getRequestProto().copy());
        }
        RequestProto pipeRp = getInterceptPipeline().getRequestProto();
        boolean isChangeRp = false;
        if (isHttp && msg instanceof HttpRequest) {
            if (!pipeRp.equals(getRequestProto())) {
                isChangeRp = true;
            }
        }

        if (isChangeRp || getChannelFuture() == null) {
            if (isHttp && !(msg instanceof HttpRequest)) {
                return;
            }
            getInterceptPipeline().beforeConnect(channel);

            ProxyHandler proxyHandler = ProxyHandleFactory.build(getInterceptPipeline().getProxyConfig() == null ?
                    proxyConfig : getInterceptPipeline().getProxyConfig());

            ChannelInitializer channelInitializer = isHttp ? new HttpProxyInitializer(channel, pipeRp, proxyHandler)
                    : new TunnelProxyInitializer(channel, proxyHandler);
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(getServerConfig().getProxyLoopGroup())
                    .channel(NioSocketChannel.class)
                    .handler(channelInitializer);
            if (proxyHandler != null) {
                bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
            } else {
                bootstrap.resolver(getServerConfig().resolver());
            }
            setRequestList(new LinkedList());
            setChannelFuture(bootstrap.connect(pipeRp.getHost(), pipeRp.getPort()));
            getChannelFuture().addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    future.channel().writeAndFlush(msg);
                    synchronized (getRequestList()) {
                        getRequestList().forEach(obj -> future.channel().writeAndFlush(obj));
                        getRequestList().clear();
                        setIsConnect(true);
                    }
                } else {
                    synchronized (getRequestList()) {
                        getRequestList().forEach(obj -> ReferenceCountUtil.release(obj));
                        getRequestList().clear();
                    }
                    getExceptionHandle().beforeCatch(channel, future.cause());
                    future.channel().close();
                    channel.close();
                }
            });
        } else {
            synchronized (getRequestList()) {
                if (getIsConnect()) {
                    getChannelFuture().channel().writeAndFlush(msg);
                } else {
                    getRequestList().add(msg);
                }
            }
        }
    }

    private HttpProxyInterceptPipeline buildPipeline() {
        HttpProxyInterceptPipeline interceptPipeline = new HttpProxyInterceptPipeline(new HttpProxyIntercept() {
            @Override
            public void beforeRequest(Channel clientChannel, HttpRequest httpRequest, HttpProxyInterceptPipeline pipeline)
                    throws Exception {
                if (isH2StreamChannel(clientChannel)
                        && httpRequest instanceof FullHttpRequest
                        && getRequestProto() != null
                        && getRequestProto().getSsl()) {
                    if (isWebSocketUpgrade(httpRequest)) {
                        FullHttpRequest h1Request = ((FullHttpRequest) httpRequest).retainedDuplicate();
                        h1Request.setProtocolVersion(HttpVersion.HTTP_1_1);
                        stripInternalHttp2Headers(h1Request.headers());
                        try {
                            handleProxyData(clientChannel, h1Request, true);
                        } finally {
                            ReferenceCountUtil.release(h1Request);
                        }
                        return;
                    }
                    // SSE(EventSource)请求降级走 H1:handleH2Forward 的 h2Bridge.send() 在事件循环上同步阻塞,
                    // 对长连接 SSE 会拖垮事件循环(502)且无法实时刷新。降级到 H1 后由 SSE 拦截器按 chunk 实时捕获。
                    if (isSseRequest(httpRequest)) {
                        FullHttpRequest h1Request = ((FullHttpRequest) httpRequest).retainedDuplicate();
                        h1Request.setProtocolVersion(HttpVersion.HTTP_1_1);
                        stripInternalHttp2Headers(h1Request.headers());
                        try {
                            handleProxyData(clientChannel, h1Request, true);
                        } finally {
                            ReferenceCountUtil.release(h1Request);
                        }
                        return;
                    }
                    handleH2Forward(clientChannel, (FullHttpRequest) httpRequest, pipeline);
                    return;
                }
                handleProxyData(clientChannel, httpRequest, true);
            }

            @Override
            public void beforeRequest(Channel clientChannel, HttpContent httpContent, HttpProxyInterceptPipeline pipeline)
                    throws Exception {
                handleProxyData(clientChannel, httpContent, true);
            }

            @Override
            public void afterResponse(Channel clientChannel, Channel proxyChannel, HttpResponse httpResponse,
                                      HttpProxyInterceptPipeline pipeline) throws Exception {
                clientChannel.writeAndFlush(httpResponse);
                if (HttpHeaderValues.WEBSOCKET.toString().equals(httpResponse.headers().get(HttpHeaderNames.UPGRADE))) {
                    proxyChannel.pipeline().remove("httpCodec");
                    clientChannel.pipeline().remove("httpCodec");
                }
            }

            @Override
            public void afterResponse(Channel clientChannel, Channel proxyChannel, HttpContent httpContent,
                                      HttpProxyInterceptPipeline pipeline) throws Exception {
                clientChannel.writeAndFlush(httpContent);
            }
        });
        getInterceptInitializer().init(interceptPipeline);
        return interceptPipeline;
    }

    private void handleH2Forward(Channel clientChannel, FullHttpRequest fullRequest, HttpProxyInterceptPipeline pipeline) {
        String authority = fullRequest.headers().get(HttpHeaderNames.HOST);
        if (authority == null || authority.isEmpty()) {
            authority = getRequestProto().getHost() + ":" + getRequestProto().getPort();
        }
        // 在 event loop 上提取 requestRaw(String),避免 FullHttpRequest 的 ByteBuf 跨线程访问。
        // 提取后 fullRequest 可由 processH2Request 的 finally 安全 release;worker 线程只持有 String。
        final String requestRaw = toRawRequest(fullRequest, authority);
        final String finalAuthority = authority;
        final HttpProxyInterceptPipeline finalPipeline = pipeline;
        try {
            H2_FORWARD_EXECUTOR.execute(() -> forwardH2Offloop(clientChannel, finalPipeline, requestRaw, finalAuthority));
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            // worker 池饱和:不阻塞 event loop,直接回 502。
            FullHttpResponse response = HttpProxyServerHandlerSupport.buildH2UpstreamErrorResponse("h2 forward pool saturated", ex);
            try {
                finalPipeline.afterResponse(clientChannel, clientChannel, (HttpResponse) response);
            } catch (Exception ax) {
                ReferenceCountUtil.release(response);
                clientChannel.close();
            }
        }
    }

    /**
     * H2 forward 的阻塞部分(上游 TLS+H2 收发、H2->H1 fallback、上游代理 socket)在 worker 线程上执行,
     * 不阻塞 Netty 事件循环。完成后切回 client channel 的 event loop 触发 afterResponse 链
     * (match/replace、xapp、intercept、history、写客户端)。
     *
     * 引用计数:afterResponse 成功时 default intercept 会 writeAndFlush(resp) 自动 release;
     * 若 afterResponse 抛异常(intercept drop / 真异常,在 default writeAndFlush 之前)则 resp 未写出,
     * catch 中显式 release 并关闭流,保持 drop 语义(不写响应 + 关闭)。客户端已断开时同样 release。
     */
    private void forwardH2Offloop(Channel clientChannel, HttpProxyInterceptPipeline pipeline, String requestRaw, String authority) {
        FullHttpResponse response;
        try {
            response = h2Bridge.send(requestRaw, authority);
        } catch (Exception ex) {
            if (HttpProxyServerHandlerSupport.isRetryableH2UpstreamFailure(ex)) {
                try {
                    response = h2Bridge.sendHttp1Fallback(requestRaw, authority);
                } catch (Exception fallbackEx) {
                    Utils.err("[xproxy][h2-fallback] upstream fallback failed: "
                            + fallbackEx.getClass().getSimpleName() + ": " + fallbackEx.getMessage());
                    response = HttpProxyServerHandlerSupport.buildH2UpstreamErrorResponse("h2->h1 fallback failed", fallbackEx);
                }
            } else {
                Utils.err("[xproxy][h2-direct] upstream h2 failed: "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                response = HttpProxyServerHandlerSupport.buildH2UpstreamErrorResponse("h2 direct", ex);
            }
        }
        final FullHttpResponse resp = response;
        clientChannel.eventLoop().execute(() -> {
            if (!clientChannel.isActive()) {
                ReferenceCountUtil.release(resp);
                return;
            }
            try {
                pipeline.afterResponse(clientChannel, clientChannel, (HttpResponse) resp);
            } catch (Exception ex) {
                ReferenceCountUtil.release(resp);
                Utils.err("[xproxy][h2-forward] afterResponse failed: " + ex.getMessage());
                clientChannel.close();
            }
        });
    }

    private boolean isWebSocketUpgrade(HttpRequest request) {
        String upgrade = request.headers().get(HttpHeaderNames.UPGRADE);
        if (upgrade == null || !HttpHeaderValues.WEBSOCKET.toString().equalsIgnoreCase(upgrade.trim())) {
            return false;
        }
        String connection = request.headers().get(HttpHeaderNames.CONNECTION);
        return connection != null && connection.toLowerCase().contains(HttpHeaderValues.UPGRADE.toString().toLowerCase());
    }

    /**
     * 判定请求是否为 SSE(EventSource):Accept 含 text/event-stream。
     * 浏览器 EventSource 必带该头;据此把 H2 流降级为 H1,避免 handleH2Forward 的同步阻塞。
     */
    private boolean isSseRequest(HttpRequest request) {
        List<String> accept = request.headers().getAll(HttpHeaderNames.ACCEPT);
        if (accept == null || accept.isEmpty()) {
            return false;
        }
        for (String value : accept) {
            for (String token : value.split(",")) {
                String t = token.trim();
                int semi = t.indexOf(';');
                String mime = (semi >= 0 ? t.substring(0, semi) : t).trim();
                if ("text/event-stream".equalsIgnoreCase(mime)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String toRawRequest(FullHttpRequest request, String authority) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.method().name()).append(' ').append(request.uri()).append(" HTTP/2\r\n");
        if (request.headers().get(HttpHeaderNames.HOST) == null) {
            sb.append("Host: ").append(authority).append("\r\n");
        }
        request.headers().forEach(h -> {
            if (!h.getKey().startsWith(":")) {
                if (h.getKey().toLowerCase().startsWith("x-http2-")) {
                    return;
                }
                sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
        });
        sb.append("\r\n");
        if (request.content().isReadable()) {
            byte[] body = new byte[request.content().readableBytes()];
            request.content().getBytes(request.content().readerIndex(), body);
            sb.append(new String(body, java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    private HttpProxyInterceptPipeline buildOnlyConnectPipeline() {
        HttpProxyInterceptPipeline interceptPipeline = new HttpProxyInterceptPipeline(new HttpProxyIntercept());
        getInterceptInitializer().init(interceptPipeline);
        return interceptPipeline;
    }

    private void stripInternalHttp2Headers(HttpHeaders headers) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>(headers.names());
        for (String name : names) {
            if (name != null && name.toLowerCase().startsWith("x-http2-")) {
                headers.remove(name);
            }
        }
    }
}
