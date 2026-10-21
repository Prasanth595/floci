package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.net.ssl.SSLContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Signs the cache listener certificate with the same local CA clients trust for Floci. */
@ApplicationScoped
public class ElastiCacheTlsCertificates {
    private final FlociCertificateAuthority authority;
    private final Set<String> hosts;
    private volatile SSLContext context;

    @Inject
    public ElastiCacheTlsCertificates(FlociCertificateAuthority authority, EmulatorConfig config) {
        this.authority = authority;
        this.hosts = new LinkedHashSet<>(List.of("localhost", "127.0.0.1"));
        config.hostname().ifPresent(hosts::add);
        config.services().elasticache().clusterAnnounceHostname().ifPresent(hosts::add);
    }

    public SSLContext sslContext() {
        SSLContext current = context;
        if (current == null) {
            synchronized (this) {
                if (context == null) {
                    context = build();
                }
                current = context;
            }
        }
        return current;
    }

    private SSLContext build() {
        try {
            CertificateGenerator.GeneratedCertificate certificate = authority.issueServerCertificate(
                    "localhost", new ArrayList<>(hosts), KeyAlgorithm.RSA_2048, null);
            return authority.serverSslContext(Pem.parseCertificate(certificate.certificatePem()),
                    Pem.parsePrivateKey(certificate.privateKeyPem()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue the ElastiCache endpoint certificate", e);
        }
    }
}
