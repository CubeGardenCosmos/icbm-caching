package cn.cubegarden.icbm.caching.virtual.net;

import cn.cubegarden.icbm.caching.virtual.config.VirtualBackendConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 26.3 游戏监听（net/minecraft/server/network/ServerConnectionListener.java 对照）：
 * ServerBootstrap + TCP_NODELAY + ReadTimeoutHandler(30) + 帧切分 + 会话处理器。
 */
public final class GameServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameServer.class);

    private final VirtualBackendConfig config;
    private final SessionFactory sessionFactory;
    private final CopyOnWriteArrayList<ClientConnection> connections = new CopyOnWriteArrayList<>();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running;

    /** 会话工厂：连接建立后创建初始（handshake）会话。 */
    public interface SessionFactory {
        ClientConnection newConnection(Channel channel);
    }

    public GameServer(VirtualBackendConfig config, SessionFactory sessionFactory) {
        this.config = config;
        this.sessionFactory = sessionFactory;
    }

    public void start() throws InterruptedException {
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(this.bossGroup, this.workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new io.netty.channel.WriteBufferWaterMark(1 << 20, 1 << 21))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("timeout", new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                                .addLast("splitter", new FrameSplitter());
                        ClientConnection connection = GameServer.this.sessionFactory.newConnection(ch);
                        GameServer.this.connections.add(connection);
                        ch.pipeline().addLast("connection", connection);
                    }
                });
        this.serverChannel = bootstrap.bind(this.config.gameHost(), this.config.gamePort()).sync().channel();
        this.running = true;
        LOGGER.info("[vb] 游戏监听 {}:{}", this.config.gameHost(), this.config.gamePort());
    }

    public void onDisconnected(ClientConnection connection) {
        this.connections.remove(connection);
    }

    public int connectionCount() {
        return this.connections.size();
    }

    public boolean isRunning() {
        return this.running;
    }

    @Override
    public void close() {
        this.running = false;
        if (this.serverChannel != null) {
            this.serverChannel.close().awaitUninterruptibly();
        }
        if (this.bossGroup != null) {
            this.bossGroup.shutdownGracefully();
        }
        if (this.workerGroup != null) {
            this.workerGroup.shutdownGracefully();
        }
    }
}
