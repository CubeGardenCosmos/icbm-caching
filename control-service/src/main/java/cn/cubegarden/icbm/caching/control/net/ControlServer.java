package cn.cubegarden.icbm.caching.control.net;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.control.tls.TlsContextFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制服务 netty 服务器（对照 ServerConnectionListener / Velocity ConnectionManager 骨架，
 * 草稿 phase2-control-protocol §1/§2）：
 * pipeline = SSL(mTLS) → 帧切分 → 消息解码 → PeerSession（状态机 + 业务分发）。
 */
public final class ControlServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlServer.class);

    private final ServiceConfig config;
    private final ControlService service;
    private final Replicator replicator;
    private final PeerRegistry registry;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean stopped;

    public ControlServer(ServiceConfig config, ControlService service, Replicator replicator, PeerRegistry registry) {
        this.config = config;
        this.service = service;
        this.replicator = replicator;
        this.registry = registry;
    }

    public void start() throws Exception {
        SslContext sslContext = null;
        if (this.config.tlsEnabled()) {
            sslContext = SslContextBuilder.forServer(TlsContextFactory.keyManagers(
                            this.config.keyStore(), this.config.keyStorePassword()))
                    .trustManager(TlsContextFactory.trustManagers(
                            this.config.trustStore(), this.config.trustStorePassword()))
                    .clientAuth(this.config.requireClientCert()
                            ? io.netty.handler.ssl.ClientAuth.REQUIRE
                            : io.netty.handler.ssl.ClientAuth.OPTIONAL)
                    .build();
        } else {
            LOGGER.warn("[control] TLS 未配置（仅限本地测试，生产必须 mTLS）");
        }
        SslContext finalSsl = sslContext;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(this.bossGroup, this.workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        var pipeline = ch.pipeline();
                        if (finalSsl != null) {
                            pipeline.addLast("ssl", finalSsl.newHandler(ch.alloc()));
                        }
                        pipeline.addLast("timeout", new ReadTimeoutHandler(60, TimeUnit.SECONDS));
                        pipeline.addLast("framer", MessageFramer.inboundFrameDecoder(config.maxFrameBytes()));
                        pipeline.addLast("decoder", new MessageFramer.MessageDecoder());
                        pipeline.addLast("encoder", MessageFramer.outboundFramePrepender());
                        pipeline.addLast("msg_encoder", new MessageFramer.MessageEncoder());
                        pipeline.addLast("session", new PeerSession(ch, config, registry, service, replicator));
                    }
                })
                .localAddress(this.config.host(), this.config.port());
        this.serverChannel = bootstrap.bind().syncUninterruptibly().channel();
        // 周期滞后检测（仅状态记录/日志；推送限流由窗口自然处理）
        this.serverChannel.eventLoop().scheduleAtFixedRate(
                this.replicator::heartbeatTick, this.config.heartbeatIntervalMillis(),
                this.config.heartbeatIntervalMillis(), TimeUnit.MILLISECONDS);
        LOGGER.info("[control] 监听 {}:{}（mTLS={}）", this.config.host(), this.config.port(),
                this.config.tlsEnabled());
    }

    /** 实际监听端口（配置 0 时用于测试）。 */
    public int port() {
        return ((java.net.InetSocketAddress) this.serverChannel.localAddress()).getPort();
    }

    public void stop() {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        this.service.markShuttingDown();
        if (this.serverChannel != null) {
            this.serverChannel.close().syncUninterruptibly();
        }
        if (this.bossGroup != null) {
            this.bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (this.workerGroup != null) {
            this.workerGroup.shutdownGracefully().syncUninterruptibly();
        }
    }
}
