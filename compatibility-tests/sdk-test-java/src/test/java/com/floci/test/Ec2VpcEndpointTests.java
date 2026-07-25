package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpAddressType;
import software.amazon.awssdk.services.ec2.model.State;
import software.amazon.awssdk.services.ec2.model.VpcEndpoint;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2VpcEndpointTests {

    @Test
    void gatewayEndpointStateDecodesThroughSdk() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String vpcId = ec2.createVpc(request -> request.cidrBlock("10.85.0.0/16")).vpc().vpcId();
            String endpointId = null;
            try {
                VpcEndpoint created = ec2.createVpcEndpoint(request -> request
                        .vpcId(vpcId)
                        .serviceName("com.amazonaws.us-east-1.s3")).vpcEndpoint();
                endpointId = created.vpcEndpointId();
                assertThat(created.state()).isEqualTo(State.AVAILABLE);
                assertThat(created.ipAddressType()).isEqualTo(IpAddressType.IPV4);
                VpcEndpoint described = ec2.describeVpcEndpoints(request -> request
                        .vpcEndpointIds(created.vpcEndpointId())).vpcEndpoints().get(0);
                assertThat(described.state()).isEqualTo(State.AVAILABLE);
                assertThat(described.ipAddressType()).isEqualTo(IpAddressType.IPV4);
            } finally {
                if (endpointId != null) {
                    String deletedId = endpointId;
                    ec2.deleteVpcEndpoints(request -> request.vpcEndpointIds(deletedId));
                }
                ec2.deleteVpc(request -> request.vpcId(vpcId));
            }
        }
    }
}
