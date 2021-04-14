/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.websocketx;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.ServerHandshakeStateEvent;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpMethod.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpUtil.*;
import static io.netty.handler.codec.http.HttpVersion.*;
import static io.netty.util.internal.ObjectUtil.*;

/**
 * Handles the HTTP handshake (the HTTP Upgrade request) for {@link WebSocketServerProtocolHandler}.
 */
class WebSocketServerProtocolHandshakeHandler extends ChannelInboundHandlerAdapter {

    private final WebSocketServerProtocolConfig serverConfig;
    private ChannelHandlerContext ctx;
    private ChannelPromise handshakePromise;

    WebSocketServerProtocolHandshakeHandler(WebSocketServerProtocolConfig serverConfig) {
        this.serverConfig = checkNotNull(serverConfig, "serverConfig");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        handshakePromise = ctx.newPromise();
    }

    // WebSocketServerProtocolHandshakeHandler的channelRead
	// 将WebSocketServerProtocolHandshakeHandler替换forbiddenHttpRequestResponder
    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        final FullHttpRequest req = (FullHttpRequest) msg;
        if (isNotWebSocketPath(req)) {//不是websocket路径就不管
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            if (!GET.equals(req.method())) {//只有GET支持的升级的
                sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN, ctx.alloc().buffer(0)));
                return;
            }

			//创建握手工厂
            final WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                    getWebSocketLocation(ctx.pipeline(), req, serverConfig.websocketPath()),
                    serverConfig.subprotocols(), serverConfig.decoderConfig());
			//根据请求头信息的sec-websocket-version来决定要哪个版本的握手对象，一般都是13，如果都不支持就会返回null
            final WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(req);//创建一个握手处理器
            final ChannelPromise localHandshakePromise = handshakePromise;//握手回调
            if (handshaker == null) {//不支持的版本
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                // Ensure we set the handshaker and replace this handler before we
                // trigger the actual handshake. Otherwise we may receive websocket bytes in this handler
                // before we had a chance to replace it.
                //
                // See https://github.com/netty/netty/issues/9471.
                WebSocketServerProtocolHandler.setHandshaker(ctx.channel(), handshaker);//设置处理器
                ctx.pipeline().replace(this, "WS403Responder",
                        WebSocketServerProtocolHandler.forbiddenHttpRequestResponder());//把当前处理器替换掉，变成403

				//WebSocketServerHandshaker的handshake
				//握手对象进行握手，其实就是发送响应数据。先会创建一个FullHttpResponse 响应，然后把跟HTTP相关的聚合，压缩处理器删除，
				// 如果有HttpServerCodec，那就在前面添加websocket的编解码器，等发送响应成功了把HttpServerCodec删了。
				// 如果是HTTP编解码器，就把解码器先替换成websocket的解码器，等发送响应成功了，再把编码器替换成websocket的编码器
                final ChannelFuture handshakeFuture = handshaker.handshake(ctx.channel(), req);
                handshakeFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {//发送不成功
                            localHandshakePromise.tryFailure(future.cause());
                            ctx.fireExceptionCaught(future.cause());
                        } else {//发送成功
                            localHandshakePromise.trySuccess();
                            // Kept for compatibility
							// 保持兼容性 触发事件
                            ctx.fireUserEventTriggered(//这个HANDSHAKE_COMPLETE是过时的
                                    WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE);
                            ctx.fireUserEventTriggered(//这个是新的
                                    new WebSocketServerProtocolHandler.HandshakeComplete(
                                            req.uri(), req.headers(), handshaker.selectedSubprotocol()));
                        }
                    }
                });
                //发送可能会等好久，所以就给了个超时的定时任务，默认设置是10秒，超时了就触发超时事件，
				// 然后关闭通道，如果发送回调了，就把定时任务取消。
                applyHandshakeTimeout();
            }
        } finally {
            req.release();
        }
    }

    private boolean isNotWebSocketPath(FullHttpRequest req) {
        String websocketPath = serverConfig.websocketPath();
        return serverConfig.checkStartsWith() ? !req.uri().startsWith(websocketPath) : !req.uri().equals(websocketPath);
    }

    //如果响应的状态码不是200或者请求不是设置长连接，就关闭通道
    private static void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        if (!isKeepAlive(req) || res.status().code() != 200) {//req不支持KeepAlive，或者res状态码不是200就等写完成了关闭通道
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String getWebSocketLocation(ChannelPipeline cp, HttpRequest req, String path) {
        String protocol = "ws";
        if (cp.get(SslHandler.class) != null) {
            // SSL in use so use Secure WebSockets
            protocol = "wss";
        }
        String host = req.headers().get(HttpHeaderNames.HOST);
        return protocol + "://" + host + path;
    }

    private void applyHandshakeTimeout() {
        final ChannelPromise localHandshakePromise = handshakePromise;
        final long handshakeTimeoutMillis = serverConfig.handshakeTimeoutMillis();
        if (handshakeTimeoutMillis <= 0 || localHandshakePromise.isDone()) {
            return;//完成了就不管了
        }

		//起一个定时任务
        final Future<?> timeoutFuture = ctx.executor().schedule(new Runnable() {
            @Override
            public void run() {
                if (!localHandshakePromise.isDone() &&
                        localHandshakePromise.tryFailure(new WebSocketHandshakeException("handshake timed out"))) {
                    ctx.flush()//没完成就刷出去，触发超时事件，然后关闭
                       .fireUserEventTriggered(ServerHandshakeStateEvent.HANDSHAKE_TIMEOUT)
                       .close();
                }
            }
        }, handshakeTimeoutMillis, TimeUnit.MILLISECONDS);

		//如果成功了，就把超时任务取消
        // Cancel the handshake timeout when handshake is finished.
        localHandshakePromise.addListener(new FutureListener<Void>() {
            @Override
            public void operationComplete(Future<Void> f) throws Exception {
                timeoutFuture.cancel(false);
            }
        });
    }
}
