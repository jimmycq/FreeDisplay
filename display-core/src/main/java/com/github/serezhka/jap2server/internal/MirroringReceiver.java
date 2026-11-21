package com.github.serezhka.jap2server.internal;

import android.os.Process;

import com.github.serezhka.jap2lib.AirPlay;
import com.github.serezhka.jap2server.AirplayDataConsumer;
import com.github.serezhka.jap2server.internal.handler.mirroring.MirroringHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

public class MirroringReceiver implements Runnable {

    private final int port;
    private final AirPlay airPlay;
    private final AirplayDataConsumer dataConsumer;

    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public MirroringReceiver(int port, AirPlay airPlay, AirplayDataConsumer dataConsumer) {
        this.port = port;
        this.airPlay = airPlay;
        this.dataConsumer = dataConsumer;
    }

    public void stop() {
        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close().syncUninterruptibly();
            }
        } catch (Exception ignored) {}
        try {
            if (bossGroup != null) bossGroup.shutdownGracefully(0, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (workerGroup != null) workerGroup.shutdownGracefully(0, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        // Nokia N1 Intel Atom quad-core optimization: allocate minimal dedicated threads
        bossGroup = eventLoopGroup(1);
        workerGroup = eventLoopGroup(2);
        try {
            serverBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(port))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) {
                            // Each channel connection gets its own MirroringHandler instance (non-sharable)
                            ch.pipeline().addLast(new MirroringHandler(airPlay, dataConsumer));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 64)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

            ChannelFuture channelFuture = null;
            for (int attempt = 1; attempt <= 4; attempt++) {
                try {
                    channelFuture = serverBootstrap.bind().sync();
                    android.util.Log.i("MirroringReceiver", "Successfully bound MirroringReceiver on port " + port);
                    break;
                } catch (Exception bindEx) {
                    android.util.Log.w("MirroringReceiver", "Port " + port + " bind attempt " + attempt + " failed: " + bindEx.getMessage());
                    if (attempt == 4) throw bindEx;
                    Thread.sleep(150);
                }
            }
            if (channelFuture != null) {
                serverChannel = channelFuture.channel();
                serverChannel.closeFuture().sync();
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable e) {
            android.util.Log.e("MirroringReceiver", "MirroringReceiver bind or runtime error: " + e.getMessage());
        } finally {
            if (bossGroup != null) bossGroup.shutdownGracefully();
            if (workerGroup != null) workerGroup.shutdownGracefully();
        }
    }

    private EventLoopGroup eventLoopGroup(int nThreads) {
        return Epoll.isAvailable() ? new EpollEventLoopGroup(nThreads) : new NioEventLoopGroup(nThreads);
    }

    private Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }
}
