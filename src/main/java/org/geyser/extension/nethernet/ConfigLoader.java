package org.geyser.extension.nethernet;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.interfaces.InterfaceDefaultOptions;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;

public class ConfigLoader {
    private static final ConfigurationTransformation.Versioned TRANSFORMER = ConfigurationTransformation.versionedBuilder()
        .versionKey("config-version")
        .addVersion(1, ConfigurationTransformation.builder().build())
        .build();

    public static Config loadConfig(File configFile) throws ConfigurateException {
        YamlConfigurationLoader loader = createLoader(configFile);

        CommentedConfigurationNode node = loader.load();
        boolean originallyEmpty = !configFile.exists() || node.isNull();

        int currentVersion = TRANSFORMER.version(node);
        TRANSFORMER.apply(node);
        int newVersion = TRANSFORMER.version(node);

        Config config = node.get(Config.class);

        // Keep ordering
        CommentedConfigurationNode newRoot = CommentedConfigurationNode.root(loader.defaultOptions());
        newRoot.set(config);

        if (originallyEmpty || currentVersion != newVersion) {
            loader.save(newRoot);
        }

        return config;
    }

    private static YamlConfigurationLoader createLoader(File configFile) {
        return YamlConfigurationLoader.builder()
            .file(configFile)
            .indent(2)
            .nodeStyle(NodeStyle.BLOCK)
            .defaultOptions(InterfaceDefaultOptions::addTo)
            .build();
    }
}
