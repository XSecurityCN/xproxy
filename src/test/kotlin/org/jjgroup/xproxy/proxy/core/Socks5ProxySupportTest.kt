package org.jjgroup.xproxy.proxy.core

import com.github.monkeywie.proxyee.exception.HttpProxyExceptionHandle
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer
import com.github.monkeywie.proxyee.server.HttpProxyServerConfig
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Socks5ProxySupportTest {

    private fun newConfig() = HttpProxyServerConfig()

    private fun newSnifferChannel(): EmbeddedChannel = EmbeddedChannel(
        ProtocolSnifferHandler(newConfig(), HttpProxyInterceptInitializer(), null, HttpProxyExceptionHandle())
    )

    private fun EmbeddedChannel.readOutboundBytes(): ByteArray {
        val buf = readOutbound<io.netty.buffer.ByteBuf>() ?: return ByteArray(0)
        val bytes = ByteArray(buf.readableBytes())
        buf.readBytes(bytes)
        buf.release()
        return bytes
    }

    @Test
    fun `sniffer routes socks5 when first byte is 0x05`() {
        val ch = newSnifferChannel()
        ch.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0x05, 0x01, 0x00)))
        assertNotNull(ch.pipeline().get("socks5Handshake"), "0x05 should route to SOCKS5 handshake handler")
        assertNull(ch.pipeline().get("httpCodec"), "SOCKS5 route must not install httpCodec")
        ch.finishAndReleaseAll()
    }

    @Test
    fun `sniffer routes http for non-socks5 first byte`() {
        val ch = newSnifferChannel()
        // 只写首字节 'G'(不完整请求,避免触发 proxyee handler 的真实上游连接)
        ch.writeInbound(Unpooled.wrappedBuffer(byteArrayOf('G'.code.toByte())))
        assertNotNull(ch.pipeline().get("httpCodec"), "non-0x05 should route to HTTP codec")
        assertNotNull(ch.pipeline().get("serverHandle"), "non-0x05 should install proxyee server handler")
        assertNull(ch.pipeline().get("socks5Handshake"))
        ch.finishAndReleaseAll()
    }

    @Test
    fun `socks5 handshake negotiates no-auth then connect to domain`() {
        val ch = newSnifferChannel()
        // method negotiation: VER=5, NMETHODS=1, METHODS=[0x00 no-auth]
        ch.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0x05, 0x01, 0x00)))
        val methodReply = ch.readOutboundBytes()
        assertEquals(listOf(0x05, 0x00), methodReply.map { it.toInt() and 0xFF }, "should select no-auth")

        // CONNECT example.com:443 (ATYP=0x03 DOMAIN)
        val domain = "example.com".toByteArray(Charsets.US_ASCII)
        val connect = byteArrayOf(0x05, 0x01, 0x00, 0x03, domain.size.toByte()) + domain +
            byteArrayOf(0x01, 0xBB.toByte()) // 443 = 0x01BB
        ch.writeInbound(Unpooled.wrappedBuffer(connect))
        val connectReply = ch.readOutboundBytes()
        assertEquals(0x05, connectReply[0].toInt() and 0xFF)
        assertEquals(0x00, connectReply[1].toInt() and 0xFF, "CONNECT should reply success (REP=0)")

        // 成功后换成隧道 handler(proxyee 子类),SOCKS5 握手 handler 被移除
        assertNotNull(ch.pipeline().get("serverHandle"), "tunnel should switch to proxyee handler")
        assertNull(ch.pipeline().get("socks5Handshake"), "handshake handler should be replaced")
        ch.finishAndReleaseAll()
    }

    @Test
    fun `socks5 connect to ipv4 address succeeds`() {
        val ch = newSnifferChannel()
        ch.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0x05, 0x01, 0x00)))
        ch.readOutboundBytes() // consume method reply

        // CONNECT 93.184.216.34:443 (ATYP=0x01 IPv4)
        val connect = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            93.toByte(), 184.toByte(), 216.toByte(), 34.toByte(),
            0x01, 0xBB.toByte()
        )
        ch.writeInbound(Unpooled.wrappedBuffer(connect))
        val connectReply = ch.readOutboundBytes()
        assertEquals(0x00, connectReply[1].toInt() and 0xFF, "IPv4 CONNECT should succeed")
        assertNotNull(ch.pipeline().get("serverHandle"))
        ch.finishAndReleaseAll()
    }

    @Test
    fun `socks5 rejects non-connect command with rep 0x07`() {
        val ch = newSnifferChannel()
        ch.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0x05, 0x01, 0x00)))
        ch.readOutboundBytes() // consume method reply

        // BIND (CMD=0x02) 不支持
        val bind = byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        ch.writeInbound(Unpooled.wrappedBuffer(bind))
        val reply = ch.readOutboundBytes()
        assertEquals(0x05, reply[0].toInt() and 0xFF)
        assertEquals(0x07, reply[1].toInt() and 0xFF, "non-CONNECT should reply 0x07 (command not supported)")
        assertNull(ch.pipeline().get("serverHandle"), "rejected command must not switch to tunnel")
        ch.finishAndReleaseAll()
    }

    @Test
    fun `socks5 rejects when no-auth not offered`() {
        val ch = newSnifferChannel()
        // 只提供 0x02(username/password),不提供 no-auth
        ch.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0x05, 0x01, 0x02)))
        val reply = ch.readOutboundBytes()
        assertEquals(listOf(0x05, 0xFF), reply.map { it.toInt() and 0xFF }, "should reply 0xFF when no-auth absent")
        ch.finishAndReleaseAll()
    }

    @Test
    fun `socks5 handshake tolerates fragmented writes`() {
        val ch = newSnifferChannel()
        // 分片写入 method negotiation
        ch.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0x05)))
        assertTrue(ch.readOutboundBytes().isEmpty(), "no reply before full method negotiation")
        ch.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0x01, 0x00)))
        val methodReply = ch.readOutboundBytes()
        assertEquals(listOf(0x05, 0x00), methodReply.map { it.toInt() and 0xFF })
        ch.finishAndReleaseAll()
    }
}
