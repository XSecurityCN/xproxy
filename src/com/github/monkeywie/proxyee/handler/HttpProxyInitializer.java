package com.github.monkeywie.proxyee.handler;

import com.github.monkeywie.proxyee.server.HttpProxyServerConfig;
import com.github.monkeywie.proxyee.util.ProtoUtil.RequestProto;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.proxy.ProxyHandler;

/**
 * HTTP代理,转发解码后的HTTP报文。
 *
 * xproxy fork:原版用 {@code clientChannel.pipeline().get("serverHandle")} 取 serverConfig,
 * 但 H2 流子通道上没有 "serverHandle"(已在 ALPN 时移除)会 NPE。改为优先从
 * {@link HttpProxyServerHandler#SERVER_HANDLE_KEY} 属性取(H2 流在 processH2Request 中设置),
 * 回退到 pipeline 查找(H1 路径)。使 H2 流可降级走 H1 上游(如 SSE)。
 */
public class HttpProxyInitializer extends ChannelInitializer {

    private Channel clientChannel;
    private RequestProto requestProto;
    private ProxyHandler proxyHandler;

    public HttpProxyInitializer(Channel clientChannel, RequestProto requestProto,
                                ProxyHandler proxyHandler) {
        this.clientChannel = clientChannel;
        this.requestProto = requestProto;
        this.proxyHandler = proxyHandler;
    }

    private HttpProxyServerHandler resolveServerHandler() {
        HttpProxyServerHandler attr = clientChannel.attr(HttpProxyServerHandler.SERVER_HANDLE_KEY).get();
        if (attr != null) {
            return attr;
        }
        Object direct = clientChannel.pipeline().get("serverHandle");
        if (direct instanceof HttpProxyServerHandler) {
            return (HttpProxyServerHandler) direct;
        }
        Channel parent = clientChannel.parent();
        if (parent != null) {
            Object parentHandler = parent.pipeline().get("serverHandle");
            if (parentHandler instanceof HttpProxyServerHandler) {
                return (HttpProxyServerHandler) parentHandler;
            }
        }
        return null;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        if (proxyHandler != null) {
            ch.pipeline().addLast(proxyHandler);
        }
        HttpProxyServerHandler serverHandler = resolveServerHandler();
        HttpProxyServerConfig serverConfig = serverHandler == null ? null : serverHandler.getServerConfig();
        if (serverConfig == null) {
            throw new IllegalStateException("HttpProxyServerHandler/serverConfig not found on client channel");
        }
        if (requestProto.getSsl()) {
            ch.pipeline().addLast(serverConfig.getClientSslCtx().newHandler(ch.alloc(), requestProto.getHost(), requestProto.getPort()));
        }
        ch.pipeline().addLast("httpCodec", new HttpClientCodec(
                serverConfig.getMaxInitialLineLength(),
                serverConfig.getMaxHeaderSize(),
                serverConfig.getMaxChunkSize()));
        ch.pipeline().addLast("proxyClientHandle", new HttpProxyClientHandler(clientChannel));
    }
}
