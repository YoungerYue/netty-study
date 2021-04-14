package io.netty.example.analysis.momery;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.CharsetUtil;

public class MyNettyServerByteBufTestHandler extends ChannelInboundHandlerAdapter {
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		ByteBuf byteBuf = (ByteBuf) msg;
		System.out.println("内存地址：" + System.identityHashCode(byteBuf) + "，内容：" + byteBuf.toString(CharsetUtil.UTF_8));

		//==============================================
		ChannelFuture channelFuture = ctx.write(byteBuf);
		channelFuture.addListener(future -> {
			if (future.isSuccess()) {
				System.out.println(future);
			}
		});
		ctx.flush();
		//==============================================

	}
}
