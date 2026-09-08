package org.geyser.extension.nethernet.admission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class InbuiltIdentityTest {
    @Test void createsAndReusesPrivateSigningIdentity(@TempDir Path directory) throws Exception {
        InbuiltIdentity.ensure(directory);
        byte[] before = Files.readAllBytes(directory.resolve("identity.p12"));
        InbuiltIdentity.ensure(directory);
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("identity.p12")));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("identity.p12"))));
    }
    @Test void refusesToReplaceInvalidIdentity(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("identity.p12"), "existing-invalid-identity");
        assertThrows(Exception.class, () -> InbuiltIdentity.ensure(directory));
        assertEquals("existing-invalid-identity", Files.readString(directory.resolve("identity.p12")));
    }
}
