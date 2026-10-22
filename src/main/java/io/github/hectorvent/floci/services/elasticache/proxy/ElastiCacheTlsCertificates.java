package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Signs the cache listener certificate with the same local CA clients trust for Floci. */
@ApplicationScoped
public class ElastiCacheTlsCertificates {
    private static final Logger LOG = Logger.getLogger(ElastiCacheTlsCertificates.class);

    private final FlociCertificateAuthority authority;
    private final CertificateGenerator generator;
    private final Set<String> hosts;
    private final Clock clock;
    private volatile ServerCertificate context;

    private record ServerCertificate(SSLContext context, Instant notAfter) {}

    @Inject
    public ElastiCacheTlsCertificates(FlociCertificateAuthority authority, EmulatorConfig config,
                                     CertificateGenerator generator) {
        this(authority, config, generator, Clock.systemUTC());
    }

    ElastiCacheTlsCertificates(FlociCertificateAuthority authority, EmulatorConfig config,
                              CertificateGenerator generator, Clock clock) {
        this.authority = authority;
        this.generator = generator;
        this.clock = clock;
        this.hosts = new LinkedHashSet<>(TlsConfigSource.defaultEndpointHostnames());
        hosts.add("::1");
        config.hostname().ifPresent(this::addHost);
        config.services().elasticache().clusterAnnounceHostname().ifPresent(this::addHost);
        String baseUrl = config.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            try {
                addHost(URI.create(baseUrl).getHost());
            } catch (IllegalArgumentException e) {
                LOG.warnv(e, "ElastiCache TLS: could not extract a hostname from floci.base-url: {0}", baseUrl);
            }
        }
    }

    private void addHost(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        String name = host.strip();
        hosts.add(name.startsWith("[") && name.endsWith("]") ? name.substring(1, name.length() - 1) : name);
    }

    public SSLContext sslContext() {
        ServerCertificate current = context;
        if (needsRenewal(current)) {
            synchronized (this) {
                if (needsRenewal(context)) {
                    context = build();
                }
                current = context;
            }
        }
        return current.context();
    }

    private boolean needsRenewal(ServerCertificate certificate) {
        return certificate == null || !clock.instant().isBefore(certificate.notAfter().minusSeconds(60));
    }

    private ServerCertificate build() {
        try {
            CertificateGenerator.GeneratedCertificate certificate = generator.generateIssuedCertificate(
                    "localhost", new ArrayList<>(hosts), KeyAlgorithm.RSA_2048, null, authority.issuer(),
                    CertificateGenerator.LeafUsage.SERVER, clock.instant().plus(365, ChronoUnit.DAYS));
            X509Certificate leaf = Pem.parseCertificate(certificate.certificatePem());
            SSLContext sslContext = authority.serverSslContext(leaf, Pem.parsePrivateKey(certificate.privateKeyPem()));
            return new ServerCertificate(sslContext, leaf.getNotAfter().toInstant());
        } catch (Exception e) {
            LOG.errorv(e, "Failed to issue the ElastiCache endpoint certificate");
            throw new IllegalStateException("Failed to issue the ElastiCache endpoint certificate", e);
        }
    }
}
