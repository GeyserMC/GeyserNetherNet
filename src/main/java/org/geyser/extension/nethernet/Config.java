package org.geyser.extension.nethernet;

import org.spongepowered.configurate.interfaces.meta.defaults.DefaultBoolean;
import org.spongepowered.configurate.interfaces.meta.defaults.DefaultString;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public interface Config {
    @Comment("HTTPS settings")
    HttpsConfig https();

    @Comment("Identity settings")
    IdentityConfig identity();

    @ConfigSerializable
    interface HttpsConfig {
        @Comment("Whether HTTPS is enabled")
        @DefaultBoolean(true)
        boolean enabled();

        @Comment("Path to the https keystore file")
        @DefaultString("https.p12")
        String keystore();

        @Comment("Password for the https keystore file")
        @DefaultString()
        String password();
    }

    @ConfigSerializable
    interface IdentityConfig {
        @Comment("Path to the identity keystore file")
        @DefaultString("identity.p12")
        String keystore();

        @Comment("Password for the identity keystore file")
        @DefaultString()
        String password();
    }

    @Comment("Do not change!")
    @SuppressWarnings("unused")
    default int configVersion() {
        return 1;
    }
}
