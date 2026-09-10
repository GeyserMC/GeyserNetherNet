package org.geyser.extension.nethernet.admission;

import dev.kastle.netty.util.nethernet.ServerIdentity;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Automatic, persistent ES384 identity for inbuilt HTTP signalling.
 */
public final class InbuiltIdentity {
    private InbuiltIdentity() {
    }

    public static void ensure(Path directory) throws Exception {
        Path file = directory.resolve("identity.p12");
        if (Files.isSymbolicLink(file)) throw new java.io.IOException("Inbuilt identity must not be a symbolic link");
        if (!Files.exists(file)) {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"));
            var pair = generator.generateKeyPair();
            var now = Instant.now();
            var name = new X500Name("CN=Geyser");
            var builder = new JcaX509v3CertificateBuilder(name, new BigInteger(159, new SecureRandom()).add(BigInteger.ONE),
                    Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(3650, ChronoUnit.DAYS)), name, pair.getPublic());
            var certificate = new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA384withECDSA").build(pair.getPrivate())));
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, new char[0]);
            store.setKeyEntry("identity", pair.getPrivate(), new char[0], new Certificate[]{certificate});
            Path temporary = Files.createTempFile(directory, ".identity-", ".p12", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                try (var output = Files.newOutputStream(temporary)) {
                    store.store(output, new char[0]);
                }
                Files.move(temporary, file); // Never replace an identity created by another process.
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        ServerIdentity.fromKeystore(file.toFile(), "");
    }
}
