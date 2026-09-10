package org.geyser.extension.nethernet.provider;

import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.netty.signalling.ServerStatus;

/**
 * The Bedrock query supplies protocol/version; actual Geyser sessions supply players.
 */
public final class GeyserStatusCollector {
    private GeyserStatusCollector() {
    }

    public static ServerStatus snapshot(BedrockPong pong, int sessions, String level, int gameType) {
        return new ServerStatus(pong.motd(), pong.protocolVersion(), pong.version(), level, sessions, pong.maximumPlayerCount(), gameType);
    }
}
