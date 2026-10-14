package io.github.hectorvent.floci.services.lambdaweb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Lambda Web Function with its revisions and endpoints. The function is the aggregate that is
 * stored: revisions and endpoints only exist inside one.
 */
@RegisterForReflection
public final class WebFunction {

    public String functionName;
    public String functionArn;
    public String accountId;
    public String region;
    public Instant createdAt;
    public Instant updatedAt;
    public Map<String, String> tags = new LinkedHashMap<>();
    public String policy;
    public String policyRevisionId;
    /** Revisions in creation order, keyed by revision id. */
    public Map<String, WebFunctionRevision> revisions = new LinkedHashMap<>();
    /** Endpoints in creation order, keyed by endpoint name. */
    public Map<String, WebFunctionEndpoint> endpoints = new LinkedHashMap<>();
}
