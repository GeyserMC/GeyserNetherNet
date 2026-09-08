package org.geyser.extension.nethernet.provider;

import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.netty.signalling.ServerStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeyserStatusCollectorTest {
    @Test void usesBedrockQueryAndCurrentSessionsWithExplicitUndiscoverableFields() {
        BedrockPong pong = new BedrockPong().motd("Geyser").protocolVersion(1234).version("preview-fixture").playerCount(99).maximumPlayerCount(50);
        assertEquals(new ServerStatus("Geyser", 1234, "preview-fixture", "world", 2, 50, 1), GeyserStatusCollector.snapshot(pong, 2, "world", 1));
        pong.motd("Reloaded").maximumPlayerCount(70);
        assertEquals(new ServerStatus("Reloaded", 1234, "preview-fixture", "new world", 3, 70, 2), GeyserStatusCollector.snapshot(pong, 3, "new world", 2));
        assertEquals(1, GeyserStatusCollector.snapshot(pong, 1, "", 0).players());
    }
    @Test void invalidOrUnavailableQueryCannotFabricateAStatus() {
        assertThrows(NullPointerException.class, () -> GeyserStatusCollector.snapshot(null, 1, "", 0));
        assertThrows(IllegalArgumentException.class, () -> GeyserStatusCollector.snapshot(new BedrockPong().motd("x").protocolVersion(0).version("unknown"), 0, "", 0));
    }
}
