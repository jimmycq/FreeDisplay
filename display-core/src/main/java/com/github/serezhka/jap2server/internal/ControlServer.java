package com.github.serezhka.jap2server.internal;

import com.github.serezhka.jap2server.AirplayDataConsumer;
import com.github.serezhka.jap2server.internal.handler.control.FairPlayHandler;
import com.github.serezhka.jap2server.internal.handler.control.HeartBeatHandler;
import com.github.serezhka.jap2server.internal.handler.control.PairingHandler;
import com.github.serezhka.jap2server.internal.handler.control.RTSPHandler;
import com.github.serezhka.jap2server.internal.handler.mirroring.MirroringHandler;
import com.github.serezhka.jap2server.internal.handler.session.SessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class ControlServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MirroringHandler.class);

    private final PairingHandler pairingHandler;
    private final FairPlayHandler fairPlayHandler;
    private final RTSPHandler rtspHandler;
    private final HeartBeatHandler heartBeatHandler;
    private final SessionManager sessionManager;

    private final int airTunesPort;
    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final ChannelGroup clientChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    public ControlServer(int airPlayPort, int airTunesPort, AirplayDataConsumer airplayDataConsumer) {
        this.airTunesPort = airTunesPort;
        this.sessionManager = new SessionManager();
        pairingHandler = new PairingHandler(sessionManager);
        fairPlayHandler = new FairPlayHandler(sessionManager);
        rtspHandler = new RTSPHandler(airPlayPort, airTunesPort, sessionManager, airplayDataConsumer);
        heartBeatHandler = new HeartBeatHandler(sessionManager);
    }

    @Override
    public void run() {
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        bossGroup = eventLoopGroup(1);
        workerGroup = eventLoopGroup(1);
        try {
            serverBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(airTunesPort))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) throws Exception {
                            clientChannels.add(ch);
                            ch.pipeline().addLast(
                                    new RtspDecoder(),
                                    new RtspEncoder(),
                                    new HttpObjectAggregator(64 * 1024),
                                    new LoggingHandler(LogLevel.DEBUG),
                                    pairingHandler,
                                    fairPlayHandler,
                                    rtspHandler,
                                    heartBeatHandler);
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            ChannelFuture channelFuture = serverBootstrap.bind().sync();
            serverChannel = channelFuture.channel();
            log.info("Control server listening on port: {}", airTunesPort);
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            log.info("Control server interrupted");
        } catch (Exception e) {
            log.error("Control server error", e);
        } finally {
            log.info("Control server stopped");
            if (bossGroup != null) bossGroup.shutdownGracefully();
            if (workerGroup != null) workerGroup.shutdownGracefully();
        }
    }

    public void stopAllSessions() {
        if (sessionManager != null) {
            sessionManager.stopAllSessions();
        }
    }

    /**
     * Force-close every AirPlay client TCP connection so the old source cannot
     * keep an RTSP mirroring session alive after the user switches devices.
     */
    public void disconnectAllClients() {
        try {
            clientChannels.close().awaitUninterruptibly(1000);
        } catch (Exception ignored) {
        }
    }

    public void stop() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (Exception ignored) {}
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    private EventLoopGroup eventLoopGroup(int nThreads) {
        return Epoll.isAvailable() ? new EpollEventLoopGroup(nThreads) : new NioEventLoopGroup(nThreads);
    }

    private Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }
}
