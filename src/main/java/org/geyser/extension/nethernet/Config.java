package org.geyser.extension.nethernet;

import org.spongepowered.configurate.interfaces.meta.defaults.DefaultString;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.List;
import java.util.Map;

@ConfigSerializable
public interface Config {
    @Comment("Signalling: inbuilt, nxs, hybrid (both), or none. Restart to apply changes.")
    @DefaultString("hybrid")
    String signalling();

    NxsConfig nxs();

    @ConfigSerializable
    interface NxsConfig {
        @Comment("Additional reachable UDP endpoints, e.g. 198.51.100.1:19133 or [2001:db8::1]:19133. Configure forwarding separately.")
        default List<String> advertiseAddresses() {
            return List.of();
        }

        @Comment("Bearer token or file:/path/to/token. Empty uses anonymous registration.")
        @DefaultString("")
        String token();

        @Comment("NXS provider origin used for discovery and registration.")
        @DefaultString("https://agent.warden.cloud")
        String endpoint();

        @Comment("Instance metadata. region and pool select placement; other keys are registration tags.")
        default Map<String, String> data() {
            return Map.of();
        }
    }

}
