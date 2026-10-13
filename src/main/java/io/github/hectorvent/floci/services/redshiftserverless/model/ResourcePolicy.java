package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The resource policy attached to a Redshift Serverless snapshot. Floci stores it but does not evaluate it. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourcePolicy {

    private String resourceArn;
    private String policy;

    public ResourcePolicy() {
    }

    public ResourcePolicy(String resourceArn, String policy) {
        this.resourceArn = resourceArn;
        this.policy = policy;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }
}
