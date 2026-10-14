package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A custom domain bound to a Redshift Serverless workgroup. Floci records the certificate ARN but
 * does not read the certificate, so it reports no expiry time.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomDomainAssociation {

    private String workgroupName;
    private String customDomainName;
    private String customDomainCertificateArn;
    private String ownerId;

    public CustomDomainAssociation() {
    }

    public CustomDomainAssociation(CustomDomainAssociation other) {
        this.ownerId = other.ownerId;
        this.workgroupName = other.workgroupName;
        this.customDomainName = other.customDomainName;
        this.customDomainCertificateArn = other.customDomainCertificateArn;
    }

    public String getWorkgroupName() {
        return workgroupName;
    }

    public void setWorkgroupName(String workgroupName) {
        this.workgroupName = workgroupName;
    }

    public String getCustomDomainName() {
        return customDomainName;
    }

    public void setCustomDomainName(String customDomainName) {
        this.customDomainName = customDomainName;
    }

    public String getCustomDomainCertificateArn() {
        return customDomainCertificateArn;
    }

    public void setCustomDomainCertificateArn(String customDomainCertificateArn) {
        this.customDomainCertificateArn = customDomainCertificateArn;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }
}
