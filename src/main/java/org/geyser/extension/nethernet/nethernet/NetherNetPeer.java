package org.geyser.extension.nethernet.nethernet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.cloudburstmc.protocol.bedrock.BedrockSessionFactory;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.geyser.extension.nethernet.nethernet.codec.NetherNetCompressionDecoder;
import org.geyser.extension.nethernet.nethernet.codec.NetherNetCompressionEncoder;
import org.geyser.extension.nethernet.nethernet.codec.NetherNetPacketDecoder;
import org.geyser.extension.nethernet.nethernet.codec.NetherNetPacketEncoder;
import org.geysermc.geyser.network.GeyserBedrockPeer;

import javax.crypto.SecretKey;
import java.util.Objects;

public class NetherNetPeer extends GeyserBedrockPeer {
    public NetherNetPeer(Channel channel, BedrockSessionFactory sessionFactory) {
        super(channel, sessionFactory);
    }

    @Override
    public void enableEncryption(SecretKey secretKey) {
        // No-op
    }

    @Override
    public void setCompression(PacketCompressionAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        this.setCompression(NetherNetChannelInitialiser.getCompression());
    }

    @Override
    public void setCompression(CompressionStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");

        boolean prefixed = this.getCodec().getProtocolVersion() >= 649;

        ChannelPipeline pipeline = this.channel.pipeline();

        if (pipeline.get(NetherNetCompressionDecoder.NAME) == null) {
            pipeline.addBefore(NetherNetPacketDecoder.NAME, NetherNetCompressionDecoder.NAME,
                new NetherNetCompressionDecoder(strategy, prefixed));
        }
        if (pipeline.get(NetherNetCompressionEncoder.NAME) == null) {
            pipeline.addBefore(NetherNetPacketEncoder.NAME, NetherNetCompressionEncoder.NAME,
                new NetherNetCompressionEncoder(strategy, prefixed, 1));
        }
    }
}
