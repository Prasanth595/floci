package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RdsProxyTlsCertificatesTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureHostIsIdempotentWhenHostAlreadyCovered() {
        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");
        SSLContext firstContext = certs.sslContext();

        certs.ensureHost("172.17.0.5");

        assertSame(firstContext, certs.sslContext(), "no regeneration for an already-covered host");
    }

    @Test
    void ensureHostRegeneratesWhenHostIsNew() {
        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");
        SSLContext firstContext = certs.sslContext();

        certs.ensureHost("host.docker.internal");

        assertNotSame(firstContext, certs.sslContext(), "a genuinely new host must trigger regeneration");
    }

    @Test
    void persistsAndReloadsSansAcrossInstances() throws Exception {
        RdsProxyTlsCertificates first = newCertificates();
        first.ensureHost("172.17.0.5");
        first.ensureHost("host.docker.internal");

        Path certFile = tempDir.resolve("tls").resolve("rds-ca.crt");
        Path keyFile = tempDir.resolve("tls").resolve("rds-ca.key");
        Path metadataFile = tempDir.resolve("tls").resolve("rds-ca.metadata.json");
        assertTrue(Files.exists(certFile));
        assertTrue(Files.exists(keyFile));
        assertTrue(Files.exists(metadataFile));
        String persistedCertPem = Files.readString(certFile);

        // A fresh instance (simulating a restart) backed by the same persistent path must reuse
        // the same cert rather than regenerating — otherwise a user's trusted sslrootcert goes stale
        // on every restart even when no new host has appeared.
        RdsProxyTlsCertificates reloaded = newCertificates();
        reloaded.ensureHost("172.17.0.5");
        reloaded.ensureHost("host.docker.internal");

        assertEquals(persistedCertPem, Files.readString(certFile));
    }

    @Test
    void growingSansReusesTheSameKeyPairSoOlderCertCopiesStillValidateNewOnes() throws Exception {
        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");

        X509Certificate firstCertificate = Pem.parseCertificate(
                Files.readString(tempDir.resolve("tls").resolve("rds-ca.crt")));

        certs.ensureHost("host.docker.internal");

        X509Certificate secondCertificate = Pem.parseCertificate(
                Files.readString(tempDir.resolve("tls").resolve("rds-ca.crt")));

        assertEquals(firstCertificate.getPublicKey(), secondCertificate.getPublicKey(),
                "growing the SAN list must reuse the existing key pair, not mint a new one, "
                        + "or a client that already trusts a prior copy of the CA loses trust");
        assertDoesNotThrow(() -> secondCertificate.verify(firstCertificate.getPublicKey()),
                "the old cert's public key must still validate the reissued cert's signature");
    }

    @Test
    void privateKeyAndTlsDirAreRestrictedToOwnerOnly() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView(PosixFileAttributeView.class),
                "POSIX permissions are not supported on this filesystem");

        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");

        Path keyFile = tlsDir.resolve("rds-ca.key");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(keyFile),
                "the CA private key must not be readable by anyone but the owner");
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(tlsDir),
                "the tls directory must not be traversable by anyone but the owner");
    }

    @Test
    void generatedCertificateIsAGenuineTrustAnchor() throws Exception {
        RdsProxyTlsCertificates certs = newCertificates();
        certs.ensureHost("172.17.0.5");

        X509Certificate certificate = Pem.parseCertificate(
                Files.readString(tempDir.resolve("tls").resolve("rds-ca.crt")));

        assertEquals(certificate.getIssuerX500Principal(), certificate.getSubjectX500Principal());
        assertTrue(certificate.getBasicConstraints() >= 0);
    }

    @Test
    void migratingLegacyCaAndGrowingSansNeverChangesTheTrustedCertificate() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        CertificateGenerator.GeneratedCertificate legacy = new CertificateGenerator()
                .generateSelfSignedCertificate("localhost", List.of("localhost", "172.17.0.5"), KeyAlgorithm.RSA_2048);
        Path certFile = tlsDir.resolve("rds-ca.crt");
        Path keyFile = tlsDir.resolve("rds-ca.key");
        Files.writeString(certFile, legacy.certificatePem());
        Files.writeString(keyFile, legacy.privateKeyPem());
        Files.writeString(tlsDir.resolve("rds-ca.metadata.json"), "{\"hostnames\":[\"localhost\",\"172.17.0.5\"]}");
        boolean posix = Files.getFileStore(tempDir).supportsFileAttributeView(PosixFileAttributeView.class);
        if (posix) {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-r--r--"));
        }

        RdsProxyTlsCertificates migrated = newCertificates();
        migrated.ensureHost("172.17.0.5");
        migrated.ensureHost("host.docker.internal");
        newCertificates().ensureHost("host.docker.internal");

        assertEquals(legacy.certificatePem(), Files.readString(certFile));
        assertEquals(legacy.privateKeyPem(), Files.readString(keyFile));
        if (posix) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keyFile));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"{", "null", "{\"hostnames\":[null]}", "{\"hostnames\":[\" \"]}", "{\"hostnames\":[]}"})
    void recoveringMetadataDoesNotReplaceAValidTrustAnchor(String metadata) throws Exception {
        newCertificates().sslContext();
        Path tlsDir = tempDir.resolve("tls");
        Path certFile = tlsDir.resolve("rds-ca.crt");
        String trustedCertificate = Files.readString(certFile);
        if (metadata != null) {
            Files.writeString(tlsDir.resolve("rds-ca.metadata.json"), metadata);
        } else {
            Files.delete(tlsDir.resolve("rds-ca.metadata.json"));
        }

        newCertificates().sslContext();

        assertEquals(trustedCertificate, Files.readString(certFile));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacesAnUnusableCaKeyWithAMatchingTrustAnchor(boolean malformedKey) throws Exception {
        newCertificates().sslContext();
        Path certFile = tempDir.resolve("tls/rds-ca.crt");
        Path keyFile = tempDir.resolve("tls/rds-ca.key");
        String previousCa = Files.readString(certFile);
        String replacementKey = malformedKey ? "not a private key"
                : new CertificateGenerator().generateCaCertificate("Unrelated CA").privateKeyPem();
        Files.writeString(keyFile, replacementKey);

        newCertificates().sslContext();

        assertNotEquals(previousCa, Files.readString(certFile));
        X509Certificate ca = Pem.parseCertificate(Files.readString(certFile));
        assertTrue(Pem.isPair(Pem.parsePrivateKey(Files.readString(keyFile)), ca.getPublicKey()));
    }

    private RdsProxyTlsCertificates newCertificates() {
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(storage.persistentPath()).thenReturn(tempDir.toString());
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.storage()).thenReturn(storage);
        return new RdsProxyTlsCertificates(config, new CertificateGenerator());
    }
}
