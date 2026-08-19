package org.geyser.extension.nethernet;

import dev.kastle.netty.channel.nethernet.NetherNetChannelFactory;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetHTTPSignaling;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import dev.kastle.netty.util.nethernet.NetherNetLogging;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.geyser.extension.nethernet.nethernet.DummyPingChannel;
import org.geyser.extension.nethernet.nethernet.NetherNetChannelInitialiser;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.network.BedrockListener;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

public class NetherNetExtension implements Extension {
    private static final Channel PING_CHANNEL = new DummyPingChannel();

    private Config config;

    private EventLoopGroup eventLoopGroup;
    private Channel netherNetChannel;
    private NetherNetServerSignaling signaling;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        this.logger().info("Loading %s...".formatted(this.description().name()));

        File configFile = dataFolder().resolve("config.yml").toFile();

        // Ensure the data folder exists
        if (!dataFolder().toFile().exists()) {
            if (!dataFolder().toFile().mkdirs()) {
                this.logger().error("Failed to create data folder, extension will not start!");
                this.disable();
                return;
            }
        }

        // Load our configuration
        try {
            config = ConfigLoader.loadConfig(configFile);
        } catch (IOException e) {
            this.logger().error("Failed to load config, extension will not start!", e);
            this.disable();
            return;
        }

        // Keep libdatachannel's own logging out of the way
        NetherNetLogging.setNativeLogLevel("WARN");

        // Start up NetherNet
        try {
            // Build the base signaling instance
            NetherNetHTTPSignaling.Builder signallingBuilder = new NetherNetHTTPSignaling.Builder()
                .setIdentityKeystore(this.dataFolder().resolve(config.identity().keystore()).toFile(), config.identity().password())
                .setMotdProvider((host, remoteAddress) -> {
                    BedrockPong pong = GeyserImpl.getInstance().getGeyserServer().onQuery(PING_CHANNEL, remoteAddress);

                    return new NetherNetServerSignaling.PongData.Builder()
                        .setServerName(pong.motd())
                        .setProtocol(pong.protocolVersion())
                        .setVersion(pong.version())
                        .setPlayerCount(pong.playerCount())
                        .setMaxPlayerCount(pong.maximumPlayerCount())
                        .build();
                });

            // Enable https if configured to do so
            if (config.https().enabled()) {
                signallingBuilder.setHttpsKeystore(this.dataFolder().resolve(config.https().keystore()).toFile(), config.https().password());
            }

            this.signaling = signallingBuilder.build();

            this.eventLoopGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

            ServerBootstrap b = new ServerBootstrap();
            b.group(eventLoopGroup)
                .channelFactory(NetherNetChannelFactory.server(signaling))
                .childHandler(new NetherNetChannelInitialiser(GeyserImpl.getInstance()));

            BedrockListener listener = this.geyserApi().bedrockListener();
            this.netherNetChannel = b.bind(new InetSocketAddress(listener.address(), listener.port())).sync().channel();

            this.logger().info("NetherNet listener started on " + (config.https().enabled() ? "https" : "http") + "://" + listener.address() + ":" + listener.port());
        } catch (Exception e) {
            this.logger().error("Failed to start NetherNet", e);
            this.disable();
        }
    }

    @Subscribe
    public void onGeyserShutdown(GeyserShutdownEvent event) {
        shutdown();
    }

    @Override
    public void disable() {
        shutdown();
        Extension.super.disable();
    }

    private void shutdown() {
        if (this.netherNetChannel != null) {
            this.netherNetChannel.close();
        }
        if (this.eventLoopGroup != null) {
            this.eventLoopGroup.shutdownGracefully();
        }
    }
}
