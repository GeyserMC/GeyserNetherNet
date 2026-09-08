package org.geyser.extension.nethernet.provider;

import org.geyser.extension.nethernet.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProviderConfigurationTest {
    private ProviderRuntimeConfiguration runtime(Path dir, String yaml, Map<String,String> env) throws Exception {
        Files.writeString(dir.resolve("config.yml"), yaml);
        return ProviderRuntimeConfiguration.resolve(ConfigLoader.loadConfig(dir.resolve("config.yml").toFile(), env), dir, "::", 20000, 40);
    }
    @Test void freshConfigHasOnlyTheFiveOptionsAndInheritsGeyser(@TempDir Path dir) throws Exception {
        var config = ConfigLoader.loadConfig(dir.resolve("config.yml").toFile(), Map.of());
        assertEquals("hybrid", config.signalling());
        String yaml = Files.readString(dir.resolve("config.yml"));
        for (String old : List.of("provider:", "mode:", "https:", "identity:", "capacity:", "state-directory:", "fake-transport:")) assertFalse(yaml.lines().anyMatch(line -> line.stripLeading().startsWith(old)), old);
        var result = ProviderRuntimeConfiguration.resolve(config, dir, "::", 20000, 40);
        assertEquals("https://agent.warden.cloud", result.origin().toString());
        assertEquals("::", result.bindAddress()); assertEquals(20001, result.udpPort());
        assertEquals(40, result.capacity()); assertEquals(dir.resolve("provider-state"), result.stateDirectory());
        assertEquals("automatic", result.clientConfiguration().registrationMode());
        assertEquals("anonymous-proof-of-work", result.clientConfiguration().authorizationScheme());
    }
    @ParameterizedTest @ValueSource(strings = {"inbuilt", "nxs", "hybrid", "none"})
    void supportsEveryModeAndEnvironmentWinsWithoutPersisting(String mode, @TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.yml"), "signalling: none\nnxs:\n  token: yaml-token\n");
        var config = ConfigLoader.loadConfig(dir.resolve("config.yml").toFile(), Map.of(
            "NETHERNET_SIGNALLING", mode, "NETHERNET_NXS_TOKEN", "env-secret", "NETHERNET_NXS_ENDPOINT", "https://signal.example.net",
            "NETHERNET_NXS_DATA", "{region: EU, pool: proxy, location: london}",
            "NETHERNET_NXS_ADVERTISE_ADDRESSES", "['1.1.1.1:29133', '[2606:4700:4700::1111]:39133', '1.1.1.1:29133']"));
        var result = ProviderRuntimeConfiguration.resolve(config, dir, "::", 19132, 20);
        assertEquals(mode, config.signalling()); assertEquals("env-secret", result.authorizationToken());
        assertEquals("https://signal.example.net", result.origin().toString());
        assertEquals("EU", result.region()); assertEquals("proxy", result.pool()); assertEquals(Map.of("location", "london"), result.tags());
        assertEquals(2, result.advertisedEndpoints().size()); assertEquals(39133, result.advertisedEndpoints().get(1).getPort());
        assertFalse(Files.readString(dir.resolve("config.yml")).contains("env-secret"));
        assertFalse(result.toString().contains("env-secret")); assertFalse(result.clientConfiguration().toString().contains("env-secret"));
    }
    @Test void arbitraryMetadataAndRegionOnlyDoNotRequireExtraOptions(@TempDir Path dir) throws Exception {
        var result = runtime(dir, "nxs:\n  data:\n    region: EU\n    role: proxy\n", Map.of());
        assertEquals("EU", result.region()); assertEquals("default", result.pool()); assertEquals(Map.of("role", "proxy"), result.tags());
        assertNull(result.authorizationToken());
    }
    @Test void readsTokenFilesAndFailsClosedWithoutLeakingContents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("token"), "file-secret\n");
        for (String source : List.of("file:token", "./token", dir.resolve("token").toString())) {
            var result = runtime(dir, "nxs:\n  token: '" + source + "'\n", Map.of());
            assertEquals("file-secret", result.authorizationToken());
        }
        for (String value : List.of("file:missing", "file:", "bad secret\n")) {
            var failure = assertThrows(IOException.class, () -> runtime(dir, "", Map.of("NETHERNET_NXS_TOKEN", value)));
            assertFalse(failure.toString().contains("bad secret"));
        }
        Files.writeString(dir.resolve("token"), "\n");
        assertThrows(IOException.class, () -> runtime(dir, "nxs:\n  token: file:token\n", Map.of()));
    }
    @ParameterizedTest @ValueSource(strings = {"example.com:19133", "::1:19133", "[::]:19133", "1.1.1.1:0", "1.1.1.1:65536", "224.0.0.1:19133", "1.1.1.1:1.5", "[fe80::1]:19133"})
    void rejectsInvalidEndpoints(String endpoint, @TempDir Path dir) {
        assertThrows(IOException.class, () -> runtime(dir, "nxs:\n  advertise-addresses: ['" + endpoint + "']\n", Map.of()));
    }
    @Test void rejectsInvalidModesPortsAndEnvironmentShapes(@TempDir Path dir) throws Exception {
        assertThrows(IOException.class, () -> runtime(dir, "signalling: invalid\n", Map.of()));
        assertThrows(IOException.class, () -> runtime(dir, "", Map.of("NETHERNET_NXS_DATA", "[]")));
        assertThrows(IOException.class, () -> runtime(dir, "", Map.of("NETHERNET_NXS_ADVERTISE_ADDRESSES", "{}")));
        var config = ConfigLoader.loadConfig(dir.resolve("fresh.yml").toFile(), Map.of());
        assertThrows(IOException.class, () -> ProviderRuntimeConfiguration.resolve(config, dir, "0.0.0.0", 65535, 20));
    }
}
