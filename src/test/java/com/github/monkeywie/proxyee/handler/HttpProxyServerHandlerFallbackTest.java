package com.github.monkeywie.proxyee.handler;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.DecoderException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpProxyServerHandlerFallbackTest {

    @Test
    void pkixMessageIsRetryable() {
        RuntimeException ex = new RuntimeException("Upstream proxy error: PKIX path building failed: unable to find valid certification path to requested target");
        assertTrue(HttpProxyServerHandlerSupport.isRetryableH2UpstreamFailure(ex));
    }

    @Test
    void sslHandshakeExceptionIsRetryable() {
        RuntimeException ex = new RuntimeException(new SSLHandshakeException("certificate_unknown"));
        assertTrue(HttpProxyServerHandlerSupport.isRetryableH2UpstreamFailure(ex));
    }

    @Test
    void remoteHostTerminatedHandshakeMessageIsRetryable() {
        RuntimeException ex = new RuntimeException("Remote host terminated the handshake");
        assertTrue(HttpProxyServerHandlerSupport.isRetryableH2UpstreamFailure(ex));
    }

    @Test
    void plainSslExceptionWithoutHandshakeOrAlpnHintsIsNotRetryable() {
        RuntimeException ex = new RuntimeException(new SSLException("record overflow"));
        assertFalse(HttpProxyServerHandlerSupport.isRetryableH2UpstreamFailure(ex));
    }

    @Test
    void unrelatedExceptionIsNotRetryable() {
        IllegalArgumentException ex = new IllegalArgumentException("malformed request line");
        assertFalse(HttpProxyServerHandlerSupport.isRetryableH2UpstreamFailure(ex));
    }

    @Test
    void alpnFailureMessageIsRetryable() {
        RuntimeException ex = new RuntimeException("no_application_protocol");
        assertTrue(HttpProxyServerHandlerSupport.isRetryableH2UpstreamFailure(ex));
    }

    @Test
    void http11HeaderParserNoBytesMessageIsRetryable() {
        RuntimeException ex = new RuntimeException(
                new java.io.IOException("HTTP/1.1 header parser received no bytes")
        );
        assertTrue(HttpProxyServerHandlerSupport.isRetryableH2UpstreamFailure(ex));
    }

    @Test
    void http11HeaderParserNoBytesSuppressedExceptionIsRetryable() {
        RuntimeException ex = new RuntimeException("HTTP/2 upstream request failed");
        ex.addSuppressed(new java.io.IOException("HTTP/1.1 header parser received no bytes"));

        assertTrue(HttpProxyServerHandlerSupport.isRetryableH2UpstreamFailure(ex));
    }

    @Test
    void downstreamHttp2IsEnabledByDefault() {
        String old = System.getProperty("xproxy.proxy.downstreamH2");
        try {
            System.clearProperty("xproxy.proxy.downstreamH2");
            assertTrue(HttpProxyServerHandlerSupport.isDownstreamHttp2Enabled());
            System.setProperty("xproxy.proxy.downstreamH2", "false");
            assertFalse(HttpProxyServerHandlerSupport.isDownstreamHttp2Enabled());
        } finally {
            if (old == null) {
                System.clearProperty("xproxy.proxy.downstreamH2");
            } else {
                System.setProperty("xproxy.proxy.downstreamH2", old);
            }
        }
    }

    @Test
    void h2ErrorResponseBodyBufferAllowsReplacementGrowth() {
        FullHttpResponse response = HttpProxyServerHandlerSupport.buildH2UpstreamErrorResponseForTest(
                "h2 direct",
                new java.net.http.HttpConnectTimeoutException("HTTP connect timed out")
        );

        assertEquals(HttpResponseStatus.BAD_GATEWAY, response.status());
        assertEquals("text/html; charset=utf-8", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION));
        assertTrue(response.content().toString(java.nio.charset.StandardCharsets.UTF_8).contains("Upstream proxy error"));

        response.content().clear();
        response.content().writeBytes(
                "HTTP connect timed out with replacement suffix".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)
        );

        assertEquals(
                "HTTP connect timed out with replacement suffix",
                response.content().toString(java.nio.charset.StandardCharsets.ISO_8859_1)
        );
    }

    @Test
    void h2RequestBodyBufferAllowsReplacementGrowth() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.valueOf("HTTP/2.0"),
                HttpMethod.POST,
                "/submit",
                Unpooled.wrappedBuffer("small".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
        );

        FullHttpRequest retained = request.retainedDuplicate();
        FullHttpRequest expandable = HttpProxyServerHandlerSupport.copyH2RequestForMutationForTest(retained);
        try {
            expandable.content().clear();
            expandable.content().writeBytes("small-but-expanded".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

            assertEquals("small-but-expanded", expandable.content().toString(java.nio.charset.StandardCharsets.ISO_8859_1));
        } finally {
            expandable.release();
            retained.release();
            request.release();
        }
    }

    @Test
    void decoderErrorResponseUsesHtmlPortal() {
        FullHttpResponse response = HttpProxyServerHandlerSupport.buildDecoderErrorResponseForTest(
                new DecoderException("bad <request>")
        );

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertEquals("text/html; charset=utf-8", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION));
        String body = response.content().toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("Bad request"));
        assertTrue(body.contains("&lt;request&gt;"));
    }

    @Test
    void directPortalResponseUsesHtmlPortal() {
        FullHttpResponse response = HttpProxyServerHandlerSupport.buildPortalResponseForTest(
                "GET",
                "/",
                "xproxy",
                "127.0.0.1",
                8080
        );

        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("text/html; charset=utf-8", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION));
        assertTrue(response.content().toString(java.nio.charset.StandardCharsets.UTF_8).contains("xproxy is running"));
    }

    @Test
    void nonPortalResponseHelperReturnsNull() {
        FullHttpResponse response = HttpProxyServerHandlerSupport.buildPortalResponseForTest(
                "GET",
                "/",
                "example.com",
                "127.0.0.1",
                8080
        );

        assertEquals(null, response);
    }

}
