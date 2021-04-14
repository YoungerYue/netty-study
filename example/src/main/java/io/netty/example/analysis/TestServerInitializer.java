package io.netty.example.analysis;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.ServerSocketChannel;

public class TestServerInitializer extends ChannelInitializer<ServerSocketChannel> {
	@Override
	protected void initChannel(ServerSocketChannel ch) {
		System.out.println(this.getClass() + " TestServerInitializer.initChannel");
		ChannelPipeline pipeline = ch.pipeline();
		pipeline.addLast(new FirstServerHandler());
		pipeline.addLast(new TwoServerHandler());
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TestServerInitializer.handlerAdded");
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TestServerInitializer.handlerRemoved");
	}

	@Override
	public void channelUnregistered(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TestServerInitializer.channelUnregistered");
		ctx.fireChannelUnregistered();
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TestServerInitializer.channelActive");
		ctx.fireChannelActive();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TestServerInitializer.channelInactive");
		ctx.fireChannelInactive();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		System.out.println(this.getClass() + " TestServerInitializer.channelRead");
		ctx.fireChannelRead(msg);
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TestServerInitializer.channelReadComplete");
		ctx.fireChannelReadComplete();
	}

	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) {
		System.out.println(this.getClass() + " TestServerInitializer.channelWritabilityChanged");
		ctx.fireChannelWritabilityChanged();
	}
}
