package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Certificate;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.OperationNotPermittedException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElbV2DefaultCertificateTest {

    @Test
    void defaultRemovalIsRefusedAndModifyCanReplaceIt() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Floci-only: requested ACM certificates are issued immediately");
        try (AcmClient acm = TestFixtures.acmClient();
             Ec2Client ec2 = TestFixtures.ec2Client();
             ElasticLoadBalancingV2Client elb = TestFixtures.elbV2Client()) {
            String first = null;
            String additional = null;
            String loadBalancer = null;
            try {
                first = acm.requestCertificate(b -> b.domainName("default.example.test").validationMethod("DNS"))
                        .certificateArn();
                additional = acm.requestCertificate(b -> b.domainName("additional.example.test").validationMethod("DNS"))
                        .certificateArn();
                Map<String, String> subnetsByZone = new LinkedHashMap<>();
                for (Subnet subnet : ec2.describeSubnets().subnets()) {
                    subnetsByZone.putIfAbsent(subnet.availabilityZone(), subnet.subnetId());
                }
                List<String> subnets = subnetsByZone.values().stream().limit(2).toList();
                assertEquals(2, subnets.size());
                String firstArn = first;
                String additionalArn = additional;
                loadBalancer = elb.createLoadBalancer(b -> b.name(TestFixtures.uniqueName("sdk-default-cert"))
                        .type("application").subnets(subnets)).loadBalancers().get(0).loadBalancerArn();
                String lbArn = loadBalancer;
                String listener = elb.createListener(b -> b.loadBalancerArn(lbArn).port(443).protocol("HTTPS")
                        .certificates(Certificate.builder().certificateArn(firstArn).build())
                        .defaultActions(a -> a.type("fixed-response").fixedResponseConfig(f ->
                                f.statusCode("200").contentType("text/plain").messageBody("ok"))))
                        .listeners().get(0).listenerArn();
                assertEquals(List.of(firstArn), certificates(elb, listener));
                OperationNotPermittedException onlyDefault = assertThrows(OperationNotPermittedException.class,
                        () -> elb.removeListenerCertificates(b -> b.listenerArn(listener)
                                .certificates(Certificate.builder().certificateArn(firstArn).build())));
                assertEquals(400, onlyDefault.statusCode());
                assertEquals(List.of(firstArn), certificates(elb, listener));
                elb.addListenerCertificates(b -> b.listenerArn(listener)
                        .certificates(Certificate.builder().certificateArn(additionalArn).build()));
                assertEquals(List.of(firstArn, additionalArn), certificates(elb, listener));
                assertThrows(OperationNotPermittedException.class,
                        () -> elb.removeListenerCertificates(b -> b.listenerArn(listener)
                                .certificates(Certificate.builder().certificateArn(firstArn).build())));
                assertEquals(List.of(firstArn, additionalArn), certificates(elb, listener));
                elb.removeListenerCertificates(b -> b.listenerArn(listener)
                        .certificates(Certificate.builder().certificateArn(additionalArn).build()));
                assertEquals(List.of(firstArn), certificates(elb, listener));
                elb.modifyListener(b -> b.listenerArn(listener)
                        .certificates(Certificate.builder().certificateArn(additionalArn).build()));
                assertEquals(List.of(additionalArn), certificates(elb, listener));
                assertThrows(OperationNotPermittedException.class,
                        () -> elb.removeListenerCertificates(b -> b.listenerArn(listener)
                                .certificates(Certificate.builder().certificateArn(additionalArn).build())));
                assertEquals(List.of(additionalArn), certificates(elb, listener));
            } finally {
                try {
                    if (loadBalancer != null) {
                        String lbArn = loadBalancer;
                        elb.deleteLoadBalancer(b -> b.loadBalancerArn(lbArn));
                    }
                } finally {
                    try {
                        if (first != null) {
                            String arn = first;
                            acm.deleteCertificate(b -> b.certificateArn(arn));
                        }
                    } finally {
                        if (additional != null) {
                            String arn = additional;
                            acm.deleteCertificate(b -> b.certificateArn(arn));
                        }
                    }
                }
            }
        }
    }

    private static List<String> certificates(ElasticLoadBalancingV2Client client, String listener) {
        return client.describeListenerCertificates(b -> b.listenerArn(listener)).certificates().stream()
                .map(Certificate::certificateArn).toList();
    }
}
