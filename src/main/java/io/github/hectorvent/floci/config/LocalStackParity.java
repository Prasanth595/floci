package io.github.hectorvent.floci.config;

import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates LocalStack Community environment variables into the Floci configuration they mean,
 * so a LocalStack setup works unchanged in the image and in a binary on the host alike.
 *
 * <p>An explicitly set Floci variable always wins: a mapping is produced only when the matching
 * {@code FLOCI_*} or {@code QUARKUS_*} environment variable is absent. The DNS suffixes are the
 * one exception: LocalStack's and Floci's hostname suffixes are appended to any configured ones.
 * The whole translation is off when {@code LOCALSTACK_PARITY=false}.
 */
public final class LocalStackParity {

    private static final Logger LOG = Logger.getLogger(LocalStackParity.class);

    static final String PARITY_SUFFIXES = "localhost.localstack.cloud,localhost.floci.io";

    private LocalStackParity() {
    }

    public static boolean enabled(Map<String, String> env) {
        return !"false".equals(env.get("LOCALSTACK_PARITY"));
    }

    /** The translation for this process's environment, as configuration property names. */
    public static Map<String, String> forCurrentEnvironment() {
        return translate(System.getenv());
    }

    /**
     * @return property name to value, for every LocalStack variable present whose Floci
     *         counterpart is not set; empty when parity is disabled
     */
    public static Map<String, String> translate(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!enabled(env)) {
            return out;
        }

        String persist = firstSet(env, "PERSISTENCE", "PERSIST_STATE");
        if ("1".equals(persist) || "true".equals(persist)) {
            put(out, env, "FLOCI_STORAGE_MODE", "floci.storage.mode", "persistent");
        }

        put(out, env, "FLOCI_PORT", "floci.port", env.get("EDGE_PORT"));
        put(out, env, "FLOCI_HOSTNAME", "floci.hostname", firstSet(env, "LOCALSTACK_HOST", "LOCALSTACK_HOSTNAME"));

        // LocalStack listens on GATEWAY_LISTEN with no further opt-in, so it also gives the
        // consent Floci requires for a non-loopback address.
        String gatewayListen = env.get("GATEWAY_LISTEN");
        if (isSet(gatewayListen)) {
            put(out, env, "QUARKUS_HTTP_HOST", "quarkus.http.host", gatewayListen);
            put(out, env, "FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE",
                    "floci.security.allow-unsafe-network-exposure", "true");
        }

        String lsLog = env.get("LS_LOG");
        if (isSet(lsLog)) {
            put(out, env, "QUARKUS_LOG_LEVEL", "quarkus.log.level", lsLog);
        } else if ("1".equals(env.get("DEBUG"))) {
            put(out, env, "QUARKUS_LOG_LEVEL", "quarkus.log.level", "DEBUG");
        }

        put(out, env, "FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK", "floci.services.lambda.docker-network",
                env.get("LAMBDA_DOCKER_NETWORK"));
        String removeContainers = env.get("LAMBDA_REMOVE_CONTAINERS");
        if ("1".equals(removeContainers) || "true".equals(removeContainers)) {
            put(out, env, "FLOCI_SERVICES_LAMBDA_EPHEMERAL", "floci.services.lambda.ephemeral", "true");
        }
        if (isSet(env.get("LAMBDA_REMOTE_DOCKER"))) {
            LOG.warn("LAMBDA_REMOTE_DOCKER is not fully supported by Floci. Use S3Bucket=hot-reload per function"
                    + " instead. See https://floci.io/docs/services/lambda");
        }

        put(out, env, "FLOCI_DOCKER_DOCKER_HOST", "floci.docker.docker-host", env.get("DOCKER_HOST"));
        // Shared by every container-backed service; per-service networks still take precedence.
        put(out, env, "FLOCI_SERVICES_DOCKER_NETWORK", "floci.services.docker-network", env.get("DOCKER_NETWORK"));

        // Container-to-container hostname routing (Function URLs, presigned S3, SQS QueueUrl, ...).
        String suffixes = env.get("FLOCI_DNS_EXTRA_SUFFIXES");
        out.put("floci.dns.extra-suffixes", isSet(suffixes) ? suffixes + "," + PARITY_SUFFIXES : PARITY_SUFFIXES);

        if ("1".equals(env.get("USE_SSL"))) {
            put(out, env, "FLOCI_TLS_ENABLED", "floci.tls.enabled", "true");
        }
        // LocalStack takes one combined PEM (certificate and key); Floci accepts it in both fields.
        String customCert = env.get("CUSTOM_SSL_CERT_PATH");
        put(out, env, "FLOCI_TLS_CERT_PATH", "floci.tls.cert-path", customCert);
        put(out, env, "FLOCI_TLS_KEY_PATH", "floci.tls.key-path", customCert);

        // LAMBDA_EXECUTOR and SERVICES are intentionally ignored.
        return out;
    }

    private static void put(Map<String, String> out, Map<String, String> env, String flociVariable,
                            String property, String value) {
        if (isSet(value) && !isSet(env.get(flociVariable))) {
            out.put(property, value);
        }
    }

    private static String firstSet(Map<String, String> env, String first, String second) {
        String value = env.get(first);
        return isSet(value) ? value : env.get(second);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }
}
