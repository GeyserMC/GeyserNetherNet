package org.geyser.extension.nethernet.nethernet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.ZlibCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.common.util.Zlib;
import org.geyser.extension.nethernet.nethernet.codec.NetherNetPacketDecoder;
import org.geyser.extension.nethernet.nethernet.codec.NetherNetPacketEncoder;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.InvalidPacketHandler;
import org.geysermc.geyser.network.GameProtocol;
import org.geyser.extension.nethernet.provider.GameOutcomeReporter;
import org.geysermc.geyser.network.UpstreamPacketHandler;
import org.geysermc.geyser.session.GeyserSession;

/**
 * Closely mirrors {@link org.geysermc.geyser.network.GeyserServerInitializer} but with the addition of NetherNet packet encoder/decoder and a different peer implementation.
 */
public class NetherNetChannelInitialiser extends ChannelInitializer<Channel> {
    private static final CompressionStrategy ZLIB_RAW_STRATEGY = new SimpleCompressionStrategy(new ZlibCompression(Zlib.RAW));

    private final GeyserImpl geyser;
    private final GameOutcomeReporter outcomes;

    private final DefaultEventLoopGroup eventLoopGroup;

    public NetherNetChannelInitialiser(GeyserImpl geyser) {
        this(geyser, new GameOutcomeReporter());
    }

    public NetherNetChannelInitialiser(GeyserImpl geyser, GameOutcomeReporter outcomes) {
        this.geyser = geyser;
        this.outcomes = outcomes;
        this.eventLoopGroup = new DefaultEventLoopGroup(0, new DefaultThreadFactory("Geyser NetherNet player thread"));
    }

    @Override
    protected void initChannel(Channel channel) throws Exception {
        channel.pipeline()
                .addLast(NetherNetPacketDecoder.NAME, new NetherNetPacketDecoder())
                .addLast(NetherNetPacketEncoder.NAME, new NetherNetPacketEncoder())
                .addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3())
                .addLast(GameOutcomeReporter.HANDLER_NAME, outcomes.observer(protocol -> GameProtocol.getBedrockCodec(protocol) != null))
                .addLast(BedrockPeer.NAME, new NetherNetPeer(channel, this::createSession));
    }

    public static CompressionStrategy getCompression() {
        return ZLIB_RAW_STRATEGY;
    }

    private BedrockServerSession createSession(BedrockPeer peer, int subClientId) {
        BedrockServerSession session = new BedrockServerSession(peer, subClientId);
        initSession(session);
        return session;
    }

    protected void initSession(BedrockServerSession bedrockServerSession) {
        try {
            bedrockServerSession.setLogging(this.geyser.config().debugMode());
            GeyserSession session = new GeyserSession(this.geyser, bedrockServerSession, this.eventLoopGroup.next());

            if (!bedrockServerSession.isSubClient()) {
                Channel channel = bedrockServerSession.getPeer().getChannel();
                channel.pipeline().addAfter(BedrockPacketCodec.NAME, InvalidPacketHandler.NAME, new InvalidPacketHandler(session));
            }

            bedrockServerSession.setPacketHandler(new UpstreamPacketHandler(this.geyser, session));
        } catch (Throwable e) {
            // Error must be caught or it will be swallowed
            this.geyser.getLogger().error("Error occurred while initializing player!", e);
            bedrockServerSession.disconnect(e.getMessage());
        }
    }
}
