package io.github.hectorvent.floci.services.lambdaweb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A network entry point of a Web Function: a domain name and the revisions it routes to. */
@RegisterForReflection
public final class WebFunctionEndpoint {

    public String endpointName;
    public String endpointArn;
    public String functionArn;
    public String description;
    public String endpointType;
    public String domainName;
    public String authType;
    public String autoDeploymentMode;
    public List<RevisionWeight> revisionWeights = new ArrayList<>();
    public List<String> regions = new ArrayList<>();
    /** Null until set. Clearing it leaves an empty object, which the wire shows as {@code {}}. */
    public ScalingConfig scalingConfig;
    /** Null until set. Clearing it leaves an empty object, which the wire shows as {@code {}}. */
    public ThrottleConfig throttleConfig;
    /** Null until the first update. */
    public String updateStatus;
    /** PerRegion endpoints: the domain name each region serves, keyed by region. */
    public Map<String, String> regionalDomainNames = new LinkedHashMap<>();
    public Instant createdAt;
    public Instant updatedAt;

    @RegisterForReflection
    public static final class RevisionWeight {
        public String revisionId;
        public int weight;
    }

    @RegisterForReflection
    public static final class ScalingConfig {
        public Integer maxEnvironments;
    }

    @RegisterForReflection
    public static final class ThrottleConfig {
        public Integer rateLimit;
    }
}
