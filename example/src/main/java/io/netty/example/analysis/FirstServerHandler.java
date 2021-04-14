package io.netty.example.analysis;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class FirstServerHandler extends ChannelInboundHandlerAdapter {
	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
		System.out.println(this.getClass() + " FirstServerHandler.channelWritabilityChanged");
		ctx.fireChannelWritabilityChanged();
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
		System.out.println(this.getClass() + " FirstServerHandler.channelRegistered");
		ctx.fireChannelRegistered();
	}

	@Override
	public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
		System.out.println(this.getClass() + " FirstServerHandler.channelUnregistered");
		ctx.fireChannelUnregistered();
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		System.out.println(this.getClass() + " FirstServerHandler.channelActive");
		ctx.fireChannelActive();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		System.out.println(this.getClass() + " FirstServerHandler.channelInactive");
		ctx.fireChannelInactive();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		System.out.println(this.getClass() + " FirstServerHandler.channelRead");
		ctx.fireChannelRead(msg);
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		System.out.println(this.getClass() + " FirstServerHandler.channelReadComplete");
		ctx.fireChannelReadComplete();
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		System.out.println(this.getClass() + " FirstServerHandler.handlerAdded");
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		System.out.println(this.getClass() + " FirstServerHandler.handlerRemoved");
	}
}
