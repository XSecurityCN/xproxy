package com.github.monkeywie.proxyee.handler;

import com.github.monkeywie.proxyee.exception.HttpProxyExceptionHandle;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.ReferenceCountUtil;

public class HttpProxyClientHandler extends ChannelInboundHandlerAdapter {

    private final Channel clientChannel;

    public HttpProxyClientHandler(Channel clientChannel) {
        this.clientChannel = clientChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!clientChannel.isOpen()) {
            ReferenceCountUtil.release(msg);
            return;
        }

        HttpProxyServerHandler serverHandler = resolveServerHandler(clientChannel);
        if (serverHandler == null) {
            clientChannel.writeAndFlush(msg);
            return;
        }

        HttpProxyInterceptPipeline interceptPipeline = serverHandler.getInterceptPipeline();
        if (interceptPipeline == null) {
            clientChannel.writeAndFlush(msg);
            return;
        }

        if (msg instanceof HttpResponse) {
            DecoderResult decoderResult = ((HttpResponse) msg).decoderResult();
            Throwable cause = decoderResult.cause();
            if (cause != null) {
                ReferenceCountUtil.release(msg);
                this.exceptionCaught(ctx, cause);
                return;
            }
            interceptPipeline.afterResponse(clientChannel, ctx.channel(), (HttpResponse) msg);
        } else if (msg instanceof HttpContent) {
            interceptPipeline.afterResponse(clientChannel, ctx.channel(), (HttpContent) msg);
        } else {
            clientChannel.writeAndFlush(msg);
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.channel().close();
        clientChannel.close();
        HttpProxyServerHandler serverHandler = resolveServerHandler(clientChannel);
        if (serverHandler != null) {
            HttpProxyExceptionHandle exceptionHandle = serverHandler.getExceptionHandle();
            exceptionHandle.afterCatch(clientChannel, ctx.channel(), cause);
        }
    }

    private HttpProxyServerHandler resolveServerHandler(Channel channel) {
        if (channel == null) {
            return null;
        }
        // H2 流子通道:H2 流在 processH2Request 把 serverHandle 挂到属性上(H2 流 pipeline 里没有 "serverHandle")。
        HttpProxyServerHandler attr = channel.attr(HttpProxyServerHandler.SERVER_HANDLE_KEY).get();
        if (attr != null) {
            return attr;
        }
        Object direct = channel.pipeline().get("serverHandle");
        if (direct instanceof HttpProxyServerHandler) {
            return (HttpProxyServerHandler) direct;
        }
        Channel parent = channel.parent();
        if (parent != null) {
            Object parentHandler = parent.pipeline().get("serverHandle");
            if (parentHandler instanceof HttpProxyServerHandler) {
                return (HttpProxyServerHandler) parentHandler;
            }
        }
        return null;
    }
}
