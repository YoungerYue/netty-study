package io.netty.example.analysis;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * TODO
 *
 * @author chenyang.yue@ttpai.cn
 */
public class TwoServerHandler extends ChannelInboundHandlerAdapter {
	@Override
	public void channelRegistered(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TwoServerHandler.channelRegistered");
		ctx.fireChannelRegistered();
	}

	@Override
	public void channelUnregistered(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TwoServerHandler.channelUnregistered");
		ctx.fireChannelUnregistered();
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TwoServerHandler.channelActive");
		ctx.fireChannelActive();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TwoServerHandler.channelInactive");
		ctx.fireChannelInactive();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		System.out.println(this.getClass() + " TwoServerHandler.channelRead");
		ctx.fireChannelRead(msg);
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TwoServerHandler.channelReadComplete");
		ctx.fireChannelReadComplete();
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TwoServerHandler.handlerAdded");
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TwoServerHandler.handlerRemoved");
	}

	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TwoServerHandler.channelWritabilityChanged");
		ctx.fireChannelWritabilityChanged();
	}
}
