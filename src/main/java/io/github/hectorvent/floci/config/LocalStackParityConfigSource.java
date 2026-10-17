package io.github.hectorvent.floci.config;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

/**
 * Serves {@link LocalStackParity}'s translation as configuration.
 *
 * <p>The ordinal (301) sits just above environment variables, matching the shell script this
 * replaces, which exported the translated values as {@code FLOCI_*} variables. A translated key is
 * only present when its Floci variable is unset, so it never shadows one; system properties (400)
 * still win.
 */
public class LocalStackParityConfigSource implements ConfigSource {

    static final int ORDINAL = 301;

    private final Map<String, String> properties;

    public LocalStackParityConfigSource() {
        this(LocalStackParity.forCurrentEnvironment());
    }

    LocalStackParityConfigSource(Map<String, String> properties) {
        this.properties = Map.copyOf(properties);
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return "floci-localstack-parity";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }
}
