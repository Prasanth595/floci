package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EgressOnlyInternetGateway {

    private String egressOnlyInternetGatewayId;
    private String region;
    private String clientToken;
    private List<InternetGatewayAttachment> attachments = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public EgressOnlyInternetGateway() {}

    public String getEgressOnlyInternetGatewayId() { return egressOnlyInternetGatewayId; }
    public void setEgressOnlyInternetGatewayId(String egressOnlyInternetGatewayId) {
        this.egressOnlyInternetGatewayId = egressOnlyInternetGatewayId;
    }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public List<InternetGatewayAttachment> getAttachments() { return attachments; }
    public void setAttachments(List<InternetGatewayAttachment> attachments) { this.attachments = attachments; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
