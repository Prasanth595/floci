package io.github.hectorvent.floci.services.rds.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.CertificateMetadata;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the RDS auth proxy's persisted CA and an in-memory server certificate signed by it.
 * The server certificate's SANs cover every advertised Postgres/MySQL host.
 *
 * <p>Persisted at {@code {persistent-path}/tls/rds-ca.{crt,key}} (plus a metadata file recording
 * the current SAN list) so the same root survives restarts. A client only has to trust it once
 * (e.g. {@code sslmode=verify-full sslrootcert=<path>}) to keep working across every local
 * database and every restart, including when advertised hosts change.
 */
@ApplicationScoped
public class RdsProxyTlsCertificates {

    private static final Logger LOG = Logger.getLogger(RdsProxyTlsCertificates.class);

    private static final List<String> DEFAULT_SANS = List.of("localhost", "127.0.0.1");
    private static final String CERT_NAME = "rds-ca.crt";
    private static final String KEY_NAME = "rds-ca.key";
    private static final String METADATA_NAME = "rds-ca.metadata.json";
    private static final String TLS_DIR = "tls";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final EmulatorConfig config;
    private final CertificateGenerator certificateGenerator;
    private final Object lock = new Object();

    private volatile ServerCertificate current;
    private CertificateGenerator.Issuer issuer;

    private record ServerCertificate(SSLContext context, Set<String> sans) {
    }

    @Inject
    public RdsProxyTlsCertificates(EmulatorConfig config, CertificateGenerator certificateGenerator) {
        this.config = config;
        this.certificateGenerator = certificateGenerator;
    }

    /**
     * Ensures {@code host} is covered by the server certificate, issuing a new leaf when needed.
     * The persisted CA is unchanged, so existing client trust stores continue to work.
     */
    public void ensureHost(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        ensureInitialized();
        if (current.sans().contains(host)) {
            return;
        }
        synchronized (lock) {
            if (current.sans().contains(host)) {
                return;
            }
            Set<String> updated = new LinkedHashSet<>(current.sans());
            updated.add(host);
            regenerate(updated);
        }
    }

    /**
     * Returns the current TLS server context. Handlers should call this per-handshake rather than
     * caching it themselves — {@link #ensureHost(String)} can swap it out at any time.
     */
    public SSLContext sslContext() {
        ensureInitialized();
        return current.context();
    }

    private void ensureInitialized() {
        if (current != null) {
            return;
        }
        synchronized (lock) {
            if (current != null) {
                return;
            }
            Path tlsDir = tlsDir();
            try {
                Files.createDirectories(tlsDir);
                restrictToOwnerOnly(tlsDir, "rwx------");
                issuer = loadOrGenerateCa(tlsDir);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize RDS proxy TLS CA", e);
            }
            Set<String> sans = new LinkedHashSet<>(DEFAULT_SANS);
            Path metadataFile = tlsDir.resolve(METADATA_NAME);
            if (Files.exists(metadataFile)) {
                try {
                    sans.addAll(readMetadata(metadataFile));
                } catch (IOException e) {
                    LOG.warnv(e, "RDS proxy TLS: invalid SAN metadata at {0}; keeping the CA and using default hosts",
                            metadataFile);
                }
            }
            regenerate(sans);
            logTrustHint(tlsDir.resolve(CERT_NAME));
        }
    }

    private void regenerate(Set<String> sans) {
        try {
            Path tlsDir = tlsDir();
            List<String> sanList = new ArrayList<>(sans);
            Instant notAfter = Instant.now().plus(365, ChronoUnit.DAYS);
            if (issuer.certificate().getNotAfter().toInstant().isBefore(notAfter)) {
                notAfter = issuer.certificate().getNotAfter().toInstant();
            }
            CertificateGenerator.GeneratedCertificate generated = certificateGenerator.generateIssuedCertificate(
                    "localhost", sanList, KeyAlgorithm.RSA_2048, null, issuer,
                    CertificateGenerator.LeafUsage.SERVER, notAfter);
            X509Certificate certificate = Pem.parseCertificate(generated.certificatePem());
            PrivateKey privateKey = Pem.parsePrivateKey(generated.privateKeyPem());
            SSLContext context = buildSslContext(certificate, privateKey);
            writeMetadata(tlsDir.resolve(METADATA_NAME), sanList);
            current = new ServerCertificate(context, Set.copyOf(sans));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RDS proxy TLS certificate", e);
        }
    }

