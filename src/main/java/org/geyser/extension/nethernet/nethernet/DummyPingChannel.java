package org.geyser.extension.nethernet.nethernet;

import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;

import java.util.Random;

/**
 * A dummy channel used for pinging the server. This is used to work with the Geyser onQuery method, which expects a raknet channel.
 */
public final class DummyPingChannel extends EmbeddedChannel {
    private static final long GUID = new Random().nextLong();

    private ChannelConfig config;

    @Override
    public ChannelConfig config() {
        ChannelConfig c = this.config;
        if (c == null) {
            c = this.config = new DefaultChannelConfig(this) {
                @SuppressWarnings("unchecked")
                @Override
                public <T> T getOption(ChannelOption<T> option) {
                    if (option == RakChannelOption.RAK_GUID) {
                        return (T) (Long) GUID;
                    }
                    return super.getOption(option);
                }
            };
        }
        return c;
    }
}
