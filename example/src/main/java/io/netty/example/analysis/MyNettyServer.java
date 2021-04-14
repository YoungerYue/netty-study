package io.netty.example.analysis;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;

public class MyNettyServer {
	public static void main(String[] args) {
		EventLoopGroup bossGroup = new NioEventLoopGroup(1);
		EventLoopGroup workerGroup = new NioEventLoopGroup();

		try {
			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					//设置线程队列中等待连接的个数
					.option(ChannelOption.SO_BACKLOG, 128)
					//保持活动连接状态
					.childOption(ChannelOption.SO_KEEPALIVE, true)
					// .handler(new NettyTestHandler())
					.handler(new TestServerInitializer())
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							// ch.pipeline().addLast(new MyNettyServerHendler());
							ch.pipeline().addLast(new StringDecoder(), new NettyServerHendler());
						}
					});

			ChannelFuture cf = bootstrap.bind(8888).sync();
			cf.addListener((ChannelFutureListener) future -> {
				if (cf.isSuccess()) {
					System.out.println("监听端口 8888 成功");
				} else {
					System.out.println("监听端口 8888 失败");
				}
			});
			cf.channel().closeFuture().sync();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}finally {
			bossGroup.shutdownGracefully();
			workerGroup.shutdownGracefully();
		}
	}
}
