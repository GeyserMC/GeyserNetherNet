package org.geyser.extension.nethernet;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.interfaces.InterfaceDefaultOptions;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.StringReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;

public final class ConfigLoader {
    public static Config loadConfig(File file) throws IOException { return loadConfig(file, System.getenv()); }

    public static Config loadConfig(File file, Map<String, String> environment) throws IOException {
        var loader = createLoader(file);
        CommentedConfigurationNode node;
        try { node = loader.load(); }
        catch (ConfigurateException invalid) { throw new IOException("Invalid YAML in signalling configuration"); }
        validateKeys(node, Set.of("signalling", "nxs"));
        validateKeys(node.node("nxs"), Set.of("advertise-addresses", "token", "endpoint", "data"));
        boolean save = !file.exists() || node.isNull();
        if (node.node("signalling").virtual()) node.node("signalling").set("hybrid");
        Config config;
        try { config = node.get(Config.class); }
        catch (ConfigurateException invalid) { throw new IOException("Invalid signalling configuration; check value types"); }
        var defaults = CommentedConfigurationNode.root(loader.defaultOptions());
        defaults.set(config);
        node.mergeFrom(defaults);
        if (save) loader.save(node);
        // Apply a single overlay after saving, so credentials from the environment never enter the file.
        for (String key : List.of("signalling", "nxs.advertise-addresses", "nxs.token", "nxs.endpoint", "nxs.data")) {
            String variable = "NETHERNET_" + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
            if (!environment.containsKey(variable)) continue;
            var target = node.node((Object[]) key.split("\\."));
            String value = environment.get(variable);
            if (key.equals("nxs.data") || key.equals("nxs.advertise-addresses")) {
                try {
                    var parsed = YamlConfigurationLoader.builder().source(() -> new BufferedReader(new StringReader(value))).build().load();
                    if (key.equals("nxs.data") ? !parsed.isMap() : !parsed.isList()) throw new IOException();
                    target.set(parsed);
                } catch (Exception invalid) { throw new IOException(variable + " must contain a YAML/JSON " + (key.equals("nxs.data") ? "string map" : "list of IP:port strings")); }
            } else target.set(value);
        }
        try {
            for (var value : node.node("nxs", "data").childrenMap().values()) {
                if (!(value.raw() instanceof String)) throw new IOException("nxs.data values must be strings");
            }
            config = node.get(Config.class);
            if (!Set.of("inbuilt", "nxs", "hybrid", "none").contains(config.signalling()))
                throw new IOException("signalling must be inbuilt, nxs, hybrid, or none");
            return config;
        } catch (ConfigurateException invalid) { throw new IOException("Invalid signalling configuration; check value types"); }
    }

    private static void validateKeys(CommentedConfigurationNode node, Set<String> keys) throws IOException {
        if (!node.virtual() && !node.isNull() && !node.isMap()) throw new IOException("Signalling configuration sections must be maps");
        if (!keys.containsAll(node.childrenMap().keySet())) throw new IOException("Unknown signalling setting; use signalling and nxs.advertise-addresses, token, endpoint, data");
    }

    private static YamlConfigurationLoader createLoader(File file) {
        return YamlConfigurationLoader.builder().file(file).indent(2).nodeStyle(NodeStyle.BLOCK)
            .defaultOptions(InterfaceDefaultOptions::addTo).build();
    }
}