    private CertificateGenerator.Issuer loadOrGenerateCa(Path tlsDir) throws Exception {
        Path certFile = tlsDir.resolve(CERT_NAME);
        Path keyFile = tlsDir.resolve(KEY_NAME);
        if (Files.exists(certFile) && Files.exists(keyFile)) {
            try {
                X509Certificate certificate = Pem.parseCertificate(Files.readString(certFile));
                PrivateKey privateKey = Pem.parsePrivateKey(Files.readString(keyFile));
                certificate.checkValidity();
                certificate.verify(certificate.getPublicKey());
                if (certificate.getBasicConstraints() < 0 || !Pem.isPair(privateKey, certificate.getPublicKey())) {
                    throw new IllegalStateException("Invalid RDS proxy CA certificate or private key");
                }
                // Legacy installs may have a world-readable CA key.
                restrictToOwnerOnly(keyFile, "rw-------");
                LOG.infov("RDS proxy TLS: reusing existing CA cert: {0}", certFile);
                return new CertificateGenerator.Issuer(certificate, privateKey);
            } catch (Exception e) {
                LOG.warnv(e, "RDS proxy TLS: invalid CA at {0}; generating a replacement that clients must trust again",
                        certFile);
            }
        }
        CertificateGenerator.GeneratedCertificate generated = certificateGenerator.generateCaCertificate("Floci RDS Proxy CA");
        Files.writeString(certFile, generated.certificatePem());
        Files.writeString(keyFile, generated.privateKeyPem());
        restrictToOwnerOnly(keyFile, "rw-------");
        LOG.infov("RDS proxy TLS: generated CA cert at {0}", certFile);
        return new CertificateGenerator.Issuer(Pem.parseCertificate(generated.certificatePem()),
                Pem.parsePrivateKey(generated.privateKeyPem()));
    }

    private SSLContext buildSslContext(X509Certificate certificate, PrivateKey privateKey) throws Exception {
        char[] password = new char[0];
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("floci-rds-proxy", privateKey, password,
                new Certificate[]{certificate, issuer.certificate()});

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    private Path tlsDir() {
        return Path.of(config.storage().persistentPath(), TLS_DIR);
    }

    /**
     * Restricts {@code path} to owner-only access. This CA's private key is meant to be trusted by
     * a developer's client tooling (e.g. {@code sslmode=verify-full}), so unlike Floci's other
     * generated keys — which only let you impersonate an emulated resource — anyone who can read
     * this one can mint a certificate for any host the developer's trust store now accepts.
     * No-ops on filesystems without POSIX permissions (e.g. Windows) rather than failing.
     */
    private static void restrictToOwnerOnly(Path path, String posixPermissions) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posixPermissions));
            }
        } catch (IOException e) {
            LOG.warnv(e, "RDS proxy TLS: failed to restrict permissions on {0}", path);
        }
    }

    private void logTrustHint(Path certFile) {
        LOG.infov("RDS proxy TLS: for sslmode=verify-full set PGSSLROOTCERT={0}",
                certFile.toAbsolutePath());
    }

    private List<String> readMetadata(Path metadataFile) throws IOException {
        CertificateMetadata metadata = OBJECT_MAPPER.readValue(metadataFile.toFile(), CertificateMetadata.class);
        List<String> hostnames = metadata != null ? metadata.getHostnames() : null;
        if (hostnames == null || hostnames.isEmpty()
                || hostnames.stream().anyMatch(host -> host == null || host.isBlank())) {
            throw new IOException("RDS proxy TLS metadata file has invalid hostnames: " + metadataFile);
        }
        return hostnames;
    }

    private void writeMetadata(Path metadataFile, List<String> sans) throws IOException {
        CertificateMetadata metadata = CertificateMetadata.create(sans, resolveFlociVersion());
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
        Files.writeString(metadataFile, json);
    }

    private static String resolveFlociVersion() {
        String env = System.getenv("FLOCI_VERSION");
        return (env != null && !env.isBlank()) ? env : "dev";
    }
}
