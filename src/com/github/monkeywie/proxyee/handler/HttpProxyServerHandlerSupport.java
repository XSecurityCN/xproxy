package com.github.monkeywie.proxyee.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import org.jjgroup.xproxy.proxy.portal.ProxyPortal;
import org.jjgroup.xproxy.proxy.portal.ProxyPortalResult;

import javax.net.ssl.SSLHandshakeException;
import java.net.InetSocketAddress;
import kotlin.Pair;

/**
 * 从 {@link HttpProxyServerHandler} 抽离的纯静态辅助方法集合：H2 请求拷贝、下游 ALPN 配置、
 * 门户/错误响应构造、本地监听地址解析、H2 上游失败可重试判定等。这些方法不依赖实例状态，
 * 移至本类仅为降低 HttpProxyServerHandler 的体积，逻辑保持不变。
 */
final class HttpProxyServerHandlerSupport {

    private static final String DOWNSTREAM_H2_PROPERTY = "xproxy.proxy.downstreamH2";
    private static final String DOWNSTREAM_H2_ENV = "XPROXY_PROXY_DOWNSTREAM_H2";

    private HttpProxyServerHandlerSupport() {
    }

    static FullHttpRequest copyH2RequestForMutation(FullHttpRequest request) {
        ByteBuf body = Unpooled.buffer(request.content().readableBytes());
        body.writeBytes(request.content(), request.content().readerIndex(), request.content().readableBytes());
        FullHttpRequest copy = new DefaultFullHttpRequest(
                request.protocolVersion(),
                request.method(),
                request.uri(),
                body
        );
        copy.headers().set(request.headers());
        copy.trailingHeaders().set(request.trailingHeaders());
        copy.setDecoderResult(request.decoderResult());
        return copy;
    }

    static FullHttpRequest copyH2RequestForMutationForTest(FullHttpRequest request) {
        return copyH2RequestForMutation(request);
    }

    static boolean isDownstreamHttp2Enabled() {
        String property = System.getProperty(DOWNSTREAM_H2_PROPERTY);
        if (property != null) {
            return Boolean.parseBoolean(property.trim());
        }
        String env = System.getenv(DOWNSTREAM_H2_ENV);
        if (env != null) {
            return Boolean.parseBoolean(env.trim());
        }
        return true;
    }

    static ApplicationProtocolConfig buildDownstreamApplicationProtocolConfig() {
        if (isDownstreamHttp2Enabled()) {
            return new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.FATAL_ALERT,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.FATAL_ALERT,
                    ApplicationProtocolNames.HTTP_2,
                    ApplicationProtocolNames.HTTP_1_1
            );
        }
        return new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.FATAL_ALERT,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.FATAL_ALERT,
                ApplicationProtocolNames.HTTP_1_1
        );
    }

    static FullHttpResponse buildH2UpstreamErrorResponse(String phase, Throwable ex) {
        return toNettyPortalResponse(
                HttpVersion.valueOf("HTTP/2.0"),
                ProxyPortal.errorPage(
                        HttpResponseStatus.BAD_GATEWAY.code(),
                        HttpResponseStatus.BAD_GATEWAY.reasonPhrase(),
                        "Upstream proxy error",
                        phase,
                        ex
                )
        );
    }

    static FullHttpResponse buildH2UpstreamErrorResponseForTest(String phase, Throwable ex) {
        return buildH2UpstreamErrorResponse(phase, ex);
    }

    static FullHttpResponse buildDecoderErrorResponseForTest(Throwable cause) {
        return buildDecoderErrorResponse(cause);
    }

    static HttpResponseStatus statusForDecoderFailure(Throwable cause) {
        if (cause instanceof TooLongHttpLineException) {
            return HttpResponseStatus.REQUEST_URI_TOO_LONG;
        }
        if (cause instanceof TooLongHttpHeaderException) {
            return HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE;
        }
        if (cause instanceof TooLongHttpContentException) {
            return HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
        }
        return HttpResponseStatus.BAD_REQUEST;
    }

    static FullHttpResponse buildDecoderErrorResponse(Throwable cause) {
        HttpResponseStatus status = statusForDecoderFailure(cause);
        return toNettyPortalResponse(
                HttpVersion.HTTP_1_1,
                ProxyPortal.errorPage(
                        status.code(),
                        status.reasonPhrase(),
                        "Bad request",
                        "decoder",
                        cause
                )
        );
    }

    static FullHttpResponse buildPortalResponseForTest(String method, String uri, String host, String listenerHost, int listenerPort) {
        return buildPortalResponse(method, uri, host, listenerHost, listenerPort);
    }

    static FullHttpResponse buildPortalResponse(String method, String uri, String host, String listenerHost, int listenerPort) {
        ProxyPortalResult result = ProxyPortal.handleRequest(method, uri, host, listenerHost, listenerPort);
        if (result == null) {
            return null;
        }
        return toNettyPortalResponse(HttpVersion.HTTP_1_1, result);
    }

    static FullHttpResponse toNettyPortalResponseForTest(HttpVersion version, ProxyPortalResult result) {
        return toNettyPortalResponse(version, result);
    }

    private static FullHttpResponse toNettyPortalResponse(HttpVersion version, ProxyPortalResult result) {
        byte[] bytes = result.getBody();
        ByteBuf body = Unpooled.buffer(bytes.length);
        body.writeBytes(bytes);
        FullHttpResponse response = new DefaultFullHttpResponse(
                version,
                HttpResponseStatus.valueOf(result.getStatusCode()),
                body
        );
        for (kotlin.Pair<String, String> header : result.getHeaders()) {
            response.headers().set(header.getFirst(), header.getSecond());
        }
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        return response;
    }

    static String localHost(Channel channel) {
        if (channel == null || !(channel.localAddress() instanceof InetSocketAddress)) {
            return null;
        }
        InetSocketAddress address = (InetSocketAddress) channel.localAddress();
        return address.getHostString();
    }

    static int localPort(Channel channel) {
        if (channel == null || !(channel.localAddress() instanceof InetSocketAddress)) {
            return -1;
        }
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    static boolean isRetryableH2UpstreamFailure(Throwable ex) {
        return isRetryableH2UpstreamFailure(ex, new java.util.IdentityHashMap<>());
    }

    private static boolean isRetryableH2UpstreamFailure(Throwable ex, java.util.IdentityHashMap<Throwable, Boolean> seen) {
        Throwable current = ex;
        while (current != null) {
            if (seen.put(current, Boolean.TRUE) != null) {
                return false;
            }
            if (current instanceof SSLHandshakeException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("pkix path building failed")
                        || lower.contains("unable to find valid certification path")
                        || lower.contains("remote host terminated the handshake")
                        || lower.contains("no_application_protocol")
                        || lower.contains("application protocol")
                        || lower.contains("alpn")
                        || lower.contains("upstream downgraded")
                        || lower.contains("http/1.1 header parser received no bytes")) {
                    return true;
                }
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (isRetryableH2UpstreamFailure(suppressed, seen)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
